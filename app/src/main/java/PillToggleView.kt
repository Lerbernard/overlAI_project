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
 * ✅ Flat (2D) labeled pill toggle: the label lives inside the track and a
 * round knob slides end to end — like a neumorphic theme switch, minus the 3D.
 * No saved instance state, so it can never re-fire listeners after recreate().
 */
class PillToggleView(context: Context) : FrameLayout(context) {

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private val trackW = dp(118)
    private val trackH = dp(40)
    private val knobSize = dp(30)
    private val knobMargin = dp(5)

    private var labelOn = "ON"
    private var labelOff = "OFF"
    private var glyphOn: String? = null
    private var glyphOff: String? = null

    var isChecked = false
        private set

    /** Called only for real user taps, never for programmatic changes. */
    var onToggle: ((Boolean) -> Unit)? = null

    private val label = TextView(context).apply {
        textSize = 11f
        setTypeface(null, Typeface.BOLD)
        letterSpacing = 0.08f
        maxLines = 1
        gravity = Gravity.CENTER
    }

    private val knob = TextView(context).apply {
        textSize = 13f
        gravity = Gravity.CENTER
        layoutParams = LayoutParams(knobSize, knobSize, Gravity.START or Gravity.CENTER_VERTICAL)
            .apply { marginStart = knobMargin }
    }

    init {
        layoutParams = LayoutParams(trackW, trackH)
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(knob)
        isClickable = true
        setOnClickListener {
            setChecked(!isChecked, animate = true)
            onToggle?.invoke(isChecked)
        }
        render(animate = false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(trackW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(trackH, MeasureSpec.EXACTLY))
    }

    fun configure(on: String, off: String, gOn: String? = null, gOff: String? = null) {
        labelOn = on; labelOff = off; glyphOn = gOn; glyphOff = gOff
        render(animate = false)
    }

    /** Programmatic state set — never fires onToggle. */
    fun setChecked(checked: Boolean, animate: Boolean = false) {
        isChecked = checked
        render(animate)
    }

    /** Re-read theme colors (call from refresh passes). */
    fun applyThemeColors() = render(animate = false)

    private fun render(animate: Boolean) {
        val t = ThemeHelper
        val active = t.primary(context)
        val idle = t.idleGray(context)
        val c = if (isChecked) active else idle

        background = GradientDrawable().apply {
            cornerRadius = trackH / 2f
            setColor(Color.argb(36, Color.red(c), Color.green(c), Color.blue(c)))
        }

        label.text = if (isChecked) labelOn else labelOff
        label.setTextColor(c)
        // keep the label clear of whichever side the knob occupies
        val clear = knobSize + knobMargin * 2
        if (isChecked) label.setPadding(dp(8), 0, clear, 0)
        else label.setPadding(clear, 0, dp(8), 0)

        knob.text = (if (isChecked) glyphOn else glyphOff) ?: ""
        knob.setTextColor(Color.WHITE)
        knob.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(c)
        }

        val target = if (isChecked) (trackW - knobSize - knobMargin * 2).toFloat() else 0f
        if (animate) {
            knob.animate().translationX(target).setDuration(170).start()
        } else {
            knob.translationX = target
        }
    }
}
