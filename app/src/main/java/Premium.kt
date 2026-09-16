package com.example.test103

import android.content.Context

/**
 * Single source of truth for premium (ad-free) status. Everything ad-related and every gate reads
 * Premium.isActive(context).
 *
 * Security note: the flag is only ever *read* from Firestore (Account.syncPremiumFromCloud). The
 * app never writes it; firestore.rules make users/{uid} read-only for clients, so a modified APK
 * can't grant itself premium in the cloud. Once Play Billing exists, purchases are verified and
 * recorded server-side.
 */
object Premium {
    private const val PREFS = "app_settings"
    private const val KEY = "premium_active"

    /**
     * Fresh installs are ad-free. Flip this to false only when Play Billing is live AND
     * BuildConfig.ADS_ENABLED is true, otherwise the paid tier ships free to everyone.
     */
    private const val DEFAULT_PREMIUM = true

    fun isActive(context: Context): Boolean {
        // No ads in this build at all: behave as premium everywhere.
        if (!BuildConfig.ADS_ENABLED) return true
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, DEFAULT_PREMIUM)
    }

    /** Local cache. Called by the cloud sync and, later, by the verified purchase flow. */
    fun setPremium(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, active).apply()
    }

    /**
     * Kept for the placeholder purchase flow. Only the local cache changes; the cloud copy is
     * written server-side after a verified purchase, never from the app.
     */
    fun setPremiumSynced(context: Context, active: Boolean) {
        setPremium(context, active)
    }
}
