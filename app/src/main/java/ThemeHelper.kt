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

    // ─── Apply to settings cards ──────────────────────────────────────────────

    fun applyCardTheme(settingsLayout: LinearLayout, context: Context) {
        for (i in 0 until settingsLayout.childCount) {
            val child = settingsLayout.getChildAt(i)
            if (child is MaterialCardView) {
                child.setCardBackgroundColor(card(context))
                val inner = child.getChildAt(0)
                if (inner is LinearLayout) {
                    for (j in 0 until inner.childCount) {
                        val v = inner.getChildAt(j)
                        if (v is TextView) v.setTextColor(textPrimary(context))
                    }
                }
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