package com.example.test103

import android.content.Context

/**
 * ✅ Single source of truth for premium status. For now it's a local flag the
 * "Remove ads" button flips; later, Play Billing will drive setPremium().
 * Everything ad-related and every gate reads Premium.isActive(context).
 */
object Premium {
    private const val PREFS = "app_settings"
    private const val KEY = "premium_active"

    fun isActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    /** Called by the purchase flow (placeholder now, Play Billing later). */
    fun setPremium(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, active).apply()
    }

    /** Local + cloud (if signed in) — use this from the purchase flow. */
    fun setPremiumSynced(context: Context, active: Boolean) {
        setPremium(context, active)
        if (Account.isSignedIn()) Account.setCloudPremium(active)
    }
}
