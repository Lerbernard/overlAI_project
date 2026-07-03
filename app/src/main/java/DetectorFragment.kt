package com.example.test103

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
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

    companion object {
        private const val API_USER   = "958521540"
        private const val API_SECRET = "6vhjTqJ9qJpQo755FcQEpbkxgphfR3md"
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

        btnPickImage.setOnClickListener {
            clearImage()
            startActivityForResult(
                Intent.createChooser(
                    Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" },
                    "Select Image"
                ), PICK_IMAGE_REQUEST
            )
        }

        btnPickVideo.setOnClickListener {
            clearVideo()
            startActivityForResult(
                Intent.createChooser(
                    Intent(Intent.ACTION_GET_CONTENT).apply { type = "video/*" },
                    "Select Video"
                ), PICK_VIDEO_REQUEST
            )
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
        if (resultCode != RESULT_OK || data?.data == null) return

        when (requestCode) {
            PICK_IMAGE_REQUEST -> {
                selectedImageUri = data.data
                imagePreview.setImageURI(selectedImageUri)
                imagePreviewCard.visibility = View.VISIBLE
                imageResultText.text = ""
                runImageDetection(selectedImageUri!!)
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
        setImageLoading(true)
        imageStatusText.text = "Analysing…"
        imageResultText.text = ""

        val file = copyUriToCache(uri, "detector_image.png") ?: run {
            imageStatusText.text = "Failed to read file."
            setImageLoading(false)
            return
        }

        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("api_user", API_USER)
            .addFormDataPart("api_secret", API_SECRET)
            .addFormDataPart("models", "genai")
            .addFormDataPart("media", file.name, file.asRequestBody("image/png".toMediaTypeOrNull()))
            .build()

        client.newCall(
            Request.Builder()
                .url("https://api.sightengine.com/1.0/check.json")
                .post(requestBody)
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    imageStatusText.text = "Network error: ${e.message}"
                    setImageLoading(false)
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body?.string() ?: "{}"
                android.util.Log.d("SIGHTENGINE_IMAGE", "Code: ${response.code} Body: $bodyStr")
                mainHandler.post {
                    if (!response.isSuccessful) {
                        imageStatusText.text = "API error ${response.code}: $bodyStr"
                        setImageLoading(false)
                        return@post
                    }
                    try {
                        val json = JSONObject(bodyStr)
                        val score: Double =
                            json.optJSONObject("type")
                                ?.optDouble("ai_generated")
                                ?.takeIf { !it.isNaN() }
                                ?: json.optJSONObject("ai")
                                    ?.optDouble("ai_generated")
                                    ?.takeIf { !it.isNaN() }
                                ?: json.optDouble("ai_generated")
                                    .takeIf { !it.isNaN() && it != 0.0 }
                                ?: run {
                                    imageStatusText.text = "Response: $bodyStr"
                                    setImageLoading(false)
                                    return@post
                                }
                        val pct = (score * 100).toInt()
                        imageResultText.text = "$pct%"
                        imageStatusText.text = buildVerdict(pct)
                        imageResultText.setTextColor(scoreColor(pct))
                    } catch (e: Exception) {
                        imageStatusText.text = "Error: ${e.message} | $bodyStr"
                    }
                    setImageLoading(false)
                }
            }
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

        val mimeType = requireContext().contentResolver.getType(uri) ?: "video/mp4"

        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("api_user", API_USER)
            .addFormDataPart("api_secret", API_SECRET)
            .addFormDataPart("models", "genai")
            .addFormDataPart("media", file.name, file.asRequestBody(mimeType.toMediaTypeOrNull()))
            .build()

        client.newCall(
            Request.Builder()
                .url("https://api.sightengine.com/1.0/video/check-sync.json")
                .post(requestBody)
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    videoStatusText.text = "Network error: ${e.message}"
                    setVideoLoading(false)
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body?.string() ?: "{}"
                android.util.Log.d("SIGHTENGINE_VIDEO", "Code: ${response.code} Body: $bodyStr")
                mainHandler.post {
                    if (!response.isSuccessful) {
                        videoStatusText.text = "API error ${response.code}: $bodyStr"
                        setVideoLoading(false)
                        return@post
                    }
                    try {
                        val json = JSONObject(bodyStr)
                        val score: Double =
                            json.optJSONObject("summary")
                                ?.optJSONObject("genai")
                                ?.optDouble("ai_generated")
                                ?.takeIf { !it.isNaN() }
                                ?: json.optJSONObject("data")
                                    ?.optJSONObject("frames")
                                    ?.optDouble("ai_generated")
                                    ?.takeIf { !it.isNaN() }
                                ?: json.optJSONObject("type")
                                    ?.optDouble("ai_generated")
                                    ?.takeIf { !it.isNaN() }
                                ?: run {
                                    videoStatusText.text = "Response: $bodyStr"
                                    setVideoLoading(false)
                                    return@post
                                }
                        val pct = (score * 100).toInt()
                        videoResultText.text = "$pct%"
                        videoStatusText.text = buildVerdict(pct)
                        videoResultText.setTextColor(scoreColor(pct))
                    } catch (e: Exception) {
                        videoStatusText.text = "Error: ${e.message} | $bodyStr"
                    }
                    setVideoLoading(false)
                }
            }
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