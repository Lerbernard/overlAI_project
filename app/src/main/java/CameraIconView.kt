package com.example.test103

import android.content.Context
import android.graphics.*
import android.view.View

/**
 * Minimalist camera icon drawn in code — light rounded square with a bold
 * black outline camera (body, top hump, lens ring, flash dot), matching the
 * classic flat-icon style. No image assets needed.
 */
class CameraIconView(context: Context) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EDEDED")
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111111")
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111111")
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val s = w / 56f                       // scale factor relative to 56dp design

        strokePaint.strokeWidth = 3.2f * s

        // Rounded-square background
        canvas.drawRoundRect(0f, 0f, w, h, 14f * s, 14f * s, bgPaint)

        // Camera body
        val bodyLeft = 10f * s
        val bodyTop = 20f * s
        val bodyRight = w - 10f * s
        val bodyBottom = h - 12f * s
        canvas.drawRoundRect(bodyLeft, bodyTop, bodyRight, bodyBottom, 5f * s, 5f * s, strokePaint)

        // Top hump (viewfinder bump) — drawn as an open path sitting on the body
        val humpPath = Path().apply {
            moveTo(20f * s, bodyTop)
            lineTo(22.5f * s, 14.5f * s)
            lineTo(33.5f * s, 14.5f * s)
            lineTo(36f * s, bodyTop)
        }
        canvas.drawPath(humpPath, strokePaint)

        // Lens ring
        val cx = w / 2f
        val cy = (bodyTop + bodyBottom) / 2f
        canvas.drawCircle(cx, cy, 8.5f * s, strokePaint)

        // Flash dot (top-right inside the body)
        canvas.drawCircle(bodyRight - 6.5f * s, bodyTop + 6f * s, 1.9f * s, dotPaint)
    }
}
