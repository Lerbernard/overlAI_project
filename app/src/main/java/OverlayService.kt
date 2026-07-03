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

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: LinearLayout
    private lateinit var mainButton: TextView
    private lateinit var cameraBtn: TextView
    private lateinit var deleteBtn: TextView
    private lateinit var resultText: TextView
    private lateinit var overlayParams: WindowManager.LayoutParams

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var isExpanded = false
    private var isProcessing = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // ✅ Network timeouts so a dead connection can't hang the request forever
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val PURPLE = Color.parseColor("#6200EE")
    private val TEAL = Color.parseColor("#03DAC5")

    companion object {
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlayUI()
    }

    private fun getRoundedRect(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpToPx(12).toFloat()
        setColor(color)
    }

    private fun createOverlayUI() {
        val mainSize = dpToPx(60)
        val subSize = dpToPx(50)

        mainButton = TextView(this).apply {
            text = "+"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = getRoundedRect(PURPLE)
            layoutParams = LinearLayout.LayoutParams(mainSize, mainSize)
        }

        cameraBtn = TextView(this).apply {
            text = "📸"; textSize = 20f; gravity = Gravity.CENTER; visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(subSize, subSize).apply { topMargin = dpToPx(10) }
            background = getRoundedRect(PURPLE)
        }

        deleteBtn = TextView(this).apply {
            text = "🗑"; textSize = 20f; gravity = Gravity.CENTER; visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(subSize, subSize).apply { topMargin = dpToPx(10) }
            background = getRoundedRect(Color.RED)
        }

        resultText = TextView(this).apply {
            text = ""; textSize = 14f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(subSize, subSize).apply { topMargin = dpToPx(10) }
            background = getRoundedRect(PURPLE)
        }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(20).toFloat()
                setColor(Color.parseColor("#121212"))
                alpha = 230
            }
        }

        overlayView.addView(mainButton)
        overlayView.addView(deleteBtn)
        overlayView.addView(cameraBtn)
        overlayView.addView(resultText)

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dpToPx(16); y = dpToPx(60) }

        // ✅ NEW: draggable bubble. Tap still toggles expand; drag moves it.
        makeDraggable()

        cameraBtn.setOnClickListener { takeScreenshot() }
        deleteBtn.setOnClickListener { stopSelf() }
        windowManager.addView(overlayView, overlayParams)
    }

    private fun makeDraggable() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var dragging = false

        mainButton.setOnTouchListener { _, event ->
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
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        // gravity is TOP|END, so x grows leftwards
                        overlayParams.x = (startX - dx).toInt().coerceAtLeast(0)
                        overlayParams.y = (startY + dy).toInt().coerceAtLeast(0)
                        try { windowManager.updateViewLayout(overlayView, overlayParams) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) toggleExpand()   // it was a tap
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleExpand() {
        isExpanded = !isExpanded
        val vis = if (isExpanded) View.VISIBLE else View.GONE
        deleteBtn.visibility = vis
        cameraBtn.visibility = vis
        if (!isExpanded) resultText.visibility = View.GONE

        mainButton.text = if (isExpanded) "✕" else "+"
        (mainButton.background as GradientDrawable).setColor(if (isExpanded) TEAL else PURPLE)
    }

    private fun takeScreenshot() {
        if (isProcessing) return
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

            // ✅ Safety net: if no frame ever arrives, recover after 3s instead of hanging
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
                    if (finalBmp !== raw) raw.recycle()   // ✅ free the padded copy
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
                            finalBmp.recycle()   // ScreenshotActivity reloads it from disk
                            val cropIntent = Intent(this@OverlayService, ScreenshotActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra("EXTRA_ACTION", "ACTION_CROP")
                            }
                            startActivity(cropIntent)
                            // keep isProcessing = true until CROP_DONE / CROP_CANCELLED / CROP_FAILED
                        } else {
                            resultText.visibility = View.VISIBLE
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
        resultText.visibility = View.VISIBLE
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
            // ✅ JPEG 90 instead of PNG 100: ~5-10x smaller upload, much faster on mobile data,
            // no meaningful difference to the detector's score.
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } catch (e: Exception) {
            updateStatus("FILE ERR")
            return
        } finally {
            bitmap.recycle()
        }

        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            // ✅ Keys now come from BuildConfig (see build.gradle.kts) — never hardcode secrets
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
            override fun onFailure(call: Call, e: IOException) { updateStatus("NET ERR") }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) { updateStatus("API ${it.code}"); return }
                    try {
                        val json = JSONObject(it.body?.string() ?: "{}")
                        val score = json.optJSONObject("type")?.optDouble("ai_generated") ?: 0.0
                        updateStatus("${(score * 100).toInt()}%")
                    } catch (e: Exception) { updateStatus("JSON ERR") }
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
                !text.endsWith("%") -> Color.DKGRAY   // any error/status word
                pct < 30 -> Color.parseColor("#4CAF50")
                pct < 70 -> Color.parseColor("#FF9800")
                else -> Color.RED
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
                    resultText.visibility = View.VISIBLE
                    runAiDetection(bitmap)
                } else {
                    resultText.visibility = View.VISIBLE
                    updateStatus("EMPTY")
                }
                return START_STICKY
            }
            // ✅ NEW: crop cancelled or failed — reset cleanly instead of hanging on "..."
            "CROP_CANCELLED" -> {
                isProcessing = false
                resultText.visibility = View.GONE
                return START_STICKY
            }
            "CROP_FAILED" -> {
                isProcessing = false
                resultText.visibility = View.VISIBLE
                updateStatus("CROP ERR")
                return START_STICKY
            }
            "CAPTURE_DENIED" -> {
                isProcessing = false
                resultText.visibility = View.GONE
                return START_STICKY
            }
        }

        val code = intent?.getIntExtra("RESULT_CODE", -1) ?: -1
        // ✅ getParcelableExtra(String) is deprecated on API 33+
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
                // ✅ Projection token can be invalid/expired on some OEMs — don't crash the service
                resetAfterCapture("PERM ERR")
            }
        } else {
            stopForegroundCompat()
        }

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
        try { stopForegroundCompat() } catch (_: Exception) {}
        try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}