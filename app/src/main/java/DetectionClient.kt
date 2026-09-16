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

/**
 * The single detection pipeline (images only), used by the overlay, the share sheet,
 * the quick-check tile and the Detector tab.
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
        400 -> "That file couldn't be analysed - try another"
        401, 403 -> "This version of OverlAI can't reach the detector - please update the app"
        413 -> "That file is too large to check"
        429 -> "Too many checks right now - try again in a little while"
        in 500..599 -> "Detection service is down - try later"
        else -> "Detection service error ($code)"
    }

    fun detectImage(context: Context, file: File,
                    onResult: (Int) -> Unit, onError: (String) -> Unit,
                    skipCache: Boolean = false) {
        val app = context.applicationContext

        // ✅ cache first: same image = same answer, zero quota
        val hash = UsageTracker.md5(file)
        if (hash != null && !skipCache) {
            UsageTracker.cachedScore(app, hash)?.let { cached ->
                main.post { onResult(cached) }
                return
            }
        }

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("models", "genai")
            .addFormDataPart("media", file.name, file.asRequestBody("image/jpeg".toMediaTypeOrNull()))
            .build()

        // Keys live in the proxy, not in the app. The token identifies the app build; the proxy
        // rate-limits per token and per IP, and can retire a token by version.
        val req = Request.Builder()
            .url("${BuildConfig.PROXY_BASE}/image")
            .addHeader("X-App-Token", BuildConfig.APP_TOKEN)
            .addHeader("X-App-Version", BuildConfig.VERSION_NAME)
            .post(body).build()
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
                        UsageTracker.increment(app)   // count it
                        if (hash != null) UsageTracker.storeScore(app, hash, pct)
                        main.post { onResult(pct) }
                    } catch (e: Exception) {
                        main.post { onError("Couldn't read the result") }
                    }
                }
            }
        })
    }

}