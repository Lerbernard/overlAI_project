package com.example.test103

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * ✅ Flat labeled toggle (like the reference): a solid rounded track with BOTH
 * options always visible, and a pill-shaped knob that slides over the active
 * side and highlights its label. No saved state → can't re-fire after recreate().
 */
class PillToggleView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private val trackW = dp(150)
    private val trackH = dp(42)
    private val pad = dp(4)                       // gap between knob and track edge
    private val knobW get() = (trackW - pad * 2) / 2   // knob covers exactly half

    private var labelOn = "ON"
    private var labelOff = "OFF"

    var isChecked = false
        private set

    /** Fires only on real user taps. */
    var onToggle: ((Boolean) -> Unit)? = null

    private fun sideLabel() = TextView(context).apply {
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        letterSpacing = 0.03f
        maxLines = 1
        gravity = Gravity.CENTER
    }
    private val leftLabel = sideLabel()
    private val rightLabel = sideLabel()

    private val knob = TextView(context)

    init {
        layoutParams = LayoutParams(trackW, trackH)
        val half = LayoutParams(knobW, LayoutParams.MATCH_PARENT)
        addView(leftLabel, LayoutParams(half.width, LayoutParams.MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL))
        addView(rightLabel, LayoutParams(half.width, LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL))
        knob.layoutParams = LayoutParams(knobW, trackH - pad * 2,
            Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = pad }
        addView(knob)
        isClickable = true
        setOnClickListener {
            setChecked(!isChecked, animate = true)
            onToggle?.invoke(isChecked)
        }
        render(false)
    }

    override fun onMeasure(w: Int, h: Int) = super.onMeasure(
        MeasureSpec.makeMeasureSpec(trackW, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(trackH, MeasureSpec.EXACTLY))

    fun configure(on: String, off: String) { labelOn = on; labelOff = off; render(false) }

    fun setChecked(checked: Boolean, animate: Boolean = false) { isChecked = checked; render(animate) }

    fun applyThemeColors() = render(false)

    private fun render(animate: Boolean) {
        val t = ThemeHelper
        val active = t.primary(context)
        val trackCol = t.card(context)
        val dim = t.textSecondary(context)

        // solid track
        background = GradientDrawable().apply {
            cornerRadius = trackH / 2f
            setColor(trackCol)
        }

        leftLabel.text = labelOff
        rightLabel.text = labelOn
        // the side under the knob is white; the other is dimmed
        leftLabel.setTextColor(if (isChecked) dim else Color.WHITE)
        rightLabel.setTextColor(if (isChecked) Color.WHITE else dim)

        // pill knob (rounded rect, not a circle) in the accent color
        knob.background = GradientDrawable().apply {
            cornerRadius = (trackH - pad * 2) / 2f
            setColor(active)
        }

        val target = if (isChecked) (trackW - knobW - pad * 2).toFloat() else 0f
        if (animate) knob.animate().translationX(target).setDuration(180).start()
        else knob.translationX = target
    }
}
