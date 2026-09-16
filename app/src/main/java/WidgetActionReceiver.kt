package com.example.test103

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Handles the "Activate / Deactivate" button on both home-screen widgets.
 *
 * It is declared with exported="false", so only this app's own PendingIntents can reach it.
 * The widget providers used to accept a custom action on their exported receivers, which let any
 * app on the phone broadcast "toggle the overlay"; now they only handle the system's widget events.
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TOGGLE) return
        if (OverlayService.isRunning) {
            context.stopService(Intent(context, OverlayService::class.java))
        } else if (Settings.canDrawOverlays(context)) {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        } else {
            // No overlay permission yet: open the app, which walks the user through it.
            context.startActivity(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        // The service state flips asynchronously; refresh both widgets shortly after.
        android.os.Handler(context.mainLooper).postDelayed({
            OverlayWidgetProvider.updateAll(context)
            OverlayStatsWidget.updateAll(context)
        }, 300)
    }

    companion object {
        const val ACTION_TOGGLE = "com.example.test103.action.WIDGET_TOGGLE"

        /** Explicit, immutable PendingIntent for the widget button. */
        fun toggleIntent(context: Context): PendingIntent {
            val intent = Intent(context, WidgetActionReceiver::class.java).apply { action = ACTION_TOGGLE }
            return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
