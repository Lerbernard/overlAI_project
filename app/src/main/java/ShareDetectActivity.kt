package com.example.test103

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream

class ShareDetectActivity : Activity() {

private lateinit var statusText: TextView
private lateinit var scoreText: TextView
private lateinit var verdictText: TextView
private lateinit var progress: ProgressBar
private lateinit var preview: ImageView

private val MAX_VIDEO_BYTES = 40L * 1024 * 1024   // sync endpoint wants small clips

private fun dp(v: Int): Int = TypedValue.applyDimension(
TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
buildUi()

val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
} else {
@Suppress("DEPRECATION")
intent.getParcelableExtra(Intent.EXTRA_STREAM)
}
val type = intent.type ?: ""

if (intent.action != Intent.ACTION_SEND || uri == null) {
statusText.text = "Nothing to analyse"
progress.visibility = android.view.View.GONE
return
}

when {
type.startsWith("image/") -> handleImage(uri)
type.startsWith("video/") -> handleVideo(uri)
else -> {
statusText.text = "Unsupported file type"
progress.visibility = android.view.View.GONE
}
}
}
private fun handleImage(uri: Uri) {
val file = copyToCache(uri, "shared_image.jpg") ?: run { fail("Couldn't read the file"); return }
val bmp = BitmapFactory.decodeFile(file.absolutePath)
if (bmp == null) { fail("Couldn't read the image"); return }
preview.setImageBitmap(bmp)
preview.visibility = android.view.View.VISIBLE

DetectionClient.detectImage(file,
onResult = { pct ->
showScore(pct)
try { HistoryManager.add(this, pct, "Shared", bmp) } catch (_: Exception) {}
},
onError = { fail(it) })
}

private fun handleVideo(uri: Uri) {
val file = copyToCache(uri, "shared_video.mp4") ?: run { fail("Couldn't read the file"); return }
if (file.length() > MAX_VIDEO_BYTES) {
fail("Video too large — try a clip under ~30s")
return
}

var frame: Bitmap? = null
try {
val r = MediaMetadataRetriever()
r.setDataSource(file.absolutePath)
frame = r.getFrameAtTime(0)
r.release()
} catch (_: Exception) {}
frame?.let {
preview.setImageBitmap(it)
preview.visibility = android.view.View.VISIBLE
}

DetectionClient.detectVideo(file,
onResult = { pct ->
showScore(pct)
try { HistoryManager.add(this, pct, "Shared", frame) } catch (_: Exception) {}
},
onError = { fail(it) })
}

// ------------------------------------------------------------------

private fun showScore(pct: Int) {
progress.visibility = android.view.View.GONE
statusText.visibility = android.view.View.GONE
val color = when {
pct < 30 -> Color.parseColor("#4CAF50")
pct < 70 -> Color.parseColor("#FF9800")
else -> Color.parseColor("#FF1744")
}
scoreText.text = "$pct%"
scoreText.setTextColor(color)
scoreText.visibility = android.view.View.VISIBLE
verdictText.text = when {
pct < 30 -> "Likely real"
pct < 70 -> "Uncertain"
else -> "Likely AI-generated"
}
verdictText.setTextColor(color)
verdictText.visibility = android.view.View.VISIBLE
}

private fun fail(msg: String) {
progress.visibility = android.view.View.GONE
statusText.text = msg
}

    private fun copyToCache(uri: Uri, name: String): File? {
        return try {
            val f = File(cacheDir, name)
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { inp ->
                FileOutputStream(f).use { out -> inp.copyTo(out) }
            }
            f
        } catch (e: Exception) {
            null
        }
    }
// ------------------------------------------------------------------

private fun buildUi() {
val dark = ThemeHelper.isDark(this)

val card = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
gravity = Gravity.CENTER_HORIZONTAL
setPadding(dp(24), dp(24), dp(24), dp(20))
background = GradientDrawable().apply {
cornerRadius = dp(24).toFloat()
setColor(if (dark) Color.parseColor("#1C1C1E") else Color.WHITE)
}
}

card.addView(TextView(this).apply {
text = "overlAI"
textSize = 18f
setTypeface(null, Typeface.BOLD)
setTextColor(Color.parseColor("#6200EE"))
})

preview = ImageView(this).apply {
visibility = android.view.View.GONE
adjustViewBounds = true
scaleType = ImageView.ScaleType.FIT_CENTER
clipToOutline = true
background = GradientDrawable().apply { cornerRadius = dp(14).toFloat() }
}
card.addView(preview, LinearLayout.LayoutParams(dp(220), dp(200)).apply { topMargin = dp(14) })

progress = ProgressBar(this)
card.addView(progress, LinearLayout.LayoutParams(dp(36), dp(36)).apply { topMargin = dp(14) })

statusText = TextView(this).apply {
text = "Analysing…"
textSize = 14f
setTextColor(if (dark) Color.parseColor("#BBBBBB") else Color.parseColor("#555555"))
}
card.addView(statusText, LinearLayout.LayoutParams(
ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

scoreText = TextView(this).apply {
visibility = android.view.View.GONE
textSize = 40f
setTypeface(null, Typeface.BOLD)
}
card.addView(scoreText)

verdictText = TextView(this).apply {
visibility = android.view.View.GONE
textSize = 15f
}
card.addView(verdictText)

card.addView(TextView(this).apply {
text = "Close"
textSize = 15f
setTypeface(null, Typeface.BOLD)
setTextColor(Color.parseColor("#6200EE"))
setPadding(dp(20), dp(12), dp(20), dp(4))
setOnClickListener { finish() }
}, LinearLayout.LayoutParams(
ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

val root = FrameLayout(this).apply {
setBackgroundColor(Color.parseColor("#99000000"))
addView(card, FrameLayout.LayoutParams(
dp(300), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
setOnClickListener { finish() }
}
setContentView(root)
}
}