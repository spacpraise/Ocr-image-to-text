package com.example.ocr

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "OcrEngine"

/**
 * A single recognized word (or word-like token) and where it sits on the source image,
 * in the source bitmap's own pixel coordinate space (origin top-left, same orientation
 * as the Bitmap that was recognized). The UI overlay scales this rect to match however
 * the bitmap is actually laid out on screen (see ImageSelectableTextOverlay).
 */
data class OcrWord(
    val text: String,
    val boundingBox: RectF
)

data class OcrResult(
    val fullText: String,
    val characterCount: Int,
    val wordCount: Int,
    val lineCount: Int,
    val confidenceNote: String = "",
    val languageDetected: String = "Auto-detected",
    val isAiModel: Boolean = false,
    // Positioned words for select-on-image overlay. Empty when no position data is
    // available (Gemini AI path, or a PDF page with neither extractable native text
    // positions nor OCR-able boxes) -- the UI should fall back to a plain text block.
    val words: List<OcrWord> = emptyList()
) {
    /** True when there's enough positional data to render the Live Text-style overlay. */
    val hasPositionedWords: Boolean get() = words.isNotEmpty()
}

object OcrEngine {

    private val mlKitRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private const val OCR_EXTRACTION_PROMPT = """
You are a precision optical character recognition (OCR) and document text extraction engine.
Analyze the provided image and extract all readable text according to these strict rules:
1. Read the actual image and transcribe all visible, readable text as accurately as possible.
2. Support all image types: screenshots, phone camera photos, printed documents, book pages, handwritten notes, receipts, forms, signs, and business cards.
3. Preserve the original language and scripts (supports single and multilingual documents).
4. Preserve original layout, paragraph breaks, reasonable reading order, headings, numbers, punctuation, and line breaks.
5. For multi-column or multi-section documents, read columns in logical natural order.
6. Do NOT summarize, translate, rewrite, correct spelling, or extrapolate the text.
7. NEVER invent or hallucinate text that cannot be clearly seen.
8. If parts of the image are blurry, cut off, or illegible, mark them as '[illegible]' and describe the issue in confidenceNote.
9. If no readable text exists in the image, set hasText to false and extractedText to empty string.
"""

    /** Default OCR path: runs entirely on-device and works without network access. */
    suspend fun recognizeText(bitmap: Bitmap): OcrResult =
        recognizeWithMlKit(bitmap, "Processed on this device. No internet connection required.")

    /** AI-first image OCR through our server; falls back to local extraction when offline or unavailable. */
    suspend fun recognizeTextWithAi(bitmap: Bitmap, offlineFallback: OcrResult? = null): OcrResult {
        return try {
            withTimeout(10_000L) {
                val jsonString = withContext(Dispatchers.IO) {
                    val maxDimension = 1600
                    val largest = maxOf(bitmap.width, bitmap.height)
                    val requestBitmap = if (largest > maxDimension) {
                        val scale = maxDimension.toFloat() / largest
                        Bitmap.createScaledBitmap(
                            bitmap,
                            (bitmap.width * scale).toInt().coerceAtLeast(1),
                            (bitmap.height * scale).toInt().coerceAtLeast(1),
                            true
                        )
                    } else bitmap

                    val output = java.io.ByteArrayOutputStream()
                    requestBitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
                    if (requestBitmap !== bitmap) requestBitmap.recycle()
                    val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                    val connection = (URL("https://snaptext-pearl.vercel.app/api/ocr").openConnection() as HttpURLConnection)
                    try {
                        connection.requestMethod = "POST"
                        connection.connectTimeout = 2_000
                        connection.readTimeout = 7_500
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.setRequestProperty("Accept", "application/json")
                        val request = JSONObject()
                            .put("imageBase64", encoded)
                            .put("mimeType", "image/jpeg")
                        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(request.toString()) }
                        val status = connection.responseCode
                        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                        if (status !in 200..299) throw IllegalStateException("Cloud AI OCR failed ($status)")
                        body
                    } finally {
                        connection.disconnect()
                    }
                }

                val json = JSONObject(jsonString)
                val finalText = json.optString("extractedText", "").trim()
                if (finalText.isBlank()) throw IllegalStateException("AI returned empty text")
                OcrResult(
                    fullText = finalText,
                    characterCount = finalText.length,
                    wordCount = finalText.split(Regex("\\s+")).count { it.isNotBlank() },
                    lineCount = finalText.lines().size,
                    confidenceNote = json.optString("confidenceNote", "Extracted directly from the image using AI."),
                    languageDetected = json.optString("languageDetected", "Auto-detected"),
                    isAiModel = true,
                    words = emptyList()
                )
            }
        } catch (e: CancellationException) {
            if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            Log.w(TAG, "Cloud AI OCR timed out; falling back to local extraction.")
            offlineFallback ?: recognizeWithMlKit(bitmap, "Extracted via on-device OCR engine")
        } catch (e: Exception) {
            Log.w(TAG, "Cloud AI OCR unavailable; falling back to local extraction: ${e.message}")
            offlineFallback ?: recognizeWithMlKit(bitmap, "Extracted via on-device OCR engine")
        }
    }

    private suspend fun recognizeWithMlKit(bitmap: Bitmap, fallbackNote: String): OcrResult {
        return suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            mlKitRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text.trim()
                    val lines = if (text.isEmpty()) 0 else visionText.textBlocks.sumOf { it.lines.size }.coerceAtLeast(text.lines().size)
                    val wordCount = if (text.isEmpty()) 0 else text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
                    val words = extractWords(visionText)
                    continuation.resume(
                        OcrResult(
                            fullText = text,
                            characterCount = text.length,
                            wordCount = wordCount,
                            lineCount = lines,
                            confidenceNote = fallbackNote,
                            languageDetected = "On-Device Detection",
                            isAiModel = false,
                            words = words
                        )
                    )
                }
                .addOnFailureListener { exception ->
                    continuation.resumeWithException(exception)
                }
        }
    }

    /** Walks ML Kit's Block/Line/Element hierarchy down to individual word-level boxes. */
    private fun extractWords(visionText: Text): List<OcrWord> {
        val words = mutableListOf<OcrWord>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val box = element.boundingBox ?: continue
                    if (element.text.isBlank()) continue
                    words.add(OcrWord(text = element.text, boundingBox = RectF(box)))
                }
            }
        }
        return words
    }

    // ---------------------------------------------------------------------
    // PDF page recognition: try positioned native text first (PdfBox), fall
    // back to rendering the page to a bitmap and running ML Kit OCR on it.
    // ---------------------------------------------------------------------

    /**
     * Extracts text (with positions, where possible) from a single page of an already-open
     * PDFBox [PDDocument]. [pageIndex] is 0-based. [renderPageAsBitmap] is a lazy callback
     * that rasterizes the page on demand -- it's only invoked if native text extraction finds
     * nothing usable, so born-digital pages skip bitmap rendering entirely.
     *
     * [pageWidthPt]/[pageHeightPt] are the PDF page's own size in PDF points (72 pt = 1 inch),
     * used to convert PdfBox's point-space word positions into the same coordinate space as
     * the rendered bitmap would use, scaled by [renderScale] (pixels per point) so the returned
     * boxes line up with whatever bitmap the caller ends up displaying.
     */
    suspend fun recognizePdfPage(
        document: PDDocument,
        pageIndex: Int,
        pageWidthPt: Float,
        pageHeightPt: Float,
        renderScale: Float,
        renderPageAsBitmap: suspend () -> Bitmap?
    ): OcrResult {
        val nativeResult = tryExtractNativePdfText(document, pageIndex, renderScale)
        if (nativeResult != null && nativeResult.fullText.isNotBlank()) {
            Log.d(TAG, "PDF page $pageIndex: using native text (${nativeResult.characterCount} chars, ${nativeResult.words.size} words)")
            return nativeResult
        }

        // No extractable native text (e.g. a scanned image embedded in the PDF) -- OCR the
        // rendered page bitmap instead.
        Log.d(TAG, "PDF page $pageIndex: no native text found, falling back to OCR of rendered bitmap")
        val bitmap = renderPageAsBitmap()
            ?: return OcrResult(
                fullText = "",
                characterCount = 0,
                wordCount = 0,
                lineCount = 0,
                confidenceNote = "Could not render this page.",
                languageDetected = "Unknown"
            )
        return recognizeWithMlKit(bitmap, "Extracted via on-device OCR engine (scanned PDF page)")
    }

    private fun tryExtractNativePdfText(
        document: PDDocument,
        pageIndex: Int,
        renderScale: Float
    ): OcrResult? {
        return try {
            val collectedWords = mutableListOf<OcrWord>()
            val textBuilder = StringBuilder()

            val stripper = object : PDFTextStripper() {
                private val currentLineWords = mutableListOf<Pair<String, RectF>>()
                private var lastBaselineY = -1f

                override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                    if (textPositions.isEmpty()) return
                    // Group consecutive glyphs into words by splitting on whitespace boundaries.
                    var wordStart = 0
                    var minX = Float.MAX_VALUE
                    var minY = Float.MAX_VALUE
                    var maxX = Float.MIN_VALUE
                    var maxY = Float.MIN_VALUE

                    fun flushWord(endExclusive: Int) {
                        if (endExclusive <= wordStart) return
                        val word = text.substring(wordStart, endExclusive)
                        if (word.isNotBlank() && minX != Float.MAX_VALUE) {
                            collectedWords.add(
                                OcrWord(
                                    text = word,
                                    boundingBox = RectF(
                                        minX * renderScale,
                                        minY * renderScale,
                                        maxX * renderScale,
                                        maxY * renderScale
                                    )
                                )
                            )
                        }
                        minX = Float.MAX_VALUE
                        minY = Float.MAX_VALUE
                        maxX = Float.MIN_VALUE
                        maxY = Float.MIN_VALUE
                    }

                    for (i in textPositions.indices) {
                        val pos = textPositions[i]
                        val ch = text.getOrNull(i) ?: ' '
                        if (ch.isWhitespace()) {
                            flushWord(i)
                            wordStart = i + 1
                            continue
                        }
                        val x = pos.xDirAdj
                        val y = pos.yDirAdj - pos.heightDir
                        val w = pos.widthDirAdj
                        val h = pos.heightDir
                        if (x < minX) minX = x
                        if (y < minY) minY = y
                        if (x + w > maxX) maxX = x + w
                        if (y + h > maxY) maxY = y + h
                    }
                    flushWord(text.length)

                    textBuilder.append(text)
                    super.writeString(text, textPositions)
                }
            }
            stripper.startPage = pageIndex + 1
            stripper.endPage = pageIndex + 1
            stripper.sortByPosition = true
            val extracted = stripper.getText(document)

            val finalText = extracted.trim()
            if (finalText.isEmpty()) return null

            val lines = finalText.lines().size
            val wordCount = finalText.split(Regex("\\s+")).filter { it.isNotBlank() }.size

            OcrResult(
                fullText = finalText,
                characterCount = finalText.length,
                wordCount = wordCount,
                lineCount = lines,
                confidenceNote = "Extracted from the document's native text layer.",
                languageDetected = "Document text",
                isAiModel = false,
                words = collectedWords
            )
        } catch (e: Exception) {
            Log.w(TAG, "Native PDF text extraction failed for page $pageIndex, will fall back to OCR: ${e.message}")
            null
        }
    }
}