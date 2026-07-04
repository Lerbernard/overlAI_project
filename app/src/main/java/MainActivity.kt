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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }

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

        findViewById<MaterialButton>(R.id.btnStart).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                startOverlayService()
            }
        }

        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Deactivated", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------------
    // History tab — minimalist rows built in code
    // ---------------------------------------------------------------------

    private fun refreshHistory() {
        val list = findViewById<LinearLayout>(R.id.historyList)
        list.removeAllViews()

        val entries = HistoryManager.getAll(this)
        val dateFmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

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
                text = dateFmt.format(Date(e.timestamp))
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

            list.addView(row)
        }
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
        findViewById<TextView>(R.id.btnClearHistory).setTextColor(t.primary(this))
        val settingsLayout = findViewById<LinearLayout>(R.id.viewSettings)
        settingsLayout.setBackgroundColor(t.background(this))

        t.applyTabTheme(this, findViewById(R.id.tabLayout))

        // ✅ Tint the gear icon: accent when selected, secondary text color otherwise
        val tabs = findViewById<TabLayout>(R.id.tabLayout)
        tabs.tabIconTint = android.content.res.ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_selected),
                intArrayOf()
            ),
            intArrayOf(t.primary(this), t.textSecondary(this))
        )

        t.applyButtonTheme(
            this,
            findViewById(R.id.btnStart),
            findViewById(R.id.btnStop)
        )

        t.applyCardTheme(settingsLayout, this)
    }

    private fun startOverlayService() {
        // ✅ CRASH FIX: use startService(), NOT startForegroundService().
        // startForegroundService() requires the service to call startForeground()
        // within ~5 seconds, but OverlayService intentionally delays that until it
        // has a MediaProjection token (Android 14 rule). The unfulfilled promise
        // was killing the app with ForegroundServiceDidNotStartInTimeException.
        // startService() is allowed here because the app is in the foreground.
        startService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "Activated", Toast.LENGTH_SHORT).show()
    }
}