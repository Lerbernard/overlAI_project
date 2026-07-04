package com.example.test103

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * The single detection pipeline, used by the overlay, share sheet (and can
 * replace DetectorFragment's networking too).
 *  ✅ human-readable error messages
 *  ✅ image results cached by file hash (no repeat API calls)
 *  ✅ every real API call counted for the monthly usage display
 * Callbacks arrive on the main thread.
 */
object DetectionClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())

    /** ✅ one place that turns HTTP codes into words a person understands */
    private fun humanError(code: Int): String = when (code) {
        400 -> "Request rejected — check your API keys"
        401, 403 -> "API keys invalid or missing"
        429 -> "Monthly API quota reached"
        in 500..599 -> "Detection service is down — try later"
        else -> "Detection service error ($code)"
    }

    fun detectImage(context: Context, file: File,
                    onResult: (Int) -> Unit, onError: (String) -> Unit) {
        val app = context.applicationContext

        // ✅ cache first: same image = same answer, zero quota
        val hash = UsageTracker.md5(file)
        if (hash != null) {
            UsageTracker.cachedScore(app, hash)?.let { cached ->
                main.post { onResult(cached) }
                return
            }
        }

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("api_user", BuildConfig.SE_API_USER)
            .addFormDataPart("api_secret", BuildConfig.SE_API_SECRET)
            .addFormDataPart("models", "genai")
            .addFormDataPart("media", file.name, file.asRequestBody("image/jpeg".toMediaTypeOrNull()))
            .build()

        val req = Request.Builder().url("https://api.sightengine.com/1.0/check.json").post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { onError("Check your internet connection") }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) { main.post { onError(humanError(it.code)) }; return }
                    try {
                        val json = JSONObject(it.body?.string() ?: "{}")
                        val score = json.optJSONObject("type")?.optDouble("ai_generated") ?: 0.0
                        val pct = (score * 100).toInt()
                        UsageTracker.increment(app, video = false)   // ✅ count it
                        if (hash != null) UsageTracker.storeScore(app, hash, pct)
                        main.post { onResult(pct) }
                    } catch (e: Exception) {
                        main.post { onError("Couldn't read the result") }
                    }
                }
            }
        })
    }

    fun detectVideo(context: Context, file: File,
                    onResult: (Int) -> Unit, onError: (String) -> Unit) {
        val app = context.applicationContext

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("api_user", BuildConfig.SE_API_USER)
            .addFormDataPart("api_secret", BuildConfig.SE_API_SECRET)
            .addFormDataPart("models", "genai")
            .addFormDataPart("media", file.name, file.asRequestBody("video/mp4".toMediaTypeOrNull()))
            .build()

        val req = Request.Builder().url("https://api.sightengine.com/1.0/video/check-sync.json").post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { onError("Check your internet connection") }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyStr = it.body?.string() ?: "{}"
                    if (!it.isSuccessful) { main.post { onError(humanError(it.code)) }; return }
                    try {
                        val json = JSONObject(bodyStr)
                        var score = json.optJSONObject("summary")
                            ?.optJSONObject("genai")?.optDouble("ai_generated")
                            ?.takeIf { d -> !d.isNaN() }
                        if (score == null) {
                            val frames = json.optJSONObject("data")?.optJSONArray("frames")
                            if (frames != null && frames.length() > 0) {
                                var m = 0.0
                                for (i in 0 until frames.length()) {
                                    val v = frames.optJSONObject(i)?.optJSONObject("type")
                                        ?.optDouble("ai_generated") ?: Double.NaN
                                    if (!v.isNaN()) m = max(m, v)
                                }
                                score = m
                            }
                        }
                        if (score == null) { main.post { onError("Couldn't read the result") }; return }
                        UsageTracker.increment(app, video = true)   // ✅ count it
                        main.post { onResult((score * 100).toInt()) }
                    } catch (e: Exception) {
                        main.post { onError("Couldn't read the result") }
                    }
                }
            }
        })
    }
}