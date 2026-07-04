package com.example.test103

import android.content.Context
import android.graphics.*
import android.view.View

/**
 * Circular themed button with a stroke-drawn glyph — no emoji, no assets.
 * Modes: PLUS (+), CLOSE (✕), PHOTO (outline camera), VIDEO (outline camcorder).
 *  - applyTheme(dark): dark/light styling
 *  - setAccent(color): tint the glyph + ring with the app's accent color
 *  - setDanger(true): bright red fill + white glyph (used by the delete target)
 */
class OutlineIconView(context: Context, var mode: Mode) : View(context) {

    enum class Mode { PLUS, CLOSE, PHOTO, VIDEO }

    private var dark = true
    private var accent: Int? = null
    private var danger = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init { refreshColors() }

    fun applyTheme(isDark: Boolean) {
        dark = isDark
        refreshColors()
        invalidate()
    }

    /** Tints the glyph and ring with the app accent color (null = neutral). */
    fun setAccent(color: Int?) {
        accent = color
        refreshColors()
        invalidate()
    }

    /** Red destructive state for the drag-to-delete target. */
    fun setDanger(active: Boolean) {
        if (danger == active) return
        danger = active
        refreshColors()
        invalidate()
    }

    fun setModeAndRedraw(m: Mode) {
        mode = m
        invalidate()
    }

    private fun refreshColors() {
        if (danger) {
            bgPaint.color = Color.parseColor("#FF1744")
            ringPaint.color = Color.parseColor("#66FFFFFF")
            glyphPaint.color = Color.WHITE
            fillPaint.color = Color.WHITE
            return
        }
        if (dark) {
            bgPaint.color = Color.parseColor("#1C1C1E")
            ringPaint.color = Color.parseColor("#40FFFFFF")
            glyphPaint.color = Color.WHITE
            fillPaint.color = Color.WHITE
        } else {
            bgPaint.color = Color.parseColor("#F5F5F5")
            ringPaint.color = Color.parseColor("#22000000")
            glyphPaint.color = Color.parseColor("#111111")
            fillPaint.color = Color.parseColor("#111111")
        }
        accent?.let { a ->
            glyphPaint.color = a
            fillPaint.color = a
            // Ring picks up a translucent version of the accent
            ringPaint.color = Color.argb(90, Color.red(a), Color.green(a), Color.blue(a))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val s = minOf(w, h) / 56f          // scale relative to a 56dp design grid

        ringPaint.strokeWidth = 1.2f * s
        glyphPaint.strokeWidth = 2.8f * s

        val r = minOf(w, h) / 2f - ringPaint.strokeWidth
        canvas.drawCircle(cx, cy, r, bgPaint)
        canvas.drawCircle(cx, cy, r, ringPaint)

        when (mode) {
            Mode.PLUS -> {
                val len = 11f * s
                canvas.drawLine(cx - len, cy, cx + len, cy, glyphPaint)
                canvas.drawLine(cx, cy - len, cx, cy + len, glyphPaint)
            }
            Mode.CLOSE -> {
                val len = 8.5f * s
                canvas.drawLine(cx - len, cy - len, cx + len, cy + len, glyphPaint)
                canvas.drawLine(cx - len, cy + len, cx + len, cy - len, glyphPaint)
            }
            Mode.PHOTO -> {
                val bl = cx - 14f * s; val br = cx + 14f * s
                val bt = cy - 7f * s;  val bb = cy + 11f * s
                canvas.drawRoundRect(bl, bt, br, bb, 3.5f * s, 3.5f * s, glyphPaint)

                val hump = Path().apply {
                    moveTo(cx - 8f * s, bt)
                    lineTo(cx - 6f * s, bt - 4.5f * s)
                    lineTo(cx + 2f * s, bt - 4.5f * s)
                    lineTo(cx + 4f * s, bt)
                }
                canvas.drawPath(hump, glyphPaint)

                canvas.drawCircle(cx, (bt + bb) / 2f, 5.5f * s, glyphPaint)
                canvas.drawCircle(br - 4f * s, bt + 4f * s, 1.4f * s, fillPaint)
            }
            Mode.VIDEO -> {
                val bl = cx - 14f * s; val br = cx + 5f * s
                val bt = cy - 8f * s;  val bb = cy + 8f * s
                canvas.drawRoundRect(bl, bt, br, bb, 3.5f * s, 3.5f * s, glyphPaint)

                val lens = Path().apply {
                    moveTo(br, cy - 4f * s)
                    lineTo(cx + 13f * s, cy - 8f * s)
                    lineTo(cx + 13f * s, cy + 8f * s)
                    lineTo(br, cy + 4f * s)
                    close()
                }
                canvas.drawPath(lens, glyphPaint)

                canvas.drawCircle(bl + 6f * s, bt + 5f * s, 1.6f * s, fillPaint)
            }
        }
    }
}