package com.example.test103

import android.content.Context
import android.graphics.*
import android.view.View

/**
 * Themed button with a stroke-drawn glyph — no emoji, no assets.
 * ✅ Borderless: circles are pure fills, no ring stroke.
 * ✅ Every color comes from colors.xml / colors-dark.xml via ThemeHelper.
 */
class OutlineIconView(
    context: Context,
    var mode: Mode,
    private val shapeStyle: Shape = Shape.CIRCLE
) : View(context) {

    enum class Mode { PLUS, CLOSE, PHOTO, VIDEO, TRASH }
    enum class Shape { CIRCLE, ROUNDED_SQUARE }

    private var dark = true
    private var fill: Int? = null
    private var glyphTint: Int? = null
    private var danger = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        refreshColors()
        contentDescription = descFor(mode)   // ✅ TalkBack support
    }

    private fun descFor(m: Mode) = when (m) {
        Mode.PLUS -> "Open OverlAI menu"
        Mode.CLOSE -> "Close menu"
        Mode.PHOTO -> "Check a photo"
        Mode.VIDEO -> "Record and check video"
        Mode.TRASH -> "Remove overlay"
    }

    fun applyTheme(isDark: Boolean) {
        dark = isDark
        refreshColors()
        invalidate()
    }

    /** Solid colored background with a white glyph. null = neutral theme look. */
    fun setFill(color: Int?) {
        fill = color
        refreshColors()
        invalidate()
    }

    /** Neutral themed background, colored glyph. */
    fun setGlyphTint(color: Int?) {
        glyphTint = color
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
        contentDescription = descFor(m)
        invalidate()
    }

    private fun refreshColors() {
        val white = androidx.core.content.ContextCompat.getColor(context, R.color.white)
        when {
            danger -> {
                bgPaint.color = ThemeHelper.scoreHigh(context)
                glyphPaint.color = white
                fillPaint.color = white
            }
            fill != null -> {
                bgPaint.color = fill!!
                glyphPaint.color = white
                fillPaint.color = white
            }
            else -> {
                bgPaint.color = androidx.core.content.ContextCompat.getColor(context,
                    if (dark) R.color.dm_overlay_btn else R.color.lm_overlay_btn)
                val glyph = androidx.core.content.ContextCompat.getColor(context,
                    if (dark) R.color.dm_overlay_glyph else R.color.lm_overlay_glyph)
                glyphPaint.color = glyph
                fillPaint.color = glyph
            }
        }
        // Colored glyph on the neutral themed background
        if (!danger && fill == null) {
            glyphTint?.let { c ->
                glyphPaint.color = c
                fillPaint.color = c
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val s = minOf(w, h) / 56f          // scale relative to a 56dp design grid

        glyphPaint.strokeWidth = 2.8f * s

        val inset = 1f * s
        if (shapeStyle == Shape.ROUNDED_SQUARE) {
            val rad = minOf(w, h) * 0.3f
            canvas.drawRoundRect(inset, inset, w - inset, h - inset, rad, rad, bgPaint)
        } else {
            val r = minOf(w, h) / 2f - inset
            canvas.drawCircle(cx, cy, r, bgPaint)
        }

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
            Mode.TRASH -> {
                canvas.drawLine(cx - 3.5f * s, cy - 11f * s, cx + 3.5f * s, cy - 11f * s, glyphPaint)
                canvas.drawLine(cx - 10f * s, cy - 8f * s, cx + 10f * s, cy - 8f * s, glyphPaint)

                val body = Path().apply {
                    moveTo(cx - 8f * s, cy - 8f * s)
                    lineTo(cx - 6.5f * s, cy + 11f * s)
                    lineTo(cx + 6.5f * s, cy + 11f * s)
                    lineTo(cx + 8f * s, cy - 8f * s)
                }
                canvas.drawPath(body, glyphPaint)

                canvas.drawLine(cx - 2.8f * s, cy - 4f * s, cx - 2.4f * s, cy + 7f * s, glyphPaint)
                canvas.drawLine(cx + 2.8f * s, cy - 4f * s, cx + 2.4f * s, cy + 7f * s, glyphPaint)
            }
        }
    }
}