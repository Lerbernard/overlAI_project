package com.example.test103

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class DetectorFragment : Fragment() {

    private lateinit var imagePanel: FrameLayout
    private lateinit var imagePreviewCard: MaterialCardView
    private lateinit var btnPickImage: MaterialButton
    private lateinit var btnCheckImage: MaterialButton
    private lateinit var imagePreview: ImageView
    private lateinit var imageResultText: TextView
    private lateinit var imageStatusText: TextView

    private lateinit var videoPanel: FrameLayout
    private lateinit var videoContainer: MaterialCardView
    private lateinit var btnPickVideo: MaterialButton
    private lateinit var btnCheckVideo: MaterialButton
    private lateinit var videoPreview: VideoView
    private lateinit var videoResultText: TextView
    private lateinit var videoStatusText: TextView

    private var selectedImageUri: Uri? = null
    private var selectedVideoUri: Uri? = null
    private var videoIsPrepared = false

    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val PICK_IMAGE_REQUEST = 101
    private val PICK_VIDEO_REQUEST = 102
    private val CROP_IMAGE_REQUEST = 103

    companion object {
        private val API_USER   = BuildConfig.SE_API_USER   // was hardcoded
        private val API_SECRET = BuildConfig.SE_API_SECRET
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_detector, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val subTabLayout = view.findViewById<TabLayout>(R.id.subTabLayout)
        imagePanel       = view.findViewById(R.id.imagePanel)
        videoPanel       = view.findViewById(R.id.videoPanel)

        imagePreviewCard = view.findViewById(R.id.imagePreviewCard)
        btnPickImage     = view.findViewById(R.id.btnPickImage)
        btnCheckImage    = view.findViewById(R.id.btnCheckImage)
        imagePreview     = view.findViewById(R.id.imagePreview)
        imageResultText  = view.findViewById(R.id.imageResultText)
        imageStatusText  = view.findViewById(R.id.imageStatusText)

        videoContainer   = view.findViewById(R.id.videoContainer)
        btnPickVideo     = view.findViewById(R.id.btnPickVideo)
        btnCheckVideo    = view.findViewById(R.id.btnCheckVideo)
        videoPreview     = view.findViewById(R.id.videoPreview)
        videoResultText  = view.findViewById(R.id.videoResultText)
        videoStatusText  = view.findViewById(R.id.videoStatusText)

        btnCheckImage.visibility = View.GONE
        btnCheckVideo.visibility = View.GONE

        applyTheme(view)

        // ✅ No MediaController — tap to play/pause
        videoPreview.setOnClickListener {
            if (!videoIsPrepared) return@setOnClickListener
            if (videoPreview.isPlaying) {
                videoPreview.pause()
            } else {
                videoPreview.start()
            }
        }

        // ✅ Prepare but do NOT auto-play
        videoPreview.setOnPreparedListener { mp ->
            videoIsPrepared = true
            mp.isLooping = true
            // Show first frame by seeking to 0 without playing
            mp.seekTo(0)
        }

        subTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> { imagePanel.visibility = View.VISIBLE; videoPanel.visibility = View.GONE }
                    1 -> { imagePanel.visibility = View.GONE;    videoPanel.visibility = View.VISIBLE }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> clearImage()
                    1 -> clearVideo()
                }
            }
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // ✅ one "Choose media" button — auto-detects image vs video
        btnPickVideo.visibility = View.GONE
        btnPickImage.text = "Choose media"
        btnPickImage.setOnClickListener {
            clearImage()
            clearVideo()
            val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            }
            startActivityForResult(Intent.createChooser(pick, "Select image or video"), PICK_IMAGE_REQUEST)
        }
    }

    // ─── Clear helpers ────────────────────────────────────────────────────────

    private fun clearImage() {
        selectedImageUri = null
        imagePreview.setImageURI(null)
        imagePreviewCard.visibility = View.GONE
        imageResultText.text = ""
        imageStatusText.text = ""
    }

    private fun clearVideo() {
        selectedVideoUri = null
        videoIsPrepared = false
        videoPreview.stopPlayback()
        videoContainer.visibility = View.GONE
        videoResultText.text = ""
        videoStatusText.text = ""
    }

    // ─── Activity results ─────────────────────────────────────────────────────

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // ✅ Crop result carries no data uri — handle it before the null guard
        if (requestCode == CROP_IMAGE_REQUEST) {
            if (resultCode == RESULT_OK) {
                val out = File(requireContext().cacheDir, "detector_crop_out.png")
                val bmp = if (out.exists()) BitmapFactory.decodeFile(out.absolutePath) else null
                if (bmp != null) {
                    imagePreview.setImageBitmap(bmp)
                    imagePreviewCard.visibility = View.VISIBLE
                    imageResultText.text = ""
                    detectImageFile(out)
                } else {
                    imageStatusText.text = "Crop failed."
                }
            } else {
                imageStatusText.text = "Crop cancelled."
            }
            return
        }

        if (resultCode != RESULT_OK || data?.data == null) return

        when (requestCode) {
            PICK_IMAGE_REQUEST -> {
                // ✅ shared picker: route videos to the video flow
                val mime = requireContext().contentResolver.getType(data.data!!) ?: ""
                if (mime.startsWith("video/")) {
                    selectedVideoUri = data.data
                    videoIsPrepared = false
                    videoContainer.visibility = View.VISIBLE
                    videoPreview.setVideoURI(selectedVideoUri)
                    videoResultText.text = ""
                    runVideoDetection(selectedVideoUri!!)
                    return
                }
                selectedImageUri = data.data
                imageResultText.text = ""

                val cropEnabled = requireContext()
                    .getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
                    .getBoolean("use_crop", true)

                if (cropEnabled) {
                    // ✅ Crop Tool is ON — crop before detecting, same as the overlay
                    val input = copyUriToCache(selectedImageUri!!, "detector_crop_in.png")
                    if (input != null) {
                        startActivityForResult(
                            Intent(requireContext(), CropActivity::class.java).apply {
                                putExtra(CropActivity.EXTRA_INPUT, input.absolutePath)
                                putExtra(CropActivity.EXTRA_OUTPUT,
                                    File(requireContext().cacheDir, "detector_crop_out.png").absolutePath)
                            }, CROP_IMAGE_REQUEST)
                    } else {
                        imageStatusText.text = "Failed to read file."
                    }
                } else {
                    imagePreview.setImageURI(selectedImageUri)
                    imagePreviewCard.visibility = View.VISIBLE
                    runImageDetection(selectedImageUri!!)
                }
            }
            PICK_VIDEO_REQUEST -> {
                selectedVideoUri = data.data
                videoIsPrepared = false
                videoContainer.visibility = View.VISIBLE
                // ✅ setVideoURI triggers prepare but onPreparedListener won't auto-play
                videoPreview.setVideoURI(selectedVideoUri)
                videoResultText.text = ""
                runVideoDetection(selectedVideoUri!!)
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onPause() {
        super.onPause()
        if (videoPreview.isPlaying) videoPreview.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        videoPreview.stopPlayback()
    }

    // ─── Image Detection ──────────────────────────────────────────────────────

    private fun runImageDetection(uri: Uri) {
        val file = copyUriToCache(uri, "detector_image.png") ?: run {
            imageStatusText.text = "Failed to read file."
            return
        }
        detectImageFile(file)
    }

    private fun detectImageFile(file: File) {
        setImageLoading(true)
        imageStatusText.text = "Analysing…"
        imageResultText.text = ""

        // ✅ shared pipeline: cache hits are free, real calls are counted,
        // and errors come back as sentences
        DetectionClient.detectImage(requireContext(), file,
            onResult = { pct ->
                if (!isAdded) return@detectImage
                imageResultText.text = "$pct%"
                imageStatusText.text = buildVerdict(pct)
                imageResultText.setTextColor(scoreColor(pct))
                try {
                    val thumb = BitmapFactory.decodeFile(file.absolutePath)
                    HistoryManager.add(requireContext(), pct, "Image", thumb)
                    thumb?.recycle()
                } catch (_: Exception) {}
                setImageLoading(false)
            },
            onError = { msg ->
                if (!isAdded) return@detectImage
                imageStatusText.text = msg
                setImageLoading(false)
            })
    }

    // ─── Video Detection ──────────────────────────────────────────────────────

    private fun runVideoDetection(uri: Uri) {
        setVideoLoading(true)
        videoStatusText.text = "Uploading and analysing…"
        videoResultText.text = ""

        val file = copyUriToCache(uri, "detector_video.mp4") ?: run {
            videoStatusText.text = "Failed to read file."
            setVideoLoading(false)
            return
        }

        DetectionClient.detectVideo(requireContext(), file,
            onResult = { pct ->
                if (!isAdded) return@detectVideo
                videoResultText.text = "$pct%"
                videoStatusText.text = buildVerdict(pct)
                videoResultText.setTextColor(scoreColor(pct))
                try {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(file.absolutePath)
                    val frame = r.getFrameAtTime(0)
                    r.release()
                    HistoryManager.add(requireContext(), pct, "Video", frame)
                    frame?.recycle()
                } catch (_: Exception) {}
                setVideoLoading(false)
            },
            onError = { msg ->
                if (!isAdded) return@detectVideo
                videoStatusText.text = msg
                setVideoLoading(false)
            })
    }

    // ─── Theme ────────────────────────────────────────────────────────────────

    private fun applyTheme(view: View) {
        val t   = ThemeHelper
        val ctx = requireContext()
        view.setBackgroundColor(t.background(ctx))
        imagePanel.setBackgroundColor(t.background(ctx))
        videoPanel.setBackgroundColor(t.background(ctx))
        imagePreviewCard.setCardBackgroundColor(t.background(ctx))  // ✅ was t.card(ctx)
        videoContainer.setCardBackgroundColor(t.background(ctx))
        val subTab = view.findViewById<TabLayout>(R.id.subTabLayout)
        subTab.setBackgroundColor(t.background(ctx))
        subTab.setTabTextColors(t.tabText(ctx), t.tabIndicator(ctx))
        subTab.setSelectedTabIndicatorColor(t.tabIndicator(ctx))
        btnPickImage.backgroundTintList = ColorStateList.valueOf(t.primary(ctx))
        btnPickVideo.backgroundTintList = ColorStateList.valueOf(t.primary(ctx))
        imageResultText.setTextColor(t.textPrimary(ctx))
        imageStatusText.setTextColor(t.textSecondary(ctx))
        videoResultText.setTextColor(t.textPrimary(ctx))
        videoStatusText.setTextColor(t.textSecondary(ctx))
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun copyUriToCache(uri: Uri, fileName: String): File? {
        return try {
            val file = File(requireContext().cacheDir, fileName)
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun buildVerdict(pct: Int) = when {
        pct < 30 -> "✅ Likely real ($pct% AI score)"
        pct < 70 -> "⚠️ Uncertain ($pct% AI score)"
        else     -> "🚨 Likely AI-generated ($pct% AI score)"
    }

    private fun scoreColor(pct: Int) = when {
        pct < 30 -> Color.parseColor("#4CAF50")
        pct < 70 -> Color.parseColor("#FF9800")
        else     -> Color.RED
    }

    private fun setImageLoading(loading: Boolean) { btnPickImage.isEnabled = !loading }
    private fun setVideoLoading(loading: Boolean)  { btnPickVideo.isEnabled = !loading }
}