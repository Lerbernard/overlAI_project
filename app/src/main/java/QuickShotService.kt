package com.example.test103

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream

/**
 * ✅ Quick check: fired from its own QS tile. Takes a screenshot, runs
 * detection, saves the result to History, and posts a notification with the
 * score that opens the History tab. No overlay bubble involved.
 */
class QuickShotService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val CH_WORK = "quick_ch"
        private const val CH_RESULT = "quick_results"
        private const val ID_WORK = 20
        private const val ID_RESULT = 21
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // startForeground immediately (specialUse) so launching us is always legal
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_WORK, workNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_WORK, workNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ✅ crop round-trip results
        when (intent?.getStringExtra("EXTRA_ACTION")) {
            "CROP_DONE" -> {
                val f = File(cacheDir, "output_quick.png")
                val bmp = if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.absolutePath) else null
                if (bmp != null) detect(bmp) else finishWithError("Crop failed")
                return START_NOT_STICKY
            }
            "CROP_CANCELLED" -> { stopSelf(); return START_NOT_STICKY }
            "CROP_FAILED" -> { finishWithError("Crop failed"); return START_NOT_STICKY }
        }

        val code = intent?.getIntExtra("RESULT_CODE", -1) ?: -1
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (code != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // upgrade to the mediaProjection foreground type now that we hold consent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_WORK, workNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_WORK, workNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        }

        try {
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = pm.getMediaProjection(code, data)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { virtualDisplay = null }
            }, mainHandler)
            mediaProjection = mp
            // small delay so the permission dialog is off screen before we shoot
            mainHandler.postDelayed({ capture(mp) }, 450)
        } catch (e: Exception) {
            finishWithError("Screen capture failed")
        }
        return START_NOT_STICKY
    }

    private fun capture(mp: MediaProjection) {
        val m = resources.displayMetrics
        imageReader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2)

        val timeout = Runnable { finishWithError("Screen capture timed out") }
        mainHandler.postDelayed(timeout, 4000)

        try {
            virtualDisplay = mp.createVirtualDisplay(
                "QuickShot", m.widthPixels, m.heightPixels, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                imageReader!!.surface, null, null
            )

            imageReader?.setOnImageAvailableListener({ ir ->
                ir.setOnImageAvailableListener(null, null)
                mainHandler.removeCallbacks(timeout)

                val img = ir.acquireLatestImage()
                if (img == null) { finishWithError("Screen capture failed"); return@setOnImageAvailableListener }

                val plane = img.planes[0]
                val rowPadding = plane.rowStride - plane.pixelStride * m.widthPixels
                val raw = Bitmap.createBitmap(
                    m.widthPixels + rowPadding / plane.pixelStride, m.heightPixels,
                    Bitmap.Config.ARGB_8888)
                raw.copyPixelsFromBuffer(plane.buffer)
                val bmp = Bitmap.createBitmap(raw, 0, 0, m.widthPixels, m.heightPixels)
                if (bmp !== raw) raw.recycle()
                img.close()
                releaseProjection()

                // ✅ same Crop Tool setting as everywhere else
                val cropOn = getSharedPreferences("app_settings", MODE_PRIVATE)
                    .getBoolean("use_crop", true)
                if (cropOn) {
                    var saved = false
                    try {
                        FileOutputStream(File(cacheDir, "input_quick.png")).use {
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        saved = true
                    } catch (_: Exception) {}
                    bmp.recycle()
                    if (saved) {
                        startActivity(Intent(this@QuickShotService, ScreenshotActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra("EXTRA_ACTION", "ACTION_CROP")
                            putExtra("TARGET", "quick")
                        })
                        // service stays foreground, waiting for the crop result
                    } else {
                        finishWithError("Couldn't save the capture")
                    }
                } else {
                    detect(bmp)
                }
            }, mainHandler)
        } catch (e: Exception) {
            mainHandler.removeCallbacks(timeout)
            finishWithError("Screen capture failed")
        }
    }

    private fun detect(bmp: Bitmap) {
        val file = File(cacheDir, "quick_shot.jpg")
        try {
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } catch (e: Exception) {
            bmp.recycle()
            finishWithError("Couldn't save the capture")
            return
        }

        DetectionClient.detectImage(this, file,
            onResult = { pct ->
                try { HistoryManager.add(this, pct, "Quick check", bmp) } catch (_: Exception) {}
                bmp.recycle()
                postResult(pct)
                stopSelf()
            },
            onError = { msg ->
                bmp.recycle()
                finishWithError(msg)
            })
    }

    // ------------------------------------------------------------------

    private fun postResult(pct: Int) {
        val verdict = when {
            pct < 30 -> "Looks like real content"
            pct < 70 -> "Hard to call"
            else -> "Looks AI-generated"
        }
        notifyResult("AI likelihood: $pct%", "$verdict — tap to view in History")
    }

    private fun finishWithError(msg: String) {
        releaseProjection()
        notifyResult("Quick check failed", msg)
        stopSelf()
    }

    private fun notifyResult(title: String, text: String) {
        val open = PendingIntent.getActivity(this, 7,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("open_tab", 2)   // ✅ straight to History
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val n: Notification = NotificationCompat.Builder(this, CH_RESULT)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ID_RESULT, n)
    }

    private fun workNotification(): Notification =
        NotificationCompat.Builder(this, CH_WORK)
            .setContentTitle("Checking screen…")
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setOngoing(true)
            .build()

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_WORK, "Quick check", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(
                NotificationChannel(CH_RESULT, "Quick check results", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun releaseProjection() {
        virtualDisplay?.release(); virtualDisplay = null
        mediaProjection?.stop(); mediaProjection = null
        imageReader?.close(); imageReader = null
    }

    override fun onDestroy() {
        releaseProjection()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}

/**
 * ✅ The second QS tile: one tap = screenshot → score → History + notification.
 */
class QuickCheckTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE   // action tile: never "on"
            label = "Quick check"
            icon = android.graphics.drawable.Icon.createWithResource(
                this@QuickCheckTile, R.drawable.ic_nav_detector)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "Screenshot"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val i = Intent(this, ScreenshotActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("EXTRA_ACTION", "ACTION_SHOT")
            putExtra("MODE", "photo")
            putExtra("TARGET", "quick")   // ✅ result routes to QuickShotService
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(
                this, 8, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivity(i)
        }
    }
}
