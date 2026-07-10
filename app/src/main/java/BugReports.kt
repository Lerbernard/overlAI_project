package com.example.test103

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.io.ByteArrayOutputStream

/**
 * ✅ Bug reports table: collection "bug_reports" in Firestore.
 * Screenshots are compressed and stored INSIDE the report (subcollection
 * "attachments"), so the whole report arrives automatically - nothing to send.
 */
object BugReports {

    fun submit(context: Context, description: String, imageUris: List<Uri>) {
        val appCtx = context.applicationContext
        Thread {
            try {
                val version = try {
                    appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName ?: "?"
                } catch (e: Exception) { "?" }

                val row = hashMapOf(
                    "description" to description,
                    "appVersion" to version,
                    "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "android" to Build.VERSION.RELEASE,
                    "uid" to (Account.uid() ?: ""),
                    "email" to (Account.email() ?: ""),
                    "imageCount" to imageUris.size,
                    "status" to "new",
                    "createdAt" to FieldValue.serverTimestamp()
                )
                FirebaseFirestore.getInstance().collection("bug_reports").add(row)
                    .addOnSuccessListener { ref ->
                        // ✅ up to 4 screenshots, compressed to fit Firestore's 1MB doc cap
                        Thread {
                            imageUris.take(4).forEachIndexed { i, uri ->
                                val b64 = encodeImage(appCtx, uri) ?: return@forEachIndexed
                                ref.collection("attachments")
                                    .add(mapOf("index" to i, "mime" to "image/jpeg", "image" to b64))
                            }
                        }.start()
                    }
                    .addOnFailureListener { e -> Log.e("BugReports", "submit failed", e) }
            } catch (e: Exception) {
                Log.e("BugReports", "submit failed", e)
            }
        }.start()
    }

    /** sampled decode + JPEG compress + base64, kept safely under the doc limit */
    private fun encodeImage(context: Context, uri: Uri): String? = try {
        // pass 1: bounds only
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) null else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1280 ||
                   bounds.outHeight / (sample * 2) >= 1280) sample *= 2
            // pass 2: sampled decode (huge camera shots stay small in memory)
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            var quality = 80
            var bytes: ByteArray
            do {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                bytes = out.toByteArray()
                quality -= 15
            } while (bytes.size > 650_000 && quality > 20)  // base64 adds ~33%
            bmp.recycle()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    } catch (e: Exception) { Log.e("BugReports", "encode failed", e); null }
}
