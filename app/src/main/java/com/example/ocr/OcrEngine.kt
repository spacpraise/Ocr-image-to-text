package com.example.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "OcrEngine"

data class OcrResult(
    val fullText: String,
    val characterCount: Int,
    val wordCount: Int,
    val lineCount: Int,
    val confidenceNote: String = "",
    val languageDetected: String = "Auto-detected",
    val isAiModel: Boolean = false
)

object OcrEngine {

    // Structured JSON Schema definition for Gemini 3.8 Flash
    private val ocrResponseSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "hasText" to Schema.boolean(description = "Whether any readable text was detected in the image"),
                "extractedText" to Schema.string(
                    description = "The exact readable text extracted from the image. Preserves layout, line breaks, paragraph breaks, and column reading order"
                ),
                "confidenceNote" to Schema.string(
                    description = "Assessment of image clarity, quality issues, or any illegible/blurry parts"
                ),
                "languageDetected" to Schema.string(
                    description = "Primary language or languages detected in the text"
                )
            ),
            optionalProperties = listOf("confidenceNote", "languageDetected")
        )
    }

    private val geminiModel by lazy {
        val config = generationConfig {
            responseMimeType = "application/json"
            responseSchema = ocrResponseSchema
        }
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = config
        )
    }

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

    /** Optional cloud AI OCR path. Call only after the user explicitly chooses cloud processing. */
    suspend fun recognizeTextWithAi(bitmap: Bitmap): OcrResult {
        // Attempt Gemini 3.8 Flash extraction first
        try {
            Log.d(TAG, "Starting Gemini 3.8 Flash multimodal text extraction...")
            val response = withTimeout(45_000L) {
                geminiModel.generateContent(
                    content {
                        image(bitmap)
                        text(OCR_EXTRACTION_PROMPT)
                    }
                )
            }

            val jsonString = response.text
            if (!jsonString.isNullOrBlank()) {
                val json = JSONObject(jsonString)
                val rawText = json.optString("extractedText", "").trim()
                val confidenceNote = json.optString("confidenceNote", "")
                val language = json.optString("languageDetected", "Auto-detected")
                val hasText = json.optBoolean("hasText", rawText.isNotEmpty())

                val finalText = if (hasText) rawText else ""
                val lines = if (finalText.isEmpty()) 0 else finalText.lines().size
                val words = if (finalText.isEmpty()) 0 else finalText.split(Regex("\\s+")).filter { it.isNotBlank() }.size

                Log.d(TAG, "Gemini extraction succeeded: ${finalText.length} chars, $words words")
                return OcrResult(
                    fullText = finalText,
                    characterCount = finalText.length,
                    wordCount = words,
                    lineCount = lines,
                    confidenceNote = confidenceNote,
                    languageDetected = language,
                    isAiModel = true
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini vision model call encountered issue, falling back to on-device ML Kit OCR: ${e.message}", e)
        }

        // Resilient fallback to on-device ML Kit OCR
        return recognizeWithMlKit(bitmap, "Extracted via on-device OCR engine")
    }

    private suspend fun recognizeWithMlKit(bitmap: Bitmap, fallbackNote: String): OcrResult {
        return suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            mlKitRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text.trim()
                    val lines = if (text.isEmpty()) 0 else visionText.textBlocks.sumOf { it.lines.size }.coerceAtLeast(text.lines().size)
                    val words = if (text.isEmpty()) 0 else text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
                    continuation.resume(
                        OcrResult(
                            fullText = text,
                            characterCount = text.length,
                            wordCount = words,
                            lineCount = lines,
                            confidenceNote = fallbackNote,
                            languageDetected = "On-Device Detection",
                            isAiModel = false
                        )
                    )
                }
                .addOnFailureListener { exception ->
                    continuation.resumeWithException(exception)
                }
        }
    }
}
