package com.example.test103

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import java.io.File

class ScreenshotActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra("EXTRA_ACTION")

        if (action == "ACTION_CROP") {
            launchCropper()
        } else {
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(projectionManager.createScreenCaptureIntent(), 1001)
        }
    }

    private fun launchCropper() {
        try {
            val inputFile = File(cacheDir, "input.png")
            val outputFile = File(cacheDir, "output.png")

            // Delete old output if it exists
            if (outputFile.exists()) outputFile.delete()
            outputFile.createNewFile()

            val authority = "${packageName}.provider"
            val inputUri = FileProvider.getUriForFile(this, authority, inputFile)
            val outputUri = FileProvider.getUriForFile(this, authority, outputFile)

            val intent = Intent("com.android.camera.action.CROP").apply {
                setDataAndType(inputUri, "image/*")
                putExtra("crop", "true")

                // --- FREE ASPECT RATIO SETTINGS ---
                // Setting these to 0 or excluding them allows free-form resizing
                putExtra("aspectX", 0)
                putExtra("aspectY", 0)
                // Some newer gallery apps look for this specific flag
                putExtra("fixedAspectRatio", false)
                // ----------------------------------

                putExtra("scale", true)
                putExtra("return-data", false)
                putExtra(android.provider.MediaStore.EXTRA_OUTPUT, outputUri)

                // CRITICAL: Grant permissions for both reading the input and writing the output
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }

            // On some devices, we need to explicitly grant permission to the resolving app
            val resInfoList = packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resInfoList) {
                val pkgName = resolveInfo.activityInfo.packageName
                grantUriPermission(pkgName, outputUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Also grant read permission to the input file
                grantUriPermission(pkgName, inputUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivityForResult(intent, 1002)
        } catch (e: Exception) {
            e.printStackTrace()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val intent = Intent(this, OverlayService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
                putExtra("EXTRA_ACTION", this@ScreenshotActivity.intent.getStringExtra("EXTRA_ACTION"))
            }
            startService(intent)
        } else if (requestCode == 1002) {
            val intent = Intent(this, OverlayService::class.java).apply {
                putExtra("EXTRA_ACTION", "CROP_DONE")
            }
            startService(intent)
        }
        finish()
    }
}