package com.example.test103

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

/**
 * ✅ Quick Settings tile (like Proton VPN's): tap to toggle the overlay.
 */
class OverlayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (OverlayService.isRunning) {
            stopService(Intent(this, OverlayService::class.java))
        } else if (Settings.canDrawOverlays(this)) {
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        } else {
            // No overlay permission yet — open the app to grant it
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(PendingIntent.getActivity(
                    this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
            return
        }
        // Service state flips asynchronously; refresh shortly after
        android.os.Handler(mainLooper).postDelayed({ refreshTile() }, 300)
    }

    private fun refreshTile() {
        qsTile?.apply {
            state = if (OverlayService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "overlAI"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (OverlayService.isRunning) "On" else "Off"
            }
            updateTile()
        }
    }

    companion object {
        /** Ask the system to re-query the tile (call when service state changes). */
        fun refresh(context: Context) {
            try {
                requestListeningState(context,
                    ComponentName(context, OverlayTileService::class.java))
            } catch (_: Exception) {}
        }
    }
}

/**
 * ✅ Home-screen widget: shows overlay state, tap to toggle.
 */
class OverlayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, buildViews(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            if (OverlayService.isRunning) {
                context.stopService(Intent(context, OverlayService::class.java))
            } else if (Settings.canDrawOverlays(context)) {
                ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
            } else {
                context.startActivity(Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            // State flips asynchronously — refresh shortly after
            android.os.Handler(context.mainLooper).postDelayed({ updateAll(context) }, 300)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.example.test103.WIDGET_TOGGLE"

        fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_overlay)
            val on = OverlayService.isRunning
            views.setTextViewText(R.id.widget_status, if (on) "ON" else "OFF")
            views.setTextColor(R.id.widget_status,
                if (on) Color.parseColor("#03DAC5") else Color.parseColor("#888888"))

            val toggle = Intent(context, OverlayWidgetProvider::class.java).apply { action = ACTION_TOGGLE }
            views.setOnClickPendingIntent(R.id.widget_root,
                PendingIntent.getBroadcast(context, 0, toggle,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return views
        }

        /** Refresh every placed widget (call when service state changes). */
        fun updateAll(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(
                    ComponentName(context, OverlayWidgetProvider::class.java))
                if (ids.isNotEmpty()) mgr.updateAppWidget(ids, buildViews(context))
            } catch (_: Exception) {}
        }
    }
}
