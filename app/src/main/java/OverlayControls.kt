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
        liveInstance = this
        refreshTile()
    }

    override fun onStopListening() {
        if (liveInstance === this) liveInstance = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // ✅ premium tile — free users open the overlay from inside the app
        if (!Premium.isActive(this)) {
            val open = PendingIntent.getActivity(this, 11,
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("open_tab", 3)
                }, PendingIntent.FLAG_IMMUTABLE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                startActivityAndCollapse(open)
            else try { open.send() } catch (_: Exception) {}
            return
        }
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
                // ✅ no deprecated overload: just launch; the shade stays up
                // on pre-14 devices and the user swipes it away
                startActivity(i)
            }
            return
        }
        // ✅ instant feedback: show the target state immediately…
        val target = !OverlayService.isRunning
        qsTile?.apply {
            state = if (target) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (target) "On" else "Off"
            }
            updateTile()
        }
        // …then confirm against the real service state a moment later
        android.os.Handler(mainLooper).postDelayed({ refreshTile() }, 350)
    }

    fun refreshTile() {
        qsTile?.apply {
            state = if (OverlayService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            // ✅ set the brand icon explicitly so it always matches
            icon = android.graphics.drawable.Icon.createWithResource(
                this@OverlayTileService, R.drawable.ic_tile)
            label = "OverlAI"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (OverlayService.isRunning) "On" else "Off"
            }
            updateTile()
        }
    }

    companion object {
        /** ✅ live handle while the shade is open — lets state changes from the
         *  app/widget/trash update the tile INSTANTLY instead of waiting for
         *  the system to re-query it. */
        @Volatile private var liveInstance: OverlayTileService? = null

        fun refresh(context: Context) {
            // direct poke first (instant when the shade is visible)…
            try {
                liveInstance?.let {
                    android.os.Handler(context.mainLooper).post { it.refreshTile() }
                }
            } catch (_: Exception) {}
            // …and the official path as fallback
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
                if (on) ContextCompat.getColor(context, R.color.dm_accent) else ContextCompat.getColor(context, R.color.widget_text_dim))

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
                if (on) ContextCompat.getColor(context, R.color.dm_accent) else ContextCompat.getColor(context, R.color.widget_text_dim))

            val (img, vid) = UsageTracker.counts(context)
            v.setTextViewText(R.id.stats_checks, "${img + vid}")

            val last = HistoryManager.getAll(context).firstOrNull()
            if (last == null) {
                v.setTextViewText(R.id.stats_score, "—")
                v.setTextColor(R.id.stats_score, ContextCompat.getColor(context, R.color.widget_text_dim))
                v.setTextViewText(R.id.stats_when, "No checks yet")
            } else {
                val c = when {
                    last.score < 30 -> ContextCompat.getColor(context, R.color.dm_score_low)
                    last.score < 70 -> ContextCompat.getColor(context, R.color.dm_score_mid)
                    else -> ContextCompat.getColor(context, R.color.dm_score_high)
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