package com.example.test103

import android.app.*
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.TypedValue
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: LinearLayout
    private lateinit var mainButton: TextView
    private lateinit var cameraBtn: TextView
    private lateinit var deleteBtn: TextView
    private lateinit var overlayParams: WindowManager.LayoutParams

    private var mediaProjection: MediaProjection? = null
    private var isExpanded = false

    companion object {
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        // Android 14+ requires specific foregroundServiceType for screen capture
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
        createOverlayUI()
    }

    private fun createOverlayUI() {
        val mainSize = dpToPx(60)
        val subSize = dpToPx(50)

        mainButton = TextView(this).apply {
            text = "+"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#6200EE")) }
            layoutParams = LinearLayout.LayoutParams(mainSize, mainSize)
        }

        cameraBtn = TextView(this).apply {
            text = "📸"; textSize = 20f; gravity = Gravity.CENTER; visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(subSize, subSize).apply { topMargin = dpToPx(10) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#6200EE")) }
        }

        deleteBtn = TextView(this).apply {
            text = "🗑"; textSize = 20f; gravity = Gravity.CENTER; visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(subSize, subSize).apply { topMargin = dpToPx(10) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.RED) }
        }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            background = GradientDrawable().apply { cornerRadius = dpToPx(35).toFloat(); setColor(Color.parseColor("#121212")); alpha = 230 }
        }
        overlayView.addView(mainButton)
        overlayView.addView(deleteBtn)
        overlayView.addView(cameraBtn)

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
        mainButton.text = if (isExpanded) "✕" else "+"
    }

    private fun takeScreenshot() {
        if (mediaProjection == null) {
            val i = Intent(this, ScreenshotActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("EXTRA_ACTION", "ACTION_SHOT")
            }
            startActivity(i)
            return
        }

        overlayView.visibility = View.GONE

        // Small delay to let the overlay hide before capturing
        Handler(Looper.getMainLooper()).postDelayed({
            val m = resources.displayMetrics

            // FIX: Use 2 buffers to prevent lag, but close quickly to prevent crash
            val reader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2)
            val vd = mediaProjection?.createVirtualDisplay(
                "Screenshot", m.widthPixels, m.heightPixels, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, reader.surface, null, null
            )

            reader.setOnImageAvailableListener({ ir ->
                ir.setOnImageAvailableListener(null, null) // Stop listening immediately
                val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener

                val plane = img.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * m.widthPixels

                val bmp = Bitmap.createBitmap(
                    m.widthPixels + rowPadding / pixelStride,
                    m.heightPixels,
                    Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(buffer)

                // Final cropped bitmap (removes padding added by some devices)
                val finalBmp = Bitmap.createBitmap(bmp, 0, 0, m.widthPixels, m.heightPixels)
                saveToGallery(finalBmp)

                // CRITICAL CLEANUP: Release resources to prevent crash on next shot
                vd?.release()
                img.close()
                reader.close()

                overlayView.visibility = View.VISIBLE
            }, Handler(Looper.getMainLooper()))
        }, 450)
    }

    private fun saveToGallery(bitmap: Bitmap) {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Shot_${System.currentTimeMillis()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Screenshots")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { s -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, s) }
            MediaScannerConnection.scanFile(this, arrayOf(it.toString()), null, null)
            Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        val code = intent?.getIntExtra("RESULT_CODE", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("DATA")
        val action = intent?.getStringExtra("EXTRA_ACTION")

        if (code == Activity.RESULT_OK && data != null) {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(code, data)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { mediaProjection = null }
            }, Handler(Looper.getMainLooper()))

            if (action == "ACTION_SHOT") takeScreenshot()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("overlay_ch", "Screen Capture", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP_SERVICE }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, "overlay_ch")
            .setContentTitle("Screen Tool Active")
            .setContentText("Tap the bubble to capture. Tap Stop to finish.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Recording", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        mediaProjection?.stop()
        mediaProjection = null
        try { windowManager.removeView(overlayView) } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}