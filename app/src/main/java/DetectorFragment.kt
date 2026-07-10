package com.example.test103

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream

class DetectorFragment : Fragment() {

    private lateinit var titleText: TextView
    private lateinit var emptyState: LinearLayout
    private lateinit var emptyIcon: ImageView
    private lateinit var emptyHint: TextView
    private lateinit var previewCard: View
    private lateinit var previewImage: ImageView
    private lateinit var videoBadge: TextView
    private lateinit var scanLine: View
    private var scanAnim: android.animation.ObjectAnimator? = null
    private lateinit var statusText: TextView
    private lateinit var verdictCard: LinearLayout
    private lateinit var verdictText: TextView
    private lateinit var scorePill: TextView
    private lateinit var detailsCard: LinearLayout
    private lateinit var valLikelihood: TextView
    private lateinit var valConfidence: TextView
    private lateinit var valType: TextView
    private var lastIsVideo = false
    private var lastPreviewBmp: Bitmap? = null
    private var session = 0
    private lateinit var emptyIconWrap: android.widget.FrameLayout
    private lateinit var emptyTitle: TextView
    private var pulseAnim: android.animation.ObjectAnimator? = null
    private lateinit var btnChoose: MaterialButton
    private lateinit var btnLoader: android.widget.ProgressBar

    private val PICK_MEDIA = 201
    private val CROP_IMAGE = 202

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_detector, container, false)

        titleText = v.findViewById(R.id.detTitle)
        emptyState = v.findViewById(R.id.emptyState)
        emptyIcon = v.findViewById(R.id.emptyIcon)
        emptyHint = v.findViewById(R.id.emptyHint)
        emptyIconWrap = v.findViewById(R.id.emptyIconWrap)
        emptyTitle = v.findViewById(R.id.emptyTitle)
        emptyState.isClickable = true
        emptyState.setOnClickListener { openPicker() }   // ✅ the whole card is tappable
        previewCard = v.findViewById(R.id.previewCard)
        previewImage = v.findViewById(R.id.previewImage)
        videoBadge = v.findViewById(R.id.videoBadge)
        scanLine = v.findViewById(R.id.scanLine)
        statusText = v.findViewById(R.id.statusText)
        verdictCard = v.findViewById(R.id.verdictCard)
        verdictText = v.findViewById(R.id.verdictText)
        scorePill = v.findViewById(R.id.scorePill)
        detailsCard = v.findViewById(R.id.detailsCard)
        valLikelihood = v.findViewById(R.id.valLikelihood)
        valConfidence = v.findViewById(R.id.valConfidence)
        valType = v.findViewById(R.id.valType)
        btnChoose = v.findViewById(R.id.btnChoose)
        btnLoader = v.findViewById(R.id.btnLoader)

        previewImage.setOnClickListener { showFullPreview() }

        // ✅ let the pulsing badge draw outside its bounds - no more clipping
        emptyState.clipChildren = false
        emptyState.clipToPadding = false

        AdManager.loadBanner(v.findViewById(R.id.adContainer))
        AdManager.preloadInterstitial(requireContext())
        applyTheme(v)
        startPulse()

        btnChoose.setOnClickListener { openPicker() }
        return v
    }

    private fun openPicker() {
        clearResult()
        val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        }
        startActivityForResult(Intent.createChooser(pick, "Select image or video"), PICK_MEDIA)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == CROP_IMAGE) {
            if (resultCode == Activity.RESULT_OK) {
                val out = File(requireContext().cacheDir, "detector_crop_out.png")
                val bmp = if (out.exists()) decodeSampled(out.absolutePath) else null
                if (bmp != null) {
                    showPreview(bmp, isVideo = false)
                    detectImageFile(out)
                } else showMessage("Crop failed")
            } else showMessage("Crop cancelled")
            return
        }

        if (requestCode != PICK_MEDIA || resultCode != Activity.RESULT_OK || data?.data == null) return

        val uri = data.data!!
        val mime = requireContext().contentResolver.getType(uri) ?: ""
        if (mime.startsWith("video/")) handleVideo(uri) else handleImage(uri)
    }

    private fun handleImage(uri: Uri) {
        val input = copyUriToCache(uri, "detector_crop_in.png") ?: run {
            showMessage("Couldn't read the file"); return
        }

        val cropEnabled = requireContext()
            .getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getBoolean("use_crop", true)

        if (cropEnabled) {
            startActivityForResult(
                Intent(requireContext(), CropActivity::class.java).apply {
                    putExtra(CropActivity.EXTRA_INPUT, input.absolutePath)
                    putExtra(CropActivity.EXTRA_OUTPUT,
                        File(requireContext().cacheDir, "detector_crop_out.png").absolutePath)
                }, CROP_IMAGE)
        } else {
            val bmp = decodeSampled(input.absolutePath)
            if (bmp == null) { showMessage("Couldn't read the image"); return }
            showPreview(bmp, isVideo = false)
            detectImageFile(input)
        }
    }

    private fun handleVideo(uri: Uri) {
        val file = copyUriToCache(uri, "detector_video.mp4") ?: run {
            showMessage("Couldn't read the file"); return
        }

        // ✅ duration guard: the sync endpoint only handles short clips
        val durationMs = try {
            val r = MediaMetadataRetriever()
            r.setDataSource(file.absolutePath)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.release()
            d
        } catch (_: Exception) { 0L }
        if (durationMs > 31_000L) {
            file.delete()
            showMessage("Video too long - try a clip under 30 seconds")
            return
        }

        var frame: Bitmap? = null
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(file.absolutePath)
            frame = r.getFrameAtTime(0)
            r.release()
        } catch (_: Exception) {}
        frame?.let { showPreview(it, isVideo = true) }

        setLoading()
        val s = ++session
        DetectionClient.detectVideo(requireContext(), file,
            onResult = { pct ->
                if (s != session || !isAdded) return@detectVideo
                showResult(pct)
                try { HistoryManager.add(requireContext(), pct, "Video", frame) } catch (_: Exception) {}
            },
            onError = { msg ->
                if (s != session || !isAdded) return@detectVideo
                showMessage(msg)
            })
    }

    private fun detectImageFile(file: File) {
        setLoading()
        val s = ++session
        DetectionClient.detectImage(requireContext(), file,
            onResult = { pct ->
                if (s != session || !isAdded) return@detectImage
                showResult(pct)
                try {
                    val thumb = decodeSampled(file.absolutePath, maxDim = 512)
                    HistoryManager.add(requireContext(), pct, "Image", thumb)
                    thumb?.recycle()
                } catch (_: Exception) {}
            },
            onError = { msg ->
                if (s != session || !isAdded) return@detectImage
                showMessage(msg)
            })
    }

    private fun showPreview(bmp: Bitmap, isVideo: Boolean) {
        lastIsVideo = isVideo
        emptyState.visibility = View.GONE
        previewCard.visibility = View.VISIBLE
        previewImage.setImageBitmap(bmp)
        lastPreviewBmp = bmp
        videoBadge.visibility = if (isVideo) View.VISIBLE else View.GONE
    }

    /** ✅ tap the preview to view it fullscreen */
    private fun showFullPreview() {
        val bmp = lastPreviewBmp ?: return
        val d = android.app.Dialog(requireContext(),
            android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.setContentView(ImageView(requireContext()).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setOnClickListener { d.dismiss() }
        })
        d.show()
    }

    /** ✅ called when the user navigates to another tab */
    fun resetPage() {
        if (!isAdded || view == null) return
        session++                       // drop any in-flight callbacks
        stopScan()
        btnLoader.visibility = View.GONE
        btnChoose.isEnabled = true
        btnChoose.text = "Choose media"
        previewCard.visibility = View.GONE
        previewImage.setImageBitmap(null)
        lastPreviewBmp = null
        clearResult()
        emptyState.visibility = View.VISIBLE
    }

    private fun setLoading() {
        verdictCard.visibility = View.GONE
        detailsCard.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = "Analysing…"
        statusText.setTextColor(ThemeHelper.textSecondary(requireContext()))
        btnChoose.isEnabled = false
        btnChoose.text = "Analysing…"
        btnLoader.visibility = View.VISIBLE   // ✅ spinner in the button
        startScan()   // ✅ visual feedback while the request is in flight
    }

    /** ✅ teal line sweeping over the preview during analysis */
    private fun startScan() {
        scanLine.setBackgroundColor(ThemeHelper.accent(requireContext()))
        scanLine.visibility = View.VISIBLE
        previewImage.post {
            val travel = (previewImage.height - scanLine.height).coerceAtLeast(1).toFloat()
            scanAnim?.cancel()
            scanAnim = android.animation.ObjectAnimator.ofFloat(
                scanLine, "translationY", 0f, travel).apply {
                duration = 1100
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun stopScan() {
        scanAnim?.cancel()
        scanAnim = null
        scanLine.visibility = View.GONE
        scanLine.translationY = 0f
    }

    private fun showResult(pct: Int) {
        stopScan()
        btnLoader.visibility = View.GONE
        btnChoose.isEnabled = true
        btnChoose.text = "Check another"
        activity?.let { AdManager.maybeShowAfterCheck(it) }
        val t = ThemeHelper
        val ctx = requireContext()
        val c = scoreColor(pct)
        statusText.visibility = View.GONE

        // ✅ verdict banner: tinted + bordered in the result color
        verdictCard.visibility = View.VISIBLE
        verdictCard.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.argb(34, Color.red(c), Color.green(c), Color.blue(c)))
            setStroke(dp(1), c)
        }
        verdictText.text = when {
            pct < 30 -> "This looks like real content"
            pct < 70 -> "This one's hard to call"
            else -> "This looks AI-generated"
        }
        verdictText.setTextColor(t.textPrimary(ctx))
        scorePill.text = "$pct%"
        scorePill.setTextColor(c)
        scorePill.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.WHITE)
        }

        // ✅ details card
        detailsCard.visibility = View.VISIBLE
        detailsCard.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(t.card(ctx))
        }
        listOf(R.id.lblLikelihood, R.id.lblConfidence, R.id.lblType).forEach {
            view?.findViewById<TextView>(it)?.setTextColor(t.textSecondary(ctx))
        }
        listOf(R.id.detDiv1, R.id.detDiv2).forEach {
            view?.findViewById<View>(it)?.setBackgroundColor(ThemeHelper.divider(requireContext()))
        }
        valLikelihood.text = "$pct%"
        valLikelihood.setTextColor(c)
        valConfidence.text = when {
            kotlin.math.abs(pct - 50) >= 35 -> "High"
            kotlin.math.abs(pct - 50) >= 15 -> "Medium"
            else -> "Low"
        }
        valConfidence.setTextColor(t.textPrimary(ctx))
        valType.text = if (lastIsVideo) "Video" else "Image"
        valType.setTextColor(t.textPrimary(ctx))

        btnChoose.text = "Try another"
    }

    private fun showMessage(msg: String) {
        stopScan()
        btnLoader.visibility = View.GONE
        btnChoose.isEnabled = true
        btnChoose.text = "Choose media"
        verdictCard.visibility = View.GONE
        detailsCard.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = msg
        statusText.setTextColor(ThemeHelper.textSecondary(requireContext()))
    }

    private fun clearResult() {
        statusText.visibility = View.GONE
        verdictCard.visibility = View.GONE
        detailsCard.visibility = View.GONE
    }

    override fun onDestroyView() {
        stopScan()
        pulseAnim?.cancel()
        super.onDestroyView()
    }

    private fun buildVerdict(pct: Int): String = when {
        pct < 30 -> "Likely real"
        pct < 70 -> "Uncertain"
        else -> "Likely AI-generated"
    }

    private fun scoreColor(pct: Int) = ThemeHelper.scoreColor(requireContext(), pct)

    private fun copyUriToCache(uri: Uri, name: String): File? {
        return try {
            val f = File(requireContext().cacheDir, name)
            val input = requireContext().contentResolver.openInputStream(uri) ?: return null
            input.use { inp -> FileOutputStream(f).use { out -> inp.copyTo(out) } }
            f
        } catch (e: Exception) { null }
    }

    private fun startPulse() {
        pulseAnim?.cancel()
        // ✅ breathing float: the badge gently swells and rises together
        pulseAnim = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            emptyIconWrap,
            android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 1.06f),
            android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 1.06f),
            android.animation.PropertyValuesHolder.ofFloat("translationY", 0f, -dp(5).toFloat())
        ).apply {
            duration = 1600
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun applyTheme(root: View) {
        val t = ThemeHelper
        val ctx = requireContext()

        titleText.setTextColor(t.textPrimary(ctx))
        emptyTitle.setTextColor(t.textPrimary(ctx))
        emptyHint.setTextColor(t.textSecondary(ctx))
        emptyIcon.setColorFilter(t.accent(ctx))   // ✅ teal lens now
        emptyIconWrap.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(t.card(ctx))
        }
        // ✅ dropzone: solid purple border (colors swapped)
        emptyState.background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), t.primary(ctx))
        }
        // ✅ teal plus badge on the purple lens - both brand colors
        root.findViewById<TextView>(R.id.plusBadge)?.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(t.primary(ctx))   // ✅ purple badge on the teal lens
            }
            setTextColor(Color.WHITE)
        }
        listOf(R.id.chip1, R.id.chip2, R.id.chip3).forEach { id ->
            root.findViewById<TextView>(id)?.apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(t.card(ctx))
                }
                setTextColor(t.accent(ctx))   // ✅ teal words instead
                setOnClickListener { openPicker() }
            }
        }

        previewImage.clipToOutline = true
        previewImage.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(t.card(ctx))
        }

        videoBadge.setTextColor(Color.WHITE)
        videoBadge.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(ThemeHelper.scrim(requireContext()))
        }

        btnChoose.backgroundTintList = android.content.res.ColorStateList.valueOf(t.primary(ctx))
        btnChoose.setTextColor(Color.WHITE)
    }

    /** ✅ decode a file scaled down near [maxDim] px so huge camera photos
     *  (50MP ≈ 200MB decoded) can't exceed Android's canvas draw limit. */
    private fun decodeSampled(path: String, maxDim: Int = 1600): android.graphics.Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim ||
                   bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) { null }
    }
}