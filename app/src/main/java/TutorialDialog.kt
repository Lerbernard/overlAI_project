package com.example.test103

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ✅ First-run tutorial: a 5-page walkthrough of the overlay, its gestures,
 * and the other ways to check content. Fully programmatic, themed, no deps.
 */
object TutorialDialog {

    private data class Page(val title: String, val body: String, val icon: (Activity) -> View)

    fun show(activity: Activity, onDone: () -> Unit = {}) {
        val t = ThemeHelper
        fun dp(v: Int): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics).toInt()

        // ✅ every icon carries its own centered FrameLayout params — previously
        // renderPage overrode them with wrap-content, making the bubble fill
        // the holder and the vector logos shrink to their intrinsic 24dp
        val iconSize = dp(96)
        fun iconParams() = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)

        fun logoIcon(a: Activity) = ImageView(a).apply {
            setImageResource(if (t.isDark(a)) R.drawable.ic_nav_logo else R.drawable.ic_nav_logo_light)
            layoutParams = iconParams()
        }

        fun bubbleIcon(a: Activity) = OutlineIconView(a, OutlineIconView.Mode.PLUS).apply {
            applyTheme(t.isDark(a))
            setGlyphTint(t.primary(a))
            layoutParams = iconParams()
        }

        fun trashIcon(a: Activity) = OutlineIconView(a, OutlineIconView.Mode.TRASH).apply {
            applyTheme(t.isDark(a))
            setGlyphTint(t.scoreHigh(a))
            layoutParams = iconParams()
        }

        fun detectorIcon(a: Activity) = ImageView(a).apply {
            setImageResource(R.drawable.ic_nav_detector)
            setColorFilter(t.accent(a))
            layoutParams = iconParams()
        }

        fun tileIcon(a: Activity) = ImageView(a).apply {
            setImageResource(R.drawable.ic_tile)
            setColorFilter(t.primary(a))
            layoutParams = iconParams()
        }

        val pages = listOf(
            Page("Welcome to OverlAI",
                "Check any image or video for signs of AI generation — right from your screen, in a couple of taps.",
                ::logoIcon),
            Page("The floating bubble",
                "Activate the overlay and a small bubble appears over everything. Tap it to open the menu, then pick the camera to check what's on screen or the camcorder to record a short clip.",
                ::bubbleIcon),
            Page("Gestures worth knowing",
                "Double-tap the bubble for an instant photo check — no menu needed.\n\nDrag it anywhere on screen, or throw it and it glides to the edge.\n\nDrag it onto the trash at the bottom to turn the overlay off.",
                ::trashIcon),
            Page("Detector and sharing",
                "The Detector tab checks images and videos from your gallery.\n\nOr share media to “Check with overlAI” straight from any other app.",
                ::detectorIcon),
            Page("Quick Settings tiles",
                "Add the OverlAI tiles to your Quick Settings panel:\n\nThe OverlAI tile switches the overlay on and off.\n\nThe Quick check tile screenshots your screen, checks it, and sends the result to History with a notification.",
                ::tileIcon)
        )

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        var index = 0

        val iconHolder = FrameLayout(activity)
        val titleView = TextView(activity).apply {
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(t.textPrimary(activity))
        }
        val bodyView = TextView(activity).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(t.textSecondary(activity))
        }
        val dots = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val nextBtn = TextView(activity).apply {
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(40), dp(13), dp(40), dp(13))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(t.primary(activity))
            }
        }
        val skipBtn = TextView(activity).apply {
            text = "Skip"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(t.textSecondary(activity))
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }

        fun renderDots() {
            dots.removeAllViews()
            for (i in pages.indices) {
                dots.addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                        marginStart = dp(4); marginEnd = dp(4)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (i == index) t.primary(activity)
                                 else t.idleGray(activity))
                    }
                })
            }
        }

        fun renderPage() {
            val p = pages[index]
            iconHolder.removeAllViews()
            iconHolder.addView(p.icon(activity))   // ✅ keeps its own sized params
            titleView.text = p.title
            bodyView.text = p.body
            nextBtn.text = if (index == pages.lastIndex) "Get started" else "Next"
            skipBtn.visibility = if (index == pages.lastIndex) View.INVISIBLE else View.VISIBLE
            renderDots()
        }

        nextBtn.setOnClickListener {
            if (index == pages.lastIndex) { dialog.dismiss(); onDone() }
            else { index++; renderPage() }
        }
        skipBtn.setOnClickListener { dialog.dismiss(); onDone() }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(t.background(activity))
            setPadding(dp(32), dp(48), dp(32), dp(32))

            addView(iconHolder, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.42f))
            addView(titleView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(bodyView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) })
            addView(View(activity), LinearLayout.LayoutParams(0, 0, 0.18f))
            addView(dots, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(nextBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(22) })
            addView(skipBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }

        renderPage()
        dialog.setContentView(root)
        dialog.setCancelable(false)
        dialog.show()
    }
}
