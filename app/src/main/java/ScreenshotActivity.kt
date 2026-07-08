package com.example.test103

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream

/**
 * Handles two jobs:
 *  1. ACTION_SHOT — asks for the MediaProjection permission and hands the token to OverlayService.
 *  2. ACTION_CROP — shows the in-app CropView.
 *
 * NOTE: this activity must have android:taskAffinity="" in the manifest so it
 * runs in its OWN task. Otherwise launching it drags MainActivity's task to the
 * foreground, and finishing the crop dumps the user back into the app instead
 * of the app they were actually looking at.
 */
class ScreenshotActivity : Activity() {

    private var cropView: CropView? = null
    private var retryCount = 0

    companion object {
        private const val MAX_FILE_RETRIES = 10
        private const val REQ_PROJECTION = 1001
        private val PURPLE = Color.parseColor("#6200EE")
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

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

    private fun loadScreenshotThenShowCropper() {
        val inputFile = File(cacheDir, if (isQuick) "input_quick.png" else "input.png")

        if (!inputFile.exists() || inputFile.length() == 0L) {
            if (retryCount++ < MAX_FILE_RETRIES) {
                Handler(Looper.getMainLooper()).postDelayed({ loadScreenshotThenShowCropper() }, 100)
            } else {
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

    private fun showCropUi(bitmap: Bitmap) {
        val root = FrameLayout(this)
        val crop = CropView(this, bitmap)
        cropView = crop
        root.addView(crop, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ── Bottom scrim so the buttons are readable over any screenshot ──
        val scrim = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#B3000000"))
            )
        }
        root.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(140), Gravity.BOTTOM))

        // ── Pill-shaped buttons ──
        fun pill(label: String, bg: Int, textCol: Int, strokeCol: Int? = null) = TextView(this).apply {
            text = label
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.02f
            gravity = Gravity.CENTER
            setTextColor(textCol)
            minWidth = dp(130)
            setPadding(dp(28), dp(13), dp(28), dp(13))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(bg)
                strokeCol?.let { setStroke(dp(1), it) }
            }
            isClickable = true
            // simple press feedback
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> v.alpha = 0.7f
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                }
                false
            }
        }

        val cancelBtn = pill(
            "Cancel",
            bg = Color.parseColor("#33FFFFFF"),          // frosted translucent
            textCol = Color.WHITE,
            strokeCol = Color.parseColor("#66FFFFFF")
        ).apply { setOnClickListener { notifyService("CROP_CANCELLED"); finish() } }

        val detectBtn = pill(
            "Detect",
            bg = PURPLE,
            textCol = Color.WHITE
        ).apply { setOnClickListener { saveCropAndReturn() } }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        bar.addView(cancelBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(12) })
        bar.addView(detectBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(12) })

        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(36) })

        // Small hint above the buttons
        val hint = TextView(this).apply {
            text = "Drag the corners to select an area"
            textSize = 13f
            setTextColor(Color.parseColor("#CCFFFFFF"))
            gravity = Gravity.CENTER
        }
        root.addView(hint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(96) })

        setContentView(root)
    }

    private fun saveCropAndReturn() {
        try {
            val cropped = cropView?.getCroppedBitmap() ?: run {
                notifyService("CROP_FAILED"); finish(); return
            }
            val outputFile = File(cacheDir, if (isQuick) "output_quick.png" else "output.png")
            FileOutputStream(outputFile).use {
                cropped.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            notifyService("CROP_DONE")
        } catch (e: Exception) {
            e.printStackTrace()
            notifyService("CROP_FAILED")
        }
        finish()
    }

    private val isQuick get() = intent.getStringExtra("TARGET") == "quick"

    private fun notifyService(action: String) {
        val target = if (isQuick) QuickShotService::class.java else OverlayService::class.java
        startService(Intent(this, target).apply {
            putExtra("EXTRA_ACTION", action)
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION) {
            val quick = intent.getStringExtra("TARGET") == "quick"
            if (resultCode == RESULT_OK && data != null) {
                if (quick) {
                    // ✅ quick-check tile flow — its own lightweight service
                    androidx.core.content.ContextCompat.startForegroundService(this,
                        Intent(this, QuickShotService::class.java).apply {
                            putExtra("RESULT_CODE", resultCode)
                            putExtra("DATA", data)
                        })
                } else {
                    startService(Intent(this, OverlayService::class.java).apply {
                        putExtra("RESULT_CODE", resultCode)
                        putExtra("DATA", data)
                        putExtra("MODE", intent.getStringExtra("MODE") ?: "photo")
                    })
                }
            } else if (!quick) {
                notifyService("CAPTURE_DENIED")
            }
            finish()
        }
    }

    override fun onBackPressed() {
        if (cropView != null) notifyService("CROP_CANCELLED")
        super.onBackPressed()
    }
}