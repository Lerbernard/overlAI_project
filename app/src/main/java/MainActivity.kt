package com.example.test103

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

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

        // ✅ Apply theme before anything is shown
        applyTheme()

        val tabLayout      = findViewById<TabLayout>(R.id.tabLayout)
        val viewOverlay    = findViewById<LinearLayout>(R.id.viewOverlay)
        val viewDetector   = findViewById<FrameLayout>(R.id.viewDetector)
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
                    2 -> viewSettings.visibility = View.VISIBLE
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        cropSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("use_crop", isChecked).apply()
        }

        darkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("dark_mode", isChecked).apply()
            recreate()
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

    private fun applyTheme() {
        val t = ThemeHelper

        // ✅ Status bar color matches theme background
        window.statusBarColor = t.background(this)

        // Also match the status bar icon color to theme
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

        // Root + all containers
        findViewById<View>(android.R.id.content).setBackgroundColor(t.background(this))
        findViewById<FrameLayout>(R.id.mainContainer).setBackgroundColor(t.background(this))
        findViewById<FrameLayout>(R.id.viewDetector).setBackgroundColor(t.background(this))
        findViewById<LinearLayout>(R.id.viewOverlay).setBackgroundColor(t.background(this))
        val settingsLayout = findViewById<LinearLayout>(R.id.viewSettings)
        settingsLayout.setBackgroundColor(t.background(this))

        // Tab bar
        t.applyTabTheme(this, findViewById(R.id.tabLayout))

        // Overlay buttons
        t.applyButtonTheme(
            this,
            findViewById(R.id.btnStart),
            findViewById(R.id.btnStop)
        )

        // Settings cards + text
        t.applyCardTheme(settingsLayout, this)
    }

    private fun startOverlayService() {
        val serviceIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
        Toast.makeText(this, "Activated", Toast.LENGTH_SHORT).show()
    }
}