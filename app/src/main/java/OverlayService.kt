package com.example.test103

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
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
import kotlin.math.max

/**
 * Overlay with dropdown:
 *   [+]  → tap → [○ camera] [○ video] outline icons appear (themed to app dark/light)
 * Photo → screenshot → (optional crop) → AI score in a result chip.
 * Record → screen recording (auto-stops at 30s), timer chip, tap chip to stop,
 *          then the video is sent to the Sightengine video endpoint.
 * Drag the icon → full-width red delete zone at the bottom.
 * Menu opens upward when the icon sits in the bottom 20% of the screen.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: LinearLayout
    private lateinit var mainButton: OutlineIconView
    private lateinit var photoBtn: OutlineIconView
    private lateinit var videoBtn: OutlineIconView
    private lateinit var resultText: TextView   // doubles as recording timer chip
    private lateinit var overlayParams: WindowManager.LayoutParams

    private var deleteZoneView: FrameLayout? = null
    private var deleteZoneHighlighted = false

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null

    private var isExpanded = false
    private var isProcessing = false
    private var isRecording = false
    private var recordStartMs = 0L
    private var upward = false        // menu/result direction, decided when opening
    private var currentLift = 0       // how many stepHeights the window is shifted up
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val BRIGHT_RED = Color.parseColor("#FF1744")

    companion object {
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        private const val MAX_RECORD_MS = 30_000L   // Sightengine sync endpoint wants short clips
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    private val screenHeight: Int get() = resources.displayMetrics.heightPixels
    private val deleteZoneHeight: Int get() = dpToPx(120)
    private val stepHeight: Int get() = dpToPx(46 + 8)

    private val isDarkTheme: Boolean
        get() = getSharedPreferences("app_settings", MODE_PRIVATE).getBoolean("dark_mode", true)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlayUI()
    }

    private fun createOverlayUI() {
        val mainSize = dpToPx(56)
        val subSize = dpToPx(46)

        fun subButton(m: OutlineIconView.Mode) = OutlineIconView(this, m).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(subSize, subSize).apply { topMargin = dpToPx(8) }
            applyTheme(isDarkTheme)
        }

        // ✅ Back to the + / ✕ system, drawn as themed outline glyphs
        mainButton = OutlineIconView(this, OutlineIconView.Mode.PLUS).apply {
            layoutParams = LinearLayout.LayoutParams(mainSize, mainSize)
            applyTheme(isDarkTheme)
        }

        photoBtn = subButton(OutlineIconView.Mode.PHOTO)
        videoBtn = subButton(OutlineIconView.Mode.VIDEO)

        resultText = TextView(this).apply {
            text = ""; textSize = 13f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(subSize, subSize).apply { topMargin = dpToPx(8) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.GRAY) }
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (isRecording) stopRecording() else { resultText.visibility = View.GONE; relayout() }
            }
        }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            background = null
        }

        relayout()   // adds children in the right order

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dpToPx(16); y = dpToPx(60) }

        makeDraggable()

        photoBtn.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            collapseMenu()
            requestProjection(mode = "photo")
        }
        videoBtn.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            collapseMenu()
            requestProjection(mode = "video")
        }

        windowManager.addView(overlayView, overlayParams)
    }

    // ---------------------------------------------------------------------
    // Layout engine: one function decides order + window lift from state
    // ---------------------------------------------------------------------

    private fun relayout() {
        val resultVisible = resultText.visibility == View.VISIBLE
        val menuVisible = isExpanded

        // Desired lift (in steps) when the stack grows upward
        val desiredLift = if (!upward) 0 else
            (if (menuVisible) 2 else 0) + (if (resultVisible) 1 else 0)

        photoBtn.visibility = if (menuVisible) View.VISIBLE else View.GONE
        videoBtn.visibility = if (menuVisible) View.VISIBLE else View.GONE

        overlayView.removeAllViews()
        val subs = listOf(photoBtn, videoBtn, resultText)
        if (upward) {
            // furthest-from-main first: result, video, photo, MAIN
            overlayView.addView(resultText)
            overlayView.addView(videoBtn)
            overlayView.addView(photoBtn)
            overlayView.addView(mainButton)
            subs.forEach {
                (it.layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0; bottomMargin = dpToPx(8) }
            }
        } else {
            overlayView.addView(mainButton)
            overlayView.addView(photoBtn)
            overlayView.addView(videoBtn)
            overlayView.addView(resultText)
            subs.forEach {
                (it.layoutParams as LinearLayout.LayoutParams).apply { topMargin = dpToPx(8); bottomMargin = 0 }
            }
        }

        // Shift the window so the main icon stays visually in place
        if (::overlayParams.isInitialized) {
            val delta = desiredLift - currentLift
            if (delta != 0) {
                overlayParams.y = (overlayParams.y - delta * stepHeight).coerceAtLeast(0)
                try { windowManager.updateViewLayout(overlayView, overlayParams) } catch (_: Exception) {}
            }
        }
        currentLift = desiredLift

        if (!menuVisible && !resultVisible) upward = false
    }

    private fun expandMenu() {
        if (isExpanded) return
        isExpanded = true
        // ✅ direction decided here: upward only in the bottom 20%
        if (currentLift == 0) {
            val centerY = overlayParams.y + overlayView.height / 2
            upward = centerY > screenHeight * 0.8
        }
        // ✅ theme can change while the service lives — refresh on every open
        val dark = isDarkTheme
        mainButton.applyTheme(dark)
        photoBtn.applyTheme(dark)
        videoBtn.applyTheme(dark)
        mainButton.setModeAndRedraw(OutlineIconView.Mode.CLOSE)
        relayout()
    }

    private fun collapseMenu() {
        if (!isExpanded) return
        isExpanded = false
        mainButton.setModeAndRedraw(OutlineIconView.Mode.PLUS)
        relayout()
    }

    // ---------------------------------------------------------------------
    // Drag / tap
    // ---------------------------------------------------------------------

    private fun makeDraggable() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var dragging = false

        mainButton.setOnTouchListener { v, event ->
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
                        collapseMenu()
                        if (!isRecording) { resultText.visibility = View.GONE; relayout() }
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
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        if (isExpanded) collapseMenu() else expandMenu()
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
            WindowManager.LayoutParams.MATCH_PARENT, deleteZoneHeight,
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
            Color.parseColor("#66FF1744"), Color.parseColor("#E6FF1744"), BRIGHT_RED
        ) else intArrayOf(
            Color.TRANSPARENT, Color.parseColor("#99FF1744"), Color.parseColor("#E6FF1744")
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
    // Projection request (shared by photo + video)
    // ---------------------------------------------------------------------

    private fun requestProjection(mode: String) {
        if (isProcessing || isRecording) return
        resultText.visibility = View.GONE
        relayout()
        startActivity(Intent(this, ScreenshotActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("EXTRA_ACTION", "ACTION_SHOT")
            putExtra("MODE", mode)
        })
    }

    // ---------------------------------------------------------------------
    // Photo capture
    // ---------------------------------------------------------------------

    private fun performCapture(mp: MediaProjection) {
        isProcessing = true
        overlayView.visibility = View.GONE
        updateStatus("...")

        mainHandler.postDelayed({
            val m = resources.displayMetrics
            imageReader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2)

            val timeoutRunnable = Runnable {
                if (isProcessing) { stopMediaProjection(); resetAfterCapture("TIMEOUT") }
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
                    if (img == null) { stopMediaProjection(); resetAfterCapture("NO IMG"); return@setOnImageAvailableListener }

                    val plane = img.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * m.widthPixels

                    val raw = Bitmap.createBitmap(
                        m.widthPixels + rowPadding / pixelStride, m.heightPixels, Bitmap.Config.ARGB_8888)
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
                            FileOutputStream(inputFile).use { finalBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            saved = true
                        } catch (e: Exception) { e.printStackTrace() }

                        if (isCropEnabled && saved) {
                            finalBmp.recycle()
                            startActivity(Intent(this@OverlayService, ScreenshotActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra("EXTRA_ACTION", "ACTION_CROP")
                            })
                        } else {
                            showResultChip()
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

    // ---------------------------------------------------------------------
    // ✅ NEW: screen video recording → Sightengine video endpoint
    // ---------------------------------------------------------------------

    private val recordFile: File get() = File(cacheDir, "record.mp4")

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val elapsed = System.currentTimeMillis() - recordStartMs
            if (elapsed >= MAX_RECORD_MS) { stopRecording(); return }
            val s = (elapsed / 1000).toInt()
            resultText.text = String.format("%d:%02d", s / 60, s % 60)
            mainHandler.postDelayed(this, 500)
        }
    }

    private fun startRecording(mp: MediaProjection) {
        val m = resources.displayMetrics
        // Cap the long edge at 1280 to keep files small; encoder needs even sizes
        val scale = max(m.widthPixels, m.heightPixels) / 1280f
        val vw = if (scale > 1f) (m.widthPixels / scale).toInt() and 0xFFFE else m.widthPixels and 0xFFFE
        val vh = if (scale > 1f) (m.heightPixels / scale).toInt() and 0xFFFE else m.heightPixels and 0xFFFE

        try {
            recordFile.delete()
            @Suppress("DEPRECATION")
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(recordFile.absolutePath)
            rec.setVideoSize(vw, vh)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoEncodingBitRate(4_000_000)
            rec.setVideoFrameRate(30)
            rec.prepare()

            virtualDisplay = mp.createVirtualDisplay(
                "Recording", vw, vh, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                rec.surface, null, null
            )

            rec.start()
            mediaRecorder = rec
            isRecording = true
            recordStartMs = System.currentTimeMillis()

            // Recording chip: red, shows timer, tap to stop
            resultText.text = "0:00"
            (resultText.background as GradientDrawable).setColor(BRIGHT_RED)
            resultText.visibility = View.VISIBLE
            relayout()
            mainHandler.postDelayed(timerRunnable, 500)

        } catch (e: Exception) {
            e.printStackTrace()
            releaseRecorder()
            stopMediaProjection()
            stopForegroundCompat()
            showResultChip()
            updateStatus("REC ERR")
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        mainHandler.removeCallbacks(timerRunnable)

        var ok = true
        try { mediaRecorder?.stop() } catch (e: Exception) { ok = false }   // throws if too short
        releaseRecorder()
        stopMediaProjection()
        stopForegroundCompat()

        if (ok && recordFile.exists() && recordFile.length() > 0) {
            updateStatus("SEND")
            runVideoDetection(recordFile)
        } else {
            updateStatus("REC ERR")
        }
    }

    private fun releaseRecorder() {
        try { mediaRecorder?.reset() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
    }

    private fun runVideoDetection(file: File) {
        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("api_user", BuildConfig.SE_API_USER)
            .addFormDataPart("api_secret", BuildConfig.SE_API_SECRET)
            .addFormDataPart("models", "genai")
            .addFormDataPart("media", file.name, file.asRequestBody("video/mp4".toMediaTypeOrNull()))
            .build()

        val request = Request.Builder()
            .url("https://api.sightengine.com/1.0/video/check-sync.json")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { updateStatus("NET ERR") }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyStr = it.body?.string() ?: "{}"
                    if (!it.isSuccessful) { updateStatus("API ${it.code}"); return }
                    try {
                        val json = JSONObject(bodyStr)
                        // Same fallback chain as DetectorFragment, plus a frames-array scan
                        var score = json.optJSONObject("summary")
                            ?.optJSONObject("genai")?.optDouble("ai_generated")
                            ?.takeIf { d -> !d.isNaN() }
                        if (score == null) {
                            val frames = json.optJSONObject("data")?.optJSONArray("frames")
                            if (frames != null && frames.length() > 0) {
                                var maxScore = 0.0
                                for (i in 0 until frames.length()) {
                                    val v = frames.optJSONObject(i)?.optJSONObject("type")
                                        ?.optDouble("ai_generated") ?: Double.NaN
                                    if (!v.isNaN()) maxScore = max(maxScore, v)
                                }
                                score = maxScore
                            }
                        }
                        if (score == null) { updateStatus("PARSE ERR"); return }

                        val pct = (score * 100).toInt()
                        updateStatus("$pct%")

                        // History entry with a frame thumbnail
                        try {
                            val retriever = MediaMetadataRetriever()
                            retriever.setDataSource(file.absolutePath)
                            val frame = retriever.getFrameAtTime(0)
                            retriever.release()
                            HistoryManager.add(this@OverlayService, pct, "Video", frame)
                            frame?.recycle()
                        } catch (_: Exception) {
                            HistoryManager.add(this@OverlayService, pct, "Video", null)
                        }
                    } catch (e: Exception) { updateStatus("JSON ERR") }
                    finally { file.delete() }
                }
            }
        })
    }

    // ---------------------------------------------------------------------
    // Result chip + status
    // ---------------------------------------------------------------------

    private fun showResultChip() {
        mainHandler.post {
            if (resultText.visibility == View.VISIBLE) return@post
            if (currentLift == 0 && !isExpanded) {
                val centerY = overlayParams.y + overlayView.height / 2
                upward = centerY > screenHeight * 0.8
            }
            resultText.visibility = View.VISIBLE
            relayout()
            resultText.alpha = 0f
            resultText.animate().alpha(1f).setDuration(150).start()
        }
    }

    private fun resetAfterCapture(status: String) {
        isProcessing = false
        overlayView.visibility = View.VISIBLE
        showResultChip()
        updateStatus(status)
        stopForegroundCompat()
    }

    private fun updateStatus(text: String) {
        mainHandler.post {
            resultText.text = text
            val pct = text.replace("%", "").toIntOrNull() ?: 0
            val color = when {
                text == "..." || text == "SEND" -> Color.GRAY
                !text.endsWith("%") -> Color.DKGRAY
                pct < 30 -> Color.parseColor("#4CAF50")
                pct < 70 -> Color.parseColor("#FF9800")
                else -> BRIGHT_RED
            }
            (resultText.background as GradientDrawable).setColor(color)
        }
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
            override fun onFailure(call: Call, e: IOException) { bitmap.recycle(); updateStatus("NET ERR") }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) { bitmap.recycle(); updateStatus("API ${it.code}"); return }
                    try {
                        val json = JSONObject(it.body?.string() ?: "{}")
                        val score = json.optJSONObject("type")?.optDouble("ai_generated") ?: 0.0
                        val pct = (score * 100).toInt()
                        updateStatus("$pct%")
                        try { HistoryManager.add(this@OverlayService, pct, "Overlay", bitmap) } catch (_: Exception) {}
                    } catch (e: Exception) { updateStatus("JSON ERR") }
                    finally { bitmap.recycle() }
                }
            }
        })
    }

    // ---------------------------------------------------------------------
    // Service plumbing
    // ---------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            if (isRecording) stopRecording()
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.getStringExtra("EXTRA_ACTION")) {
            "CROP_DONE" -> {
                isProcessing = false
                val croppedFile = File(cacheDir, "output.png")
                val bitmap = if (croppedFile.exists()) BitmapFactory.decodeFile(croppedFile.absolutePath) else null
                if (bitmap != null) { showResultChip(); runAiDetection(bitmap) }
                else { showResultChip(); updateStatus("EMPTY") }
                return START_STICKY
            }
            "CROP_CANCELLED", "CAPTURE_DENIED" -> {
                isProcessing = false
                mainHandler.post { resultText.visibility = View.GONE; relayout() }
                return START_STICKY
            }
            "CROP_FAILED" -> {
                isProcessing = false
                showResultChip(); updateStatus("CROP ERR")
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
        val mode = intent?.getStringExtra("MODE") ?: "photo"

        if (code == Activity.RESULT_OK && data != null) {
            createNotificationChannel()
            val notification = createNotification(recording = mode == "video")
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
                if (mode == "video") startRecording(mp) else performCapture(mp)
            } catch (e: Exception) {
                resetAfterCapture("PERM ERR")
            }
        }

        return START_STICKY
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("overlay_ch", "AI Tool", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(recording: Boolean = false): Notification {
        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP_SERVICE }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, "overlay_ch")
            .setContentTitle(if (recording) "overlAI Recording…" else "overlAI Active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        if (isRecording) {
            isRecording = false
            mainHandler.removeCallbacks(timerRunnable)
            try { mediaRecorder?.stop() } catch (_: Exception) {}
            releaseRecorder()
        }
        stopMediaProjection()
        hideDeleteZone()
        try { stopForegroundCompat() } catch (_: Exception) {}
        try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}