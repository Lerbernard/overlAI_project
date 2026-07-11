package com.example.test103

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Simple detection-history store. Entries live as a JSON array in
 * SharedPreferences; thumbnails are small JPEGs in filesDir/history/.
 * Capped at MAX_ENTRIES - oldest entries (and their thumbnails) are pruned.
 */
object HistoryManager {

    private const val PREFS = "app_settings"
    private const val KEY = "history_v1"
    private const val MAX_ENTRIES = 50

    data class Entry(
        val timestamp: Long,
        val score: Int,          // 0–100 AI probability
        val source: String,      // "Overlay", "Image", "Video"
        val thumbPath: String?,  // null if no thumbnail
        val videoPath: String? = null   // ✅ playable copy, if this was a video
    )

    fun add(context: Context, score: Int, source: String,
            thumbnail: Bitmap? = null, videoPath: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))

        // Save a small thumbnail if given
        var thumbPath: String? = null
        if (thumbnail != null) {
            try {
                val dir = File(context.filesDir, "history").apply { mkdirs() }
                val f = File(dir, "t_${System.currentTimeMillis()}.jpg")
                val scaled = scaleDown(thumbnail, 640)   // bigger for the detail popup
                FileOutputStream(f).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                if (scaled !== thumbnail) scaled.recycle()
                thumbPath = f.absolutePath
            } catch (_: Exception) {}
        }

        val obj = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("score", score)
            put("source", source)
            put("thumb", thumbPath ?: JSONObject.NULL)
            put("video", videoPath ?: JSONObject.NULL)
        }

        // Newest first
        val newArr = JSONArray().put(obj)
        for (i in 0 until arr.length()) newArr.put(arr.getJSONObject(i))

        // Prune beyond cap (and delete orphaned thumbnails)
        while (newArr.length() > MAX_ENTRIES) {
            val removed = newArr.getJSONObject(newArr.length() - 1)
            removed.optString("thumb", null)?.let { p -> runCatching { File(p).delete() } }
            if (!removed.isNull("video")) runCatching { File(removed.optString("video")).delete() }
            newArr.remove(newArr.length() - 1)
        }

        prefs.edit().putString(KEY, newArr.toString()).apply()

        // ✅ keep the stats widget's "last result" fresh
        try { OverlayStatsWidget.updateAll(context) } catch (_: Exception) {}
    }

    fun getAll(context: Context): List<Entry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Entry(
                timestamp = o.optLong("ts"),
                score = o.optInt("score"),
                source = o.optString("source", "?"),
                thumbPath = if (o.isNull("thumb")) null else o.optString("thumb"),
                videoPath = if (o.isNull("video")) null else o.optString("video")
            ))
        }
        return out
    }

    /** ✅ Delete one entry (matched by timestamp) and its thumbnail. */
    fun remove(context: Context, timestamp: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optLong("ts") == timestamp) {
                if (!o.isNull("thumb")) runCatching { File(o.optString("thumb")).delete() }
                if (!o.isNull("video")) runCatching { File(o.optString("video")).delete() }
            } else {
                out.put(o)
            }
        }
        prefs.edit().putString(KEY, out.toString()).apply()
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY).apply()
        runCatching { File(context.filesDir, "history").deleteRecursively() }
        runCatching { File(context.filesDir, "videos").deleteRecursively() }
    }

    /** ✅ persist a playable copy of a checked video; keeps only the newest 10
     *  so storage stays bounded (older entries just lose their play button). */
    fun saveVideoCopy(context: Context, src: File): String? = try {
        val dir = File(context.filesDir, "videos").apply { mkdirs() }
        val dst = File(dir, "v_${System.currentTimeMillis()}.mp4")
        src.copyTo(dst, overwrite = true)
        dir.listFiles()?.sortedByDescending { it.name }?.drop(10)
            ?.forEach { runCatching { it.delete() } }
        dst.absolutePath
    } catch (_: Exception) { null }

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val ratio = maxOf(src.width, src.height).toFloat() / maxDim
        if (ratio <= 1f) return src
        return Bitmap.createScaledBitmap(
            src, (src.width / ratio).toInt(), (src.height / ratio).toInt(), true)
    }
}