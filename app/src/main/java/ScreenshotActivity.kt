package com.example.test103

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.io.File
import java.io.FileOutputStream

/**
 * Handles two jobs:
 *  1. ACTION_SHOT — asks for the MediaProjection permission and hands the token to OverlayService.
 *  2. ACTION_CROP — shows the in-app CropView (replaces the old external
 *     "com.android.camera.action.CROP" intent, which doesn't exist on most modern devices).
 */
class ScreenshotActivity : Activity() {

    private var cropView: CropView? = null
    private var retryCount = 0

    companion object {
        private const val MAX_FILE_RETRIES = 10   // 10 x 100ms = 1s max wait
        private const val REQ_PROJECTION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent.getStringExtra("EXTRA_ACTION")) {
            "ACTION_CROP" -> loadScreenshotThenShowCropper()
            else -> {
                val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(pm.createScreenCaptureIntent(), REQ_PROJECTION)
            }
        }
    }

    /** Waits (with a hard cap) for input.png to be flushed, then shows the crop UI. */
    private fun loadScreenshotThenShowCropper() {
        val inputFile = File(cacheDir, "input.png")

        if (!inputFile.exists() || inputFile.length() == 0L) {
            if (retryCount++ < MAX_FILE_RETRIES) {
                Handler(Looper.getMainLooper()).postDelayed({ loadScreenshotThenShowCropper() }, 100)
            } else {
                // File never showed up — tell the service so it can reset its UI.
                notifyService("CROP_FAILED")
                finish()
            }
            return
        }

        val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
        if (bitmap == null) {
            notifyService("CROP_FAILED")
            finish()
            return
        }

        showCropUi(bitmap)
    }

    private fun showCropUi(bitmap: android.graphics.Bitmap) {
        val root = FrameLayout(this)
        val crop = CropView(this, bitmap)
        cropView = crop
        root.addView(crop, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Bottom button bar: Cancel | Detect
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(32, 24, 32, 48)
        }

        fun makeButton(label: String, bg: Int, onClick: () -> Unit) = Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setBackgroundColor(bg)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = 16; marginEnd = 16 }
            setOnClickListener { onClick() }
        }

        bar.addView(makeButton("Cancel", Color.parseColor("#555555")) {
            notifyService("CROP_CANCELLED")
            finish()
        })

        bar.addView(makeButton("Detect", Color.parseColor("#6200EE")) {
            saveCropAndReturn()
        })

        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))

        setContentView(root)
    }

    private fun saveCropAndReturn() {
        try {
            val cropped = cropView?.getCroppedBitmap() ?: run {
                notifyService("CROP_FAILED"); finish(); return
            }
            val outputFile = File(cacheDir, "output.png")
            FileOutputStream(outputFile).use {
                cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            notifyService("CROP_DONE")
        } catch (e: Exception) {
            e.printStackTrace()
            notifyService("CROP_FAILED")
        }
        finish()
    }

    private fun notifyService(action: String) {
        startService(Intent(this, OverlayService::class.java).apply {
            putExtra("EXTRA_ACTION", action)
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                startService(Intent(this, OverlayService::class.java).apply {
                    putExtra("RESULT_CODE", resultCode)
                    putExtra("DATA", data)
                })
            } else {
                // User denied screen capture — reset the service UI instead of leaving "..."
                notifyService("CAPTURE_DENIED")
            }
            finish()
        }
    }

    override fun onBackPressed() {
        // Back during crop = cancel, and make sure the service resets
        if (cropView != null) notifyService("CROP_CANCELLED")
        super.onBackPressed()
    }
}