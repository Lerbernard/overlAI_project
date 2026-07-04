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

            // ✅ Proton-style glow: teal gradient bleeds from the top while active
            views.setInt(R.id.widget_root, "setBackgroundResource",
                if (on) R.drawable.widget_bg_on else R.drawable.widget_bg)

            views.setImageViewResource(R.id.widget_dot,
                if (on) R.drawable.widget_dot_on else R.drawable.widget_dot_off)
            views.setTextViewText(R.id.widget_status, if (on) "Overlay on" else "Overlay off")
            views.setTextColor(R.id.widget_status,
                if (on) Color.parseColor("#03DAC5") else Color.parseColor("#999999"))

            // ✅ Proton-style: Activate (purple) / Deactivate (gray)
            views.setTextViewText(R.id.widget_action, if (on) "Deactivate" else "Activate")
            views.setInt(R.id.widget_action, "setBackgroundResource",
                if (on) R.drawable.widget_btn_gray else R.drawable.widget_btn_purple)

            // ✅ the button toggles the overlay…
            val toggle = Intent(context, OverlayWidgetProvider::class.java).apply { action = ACTION_TOGGLE }
            views.setOnClickPendingIntent(R.id.widget_action,
                PendingIntent.getBroadcast(context, 0, toggle,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            // ✅ …while the rest of the widget opens the app
            val open = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val openPi = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, openPi)
            views.setOnClickPendingIntent(R.id.widget_header, openPi)
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

/**
 * ✅ Second widget: dashboard-style — state, checks this month, last result.
 * The bottom button toggles the overlay; everywhere else opens the app.
 */
class OverlayStatsWidget : AppWidgetProvider() {

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
            android.os.Handler(context.mainLooper).postDelayed({ updateAll(context) }, 300)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.example.test103.STATS_WIDGET_TOGGLE"

        fun buildViews(context: Context): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_stats)
            val on = OverlayService.isRunning

            v.setInt(R.id.stats_root, "setBackgroundResource",
                if (on) R.drawable.widget_bg_on else R.drawable.widget_bg)
            v.setImageViewResource(R.id.stats_dot,
                if (on) R.drawable.widget_dot_on else R.drawable.widget_dot_off)
            v.setTextViewText(R.id.stats_status, if (on) "Overlay on" else "Overlay off")
            v.setTextColor(R.id.stats_status,
                if (on) Color.parseColor("#03DAC5") else Color.parseColor("#999999"))

            val (img, vid) = UsageTracker.counts(context)
            v.setTextViewText(R.id.stats_checks, "${img + vid}")

            val last = HistoryManager.getAll(context).firstOrNull()
            if (last == null) {
                v.setTextViewText(R.id.stats_score, "—")
                v.setTextColor(R.id.stats_score, Color.parseColor("#999999"))
                v.setTextViewText(R.id.stats_when, "No checks yet")
            } else {
                val c = when {
                    last.score < 30 -> Color.parseColor("#4CAF50")
                    last.score < 70 -> Color.parseColor("#FF9800")
                    else -> Color.parseColor("#FF1744")
                }
                v.setTextViewText(R.id.stats_score, "${last.score}%")
                v.setTextColor(R.id.stats_score, c)
                v.setTextViewText(R.id.stats_when,
                    android.text.format.DateUtils.getRelativeTimeSpanString(last.timestamp))
                // small decoded thumbnail (RemoteViews-safe size)
                last.thumbPath?.let { p ->
                    try {
                        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                        val bmp = android.graphics.BitmapFactory.decodeFile(p, opts)
                        if (bmp != null) v.setImageViewBitmap(R.id.stats_thumb, bmp)
                    } catch (_: Exception) {}
                }
            }

            v.setTextViewText(R.id.stats_action, if (on) "Deactivate" else "Activate")
            v.setInt(R.id.stats_action, "setBackgroundResource",
                if (on) R.drawable.widget_btn_gray else R.drawable.widget_btn_purple)

            val toggle = Intent(context, OverlayStatsWidget::class.java).apply { action = ACTION_TOGGLE }
            v.setOnClickPendingIntent(R.id.stats_action,
                PendingIntent.getBroadcast(context, 2, toggle,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val open = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            v.setOnClickPendingIntent(R.id.stats_root,
                PendingIntent.getActivity(context, 3, open, PendingIntent.FLAG_IMMUTABLE))
            return v
        }

        fun updateAll(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, OverlayStatsWidget::class.java))
                if (ids.isNotEmpty()) mgr.updateAppWidget(ids, buildViews(context))
            } catch (_: Exception) {}
        }
    }
}