package com.example.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.ocr.OcrEngine
import com.example.ocr.OcrWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * User-started floating OCR. Frames remain in memory and are never written to gallery/storage.
 * Android's MediaProjection consent is required for every new capture session.
 */
class FloatingOcrService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private var selectionView: SelectionOverlay? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var screenWidth = 0
    private var screenHeight = 0
    private var density = 1
    private var expanded = false
    private var scrollMode = false
    private var lastFrameHash = 0
    private var lastOcrAt = 0L
    private var captureRequested = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow SnapText to display over other apps first.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let { getProjectionData(it) }
        if (data == null || resultCode == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            startProjection(resultCode, data)
            showBubble()
        } catch (error: Exception) {
            Toast.makeText(this, "Floating OCR couldn't start: ${error.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun getProjectionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_RESULT_DATA)

    private fun startProjection(resultCode: Int, data: Intent) {
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        density = metrics.densityDpi
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        captureThread = HandlerThread("SnapTextScreenCapture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (!captureRequested.get() && (!scrollMode || now - lastOcrAt < 900L)) return@setOnImageAvailableListener
                val bitmap = imageToBitmap(image) ?: return@setOnImageAvailableListener
                val hash = frameHash(bitmap)
                if (!captureRequested.get() && hash == lastFrameHash) {
                    bitmap.recycle()
                    return@setOnImageAvailableListener
                }
                lastFrameHash = hash
                captureRequested.set(false)
                lastOcrAt = now
                recognizeFrame(bitmap)
            } finally {
                image.close()
            }
        }, captureHandler)
        virtualDisplay = projection!!.createVirtualDisplay(
            "SnapTextFloatingOCR", screenWidth, screenHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, captureHandler
        )
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, captureHandler)
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        val paddedWidth = screenWidth + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight)
        padded.recycle()
        return cropped
    }

    private fun frameHash(bitmap: Bitmap): Int {
        var hash = 1
        val xStep = (bitmap.width / 24).coerceAtLeast(1)
        val yStep = (bitmap.height / 24).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                hash = 31 * hash + bitmap.getPixel(x, y)
                x += xStep
            }
            y += yStep
        }
        return hash
    }

    private fun recognizeFrame(bitmap: Bitmap) {
        scope.launch {
            try {
                val result = OcrEngine.recognizeText(bitmap)
                selectionView?.setWords(result.words, bitmap.width, bitmap.height)
                selectionView?.visibility = View.VISIBLE
                if (scrollMode) Handler(mainLooper).postDelayed({
                    if (scrollMode) selectionView?.visibility = View.INVISIBLE
                }, 700L)
            } catch (_: Exception) {
                Toast.makeText(this@FloatingOcrService, "Couldn't recognize this screen. Try again.", Toast.LENGTH_SHORT).show()
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun showBubble() {
        if (bubble != null) return
        val handle = BubbleHandle(this) {
            if (!expanded) {
                expanded = true
                bubble?.let { windowManager.removeView(it) }
                val camera = BubbleHandle(this) { createSelectionOverlay(); selectionView?.visibility = View.INVISIBLE; captureRequested.set(true) }.apply { setExpandedCamera() }
                bubble = camera
                windowManager.addView(camera, bubbleParams(64, 64))
            } else {
                captureRequested.set(true)
            }
        }
        bubble = handle
        windowManager.addView(handle, bubbleParams(8, 76))
    }

    private fun bubbleParams(widthDp: Int, heightDp: Int): WindowManager.LayoutParams {
        val p = (resources.displayMetrics.density * widthDp).toInt()
        val h = (resources.displayMetrics.density * heightDp).toInt()
        return WindowManager.LayoutParams(
            p, h, if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = 0
        }
    }

    private fun createSelectionOverlay() {
        if (selectionView != null) return
        selectionView = SelectionOverlay(this,
            onCopy = { text ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("SnapText OCR", text))
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            onScrollMode = { enabled ->
                scrollMode = enabled
                selectionView?.setTouchThrough(enabled)
                selectionView?.setScrollMode(enabled)
                if (enabled) selectionView?.visibility = View.INVISIBLE
                captureRequested.set(true)
            },
            onClose = { removeSelectionOverlay() }
        )
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        selectionView?.visibility = View.INVISIBLE
        windowManager.addView(selectionView, params)
    }

    private fun removeSelectionOverlay() {
        selectionView?.let { runCatching { windowManager.removeView(it) } }
        selectionView = null
        scrollMode = false
    }

    override fun onDestroy() {
        removeSelectionOverlay()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        captureThread?.quitSafely()
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("SnapText floating OCR is active")
            .setContentText("Tap the blue edge handle to recognize your screen.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", android.app.PendingIntent.getService(
                this, 4, Intent(this, FloatingOcrService::class.java).setAction(ACTION_STOP),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            ))
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Floating OCR", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private class BubbleHandle(context: Context, private val onTap: () -> Unit) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var downX = 0f
        private var downY = 0f
        fun setExpandedCamera() { invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width < dp(20)) {
                paint.color = Color.argb(180, 35, 145, 255)
                canvas.drawRoundRect(width / 2f - dp(2), 0f, width / 2f + dp(2), height.toFloat(), dp(3), dp(3), paint)
            } else {
                paint.color = Color.argb(235, 24, 115, 230)
                canvas.drawCircle(width / 2f, height / 2f, dp(27), paint)
                paint.color = Color.WHITE
                val cx = width / 2f
                val cy = height / 2f
                val body = RectF(cx - dp(15), cy - dp(10), cx + dp(15), cy + dp(11))
                canvas.drawRoundRect(body, dp(4), dp(4), paint)
                canvas.drawRoundRect(RectF(cx - dp(8), cy - dp(14), cx + dp(3), cy - dp(10)), dp(2), dp(2), paint)
                paint.color = Color.rgb(24, 115, 230)
                canvas.drawCircle(cx, cy, dp(6), paint)
                paint.color = Color.WHITE
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2)
                canvas.drawCircle(cx, cy, dp(5), paint)
                paint.style = Paint.Style.FILL
            }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; return true }
                MotionEvent.ACTION_UP -> { if (abs(event.rawX - downX) < dp(12) && abs(event.rawY - downY) < dp(12)) onTap(); return true }
            }
            return true
        }
        private fun dp(v: Int) = v * resources.displayMetrics.density
    }

    companion object {
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val ACTION_STOP = "com.example.floating.STOP"
        private const val CHANNEL_ID = "snaptext_floating_ocr"
        private const val NOTIFICATION_ID = 7204
    }
}

/** Transparent word-selection layer. It does not save or export captured screen images. */
private class SelectionOverlay(
    context: Context,
    private val onCopy: (String) -> Unit,
    private val onScrollMode: (Boolean) -> Unit,
    private val onClose: () -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var words: List<OcrWord> = emptyList()
    private var scaleX = 1f
    private var scaleY = 1f
    private var selected = linkedSetOf<Int>()
    private var scrollMode = false
    private var touchThrough = false

    fun setWords(value: List<OcrWord>, sourceWidth: Int, sourceHeight: Int) {
        words = value
        scaleX = width.toFloat() / sourceWidth.coerceAtLeast(1)
        scaleY = height.toFloat() / sourceHeight.coerceAtLeast(1)
        selected.clear()
        invalidate()
    }
    fun setTouchThrough(enabled: Boolean) {
        touchThrough = enabled
        val params = layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = if (enabled) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(this, params)
    }
    fun setScrollMode(enabled: Boolean) { scrollMode = enabled; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (scrollMode) {
            paint.color = Color.argb(210, 20, 110, 230)
            paint.textSize = 14f * resources.displayMetrics.density
            canvas.drawText("Scroll mode • OCR refreshes as you move", 18f, height - 28f, paint)
            return
        }
        val now = System.currentTimeMillis()
        words.forEachIndexed { index, word ->
            val r = RectF(word.boundingBox.left * scaleX, word.boundingBox.top * scaleY,
                word.boundingBox.right * scaleX, word.boundingBox.bottom * scaleY)
            paint.color = if (index in selected) Color.argb(105, 40, 145, 255) else Color.argb(45, 80, 175, 255)
            canvas.drawRoundRect(r, 4f, 4f, paint)
            paint.color = Color.argb((125 + (now / 120 % 100).toInt()).coerceAtMost(225), 90, 190, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            canvas.drawRoundRect(r, 4f, 4f, paint)
            paint.style = Paint.Style.FILL
            // Small recognition glints fade away after OCR settles.
            if (now - lastRecognizedAt < 1300L) {
                paint.color = Color.argb(180, 255, 255, 255)
                canvas.drawCircle(r.left, r.top, 2.2f, paint)
            }
        }
        val d = resources.displayMetrics.density
        paint.color = Color.argb(225, 18, 28, 44)
        canvas.drawRoundRect(16f * d, 8f * d, width - 16f * d, 64f * d, 18f * d, 18f * d, paint)
        paint.color = Color.WHITE
        paint.textSize = 14f * d
        canvas.drawText("Select all", 28f * d, 42f * d, paint)
        canvas.drawText("Copy", 126f * d, 42f * d, paint)
        canvas.drawText("Scroll", 196f * d, 42f * d, paint)
        canvas.drawText("×", width - 42f * d, 42f * d, paint)
        if (now - lastRecognizedAt < 1300L) postInvalidateDelayed(100)
    }

    private var lastRecognizedAt = System.currentTimeMillis()
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (touchThrough) return false
        if (event.action != MotionEvent.ACTION_UP) return true
        val x = event.x
        val y = event.y
        val d = resources.displayMetrics.density
        if (y < 75f * d) {
            when {
                x < 115f * d -> selected = words.indices.toCollection(linkedSetOf())
                x < 195f * d -> onCopy(selected.sorted().joinToString(" ") { words[it].text }.ifBlank { words.joinToString(" ") { it.text } })
                x < 270f * d -> onScrollMode(!scrollMode)
                else -> onClose()
            }
            invalidate()
            return true
        }
        val hit = words.indices.firstOrNull { i ->
            val r = words[i].boundingBox
            x >= r.left * scaleX && x <= r.right * scaleX && y >= r.top * scaleY && y <= r.bottom * scaleY
        }
        if (hit != null) {
            if (hit in selected) selected.remove(hit) else selected.add(hit)
            invalidate()
        }
        return true
    }
}
