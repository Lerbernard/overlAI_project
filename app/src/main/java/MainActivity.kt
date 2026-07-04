package com.example.test103

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (ThemeHelper.isDark(this)) R.style.Theme_App_Dark else R.style.Theme_App_Light)

        super.onCreate(savedInstanceState)

        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        supportActionBar?.hide()

        setContentView(R.layout.activity_main)

        applyTheme()

        val tabLayout      = findViewById<TabLayout>(R.id.tabLayout)
        val viewOverlay    = findViewById<LinearLayout>(R.id.viewOverlay)
        val viewDetector   = findViewById<FrameLayout>(R.id.viewDetector)
        val viewHistory    = findViewById<LinearLayout>(R.id.viewHistory)
        val viewSettings   = findViewById<LinearLayout>(R.id.viewSettings)
        val cropSwitch     = findViewById<SwitchMaterial>(R.id.crop_switch)
        val darkModeSwitch = findViewById<SwitchMaterial>(R.id.dark_mode_switch)

        // ✅ Gear tab hugs its icon; text tabs share the remaining width
        tabLayout.post {
            val strip = tabLayout.getChildAt(0) as LinearLayout
            for (i in 0 until strip.childCount) {
                val tab = strip.getChildAt(i)
                val p = tab.layoutParams as LinearLayout.LayoutParams
                if (i == 3) {
                    p.width = LinearLayout.LayoutParams.WRAP_CONTENT
                    p.weight = 0f
                    tab.minimumWidth = 0
                    tab.scaleX = 0.85f
                    tab.scaleY = 0.85f
                } else {
                    p.width = 0
                    p.weight = 1f
                }
                tab.layoutParams = p
            }
            strip.requestLayout()
        }

        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        cropSwitch.isChecked     = prefs.getBoolean("use_crop", true)
        darkModeSwitch.isChecked = ThemeHelper.isDark(this)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                viewOverlay.visibility  = View.GONE
                viewDetector.visibility = View.GONE
                viewHistory.visibility  = View.GONE
                viewSettings.visibility = View.GONE
                when (tab?.position) {
                    0 -> viewOverlay.visibility = View.VISIBLE
                    1 -> {
                        viewDetector.visibility = View.VISIBLE
                        if (supportFragmentManager.findFragmentById(R.id.viewDetector) == null) {
                            supportFragmentManager.beginTransaction()
                                .replace(R.id.viewDetector, DetectorFragment())
                                .commit()
                        }
                    }
                    2 -> {
                        viewHistory.visibility = View.VISIBLE
                        refreshHistory()   // ✅ reload every time the tab opens
                    }
                    3 -> viewSettings.visibility = View.VISIBLE
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // ✅ After a dark-mode recreate(), jump back to the tab the user was on
        val restoreTab = prefs.getInt("restore_tab", 0)
        if (restoreTab != 0) {
            tabLayout.getTabAt(restoreTab)?.select()
            prefs.edit().remove("restore_tab").apply()
        }

        cropSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("use_crop", isChecked).apply()
        }

        darkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit()
                .putBoolean("dark_mode", isChecked)
                .putInt("restore_tab", tabLayout.selectedTabPosition)
                .apply()
            recreate()
        }

        findViewById<TextView>(R.id.btnClearHistory).setOnClickListener {
            HistoryManager.clear(this)
            refreshHistory()
        }

        // ✅ dashboard: one toggle button, state-aware
        findViewById<MaterialButton>(R.id.btnToggleOverlay).setOnClickListener { btn ->
            if (OverlayService.isRunning) {
                stopService(Intent(this, OverlayService::class.java))
                Toast.makeText(this, "Overlay deactivated", Toast.LENGTH_SHORT).show()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                maybeShowPermissionIntro()
            } else {
                startOverlayService()
            }
            btn.postDelayed({ refreshDashboard() }, 350)
        }
    }

    // ---------------------------------------------------------------------
    // History tab — minimalist rows built in code
    // ---------------------------------------------------------------------

    private fun refreshHistory() {
        val list = findViewById<LinearLayout>(R.id.historyList)
        list.removeAllViews()

        val entries = HistoryManager.getAll(this)
        val dayKeyFmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val dayLabelFmt = SimpleDateFormat("MMMM d", Locale.getDefault())
        val todayKey = dayKeyFmt.format(Date())
        val yesterdayKey = dayKeyFmt.format(Date(System.currentTimeMillis() - 86_400_000L))
        var lastDayKey = ""

        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No detections yet"
                textSize = 15f
                setTextColor(ThemeHelper.textSecondary(this@MainActivity))
                gravity = Gravity.CENTER
                setPadding(0, dp(48), 0, 0)
            })
            return
        }

        for (e in entries) {
            // ✅ day-group headers: Today / Yesterday / date
            val dayKey = dayKeyFmt.format(Date(e.timestamp))
            if (dayKey != lastDayKey) {
                lastDayKey = dayKey
                list.addView(TextView(this).apply {
                    text = when (dayKey) {
                        todayKey -> "Today"
                        yesterdayKey -> "Yesterday"
                        else -> dayLabelFmt.format(Date(e.timestamp))
                    }
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ThemeHelper.textSecondary(this@MainActivity))
                    setPadding(dp(4), dp(10), 0, dp(6))
                })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(ThemeHelper.card(this@MainActivity))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }

            // Thumbnail (rounded) or a plain placeholder square
            val thumb = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor("#22888888"))
                }
                val bmp: Bitmap? = e.thumbPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
                if (bmp != null) setImageBitmap(bmp)
            }
            row.addView(thumb)

            // Middle: source + date
            val mid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            mid.addView(TextView(this).apply {
                text = e.source
                textSize = 15f
                setTextColor(ThemeHelper.textPrimary(this@MainActivity))
            })
            mid.addView(TextView(this).apply {
                text = android.text.format.DateUtils.getRelativeTimeSpanString(e.timestamp)   // ✅ "2 hours ago"
                textSize = 12f
                setTextColor(ThemeHelper.textSecondary(this@MainActivity))
            })
            row.addView(mid)

            // Score pill, colored by severity
            val scoreColor = when {
                e.score < 30 -> Color.parseColor("#4CAF50")
                e.score < 70 -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#FF1744")
            }
            row.addView(TextView(this).apply {
                text = "${e.score}%"
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(scoreColor)
                }
            })

            // ✅ small red trash to delete just this entry
            row.addView(OutlineIconView(this, OutlineIconView.Mode.TRASH).apply {
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(10) }
                applyTheme(ThemeHelper.isDark(this@MainActivity))
                setGlyphTint(Color.parseColor("#FF1744"))
                setOnClickListener {
                    HistoryManager.remove(this@MainActivity, e.timestamp)
                    refreshHistory()
                }
            })

            // ✅ Tap a row for the expanded popup
            row.isClickable = true
            row.setOnClickListener { showHistoryDialog(e) }

            list.addView(row)
        }
    }

    /** Popup with the enlarged image, score, verdict, source and date. */
    private fun showHistoryDialog(e: HistoryManager.Entry) {
        val t = ThemeHelper
        val dateFmt = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault())

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(12))
        }

        val bmp: Bitmap? = e.thumbPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        if (bmp != null) {
            container.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                clipToOutline = true
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(300)
                ).apply { bottomMargin = dp(16) }
                // ✅ tap the image to open it fullscreen
                isClickable = true
                setOnClickListener { showFullImage(e.thumbPath!!) }
            })
        }

        val scoreColor = when {
            e.score < 30 -> Color.parseColor("#4CAF50")
            e.score < 70 -> Color.parseColor("#FF9800")
            else -> Color.parseColor("#FF1744")
        }
        val verdict = when {
            e.score < 30 -> "Likely real"
            e.score < 70 -> "Uncertain"
            else -> "Likely AI-generated"
        }

        container.addView(TextView(this).apply {
            text = "${e.score}% · $verdict"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(scoreColor)
        })
        container.addView(TextView(this).apply {
            text = "${e.source} · ${dateFmt.format(Date(e.timestamp))}"
            textSize = 13f
            setTextColor(t.textSecondary(this@MainActivity))
            setPadding(0, dp(4), 0, 0)
        })

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton("Close", null)
            .setNegativeButton("Delete") { _, _ ->
                HistoryManager.remove(this, e.timestamp)
                refreshHistory()
            }
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(t.card(this@MainActivity))
        })
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(t.primary(this))
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.parseColor("#FF1744"))
    }

    /** Fullscreen picture viewer — tap anywhere to close. */
    private fun showFullImage(path: String) {
        val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return
        val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.setContentView(ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setOnClickListener { d.dismiss() }
        })
        d.show()
    }

    // ---------------------------------------------------------------------

    private fun applyTheme() {
        val t = ThemeHelper

        window.statusBarColor = t.background(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                if (!t.isDark(this)) android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (!t.isDark(this)) {
                window.decorView.systemUiVisibility or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                window.decorView.systemUiVisibility and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        }

        findViewById<View>(android.R.id.content).setBackgroundColor(t.background(this))
        findViewById<FrameLayout>(R.id.mainContainer).setBackgroundColor(t.background(this))
        findViewById<FrameLayout>(R.id.viewDetector).setBackgroundColor(t.background(this))
        findViewById<LinearLayout>(R.id.viewOverlay).setBackgroundColor(t.background(this))
        findViewById<LinearLayout>(R.id.viewHistory).setBackgroundColor(t.background(this))
        findViewById<TextView>(R.id.historyTitle).setTextColor(t.textPrimary(this))
        findViewById<TextView>(R.id.usage_title)?.setTextColor(t.textPrimary(this))
        findViewById<TextView>(R.id.usage_text)?.setTextColor(t.textSecondary(this))
        findViewById<TextView>(R.id.btnClearHistory).setTextColor(t.primary(this))
        val settingsLayout = findViewById<LinearLayout>(R.id.viewSettings)
        settingsLayout.setBackgroundColor(t.background(this))

        t.applyTabTheme(this, findViewById(R.id.tabLayout))

        // ✅ Gear matches the purple tab text in both states
        val tabs = findViewById<TabLayout>(R.id.tabLayout)
        tabs.tabIconTint = android.content.res.ColorStateList.valueOf(t.primary(this))

        t.applyCardTheme(settingsLayout, this)
    }

    override fun onResume() {
        super.onResume()
        refreshUsage()
        refreshDashboard()
    }

    /** ✅ Overlay tab dashboard: state card + stats, mirrors the widget. */
    private fun refreshDashboard() {
        val t = ThemeHelper
        val on = OverlayService.isRunning
        val teal = Color.parseColor("#03DAC5")

        val card = findViewById<LinearLayout>(R.id.dashCard) ?: return
        card.background = if (on) GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#5903DAC5"), t.card(this), t.card(this))
        ).apply { cornerRadius = dp(26).toFloat() }
        else GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(t.card(this@MainActivity)) }

        findViewById<View>(R.id.dashDot).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) teal else Color.parseColor("#888888"))
        }
        findViewById<TextView>(R.id.dashState).apply {
            text = if (on) "Overlay is on" else "Overlay is off"
            setTextColor(t.textPrimary(this@MainActivity))
        }
        findViewById<TextView>(R.id.dashSub).apply {
            text = if (on) "Tap the floating button to check anything on screen"
            else "Activate to check content in any app"
            setTextColor(t.textSecondary(this@MainActivity))
        }
        findViewById<MaterialButton>(R.id.btnToggleOverlay).apply {
            text = if (on) "Deactivate" else "Activate"
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (on) Color.parseColor("#3A3A3E") else t.primary(this@MainActivity))
            setTextColor(Color.WHITE)
        }

        // stats row
        val cardBg = { GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(t.card(this@MainActivity)) } }
        findViewById<LinearLayout>(R.id.statCard1)?.background = cardBg()
        findViewById<LinearLayout>(R.id.statCard2)?.background = cardBg()
        findViewById<TextView>(R.id.statChecksLabel)?.setTextColor(t.textSecondary(this))
        findViewById<TextView>(R.id.statLastLabel)?.setTextColor(t.textSecondary(this))

        val (img, vid) = UsageTracker.counts(this)
        findViewById<TextView>(R.id.statChecks)?.apply {
            text = "${img + vid}"
            setTextColor(t.textPrimary(this@MainActivity))
        }

        val last = HistoryManager.getAll(this).firstOrNull()
        val scoreTv = findViewById<TextView>(R.id.statScore)
        val whenTv = findViewById<TextView>(R.id.statWhen)
        val thumbIv = findViewById<ImageView>(R.id.statThumb)
        if (last == null) {
            scoreTv?.text = "—"; scoreTv?.setTextColor(t.textSecondary(this))
            whenTv?.text = "No checks yet"; whenTv?.setTextColor(t.textSecondary(this))
            thumbIv?.setImageBitmap(null)
        } else {
            val c = when {
                last.score < 30 -> Color.parseColor("#4CAF50")
                last.score < 70 -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#FF1744")
            }
            scoreTv?.text = "${last.score}%"; scoreTv?.setTextColor(c)
            whenTv?.text = android.text.format.DateUtils.getRelativeTimeSpanString(last.timestamp)
            whenTv?.setTextColor(t.textSecondary(this))
            thumbIv?.let { iv ->
                iv.background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#22888888"))
                }
                iv.clipToOutline = true
                iv.setImageBitmap(last.thumbPath?.let { p ->
                    runCatching { BitmapFactory.decodeFile(p) }.getOrNull() })
            }
        }
    }

    /** ✅ friendly explanation before the scary system permission screen */
    private fun maybeShowPermissionIntro() {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val goToSettings = {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
        if (prefs.getBoolean("intro_shown", false)) { goToSettings(); return }

        val t = ThemeHelper
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(28))
            setBackgroundColor(t.card(this@MainActivity))
        }
        box.addView(TextView(this).apply {
            text = "One quick permission"
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(t.textPrimary(this@MainActivity))
        })
        box.addView(TextView(this).apply {
            text = "OverlAI draws a small floating button over other apps so you can check images anywhere. Android will ask you to allow \"display over other apps\" — flip the switch for OverlAI, then come back and tap Activate again."
            textSize = 14f
            setTextColor(t.textSecondary(this@MainActivity))
            setPadding(0, dp(10), 0, 0)
        })
        box.addView(MaterialButton(this).apply {
            text = "Continue"
            cornerRadius = dp(24)
            backgroundTintList = android.content.res.ColorStateList.valueOf(t.primary(this@MainActivity))
            setTextColor(Color.WHITE)
            setOnClickListener {
                prefs.edit().putBoolean("intro_shown", true).apply()
                sheet.dismiss()
                goToSettings()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) })
        sheet.setContentView(box)
        sheet.show()
    }

    /** ✅ monthly API usage shown in Settings */
    private fun refreshUsage() {
        val (img, vid) = UsageTracker.counts(this)
        findViewById<TextView>(R.id.usage_text)?.text = "Images: $img · Videos: $vid"
    }

    private fun startOverlayService() {
        // ✅ ask for notification permission (Android 13+) so the persistent
        // "overlay is on" notification can show
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
        }
        // ✅ safe again: the service now calls startForeground() immediately
        // in onCreate (specialUse type), so the 5-second promise is honored
        androidx.core.content.ContextCompat.startForegroundService(
            this, Intent(this, OverlayService::class.java))
        Toast.makeText(this, "Activated", Toast.LENGTH_SHORT).show()
    }
}