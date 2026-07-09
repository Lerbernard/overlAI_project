package com.example.test103

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds

/**
 * ✅ Thin wrapper around the Google Mobile Ads SDK.
 *
 * IMPORTANT: these are Google's official TEST ad unit IDs - they show test
 * ads and are safe during development. Before publishing, replace TEST_BANNER
 * with your real AdMob banner unit ID (and put the real App ID in the manifest
 * meta-data). Never click your own live ads.
 */
object AdManager {
    private const val TAG = "AdManager"
    // Google's universal test banner unit
    private const val TEST_BANNER = "ca-app-pub-3940256099942544/6300978111"
    // Google's universal test interstitial unit
    private const val TEST_INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"

    @Volatile private var initialized = false
    private var interstitial: com.google.android.gms.ads.interstitial.InterstitialAd? = null
    private var checksSinceAd = 0

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        try {
            MobileAds.initialize(context.applicationContext) {
                Log.d(TAG, "Mobile Ads initialized")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ads init failed", e)
        }
    }

    /**
     * Fill [container] with an adaptive banner - unless the user is premium,
     * in which case the container is emptied and hidden.
     */
    /** Preload an interstitial so it's ready to show later. */
    fun preloadInterstitial(context: Context) {
        if (Premium.isActive(context)) return
        init(context)
        try {
            com.google.android.gms.ads.interstitial.InterstitialAd.load(
                context, TEST_INTERSTITIAL, AdRequest.Builder().build(),
                object : com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: com.google.android.gms.ads.interstitial.InterstitialAd) {
                        interstitial = ad
                    }
                    override fun onAdFailedToLoad(e: com.google.android.gms.ads.LoadAdError) {
                        interstitial = null
                    }
                })
        } catch (e: Exception) { Log.e(TAG, "Interstitial load failed", e) }
    }

    /**
     * Show an interstitial after a detection - but only every 3rd check, so
     * users aren't hit with a full-screen ad every single time. No-op for premium.
     */
    fun maybeShowAfterCheck(activity: android.app.Activity) {
        if (Premium.isActive(activity)) return
        checksSinceAd++
        if (checksSinceAd < 3) { preloadInterstitial(activity); return }
        val ad = interstitial
        if (ad == null) { preloadInterstitial(activity); return }
        checksSinceAd = 0
        ad.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitial = null
                preloadInterstitial(activity)
            }
        }
        try { ad.show(activity) } catch (e: Exception) { Log.e(TAG, "show failed", e) }
    }

    fun loadBanner(container: FrameLayout) {
        val context = container.context
        if (Premium.isActive(context)) {
            container.removeAllViews()
            container.visibility = View.GONE
            return
        }
        init(context)
        try {
            container.removeAllViews()
            val adView = AdView(context)
            adView.setAdSize(AdSize.BANNER)
            adView.adUnitId = TEST_BANNER
            container.addView(adView)
            container.visibility = View.VISIBLE
            adView.loadAd(AdRequest.Builder().build())
        } catch (e: Exception) {
            Log.e(TAG, "Banner load failed", e)
            container.visibility = View.GONE
        }
    }
}
