package com.example.test103

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Notch handling
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }

        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        supportActionBar?.hide()

        setContentView(R.layout.activity_main)

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val viewOverlay = findViewById<LinearLayout>(R.id.viewOverlay)
        val viewDetector = findViewById<LinearLayout>(R.id.viewDetector)
        val viewSettings = findViewById<LinearLayout>(R.id.viewSettings)
        val startBtn = findViewById<MaterialButton>(R.id.btnStart)
        val stopBtn = findViewById<MaterialButton>(R.id.btnStop)
        val cropSwitch = findViewById<SwitchMaterial>(R.id.crop_switch)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                viewOverlay.visibility = View.GONE
                viewDetector.visibility = View.GONE
                viewSettings.visibility = View.GONE

                when (tab?.position) {
                    0 -> viewOverlay.visibility = View.VISIBLE
                    1 -> viewDetector.visibility = View.VISIBLE
                    2 -> viewSettings.visibility = View.VISIBLE
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        cropSwitch.isChecked = prefs.getBoolean("use_crop", true)
        cropSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("use_crop", isChecked).apply()
        }

        startBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            } else {
                startOverlayService()
            }
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Deactivated", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startOverlayService() {
        val serviceIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Activated", Toast.LENGTH_SHORT).show()
    }
}