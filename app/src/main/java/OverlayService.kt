package com.example.test103

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.TypedValue
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Single-button overlay: the camera icon IS the whole UI.
 * Tap → capture → the result circle slides in directly below the icon
 * (or above it when the icon sits in the bottom 20% of the screen).
 * Drag → full-width red delete zone at the bottom.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: LinearLayout
    private lateinit var cameraButton: CameraIconView
    private lateinit var resultText: TextView
    private lateinit var overlayParams: WindowManager.LayoutParams

    private var deleteZoneView: FrameLayout? = null
    private var deleteZoneHighlighted = false

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var isProcessing = false
    private var resultAbove = false   // result placed above the icon (bottom 20%)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val BRIGHT_RED = Color.parseColor("#FF1744")

    companion object {
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    private val screenHeight: Int get() = resources.displayMetrics.heightPixels
    private val deleteZoneHeight: Int get() = dpToPx(120)
    private val stepHeight: Int get() = dpToPx(46 + 8)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlayUI()
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dpToPx(1), Color.parseColor("#33000000"))
    }

    private fun createOverlayUI() {
        val mainSize = dpToPx(56)
        val subSize = dpToPx(46)

        cameraButton = CameraIconView(this).apply {
            layoutParams = LinearLayout.LayoutParams(mainSize, mainSize)
        }

        resultText = TextView(this).apply {
            text = ""; textSize = 13f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(subSize, subSize).apply { topMargin = dpToPx(8) }
            background = circle(Color.GRAY)
            // Tap the result to dismiss it
            setOnClickListener { hideResultTile() }
        }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            background = null
        }

        overlayView.addView(cameraButton)
        overlayView.addView(resultText)

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dpToPx(16); y = dpToPx(60) }

        makeDraggable()
        windowManager.addView(overlayView, overlayParams)
    }

    // ---------------------------------------------------------------------
    // Drag (move / delete) + tap (capture)
    // ---------------------------------------------------------------------

    private fun makeDraggable() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var dragging = false

        cameraButton.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = overlayParams.x; startY = overlayParams.y
                    touchX = event.rawX; touchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        hideResultTile()
                        showDeleteZone()
                    }
                    if (dragging) {
                        overlayParams.x = (startX - dx).toInt().coerceAtLeast(0)
                        overlayParams.y = (startY + dy).toInt().coerceAtLeast(0)
                        try { windowManager.updateViewLayout(overlayView, overlayParams) } catch (_: Exception) {}
                        setDeleteZoneHighlight(event.rawY > screenHeight - deleteZoneHeight)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val droppedInZone = event.rawY > screenHeight - deleteZoneHeight
                        hideDeleteZone()
                        if (droppedInZone && event.actionMasked == MotionEvent.ACTION_UP) {
                            stopSelf()
                            return@setOnTouchListener true
                        }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        takeScreenshot()   // ✅ tap = capture, no menu
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun showDeleteZone() {
        if (deleteZoneView != null) return

        val zone = FrameLayout(this).apply {
            background = deleteZoneGradient(highlight = false)
            alpha = 0f
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            deleteZoneHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        try {
            windowManager.addView(zone, params)
            deleteZoneView = zone
            zone.animate().alpha(1f).setDuration(180).start()
        } catch (_: Exception) {}
    }

    private fun deleteZoneGradient(highlight: Boolean): GradientDrawable {
        val colors = if (highlight) intArrayOf(
            Color.parseColor("#66FF1744"),
            Color.parseColor("#E6FF1744"),
            BRIGHT_RED
        ) else intArrayOf(
            Color.TRANSPARENT,
            Color.parseColor("#99FF1744"),
            Color.parseColor("#E6FF1744")
        )
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors)
    }

    private fun setDeleteZoneHighlight(active: Boolean) {
        if (active == deleteZoneHighlighted) return
        deleteZoneHighlighted = active
        deleteZoneView?.background = deleteZoneGradient(active)
    }

    private fun hideDeleteZone() {
        val zone = deleteZoneView ?: return
        deleteZoneView = null
        deleteZoneHighlighted = false
        zone.animate().alpha(0f).setDuration(180).withEndAction {
            try { windowManager.removeView(zone) } catch (_: Exception) {}
        }.start()
    }

    // ---------------------------------------------------------------------
    // Result tile — expands below the icon (above when in bottom 20%)
    // ---------------------------------------------------------------------

    private fun showResultTile() {
        mainHandler.post {
            if (resultText.visibility == View.VISIBLE) return@post

            val centerY = overlayParams.y + overlayView.height / 2
            resultAbove = centerY > screenHeight * 0.8

            overlayView.removeAllViews()
            if (resultAbove) {
                overlayView.addView(resultText)
                overlayView.addView(cameraButton)
                (resultText.layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0; bottomMargin = dpToPx(8) }
                overlayParams.y = (overlayParams.y - stepHeight).coerceAtLeast(0)
                try { windowManager.updateViewLayout(overlayView, overlayParams) } catch (_: Exception) {}
            } else {
                overlayView.addView(cameraButton)
                overlayView.addView(resultText)
                (resultText.layoutParams as LinearLayout.LayoutParams).apply { topMargin = dpToPx(8); bottomMargin = 0 }
            }

            resultText.visibility = View.VISIBLE
            resultText.alpha = 0f
            resultText.animate().alpha(1f).setDuration(150).start()
        }
    }

    private fun hideResultTile() {
        mainHandler.post {
            if (resultText.visibility != View.VISIBLE) return@post
            resultText.visibility = View.GONE
            if (resultAbove) {
                overlayParams.y += stepHeight
                try { windowManager.updateViewLayout(overlayView, overlayParams) } catch (_: Exception) {}
                resultAbove = false
            }
        }
    }

    // ---------------------------------------------------------------------
    // Capture + detection
    // ---------------------------------------------------------------------

    private fun takeScreenshot() {
        if (isProcessing) return
        hideResultTile()
        val i = Intent(this, ScreenshotActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("EXTRA_ACTION", "ACTION_SHOT")
        }
        startActivity(i)
    }

    private fun performCapture(mp: MediaProjection) {
        isProcessing = true
        overlayView.visibility = View.GONE
        updateStatus("...")

        mainHandler.postDelayed({
            val m = resources.displayMetrics
            imageReader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2)

            val timeoutRunnable = Runnable {
                if (isProcessing) {
                    stopMediaProjection()
                    resetAfterCapture("TIMEOUT")
                }
            }
            mainHandler.postDelayed(timeoutRunnable, 3000)

            try {
                virtualDisplay = mp.createVirtualDisplay(
                    "Screenshot", m.widthPixels, m.heightPixels, m.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                    imageReader!!.surface, null, null
                )

                imageReader?.setOnImageAvailableListener({ ir ->
                    ir.setOnImageAvailableListener(null, null)
                    mainHandler.removeCallbacks(timeoutRunnable)

                    val img = ir.acquireLatestImage()
                    if (img == null) {
                        stopMediaProjection()
                        resetAfterCapture("NO IMG")
                        return@setOnImageAvailableListener
                    }

                    val plane = img.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * m.widthPixels

                    val raw = Bitmap.createBitmap(
                        m.widthPixels + rowPadding / pixelStride,
                        m.heightPixels,
                        Bitmap.Config.ARGB_8888
                    )
                    raw.copyPixelsFromBuffer(buffer)
                    val finalBmp = Bitmap.createBitmap(raw, 0, 0, m.widthPixels, m.heightPixels)
                    if (finalBmp !== raw) raw.recycle()
                    img.close()

                    stopMediaProjection()

                    val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
                    val isCropEnabled = prefs.getBoolean("use_crop", true)

                    mainHandler.post {
                        overlayView.visibility = View.VISIBLE

                        val inputFile = File(cacheDir, "input.png")
                        var saved = false
                        try {
                            FileOutputStream(inputFile).use {
                                finalBmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            saved = true
                        } catch (e: Exception) { e.printStackTrace() }

                        if (isCropEnabled && saved) {
                            finalBmp.recycle()
                            val cropIntent = Intent(this@OverlayService, ScreenshotActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra("EXTRA_ACTION", "ACTION_CROP")
                            }
                            startActivity(cropIntent)
                        } else {
                            showResultTile()
                            runAiDetection(finalBmp)
                            isProcessing = false
                        }
                        stopForegroundCompat()
                    }
                }, mainHandler)

            } catch (e: Exception) {
                mainHandler.removeCallbacks(timeoutRunnable)
                stopMediaProjection()
                mainHandler.post { resetAfterCapture("ERR") }
            }
        }, 400)
    }

    private fun resetAfterCapture(status: String) {
        isProcessing = false
        overlayView.visibility = View.VISIBLE
        showResultTile()
        updateStatus(status)
        stopForegroundCompat()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun stopMediaProjection() {
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        imageReader?.close()
        imageReader = null
    }

    private fun runAiDetection(bitmap: Bitmap) {
        val file = File(cacheDir, "temp_detect.jpg")
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } catch (e: Exception) {
            updateStatus("FILE ERR")
            bitmap.recycle()
            return
        }

        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("api_user", BuildConfig.SE_API_USER)
            .addFormDataPart("api_secret", BuildConfig.SE_API_SECRET)
            .addFormDataPart("models", "genai")
            .addFormDataPart("media", file.name, file.asRequestBody("image/jpeg".toMediaTypeOrNull()))
            .build()

        val request = Request.Builder()
            .url("https://api.sightengine.com/1.0/check.json")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                bitmap.recycle()
                updateStatus("NET ERR")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        bitmap.recycle()
                        updateStatus("API ${it.code}"); return
                    }
                    try {
                        val json = JSONObject(it.body?.string() ?: "{}")
                        val score = json.optJSONObject("type")?.optDouble("ai_generated") ?: 0.0
                        val pct = (score * 100).toInt()
                        updateStatus("$pct%")
                        // ✅ Record in history (with thumbnail)
                        try { HistoryManager.add(this@OverlayService, pct, "Overlay", bitmap) } catch (_: Exception) {}
                    } catch (e: Exception) { updateStatus("JSON ERR") }
                    finally { bitmap.recycle() }
                }
            }
        })
    }

    private fun updateStatus(text: String) {
        mainHandler.post {
            resultText.text = text
            val pct = text.replace("%", "").toIntOrNull() ?: 0
            val color = when {
                text == "..." -> Color.GRAY
                !text.endsWith("%") -> Color.DKGRAY
                pct < 30 -> Color.parseColor("#4CAF50")
                pct < 70 -> Color.parseColor("#FF9800")
                else -> BRIGHT_RED
            }
            (resultText.background as GradientDrawable).setColor(color)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.getStringExtra("EXTRA_ACTION")) {
            "CROP_DONE" -> {
                isProcessing = false
                val croppedFile = File(cacheDir, "output.png")
                val bitmap = if (croppedFile.exists()) BitmapFactory.decodeFile(croppedFile.absolutePath) else null
                if (bitmap != null) {
                    showResultTile()
                    runAiDetection(bitmap)
                } else {
                    showResultTile()
                    updateStatus("EMPTY")
                }
                return START_STICKY
            }
            "CROP_CANCELLED" -> {
                isProcessing = false
                hideResultTile()
                return START_STICKY
            }
            "CROP_FAILED" -> {
                isProcessing = false
                showResultTile()
                updateStatus("CROP ERR")
                return START_STICKY
            }
            "CAPTURE_DENIED" -> {
                isProcessing = false
                hideResultTile()
                return START_STICKY
            }
        }

        val code = intent?.getIntExtra("RESULT_CODE", -1) ?: -1
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (code == Activity.RESULT_OK && data != null) {
            createNotificationChannel()
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }

            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection?.stop()

            try {
                val mp = mpManager.getMediaProjection(code, data)
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        mediaProjection = null
                        virtualDisplay = null
                    }
                }, mainHandler)

                mediaProjection = mp
                performCapture(mp)
            } catch (e: Exception) {
                resetAfterCapture("PERM ERR")
            }
        }
        // NOTE: no stopForeground() in the else branch — the service is only
        // foreground during capture, and MainActivity now uses startService().

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("overlay_ch", "AI Tool", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP_SERVICE }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, "overlay_ch")
            .setContentTitle("overlAI Active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        stopMediaProjection()
        hideDeleteZone()
        try { stopForegroundCompat() } catch (_: Exception) {}
        try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}