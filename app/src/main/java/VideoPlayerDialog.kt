package com.example.test103

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File

/**
 * ✅ Shared video player popup: shows the first frame with a centered play
 * button and NEVER autoplays. Tap play to start; tap the video to pause;
 * finishing returns to the first frame with the play button back.
 */
object VideoPlayerDialog {

    fun show(context: Context, path: String) {
        if (!File(path).exists()) {
            Toast.makeText(context, "Video file is no longer available", Toast.LENGTH_SHORT).show()
            return
        }
        fun dp(v: Int) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

        val video = VideoView(context)
        val playBtn = TextView(context).apply {
            text = "\u25B6"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, 0, 0)   // optical centering of the triangle
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(170, 20, 20, 24))
            }
            layoutParams = FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER)
        }

        val frame = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420), Gravity.CENTER))
            addView(playBtn)
        }

        val dialog = AlertDialog.Builder(context).setView(frame).create()
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.BLACK)
        })

        video.setVideoPath(path)
        video.setOnPreparedListener { mp ->
            mp.isLooping = false
            video.seekTo(1)   // ✅ first frame visible, but do NOT play
        }
        video.setOnCompletionListener {
            playBtn.visibility = View.VISIBLE
            video.seekTo(1)
        }
        playBtn.setOnClickListener {
            playBtn.visibility = View.GONE
            video.start()
        }
        video.setOnClickListener {
            if (video.isPlaying) {
                video.pause()
                playBtn.visibility = View.VISIBLE
            }
        }
        dialog.setOnDismissListener { runCatching { video.stopPlayback() } }
        dialog.show()
    }
}
