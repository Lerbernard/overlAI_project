package com.example.test103

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Calendar

/**
 * ✅ Quota awareness:
 *  - counts API calls per month (images / videos separately)
 *  - caches image results by file hash so re-checking the same picture
 *    never burns a second API call
 */
object UsageTracker {

    private const val PREFS = "app_settings"
    private const val CACHE_KEY = "detect_cache_v1"
    private const val CACHE_CAP = 300

    private fun monthKey(): String {
        val c = Calendar.getInstance()
        return "${c.get(Calendar.YEAR)}_${c.get(Calendar.MONTH) + 1}"
    }

    fun increment(context: Context, video: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = (if (video) "usage_vid_" else "usage_img_") + monthKey()
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    /** Pair(imageCalls, videoCalls) for the current month. */
    fun counts(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Pair(
            prefs.getInt("usage_img_" + monthKey(), 0),
            prefs.getInt("usage_vid_" + monthKey(), 0)
        )
    }

    // ------------------------------------------------------------------
    // Result cache (images only — same pixels, same answer)
    // ------------------------------------------------------------------

    fun md5(file: File): String? = try {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { null }

    fun cachedScore(context: Context, hash: String): Int? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val obj = JSONObject(prefs.getString(CACHE_KEY, "{}") ?: "{}")
        return if (obj.has(hash)) obj.optInt(hash) else null
    }

    fun storeScore(context: Context, hash: String, pct: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var obj = JSONObject(prefs.getString(CACHE_KEY, "{}") ?: "{}")
        if (obj.length() >= CACHE_CAP) obj = JSONObject()   // simple reset when full
        obj.put(hash, pct)
        prefs.edit().putString(CACHE_KEY, obj.toString()).apply()
    }
}
