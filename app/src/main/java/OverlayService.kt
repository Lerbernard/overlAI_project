package com.example.test103

import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator
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
import kotlin.math.hypot
import kotlin.math.max

/**
 * Floating overlay, v9.
 *
 * ARCHITECTURE: the window is a fixed-height FRAME tall enough for the fully
 * expanded stack. The button column (stackView) gravitates to the TOP or
 * BOTTOM of that frame. Expanding up or down is therefore pure view layout
 * inside a window that never moves or resizes — perfectly smooth both ways,
 * no gravity switching, no correction passes.
 *
 * Result: a colored pill attached to the stack showing [source icon | NN%].
 * Drag: throwable (velocity fling), trash appears after 300ms, deletes only
 * when the stack is close to it, snaps/flings to edges with padding.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var rootView: FrameLayout          // fixed-size window content
    private lateinit var stackView: LinearLayout        // the visible column
    private lateinit var mainButton: OutlineIconView
    private lateinit var photoBtn: OutlineIconView
    private lateinit var videoBtn: OutlineIconView
    private lateinit var resultChip: LinearLayout       // [mini icon | percentage]
    private lateinit var resultLabel: TextView
    private lateinit var overlayParams: WindowManager.LayoutParams

    private var deleteZoneView: FrameLayout? = null
    private var deleteIcon: OutlineIconView? = null
    private var deleteZoneHighlighted = false

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null

    private var isExpanded = false
    private var isProcessing = false
    private var isRecording = false
    private var recordStartMs = 0L
    private var stackAtBottom = false     // stack gravitates to frame bottom = grows upward
    private var lastMode = "photo"        // which source produced the pending result
    private val mainHandler = Handler(Looper.getMainLooper())

    private val BRIGHT_RED = Color.parseColor("#FF1744")
    private val PURPLE = Color.parseColor("#6200EE")
    private val TEAL = Color.parseColor("#03DAC5")

    companion object {
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        private const val MAX_RECORD_MS = 30_000L
        /** ✅ live state for the QS tile + widget */
        @Volatile var isRunning = false
            private set
    }

    // ---------------------------------------------------------------------
    // Geometry
    // ---------------------------------------------------------------------

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    private val mainSize: Int get() = dpToPx(64)
    private val subSize: Int get() = dpToPx(48)
    private val gap: Int get() = dpToPx(8)              // ✅ ONE spacing everywhere
    // frame tall enough for: main + photo + video + result pill + panel pad
    private val frameHeight: Int get() = mainSize + 3 * (subSize + gap) + gap + dpToPx(4)

    private val screenHeight: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(p)
            p.y
        }

    private val screenWidth: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(p)
            p.x
        }

    private val edgePadding: Int get() = dpToPx(18)
    private val deleteWindowSize: Int get() = dpToPx(120)
    private val deleteIconSize: Int get() = dpToPx(72)
    private val deleteBottomOffset: Int get() = dpToPx(28)
    private val deleteProximity: Int get() = dpToPx(80)

    /** Where the main button's top edge is on screen, regardless of direction. */
    private fun buttonTopOnScreen(): Int =
        overlayParams.y + if (stackAtBottom) frameHeight - mainSize else 0

    /** Window-y bounds that keep the main button fully on screen. */
    private fun clampWindowY(y: Int): Int {
        val minY = if (stackAtBottom) -(frameHeight - mainSize) else 0
        val maxY = if (stackAtBottom) screenHeight - frameHeight else screenHeight - mainSize
        return y.coerceIn(minY, maxY.coerceAtLeast(minY))
    }

    private val isDarkTheme: Boolean
        get() = getSharedPreferences("app_settings", MODE_PRIVATE).getBoolean("dark_mode", true)

    // ---------------------------------------------------------------------
    // UI construction
    // ---------------------------------------------------------------------

    private fun themedPanel() = GradientDrawable().apply {
        cornerRadius = dpToPx(32).toFloat()   // pill ends match the 64dp circle
        if (isDarkTheme) {
            setColor(Color.parseColor("#661A1A1C"))
            setStroke(dpToPx(1), Color.parseColor("#26FFFFFF"))
        } else {
            setColor(Color.parseColor("#80FFFFFF"))
            setStroke(dpToPx(1), Color.parseColor("#14000000"))
        }
    }

    /** Panel + growth-side padding only while more than the button is showing. */
    private fun refreshPanel() {
        val showPanel = isExpanded || resultChip.visibility == View.VISIBLE
        if (showPanel) {
            if (stackAtBottom) stackView.setPadding(0, gap, 0, 0)
            else stackView.setPadding(0, 0, 0, gap)
            stackView.background = themedPanel()
        } else {
            stackView.setPadding(0, 0, 0, 0)
            stackView.background = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlayUI()
        // ✅ persistent "overlay is on" notification via a specialUse
        // foreground service — also makes startForegroundService() safe again
        createNotificationChannel()
        goForeground(capturing = false)
        OverlayTileService.refresh(this)
        OverlayWidgetProvider.updateAll(this)
        OverlayStatsWidget.updateAll(this)
    }

    /** Foreground with the right type: specialUse while idle, +mediaProjection
     *  only while actually capturing (Android 14 forbids it earlier). */
    private fun goForeground(capturing: Boolean) {
        val notif = createNotification(recording = isRecording)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                val type = if (capturing)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                startForeground(1, notif, type)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && capturing ->
                startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else -> startForeground(1, notif)
        }
    }

    private fun createOverlayUI() {
        fun subButton(m: OutlineIconView.Mode) = OutlineIconView(this, m).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(subSize, subSize)
            applyTheme(isDarkTheme)
            setGlyphTint(TEAL)
        }

        mainButton = OutlineIconView(this, OutlineIconView.Mode.PLUS).apply {
            layoutParams = LinearLayout.LayoutParams(mainSize, mainSize)
            applyTheme(isDarkTheme)
            setGlyphTint(PURPLE)
        }

        photoBtn = subButton(OutlineIconView.Mode.PHOTO)
        videoBtn = subButton(OutlineIconView.Mode.VIDEO)

        // ✅ Result pill: percentage text, colored by the result. It expands
        // beneath the ACTUAL camera/video button that was tapped.
        resultLabel = TextView(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        resultChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(24).toFloat()
                setColor(Color.GRAY)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, subSize)
            addView(resultLabel)
        }

        stackView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        applyChildOrder()

        // Fixed-size frame; the stack floats to its top or bottom
        rootView = FrameLayout(this).apply {
            clipChildren = false
            addView(stackView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ))
        }

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, frameHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = edgePadding; y = dpToPx(80) }

        refreshPanel()
        makeDraggable()

        windowManager.addView(rootView, overlayParams)
    }

    /** Stack the children so growth happens away from the main button.
     *  ✅ every gap is identical (camera↔video same as video↔result). */
    private fun applyChildOrder() {
        stackView.removeAllViews()
        val subs = listOf(photoBtn, videoBtn, resultChip)
        if (stackAtBottom) {
            stackView.addView(resultChip)
            stackView.addView(videoBtn)
            stackView.addView(photoBtn)
            stackView.addView(mainButton)
            subs.forEach {
                (it.layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0; bottomMargin = gap }
            }
        } else {
            stackView.addView(mainButton)
            stackView.addView(photoBtn)
            stackView.addView(videoBtn)
            stackView.addView(resultChip)
            subs.forEach {
                (it.layoutParams as LinearLayout.LayoutParams).apply { topMargin = gap; bottomMargin = 0 }
            }
        }
    }

    /** Flip which end of the frame the stack hugs, keeping the button pinned.
     *  Pure integer math in ONE coordinate space — no correction needed. */
    private fun setDirectionUp(up: Boolean) {
        if (up == stackAtBottom) return
        val btnTop = buttonTopOnScreen()
        stackAtBottom = up
        (stackView.layoutParams as FrameLayout.LayoutParams).gravity =
            (if (up) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
        applyChildOrder()
        overlayParams.y = clampWindowY(btnTop - if (up) frameHeight - mainSize else 0)
        try { windowManager.updateViewLayout(rootView, overlayParams) } catch (_: Exception) {}
    }

    private fun maybeRestoreDirection() {
        if (stackAtBottom && !isExpanded && resultChip.visibility != View.VISIBLE) {
            setDirectionUp(false)
        }
    }

    // ---------------------------------------------------------------------
    // Expand / collapse — pure view animation inside the fixed frame
    // ---------------------------------------------------------------------

    private fun expandMenu() {
        if (isExpanded) return
        isExpanded = true

        if (!stackAtBottom) {
            val centerY = buttonTopOnScreen() + mainSize / 2
            if (centerY > screenHeight * 0.7) setDirectionUp(true)
        }

        refreshPanelAndTheme()

        val slide = dpToPx(14).toFloat() * (if (stackAtBottom) 1f else -1f)
        for (b in listOf(photoBtn, videoBtn)) {
            b.visibility = View.VISIBLE
            b.alpha = 0f
            b.translationY = slide
            b.animate().alpha(1f).translationY(0f)
                .setDuration(170).setInterpolator(DecelerateInterpolator()).start()
        }

        mainButton.setModeAndRedraw(OutlineIconView.Mode.CLOSE)
        mainButton.setGlyphTint(TEAL)
    }

    private fun collapseMenu() {
        if (!isExpanded) return
        isExpanded = false
        // ✅ collapsing dismisses everything, answer included
        photoBtn.visibility = View.GONE
        videoBtn.visibility = View.GONE
        resultChip.visibility = View.GONE
        mainButton.setModeAndRedraw(OutlineIconView.Mode.PLUS)
        mainButton.setGlyphTint(PURPLE)
        refreshPanel()
        maybeRestoreDirection()
    }

    private fun refreshPanelAndTheme() {
        val dark = isDarkTheme
        mainButton.applyTheme(dark)
        photoBtn.applyTheme(dark)
        videoBtn.applyTheme(dark)
        photoBtn.setGlyphTint(TEAL)
        videoBtn.setGlyphTint(TEAL)
        refreshPanel()
    }

    // ---------------------------------------------------------------------
    // Drag / throw / trash
    // ---------------------------------------------------------------------

    private var xAnimator: ValueAnimator? = null

    /** ✅ Attach move/throw handling to a view. A tap runs onTap; a drag moves
     *  the whole overlay — applied to every element so the expanded stack can
     *  be dragged from any part of it. */
    private fun attachDrag(view: View, onTap: () -> Unit) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var dragging = false
        var velocityTracker: VelocityTracker? = null

        val showTrashRunnable = Runnable { if (dragging) showDeleteZone() }

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    xAnimator?.cancel()
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    startX = overlayParams.x; startY = overlayParams.y
                    touchX = event.rawX; touchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        mainHandler.postDelayed(showTrashRunnable, 300)
                    }
                    if (dragging) {
                        overlayParams.x = (startX - dx).toInt().coerceAtLeast(0)
                        overlayParams.y = clampWindowY((startY + dy).toInt())
                        try { windowManager.updateViewLayout(rootView, overlayParams) } catch (_: Exception) {}
                        setDeleteZoneHighlight(isNearTrash(event.rawX, event.rawY))
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(showTrashRunnable)
                    if (dragging) {
                        val nearTrash = isNearTrash(event.rawX, event.rawY)
                        hideDeleteZone()
                        if (nearTrash && event.actionMasked == MotionEvent.ACTION_UP) {
                            velocityTracker?.recycle(); velocityTracker = null
                            stopSelf()
                            return@setOnTouchListener true
                        }
                        var vx = 0f; var vy = 0f
                        velocityTracker?.let {
                            it.addMovement(event)
                            it.computeCurrentVelocity(1000)
                            vx = it.xVelocity
                            vy = it.yVelocity
                        }
                        flingRelease(vx, vy, event.rawX)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onTap()
                    }
                    velocityTracker?.recycle(); velocityTracker = null
                    true
                }
                else -> false
            }
        }
    }

    private fun makeDraggable() {
        attachDrag(mainButton) { if (isExpanded) collapseMenu() else expandMenu() }
        attachDrag(photoBtn) { requestProjection(mode = "photo") }
        attachDrag(videoBtn) { requestProjection(mode = "video") }
        attachDrag(resultChip) { if (isRecording) stopRecording() else hideResultChip() }
    }

    /** YouTube-PiP style throw: velocity picks the edge, momentum carries y. */
    private fun flingRelease(vx: Float, vy: Float, releaseRawX: Float) {
        xAnimator?.cancel()
        val minFling = 600f

        val leftX = (screenWidth - rootView.width - edgePadding).coerceAtLeast(0)
        val rightX = edgePadding
        val targetX = when {
            vx > minFling -> rightX
            vx < -minFling -> leftX
            releaseRawX >= screenWidth / 2f -> rightX
            else -> leftX
        }

        val projected = (vy * 0.18f).toInt()
        val targetY = clampWindowY(overlayParams.y + projected)

        val startX = overlayParams.x
        val startY = overlayParams.y
        if (startX == targetX && startY == targetY) return

        val speed = hypot(vx, vy)
        val dur = (200 + (speed / 6000f) * 220).toLong().coerceIn(200L, 420L)

        xAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                overlayParams.x = (startX + (targetX - startX) * f).toInt()
                overlayParams.y = (startY + (targetY - startY) * f).toInt()
                try { windowManager.updateViewLayout(rootView, overlayParams) } catch (_: Exception) {}
            }
            start()
        }
    }

    /** Close only when the visible stack (not the empty frame) nears the trash. */
    private fun isNearTrash(rawX: Float, rawY: Float): Boolean {
        if (deleteZoneView == null) return false
        val trashCx = screenWidth / 2f
        val trashCy = screenHeight - deleteBottomOffset - deleteWindowSize / 2f

        val loc = IntArray(2)
        stackView.getLocationOnScreen(loc)
        val left = loc[0].toFloat()
        val top = loc[1].toFloat()
        val right = left + stackView.width
        val bottom = top + stackView.height

        val nx = trashCx.coerceIn(left, right)
        val ny = trashCy.coerceIn(top, bottom)
        val rectDist = hypot(trashCx - nx, trashCy - ny)
        val fingerDist = hypot(rawX - trashCx, rawY - trashCy)

        return minOf(rectDist, fingerDist) < deleteProximity
    }

    private fun showDeleteZone() {
        if (deleteZoneView != null) return

        val icon = OutlineIconView(this, OutlineIconView.Mode.TRASH).apply {
            applyTheme(isDarkTheme)
            setGlyphTint(BRIGHT_RED)
            layoutParams = FrameLayout.LayoutParams(deleteIconSize, deleteIconSize, Gravity.CENTER)
        }
        val container = FrameLayout(this).apply {
            addView(icon)
            alpha = 0f
        }

        val params = WindowManager.LayoutParams(
            deleteWindowSize, deleteWindowSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = deleteBottomOffset
        }

        try {
            windowManager.addView(container, params)
            deleteZoneView = container
            deleteIcon = icon
            container.animate().alpha(1f).setDuration(180).start()
        } catch (_: Exception) {}
    }

    private fun setDeleteZoneHighlight(active: Boolean) {
        if (active == deleteZoneHighlighted) return
        deleteZoneHighlighted = active
        val icon = deleteIcon ?: return
        icon.setDanger(active)
        icon.animate()
            .scaleX(if (active) 1.35f else 1f)
            .scaleY(if (active) 1.35f else 1f)
            .setDuration(130)
            .start()
    }

    private fun hideDeleteZone() {
        val zone = deleteZoneView ?: return
        deleteZoneView = null
        deleteIcon = null
        deleteZoneHighlighted = false
        zone.animate().alpha(0f).setDuration(180).withEndAction {
            try { windowManager.removeView(zone) } catch (_: Exception) {}
        }.start()
    }

    // ---------------------------------------------------------------------
    // Result pill
    // ---------------------------------------------------------------------

    private fun showResultChip() {
        mainHandler.post {
            if (resultChip.visibility == View.VISIBLE) return@post
            if (!stackAtBottom && !isExpanded) {
                val centerY = buttonTopOnScreen() + mainSize / 2
                if (centerY > screenHeight * 0.7) setDirectionUp(true)
            }
            // ✅ the result expands beneath the actual button that was tapped
            if (!isExpanded) {
                val srcBtn = if (lastMode == "video") videoBtn else photoBtn
                photoBtn.visibility = if (srcBtn === photoBtn) View.VISIBLE else View.GONE
                videoBtn.visibility = if (srcBtn === videoBtn) View.VISIBLE else View.GONE
                srcBtn.alpha = 1f
                srcBtn.translationY = 0f
            }
            resultChip.visibility = View.VISIBLE
            refreshPanel()
            resultChip.alpha = 0f
            resultChip.translationY = dpToPx(10).toFloat() * (if (stackAtBottom) 1f else -1f)
            resultChip.animate().alpha(1f).translationY(0f)
                .setDuration(170).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun hideResultChip() {
        mainHandler.post {
            if (resultChip.visibility != View.VISIBLE) return@post
            resultChip.visibility = View.GONE
            if (!isExpanded) {
                photoBtn.visibility = View.GONE
                videoBtn.visibility = View.GONE
            }
            refreshPanel()
            maybeRestoreDirection()
        }
    }

    private fun updateStatus(text: String) {
        mainHandler.post {
            resultLabel.text = text
            val pct = text.replace("%", "").toIntOrNull() ?: 0
            val color = when {
                text == "..." || text == "SEND" -> Color.GRAY
                !text.endsWith("%") -> Color.DKGRAY
                pct < 30 -> Color.parseColor("#4CAF50")
                pct < 70 -> Color.parseColor("#FF9800")
                else -> BRIGHT_RED
            }
            (resultChip.background as GradientDrawable).setColor(color)
        }
    }

    // ---------------------------------------------------------------------
    // Projection request (shared by photo + video)
    // ---------------------------------------------------------------------

    private fun requestProjection(mode: String) {
        if (isProcessing || isRecording) return
        lastMode = mode
        hideResultChip()
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
        rootView.visibility = View.GONE
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
                        rootView.visibility = View.VISIBLE

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
    // Screen video recording
    // ---------------------------------------------------------------------

    private val recordFile: File get() = File(cacheDir, "record.mp4")

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val elapsed = System.currentTimeMillis() - recordStartMs
            if (elapsed >= MAX_RECORD_MS) { stopRecording(); return }
            val s = (elapsed / 1000).toInt()
            resultLabel.text = String.format("%d:%02d", s / 60, s % 60)
            mainHandler.postDelayed(this, 500)
        }
    }

    private fun startRecording(mp: MediaProjection) {
        val m = resources.displayMetrics
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

            resultLabel.text = "0:00"
            (resultChip.background as GradientDrawable).setColor(BRIGHT_RED)
            showResultChip()
            mainHandler.postDelayed(timerRunnable, 500)

        } catch (e: Exception) {
            e.printStackTrace()
            releaseRecorder()
            stopMediaProjection()
            stopForegroundCompat()
            showResultChip()
            failStatus("REC ERR")
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        mainHandler.removeCallbacks(timerRunnable)

        var ok = true
        try { mediaRecorder?.stop() } catch (e: Exception) { ok = false }
        releaseRecorder()
        stopMediaProjection()
        stopForegroundCompat()

        if (ok && recordFile.exists() && recordFile.length() > 0) {
            updateStatus("SEND")
            runVideoDetection(recordFile)
        } else {
            failStatus("REC ERR")
        }
    }

    private fun releaseRecorder() {
        try { mediaRecorder?.reset() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
    }

    private fun runVideoDetection(file: File) {
        DetectionClient.detectVideo(this, file,
            onResult = { pct ->
                updateStatus("$pct%")
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(file.absolutePath)
                    val frame = retriever.getFrameAtTime(0)
                    retriever.release()
                    HistoryManager.add(this, pct, "Video", frame)
                    frame?.recycle()
                } catch (_: Exception) {
                    HistoryManager.add(this, pct, "Video", null)
                }
                file.delete()
            },
            onError = { msg ->
                failStatus(msg)
                file.delete()
            })
    }

    // ---------------------------------------------------------------------
    // Image detection + status
    // ---------------------------------------------------------------------

    private fun resetAfterCapture(status: String) {
        isProcessing = false
        rootView.visibility = View.VISIBLE
        showResultChip()
        failStatus(status)
        stopForegroundCompat()
    }

    /** ✅ short word in the pill + a human-readable toast */
    private fun failStatus(code: String) {
        val msg = when (code) {
            "TIMEOUT" -> "Screen capture timed out — try again"
            "NO IMG", "ERR", "PERM ERR" -> "Screen capture failed"
            "CROP ERR" -> "Crop failed"
            "EMPTY" -> "Nothing was selected"
            "REC ERR" -> "Recording failed"
            "FILE ERR" -> "Couldn't save the capture"
            else -> code   // already human-readable (from DetectionClient)
        }
        updateStatus("Error")
        mainHandler.post {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun runAiDetection(bitmap: Bitmap) {
        val file = File(cacheDir, "temp_detect.jpg")
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } catch (e: Exception) {
            failStatus("FILE ERR")
            bitmap.recycle()
            return
        }

        // ✅ shared pipeline: cached results are free, real calls are counted,
        // and errors come back as sentences instead of codes
        DetectionClient.detectImage(this, file,
            onResult = { pct ->
                updateStatus("$pct%")
                try { HistoryManager.add(this, pct, "Overlay", bitmap) } catch (_: Exception) {}
                bitmap.recycle()
            },
            onError = { msg ->
                bitmap.recycle()
                failStatus(msg)
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
                else { showResultChip(); failStatus("EMPTY") }
                return START_STICKY
            }
            "CROP_CANCELLED", "CAPTURE_DENIED" -> {
                isProcessing = false
                hideResultChip()
                return START_STICKY
            }
            "CROP_FAILED" -> {
                isProcessing = false
                showResultChip(); failStatus("CROP ERR")
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
            goForeground(capturing = true)

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

    /** ✅ after a capture, fall back to the idle specialUse foreground
     *  (keeps the "overlay is on" notification alive). */
    private fun stopForegroundCompat() {
        goForeground(capturing = false)
    }

    private fun reallyStopForeground() {
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

        val openIntent = PendingIntent.getActivity(this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, "overlay_ch")
            .setContentTitle(if (recording) "overlAI — recording screen…" else "overlAI overlay is on")
            .setContentText(if (recording) "Tap the timer chip to stop" else "Tap to open the app")
            .setContentIntent(openIntent)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn off", stopPendingIntent)
            .build()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // ✅ rotation: re-clamp so the bubble can't be stranded off-screen
        if (isExpanded) collapseMenu()
        hideDeleteZone()
        overlayParams.x = overlayParams.x.coerceIn(
            0, (screenWidth - rootView.width - edgePadding).coerceAtLeast(0))
        overlayParams.y = clampWindowY(overlayParams.y)
        try { windowManager.updateViewLayout(rootView, overlayParams) } catch (_: Exception) {}
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
        xAnimator?.cancel()
        try { reallyStopForeground() } catch (_: Exception) {}
        try { windowManager.removeView(rootView) } catch (_: Exception) {}
        isRunning = false
        OverlayTileService.refresh(this)
        OverlayWidgetProvider.updateAll(this)
        OverlayStatsWidget.updateAll(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}