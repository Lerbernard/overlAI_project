package com.example.test103

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream

/**
 * Reusable crop screen for the Detector tab (and anywhere else that needs a
 * result-based crop). Unlike ScreenshotActivity (which runs in its own task
 * for the overlay flow), this is a normal activity so startActivityForResult
 * works from fragments.
 *
 *   EXTRA_INPUT  - absolute path of the image to crop
 *   EXTRA_OUTPUT - absolute path to write the cropped PNG
 *   Result: RESULT_OK when cropped, RESULT_CANCELED otherwise.
 */
class CropActivity : Activity() {

    companion object {
        const val EXTRA_INPUT = "crop_input"
        const val EXTRA_OUTPUT = "crop_output"
        private val PURPLE = Color.parseColor("#6200EE")
    }

    private var cropView: CropView? = null
    private lateinit var outputPath: String

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val inputPath = intent.getStringExtra(EXTRA_INPUT)
        outputPath = intent.getStringExtra(EXTRA_OUTPUT) ?: ""

        val bitmap = inputPath?.let { BitmapFactory.decodeFile(it) }
        if (bitmap == null || outputPath.isEmpty()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        buildUi(bitmap)
    }

    private fun buildUi(bitmap: Bitmap) {
        val root = FrameLayout(this)
        val crop = CropView(this, bitmap)
        cropView = crop
        root.addView(crop, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val scrim = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#B3000000"))
            )
        }
        root.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(140), Gravity.BOTTOM))

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
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.alpha = 0.7f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                }
                false
            }
        }

        val cancelBtn = pill("Cancel", Color.parseColor("#33FFFFFF"), Color.WHITE,
            Color.parseColor("#66FFFFFF")).apply {
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        }

        val detectBtn = pill("Detect", PURPLE, Color.WHITE).apply {
            setOnClickListener { saveAndFinish() }
        }

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
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(36) })

        val hint = TextView(this).apply {
            text = "Drag the corners to select an area"
            textSize = 13f
            setTextColor(Color.parseColor("#CCFFFFFF"))
            gravity = Gravity.CENTER
        }
        root.addView(hint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(96) })

        setContentView(root)
    }

    private fun saveAndFinish() {
        try {
            val cropped = cropView?.getCroppedBitmap() ?: run {
                setResult(RESULT_CANCELED); finish(); return
            }
            FileOutputStream(File(outputPath)).use {
                cropped.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            setResult(RESULT_OK)
        } catch (e: Exception) {
            e.printStackTrace()
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    override fun onBackPressed() {
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }
}
