package com.example.test103

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: LinearLayout
    private lateinit var mainButton: TextView
    private lateinit var resultView: TextView
    private lateinit var overlayParams: WindowManager.LayoutParams
    private lateinit var trashView: TextView
    private lateinit var trashParams: WindowManager.LayoutParams

    private var isExpanded = false
    private val BUTTON_SIZE = 190
    private val PADDING = 20
    private val COLLAPSED_SIZE = BUTTON_SIZE + (PADDING * 2)

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createTrashButton()
        createOverlayButton()
        windowManager.addView(overlayView, overlayParams)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("DATA")
        if (resultCode != -1 && data != null) {
            processScreenshot(resultCode, data)
        }
        return START_NOT_STICKY
    }

    private fun createOverlayButton() {
        mainButton = createMainButton("+")
        val muteButton = createSubButton("🔇")
        val cameraButton = createSubButton("📸")

        resultView = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(20, 15, 20, 15)
            visibility = View.GONE // Start completely hidden
            alpha = 0f
        }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(PADDING, PADDING, PADDING, PADDING)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 1000f
                setColor(0xFF1E1E1E.toInt())
                setStroke(6, Color.BLACK)
            }
            elevation = 45f
        }

        overlayView.addView(mainButton)
        overlayView.addView(muteButton)
        overlayView.addView(cameraButton)
        overlayView.addView(resultView)

        overlayParams = WindowManager.LayoutParams(
            COLLAPSED_SIZE, COLLAPSED_SIZE,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - 260
            y = 300
        }

        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = overlayParams.x; initialY = overlayParams.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    showTrash(); true
                }
                MotionEvent.ACTION_MOVE -> {
                    overlayParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    overlayParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, overlayParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    hideTrash()
                    if (abs(event.rawX - initialTouchX) < 15 && abs(event.rawY - initialTouchY) < 15) {
                        if (isExpanded && isViewClicked(cameraButton, event.rawX, event.rawY)) {
                            requestScreenshotPermission()
                        } else {
                            // Result view is NOT passed here; only sub-buttons toggle
                            toggleExpand(muteButton, cameraButton)
                        }
                    } else if (isOverTrash()) { stopSelf() } else { snapToEdge() }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleExpand(vararg buttons: View) {
        isExpanded = !isExpanded
        // Reset result view if contracting
        if (!isExpanded) {
            resultView.visibility = View.GONE
            resultView.alpha = 0f
        }

        val targetHeight = if (isExpanded) 650 else COLLAPSED_SIZE

        ValueAnimator.ofInt(overlayParams.height, targetHeight).apply {
            duration = 250
            addUpdateListener {
                overlayParams.height = it.animatedValue as Int
                windowManager.updateViewLayout(overlayView, overlayParams)
            }
            start()
        }

        val alphaVal = if (isExpanded) 1f else 0f
        buttons.forEach { v ->
            if (isExpanded) v.visibility = View.VISIBLE
            v.animate().alpha(alphaVal).setDuration(250).withEndAction {
                if (!isExpanded) v.visibility = View.GONE
            }.start()
        }
    }

    private fun processScreenshot(resultCode: Int, data: Intent) {
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = pm.getMediaProjection(resultCode, data) ?: return
        val metrics = resources.displayMetrics
        val reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)

        projection.createVirtualDisplay("Capture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)

        Handler(Looper.getMainLooper()).postDelayed({
            val img = reader.acquireLatestImage()
            if (img != null) {
                val plane = img.planes[0]
                val bitmap = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, metrics.heightPixels, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, metrics.widthPixels, metrics.heightPixels)

                val file = File(cacheDir, "temp_check.jpg")
                FileOutputStream(file).use { cropped.compress(Bitmap.CompressFormat.JPEG, 80, it) }

                img.close()
                projection.stop()
                checkAiImage(file)
            }
        }, 600)
    }

    private fun checkAiImage(file: File) {
        mainButton.text = "⌛" // Visual indicator that it's processing

        // REPLACE WITH YOUR ACTUAL KEYS
        val apiUser = "958521540"
        val apiSecret = "6vhjTqJ9qJpQo755FcQEpbkxgphfR3md"

        val client = OkHttpClient()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("media", file.name, file.asRequestBody("image/jpeg".toMediaType()))
            .addFormDataPart("models", "genai")
            .addFormDataPart("api_user", apiUser)
            .addFormDataPart("api_secret", apiSecret)
            .build()

        val request = Request.Builder().url("https://api.sightengine.com/1.0/check.json").post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Handler(Looper.getMainLooper()).post { mainButton.text = "+" }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val json = JSONObject(it.body?.string() ?: "{}")
                        val genai = json.optJSONObject("genai")
                        val score = genai?.optDouble("confidence", 0.0) ?: 0.0

                        Handler(Looper.getMainLooper()).post {
                            mainButton.text = "+"
                            displayResult(score > 0.5, score)
                            if (file.exists()) file.delete() // Delete screenshot after use
                        }
                    } catch (e: Exception) {
                        Handler(Looper.getMainLooper()).post { mainButton.text = "+" }
                    }
                }
            }
        })
    }

    private fun displayResult(isAi: Boolean, score: Double) {
        resultView.text =  if (isAi) "AI ${(score * 100).toInt()}%" else "Likely Human ${(100 - score*100).toInt()}%"

        resultView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f
            setColor(color)
            setStroke(4, 0xFFFF9800.toInt()) // Orange border
        }

        resultView.visibility = View.VISIBLE

        // Grow the container to fit the result
        val currentHeight = overlayParams.height
        ValueAnimator.ofInt(currentHeight, currentHeight + 150).apply {
            duration = 300
            addUpdateListener {
                overlayParams.height = it.animatedValue as Int
                windowManager.updateViewLayout(overlayView, overlayParams)
            }
            start()
        }
        resultView.animate().alpha(1f).setDuration(300).start()
    }

    // --- HELPER FUNCTIONS ---
    private fun createMainButton(icon: String) = TextView(this).apply {
        text = icon; textSize = 34f; gravity = Gravity.CENTER; setTextColor(Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFF9800.toInt()) }
    }
    private fun createSubButton(icon: String) = TextView(this).apply {
        text = icon; textSize = 26f; gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(160, 160).apply { topMargin = 25 }
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF333333.toInt()) }
    }
    private fun requestScreenshotPermission() {
        val intent = Intent(this, ScreenshotActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        startActivity(intent)
    }
    private fun isViewClicked(view: View, x: Float, y: Float): Boolean {
        val loc = IntArray(2).also { view.getLocationOnScreen(it) }
        return Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height).contains(x.toInt(), y.toInt())
    }
    private fun createTrashButton() {
        trashView = TextView(this).apply {
            text = "✕"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFF3B30.toInt()) }
        }
        trashParams = WindowManager.LayoutParams(220, 220, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 100
        }
    }
    private fun showTrash() { try { windowManager.addView(trashView, trashParams) } catch (e: Exception) {} }
    private fun hideTrash() { try { windowManager.removeView(trashView) } catch (e: Exception) {} }
    private fun isOverTrash(): Boolean {
        val tLoc = IntArray(2).also { trashView.getLocationOnScreen(it) }
        val oLoc = IntArray(2).also { overlayView.getLocationOnScreen(it) }
        return Rect(tLoc[0], tLoc[1], tLoc[0] + trashView.width, tLoc[1] + trashView.height).contains(oLoc[0] + overlayView.width/2, oLoc[1] + overlayView.height/2)
    }
    private fun snapToEdge() {
        val screenWidth = resources.displayMetrics.widthPixels
        val targetX = if (overlayParams.x < screenWidth / 2) 0 else screenWidth - overlayView.width
        ValueAnimator.ofInt(overlayParams.x, targetX).apply {
            duration = 300
            addUpdateListener { overlayParams.x = it.animatedValue as Int; windowManager.updateViewLayout(overlayView, overlayParams) }
            start()
        }
    }
    override fun onDestroy() { super.onDestroy(); try { windowManager.removeView(overlayView) } catch (e: Exception) {} }
    override fun onBind(intent: Intent?): IBinder? = null
}