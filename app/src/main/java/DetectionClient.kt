package com.example.test103

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
 * Shared Sightengine client. Callbacks are delivered on the main thread.
 */
object DetectionClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())

    fun detectImage(file: File, onResult: (Int) -> Unit, onError: (String) -> Unit) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("api_user", BuildConfig.SE_API_USER)
            .addFormDataPart("api_secret", BuildConfig.SE_API_SECRET)
            .addFormDataPart("models", "genai")
            .addFormDataPart("media", file.name, file.asRequestBody("image/jpeg".toMediaTypeOrNull()))
            .build()

        val req = Request.Builder().url("https://api.sightengine.com/1.0/check.json").post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { onError("No internet connection") }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) { main.post { onError("Service error (${it.code})") }; return }
                    try {
                        val json = JSONObject(it.body?.string() ?: "{}")
                        val score = json.optJSONObject("type")?.optDouble("ai_generated") ?: 0.0
                        main.post { onResult((score * 100).toInt()) }
                    } catch (e: Exception) {
                        main.post { onError("Couldn't read the result") }
                    }
                }
            }
        })
    }

    fun detectVideo(file: File, onResult: (Int) -> Unit, onError: (String) -> Unit) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("api_user", BuildConfig.SE_API_USER)
            .addFormDataPart("api_secret", BuildConfig.SE_API_SECRET)
            .addFormDataPart("models", "genai")
            .addFormDataPart("media", file.name, file.asRequestBody("video/mp4".toMediaTypeOrNull()))
            .build()

        val req = Request.Builder().url("https://api.sightengine.com/1.0/video/check-sync.json").post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { onError("No internet connection") }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyStr = it.body?.string() ?: "{}"
                    if (!it.isSuccessful) { main.post { onError("Service error (${it.code})") }; return }
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
                        main.post { onResult((score * 100).toInt()) }
                    } catch (e: Exception) {
                        main.post { onError("Couldn't read the result") }
                    }
                }
            }
        })
    }
}
