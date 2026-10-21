package com.example.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ImageMetadata(
    val filename: String,
    val dimensions: String,
    val formattedSize: String,
    val format: String
)

object ImageUtils {

    fun loadBitmapFromUri(context: Context, uri: Uri, maxDimension: Int = 1800): Bitmap? {
        if (maxDimension <= 0) return null
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            } ?: return null

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > maxDimension * 2) {
                sampleSize *= 2
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (_: SecurityException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    fun loadBitmapFromFile(path: String, maxDimension: Int = 1800): Bitmap? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            var inSampleSize = 1
            val (height: Int, width: Int) = options.outHeight to options.outWidth
            if (height > maxDimension || width > maxDimension) {
                val halfHeight: Int = height / 2
                val halfWidth: Int = width / 2
                while (halfHeight / inSampleSize >= maxDimension && halfWidth / inSampleSize >= maxDimension) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun cropCenterBitmap(bitmap: Bitmap, cropFactor: Float = 0.85f): Bitmap {
        val newWidth = (bitmap.width * cropFactor).toInt().coerceAtLeast(100)
        val newHeight = (bitmap.height * cropFactor).toInt().coerceAtLeast(100)
        val startX = ((bitmap.width - newWidth) / 2).coerceAtLeast(0)
        val startY = ((bitmap.height - newHeight) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(bitmap, startX, startY, newWidth, newHeight)
    }

    fun saveBitmapToInternalStorage(context: Context, bitmap: Bitmap): String {
        val scansDir = File(context.filesDir, "scans")
        if (!scansDir.exists()) {
            scansDir.mkdirs()
        }
        val file = File(scansDir, "scan_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        return file.absolutePath
    }

    fun createAndSaveThumbnail(context: Context, bitmap: Bitmap, maxDim: Int = 300): String {
        val thumbsDir = File(context.filesDir, "scans/thumbs")
        if (!thumbsDir.exists()) {
            thumbsDir.mkdirs()
        }
        val file = File(thumbsDir, "thumb_${System.currentTimeMillis()}.jpg")
        
        val width = bitmap.width
        val height = bitmap.height
        val scale = if (width > height) maxDim.toFloat() / width else maxDim.toFloat() / height
        val thumbBitmap = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(bitmap, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
        } else {
            bitmap
        }
        FileOutputStream(file).use { out ->
            thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
        }
        if (thumbBitmap != bitmap) {
            thumbBitmap.recycle()
        }
        return file.absolutePath
    }

    fun getImageMetadata(context: Context, uri: Uri): ImageMetadata {
        var filename = "image_${System.currentTimeMillis()}.jpg"
        var sizeBytes = 0L

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) {
                        filename = cursor.getString(nameIndex) ?: filename
                    }
                    if (sizeIndex != -1) {
                        sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        val dimensions = try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            "${options.outWidth} × ${options.outHeight}"
        } catch (e: Exception) {
            "Unknown"
        }

        val format = when {
            filename.endsWith(".png", true) -> "PNG"
            filename.endsWith(".webp", true) -> "WebP"
            else -> "JPEG"
        }

        val formattedSize = if (sizeBytes > 0) {
            val kb = sizeBytes / 1024
            if (kb > 1024) String.format(Locale.US, "%.1f MB", kb / 1024.0) else "$kb KB"
        } else {
            "Direct Capture"
        }

        return ImageMetadata(
            filename = filename,
            dimensions = dimensions,
            formattedSize = formattedSize,
            format = format
        )
    }

    fun createSampleDocumentBitmap(type: SampleDocumentType): Bitmap {
        val width = 1000
        val height = 1350
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Clean white paper background with subtle inner shadow
        canvas.drawColor(Color.WHITE)

        val borderPaint = Paint().apply {
            color = Color.rgb(226, 232, 240)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawRect(Rect(16, 16, width - 16, height - 16), borderPaint)

        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 23, 42)
            textSize = 42f
            isFakeBoldText = true
        }

        val subheaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(37, 99, 235)
            textSize = 28f
            isFakeBoldText = true
        }

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 41, 59)
            textSize = 24f
        }

        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(100, 116, 139)
            textSize = 20f
        }

        when (type) {
            SampleDocumentType.CONTRACT -> {
                canvas.drawText("STANDARD SERVICE AGREEMENT", 60f, 110f, headerPaint)
                canvas.drawText("Reference No: AGT-2026-98124 | Date: October 21, 2026", 60f, 160f, mutedPaint)
                canvas.drawLine(60f, 190f, (width - 60).toFloat(), 190f, borderPaint)

                canvas.drawText("1. PURPOSE AND SCOPE OF WORK", 60f, 250f, subheaderPaint)
                val lines = listOf(
                    "This Agreement is entered between SnapText Solutions LLC (\"Provider\")",
                    "and the Client for modern digital document processing and text extraction.",
                    "The Provider agrees to deliver high-fidelity OCR scanning services,",
                    "real-time data conversion, and localized on-device processing capabilities.",
                    "",
                    "2. PRIVACY AND LOCAL DATA RETENTION",
                    "All captured images, character blocks, and extracted notes shall remain",
                    "strictly on the Client's device. No telemetry, customer records, or raw",
                    "media files are transferred or shared with external third-party hosts.",
                    "",
                    "3. PERFORMANCE OBLIGATIONS & WARRANTY",
                    "The extraction engine guarantees standard optical character recognition",
                    "with high accuracy across typed documents, invoices, receipts, and signs.",
                    "Both parties accept terms in good faith upon signature below."
                )
                var y = 290f
                for (line in lines) {
                    if (line.startsWith("2.") || line.startsWith("3.")) {
                        y += 20f
                        canvas.drawText(line, 60f, y, subheaderPaint)
                    } else {
                        canvas.drawText(line, 60f, y, bodyPaint)
                    }
                    y += 38f
                }

                canvas.drawText("Authorized Signatory: John Doe, Legal Counsel", 60f, 1100f, mutedPaint)
                canvas.drawText("Client Signatory: Jane Smith, Executive Director", 60f, 1140f, mutedPaint)
            }
            SampleDocumentType.INVOICE -> {
                canvas.drawText("COMMERCIAL TAX INVOICE", 60f, 110f, headerPaint)
                canvas.drawText("Invoice Number: INV-88231 • Issued: 21 Oct 2026", 60f, 160f, mutedPaint)
                canvas.drawLine(60f, 190f, (width - 60).toFloat(), 190f, borderPaint)

                canvas.drawText("Billed To: ACME Corporation Worldwide", 60f, 240f, subheaderPaint)
                canvas.drawText("100 Innovation Boulevard, Tech District, Suite 400", 60f, 280f, bodyPaint)

                canvas.drawText("ITEM DESCRIPTION                        QTY    RATE       TOTAL", 60f, 370f, subheaderPaint)
                canvas.drawLine(60f, 390f, (width - 60).toFloat(), 390f, borderPaint)

                val items = listOf(
                    "1. Document Scanner Pro Suite           1      $450.00    $450.00",
                    "2. Annual Maintenance Support          1      $120.00    $120.00",
                    "3. On-Device OCR Offline Engine        1      $280.00    $280.00",
                    "4. Client Workstation Integration      2      $75.00     $150.00"
                )
                var y = 430f
                for (item in items) {
                    canvas.drawText(item, 60f, y, bodyPaint)
                    y += 45f
                }

                canvas.drawLine(60f, y + 20f, (width - 60).toFloat(), y + 20f, borderPaint)
                y += 70f
                canvas.drawText("SUBTOTAL:                                         $1,000.00", 60f, y, bodyPaint)
                y += 40f
                canvas.drawText("TAX (8%):                                         $80.00", 60f, y, bodyPaint)
                y += 50f
                canvas.drawText("TOTAL AMOUNT DUE:                                 $1,080.00", 60f, y, subheaderPaint)

                canvas.drawText("Payment Terms: Due within 30 days via Direct Deposit.", 60f, 1120f, mutedPaint)
            }
            SampleDocumentType.MEETING_NOTES -> {
                canvas.drawText("PRODUCT SPRINT & ACTION NOTES", 60f, 110f, headerPaint)
                canvas.drawText("Meeting Date: October 21, 2026 • Attendees: Mobile Team", 60f, 160f, mutedPaint)
                canvas.drawLine(60f, 190f, (width - 60).toFloat(), 190f, borderPaint)

                canvas.drawText("KEY OBJECTIVES FOR SNAPTEXT:", 60f, 240f, subheaderPaint)
                val lines = listOf(
                    "• Fast on-device camera & gallery image capture.",
                    "• Instant OCR without login or account registration.",
                    "• Clean Material 3 visual hierarchy with subtle borders.",
                    "• Animated laser scanning bar during OCR extraction.",
                    "• Full-text editing mode to correct typos seamlessly.",
                    "• One-tap copy to clipboard and system share sheet.",
                    "• Room database persistence for history retrieval."
                )
                var y = 290f
                for (line in lines) {
                    canvas.drawText(line, 60f, y, bodyPaint)
                    y += 44f
                }

                y += 30f
                canvas.drawText("ACTION ASSIGNMENTS:", 60f, y, subheaderPaint)
                y += 45f
                canvas.drawText("1. Finalize mobile responsive layout for tablets.", 60f, y, bodyPaint)
                y += 40f
                canvas.drawText("2. Verify zero cloud network dependency.", 60f, y, bodyPaint)
                y += 40f
                canvas.drawText("3. Add storage clear safety confirmation.", 60f, y, bodyPaint)
            }
        }

        return bitmap
    }
}

enum class SampleDocumentType(val title: String, val subtitle: String) {
    CONTRACT("Contract Agreement", "Service terms, clauses, and signatures"),
    INVOICE("Commercial Invoice", "Line items, quantities, and totals"),
    MEETING_NOTES("Meeting Notes", "Bullet items and action assignments")
}
