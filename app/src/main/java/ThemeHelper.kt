package com.example.test103

import android.content.Context
import android.content.res.ColorStateList
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView

object ThemeHelper {

    fun isDark(context: Context): Boolean {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        if (!prefs.contains("dark_mode")) {
            // ✅ no saved choice yet — follow the device theme
            val ui = context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
            return ui == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        return prefs.getBoolean("dark_mode", true)
    }

    fun primary(context: Context) = color(context,
        if (isDark(context)) R.color.dm_primary else R.color.lm_primary)

    fun accent(context: Context) = color(context,
        if (isDark(context)) R.color.dm_accent else R.color.lm_accent)

    fun background(context: Context) = color(context,
        if (isDark(context)) R.color.dm_background else R.color.lm_background)

    fun surface(context: Context) = color(context,
        if (isDark(context)) R.color.dm_surface else R.color.lm_surface)

    fun card(context: Context) = color(context,
        if (isDark(context)) R.color.dm_card else R.color.lm_card)

    fun textPrimary(context: Context) = color(context,
        if (isDark(context)) R.color.dm_text_primary else R.color.lm_text_primary)

    fun textSecondary(context: Context) = color(context,
        if (isDark(context)) R.color.dm_text_secondary else R.color.lm_text_secondary)

    fun tabIndicator(context: Context) = color(context,
        if (isDark(context)) R.color.dm_tab_indicator else R.color.lm_tab_indicator)

    fun tabText(context: Context) = color(context,
        if (isDark(context)) R.color.dm_tab_text else R.color.lm_tab_text)

    fun btnSecondary(context: Context) = color(context,
        if (isDark(context)) R.color.dm_btn_secondary else R.color.lm_btn_secondary)

    fun btnSecondaryText(context: Context) = color(context,
        if (isDark(context)) R.color.dm_btn_secondary_text else R.color.lm_btn_secondary_text)

    // ✅ semantic colors — change them in colors.xml / colors-dark.xml only
    fun scoreLow(context: Context) = color(context,
        if (isDark(context)) R.color.dm_score_low else R.color.lm_score_low)

    fun scoreMid(context: Context) = color(context,
        if (isDark(context)) R.color.dm_score_mid else R.color.lm_score_mid)

    fun scoreHigh(context: Context) = color(context,
        if (isDark(context)) R.color.dm_score_high else R.color.lm_score_high)

    /** One place that maps a percentage to its color. */
    fun scoreColor(context: Context, pct: Int) = when {
        pct < 30 -> scoreLow(context)
        pct < 70 -> scoreMid(context)
        else -> scoreHigh(context)
    }

    fun idleGray(context: Context) = color(context,
        if (isDark(context)) R.color.dm_idle_gray else R.color.lm_idle_gray)

    fun btnNeutral(context: Context) = color(context,
        if (isDark(context)) R.color.dm_btn_neutral else R.color.lm_btn_neutral)

    fun divider(context: Context) = color(context,
        if (isDark(context)) R.color.dm_divider else R.color.lm_divider)

    fun overlayBtn(context: Context) = color(context,
        if (isDark(context)) R.color.dm_overlay_btn else R.color.lm_overlay_btn)

    fun overlayGlyph(context: Context) = color(context,
        if (isDark(context)) R.color.dm_overlay_glyph else R.color.lm_overlay_glyph)

    fun overlayPanel(context: Context) = color(context,
        if (isDark(context)) R.color.dm_overlay_panel else R.color.lm_overlay_panel)

    fun scrim(context: Context) = color(context,
        if (isDark(context)) R.color.dm_scrim else R.color.lm_scrim)

    // ─── Apply to settings cards ──────────────────────────────────────────────

    fun applyCardTheme(root: android.view.View, context: Context) {
        // ✅ walk the whole subtree so nested cards (inside a ScrollView) are
        // themed, AND recolor their text so hardcoded light-mode titles follow
        // the theme in dark mode.
        when (root) {
            is MaterialCardView -> {
                root.setCardBackgroundColor(card(context))
                for (k in 0 until root.childCount) applyCardTheme(root.getChildAt(k), context)
            }
            is TextView -> {
                // leave already-themed / accent text alone; only fix the ones that
                // are still the light-mode primary color
                if (root.currentTextColor == color(context, R.color.lm_text_primary)) {
                    root.setTextColor(textPrimary(context))
                }
            }
            is android.view.ViewGroup -> {
                for (k in 0 until root.childCount) applyCardTheme(root.getChildAt(k), context)
            }
        }
    }

    // ─── Apply to any button pair ─────────────────────────────────────────────

    fun applyButtonTheme(
        context: Context,
        primary: com.google.android.material.button.MaterialButton,
        secondary: com.google.android.material.button.MaterialButton
    ) {
        primary.backgroundTintList  = ColorStateList.valueOf(primary(context))
        secondary.backgroundTintList = ColorStateList.valueOf(btnSecondary(context))
        secondary.setTextColor(btnSecondaryText(context))
    }

    // ─── Apply to any TabLayout ───────────────────────────────────────────────

    fun applyTabTheme(context: Context, tabLayout: com.google.android.material.tabs.TabLayout) {
        tabLayout.setBackgroundColor(background(context))
        tabLayout.setTabTextColors(tabText(context), tabIndicator(context))
        tabLayout.setSelectedTabIndicatorColor(tabIndicator(context))
    }

    private fun color(context: Context, res: Int) = ContextCompat.getColor(context, res)
}