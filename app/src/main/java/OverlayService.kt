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
    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

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
        createNotificationChannel()

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
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
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
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

        mainButton.setOnClickListener { toggleExpand() }
        cameraBtn.setOnClickListener { takeScreenshot() }
        deleteBtn.setOnClickListener { stopSelf() }
        windowManager.addView(overlayView, overlayParams)
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

        // Always request fresh permission to ensure a fresh capture session
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
            // Re-initialize imageReader every time for a clean buffer
            imageReader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2)

            try {
                virtualDisplay = mp.createVirtualDisplay(
                    "Screenshot", m.widthPixels, m.heightPixels, m.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                    imageReader!!.surface, null, null
                )

                imageReader?.setOnImageAvailableListener({ ir ->
                    ir.setOnImageAvailableListener(null, null)
                    val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener

                    val plane = img.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * m.widthPixels

                    val bmp = Bitmap.createBitmap(m.widthPixels + rowPadding / pixelStride, m.heightPixels, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(buffer)
                    val finalBmp = Bitmap.createBitmap(bmp, 0, 0, m.widthPixels, m.heightPixels)
                    img.close()

                    // --- STOP EVERYTHING FOR REPEATABILITY ---
                    stopMediaProjection()

                    val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
                    val isCropEnabled = prefs.getBoolean("use_crop", true)

                    mainHandler.post {
                        overlayView.visibility = View.VISIBLE

                        // Always save the raw capture to input.png so the cropper is up to date
                        val inputFile = File(cacheDir, "input.png")
                        try {
                            FileOutputStream(inputFile).use {
                                finalBmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                        } catch (e: Exception) { e.printStackTrace() }

                        if (isCropEnabled) {
                            val cropIntent = Intent(this@OverlayService, ScreenshotActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra("EXTRA_ACTION", "ACTION_CROP")
                            }
                            startActivity(cropIntent)
                        } else {
                            resultText.visibility = View.VISIBLE
                            runAiDetection(finalBmp)
                        }
                        isProcessing = false
                    }
                }, mainHandler)
            } catch (e: Exception) {
                mainHandler.post {
                    isProcessing = false
                    overlayView.visibility = View.VISIBLE
                    updateStatus("ERR")
                    stopMediaProjection()
                }
            }
        }, 400) // Small delay to let overlay hide
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
        val file = File(cacheDir, "temp_detect.png")
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            updateStatus("FILE ERR")
            return
        }

        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("api_user", "958521540")
            .addFormDataPart("api_secret", "6vhjTqJ9qJpQo755FcQEpbkxgphfR3md")
            .addFormDataPart("models", "genai")
            .addFormDataPart("media", file.name, file.asRequestBody("image/png".toMediaTypeOrNull()))
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
                text.contains("ERR") -> Color.DKGRAY
                pct < 30 -> Color.parseColor("#4CAF50")
                pct < 70 -> Color.parseColor("#FF9800")
                else -> Color.RED
            }
            (resultText.background as GradientDrawable).setColor(color)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) { stopSelf(); return START_NOT_STICKY }

        if (intent?.getStringExtra("EXTRA_ACTION") == "CROP_DONE") {
            val croppedFile = File(cacheDir, "output.png")
            if (croppedFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(croppedFile.absolutePath)
                if (bitmap != null) {
                    resultText.visibility = View.VISIBLE
                    runAiDetection(bitmap)
                } else {
                    updateStatus("EMPTY")
                }
            }
            return START_STICKY
        }

        val code = intent?.getIntExtra("RESULT_CODE", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("DATA")

        if (code == Activity.RESULT_OK && data != null) {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            // Clear any old projection before starting a new one
            mediaProjection?.stop()

            val mp = mpManager.getMediaProjection(code, data)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mediaProjection = null
                    virtualDisplay = null
                }
            }, mainHandler)

            mediaProjection = mp
            // Trigger capture immediately once we have the token
            performCapture(mp)
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
        try { windowManager.removeView(overlayView) } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}