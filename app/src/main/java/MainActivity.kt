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

        val viewOverlay    = findViewById<LinearLayout>(R.id.viewOverlay)
        val viewDetector   = findViewById<FrameLayout>(R.id.viewDetector)
        val viewHistory    = findViewById<LinearLayout>(R.id.viewHistory)
        val viewSettings   = findViewById<LinearLayout>(R.id.viewSettings)

        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        // toggle labels are populated by refreshSettingsStatus()

        // ✅ bottom navigation, Proton-style: icon pill + label
        buildBottomNav()
        selectTab(prefs.getInt("restore_tab", 0))
        prefs.edit().remove("restore_tab").apply()

        // ✅ quick-check notification deep-links straight to a tab
        intent?.getIntExtra("open_tab", -1)?.takeIf { it in 0..3 }?.let { selectTab(it) }

        // ✅ full walkthrough on first launch
        if (!prefs.getBoolean("tutorial_done", false)) {
            TutorialDialog.show(this) {
                prefs.edit().putBoolean("tutorial_done", true).apply()
            }
        }

        // ✅ flat pill toggles (label in the track, sliding knob)
        findViewById<PillToggleView>(R.id.cropSwitch)?.apply {
            configure("ON", "OFF")
            onToggle = { checked -> prefs.edit().putBoolean("use_crop", checked).apply() }
        }

        findViewById<PillToggleView>(R.id.autoOffSwitch)?.apply {
            configure("ON", "OFF")
            onToggle = { checked -> prefs.edit().putBoolean("auto_off", checked).apply() }
        }

        findViewById<PillToggleView>(R.id.themeSwitch)?.apply {
            configure("DARK", "LIGHT")
            onToggle = { checked ->
                // ✅ only recreate on a REAL change — this is what caused the
                // infinite relaunch loop before
                if (checked != ThemeHelper.isDark(this@MainActivity)) {
                    prefs.edit()
                        .putBoolean("dark_mode", checked)
                        .putInt("restore_tab", currentTab)
                        .apply()
                    recreate()
                }
            }
        }

        // ✅ sync the icon pref with the ACTUAL enabled alias, then wire the preview
        try {
            val state = packageManager.getComponentEnabledSetting(
                android.content.ComponentName(this, "com.example.test103.LauncherLight"))
            prefs.edit().putBoolean("icon_light",
                state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED).apply()
        } catch (_: Exception) {}

        findViewById<ImageView>(R.id.iconPreview)?.setOnClickListener {
            val toLight = !prefs.getBoolean("icon_light", false)
            prefs.edit().putBoolean("icon_light", toLight).apply()
            applyLauncherIcon(toLight)
            refreshSettingsStatus()
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

        // ✅ friendly empty state
        if (entries.isEmpty()) {
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(90), 0, 0)
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_nav_history)
                    setColorFilter(ThemeHelper.textSecondary(this@MainActivity))
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                })
                addView(TextView(this@MainActivity).apply {
                    text = "No checks yet"
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(ThemeHelper.textPrimary(this@MainActivity))
                    setPadding(0, dp(16), 0, 0)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(TextView(this@MainActivity).apply {
                    text = "Results from the overlay, Detector,\nshares and quick checks land here"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(ThemeHelper.textSecondary(this@MainActivity))
                    setPadding(0, dp(6), 0, 0)
                })
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))   // ✅ full width → truly centered
            return
        }

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
                    setColor(ThemeHelper.divider(this@MainActivity))
                }
                val bmp: Bitmap? = e.thumbPath?.let { decodeSampled(it, dp(56)) }   // ✅ sampled
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
                else -> ThemeHelper.scoreHigh(this@MainActivity)
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
                setGlyphTint(ThemeHelper.scoreHigh(this@MainActivity))
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
    /** ✅ decode a thumbnail no larger than needed — keeps History smooth */
    private fun decodeSampled(path: String, targetPx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx &&
            bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) { null }

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
            else -> ThemeHelper.scoreHigh(this@MainActivity)
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
            .setPositiveButton("Delete") { _, _ ->
                HistoryManager.remove(this, e.timestamp)
                refreshHistory()
            }
            .setNegativeButton("Close", null)
            .setNeutralButton("Share") { _, _ -> shareEntry(e) }
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(t.card(this@MainActivity))
        })
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(ThemeHelper.scoreHigh(this@MainActivity))   // Delete — red, rightmost
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(t.primary(this))
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setTextColor(t.primary(this))
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
        findViewById<TextView>(R.id.pageTitleHistory)?.setTextColor(t.textPrimary(this))
        findViewById<TextView>(R.id.pageTitleSettings)?.setTextColor(t.textPrimary(this))
        findViewById<TextView>(R.id.usage_title)?.setTextColor(t.textPrimary(this))
        findViewById<TextView>(R.id.usage_text)?.setTextColor(t.textSecondary(this))
        findViewById<TextView>(R.id.btnClearHistory).setTextColor(t.primary(this))
        val settingsLayout = findViewById<LinearLayout>(R.id.viewSettings)
        settingsLayout.setBackgroundColor(t.background(this))

        // ✅ bottom bar theming
        findViewById<View>(R.id.navDivider).setBackgroundColor(ThemeHelper.divider(this@MainActivity))
        findViewById<LinearLayout>(R.id.bottomNav).setBackgroundColor(t.background(this))

        t.applyCardTheme(settingsLayout, this)
    }

    // ------------------------------------------------------------------
    // Bottom navigation (custom: icon pill + label, teal = selected)
    // ------------------------------------------------------------------

    private var currentTab = 0
    private val navIcons = ArrayList<ImageView>()
    private val navPills = ArrayList<FrameLayout>()
    private val navLabels = ArrayList<TextView>()

    private fun buildBottomNav() {
        val bar = findViewById<LinearLayout>(R.id.bottomNav)
        bar.removeAllViews()
        navIcons.clear(); navPills.clear(); navLabels.clear()

        val items = listOf(
            Pair("OverlAI", R.drawable.ic_nav_logo),   // ✅ brand logo as the home icon
            Pair("Detector", R.drawable.ic_nav_detector),
            Pair("History", R.drawable.ic_nav_history),
            Pair("Settings", R.drawable.ic_settings)
        )

        items.forEachIndexed { index, (label, iconRes) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                isClickable = true
                setOnClickListener { selectTab(index) }
            }

            val pill = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(32))
            }
            val icon = ImageView(this).apply {
                setImageResource(iconRes)
                contentDescription = label
                layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
            }
            pill.addView(icon)

            val text = TextView(this).apply {
                this.text = label
                textSize = 12f
                maxLines = 1
                includeFontPadding = false          // ✅ kills the phantom top gap
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(5), 0, 0)
            }

            item.addView(pill)
            item.addView(text)
            bar.addView(item)

            navPills.add(pill)
            navIcons.add(icon)
            navLabels.add(text)
        }
    }

    private fun selectTab(index: Int) {
        val previous = currentTab
        currentTab = index

        // ✅ leaving the Detector clears it back to its empty state
        if (previous == 1 && index != 1) {
            (supportFragmentManager.findFragmentById(R.id.viewDetector) as? DetectorFragment)
                ?.resetPage()
        }

        findViewById<LinearLayout>(R.id.viewOverlay).visibility = if (index == 0) View.VISIBLE else View.GONE
        findViewById<FrameLayout>(R.id.viewDetector).visibility = if (index == 1) View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.viewHistory).visibility = if (index == 2) View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.viewSettings).visibility = if (index == 3) View.VISIBLE else View.GONE

        if (index == 1 && supportFragmentManager.findFragmentById(R.id.viewDetector) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.viewDetector, DetectorFragment())
                .commit()
        }
        if (index == 2) refreshHistory()

        getSharedPreferences("app_settings", MODE_PRIVATE)
            .edit().putInt("restore_tab", index).apply()

        styleBottomNav()
    }

    private fun styleBottomNav() {
        val t = ThemeHelper
        val selected = t.tabIndicator(this)          // ✅ teal = selected, as before
        val pillBg = Color.argb(46, Color.red(selected), Color.green(selected), Color.blue(selected))
        val idle = t.textSecondary(this)

        // ✅ light mode uses the dark-dot logo so the mark stays visible
        navIcons.getOrNull(0)?.setImageResource(
            if (ThemeHelper.isDark(this)) R.drawable.ic_nav_logo else R.drawable.ic_nav_logo_light)

        for (i in navIcons.indices) {
            val isSel = i == currentTab
            if (i == 0) {
                // ✅ brand colors only while Home is selected; grey otherwise
                if (isSel) {
                    navIcons[i].clearColorFilter()
                } else {
                    navIcons[i].setColorFilter(idle)
                }
                navIcons[i].alpha = 1f
            } else {
                navIcons[i].alpha = 1f
                navIcons[i].setColorFilter(if (isSel) selected else idle)
            }
            navLabels[i].setTextColor(if (isSel) selected else idle)
            navLabels[i].setTypeface(null,
                if (isSel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            navPills[i].background = if (isSel) GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(pillBg)
            } else null
        }
    }

    /** ✅ About card: version + tutorial replay */
    private fun setupAboutCard() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "?" }
        findViewById<TextView>(R.id.aboutVersion)?.apply {
            text = "OverlAI v$version"
            setTextColor(ThemeHelper.textPrimary(this@MainActivity))
        }
        findViewById<TextView>(R.id.btnTerms)?.apply {
            setTextColor(ThemeHelper.primary(this@MainActivity))
            setOnClickListener { showLegalDialog("Terms of Use", TERMS_TEXT) }
        }
        findViewById<TextView>(R.id.btnPrivacy)?.apply {
            setTextColor(ThemeHelper.primary(this@MainActivity))
            setOnClickListener { showLegalDialog("Privacy Policy", PRIVACY_TEXT) }
        }
        findViewById<TextView>(R.id.btnTutorial)?.apply {
            setTextColor(ThemeHelper.primary(this@MainActivity))
            setOnClickListener { TutorialDialog.show(this@MainActivity) }
        }
    }

    /** ✅ scrollable popup for Terms / Privacy */
    private fun showLegalDialog(title: String, body: String) {
        val t = ThemeHelper
        val scroll = android.widget.ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(20), dp(22), dp(8))
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 19f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(t.textPrimary(this@MainActivity))
                })
                addView(TextView(this@MainActivity).apply {
                    text = body
                    textSize = 14f
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setTextColor(t.textSecondary(this@MainActivity))
                    setPadding(0, dp(12), 0, 0)
                })
            })
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton("Close", null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(t.card(this@MainActivity))
        })
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            ?.setTextColor(t.primary(this))
    }

    companion object {
        private const val PRIVACY_TEXT = """When you check an image or video, that media is uploaded to Sightengine, a third-party detection service, for analysis. Sightengine's own privacy policy applies to that processing.

What stays on your device: detection results, thumbnails in your History, and monthly usage counters. OverlAI has no accounts, no analytics, and no ads — nothing else leaves your phone.

Screen capture only happens when you trigger it, and Android asks for your consent each time. The overlay permission is used solely to draw the floating bubble.

You can delete individual results from History at any time, or remove everything by clearing the app's data or uninstalling. Notifications are used only for overlay status and quick-check results, and can be disabled in system settings."""

        private const val TERMS_TEXT = """OverlAI is provided as-is, without warranties of any kind.

Detection results are probabilistic estimates produced by a third-party AI model. They can be wrong in both directions and must not be treated as proof that content is or is not AI-generated. Do not rely on them for legal, journalistic, or other critical decisions.

You are responsible for the content you capture and submit, including respecting other people's rights and privacy when checking material that isn't yours. You agree not to use the app to harass others or to conduct unlawful surveillance.

Detection depends on an external service and may be unavailable, rate-limited, or changed at any time. The developer is not liable for any damages arising from use of the app or reliance on its results.

These terms may be updated as the app evolves; continued use means acceptance of the current version."""
    }

    /** ✅ share a history entry's image via the system share sheet */
    private fun shareEntry(e: HistoryManager.Entry) {
        val path = e.thumbPath
        if (path == null) {
            Toast.makeText(this, "No image stored for this entry", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${BuildConfig.APPLICATION_ID}.fileprovider", java.io.File(path))
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "AI likelihood: ${e.score}% — checked with OverlAI")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share result"))
        } catch (ex: Exception) {
            Toast.makeText(this, "Couldn't share this image", Toast.LENGTH_SHORT).show()
        }
    }

    /** ✅ sync the pill toggles + launcher-icon preview */
    private fun refreshSettingsStatus() {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        findViewById<PillToggleView>(R.id.cropSwitch)?.apply {
            setChecked(prefs.getBoolean("use_crop", true))
            applyThemeColors()
        }
        findViewById<PillToggleView>(R.id.autoOffSwitch)?.apply {
            setChecked(prefs.getBoolean("auto_off", false))
            applyThemeColors()
        }
        findViewById<PillToggleView>(R.id.themeSwitch)?.apply {
            setChecked(ThemeHelper.isDark(this@MainActivity))
            applyThemeColors()
        }

        val light = prefs.getBoolean("icon_light", false)
        findViewById<ImageView>(R.id.iconPreview)?.setImageDrawable(iconPreviewDrawable(light))
    }

    private fun iconPreviewDrawable(light: Boolean): android.graphics.drawable.Drawable {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (light) Color.WHITE else Color.parseColor("#0E0E10"))
            if (light) setStroke(dp(1), Color.parseColor("#33000000"))
        }
        val mark = androidx.core.content.ContextCompat.getDrawable(this,
            if (light) R.drawable.ic_nav_logo_light else R.drawable.ic_nav_logo)!!.mutate()
        val layer = android.graphics.drawable.LayerDrawable(arrayOf(bg, mark))
        val inset = dp(9)
        layer.setLayerInset(1, inset, inset, inset, inset)
        return layer
    }

    /** ✅ swap the launcher (and Android 12+ splash) icon via activity aliases */
    private fun applyLauncherIcon(light: Boolean) {
        val pm = packageManager
        val dark = android.content.ComponentName(this, "com.example.test103.LauncherDark")
        val lightC = android.content.ComponentName(this, "com.example.test103.LauncherLight")
        try {
            pm.setComponentEnabledSetting(if (light) lightC else dark,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(if (light) dark else lightC,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP)
            Toast.makeText(this,
                "Icon updated — your launcher may take a moment", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't switch the icon", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getIntExtra("open_tab", -1).takeIf { it in 0..3 }?.let { selectTab(it) }
    }

    override fun onResume() {
        super.onResume()
        setupAboutCard()
        refreshSettingsStatus()
        refreshUsage()
        refreshDashboard()
    }

    /** ✅ Overlay tab: minimal centered hero — ring, state, button. */
    private fun refreshDashboard() {
        val t = ThemeHelper
        val on = OverlayService.isRunning
        // ✅ the logo IS the status light: full color when on, gray when off
        findViewById<ImageView>(R.id.dashLogo)?.apply {
            // ✅ dot color follows the theme: white dots in dark, black in light
            setImageResource(if (ThemeHelper.isDark(this@MainActivity))
                R.drawable.ic_nav_logo else R.drawable.ic_nav_logo_light)
            if (on) {
                clearColorFilter()
                alpha = 1f
            } else {
                val cm = android.graphics.ColorMatrix().apply { setSaturation(0f) }
                colorFilter = android.graphics.ColorMatrixColorFilter(cm)
                alpha = 0.4f
            }
        }

        findViewById<TextView>(R.id.dashState)?.apply {
            text = if (on) "Overlay is on" else "Overlay is off"
            setTextColor(t.textPrimary(this@MainActivity))
        }

        findViewById<MaterialButton>(R.id.btnToggleOverlay)?.apply {
            text = if (on) "Deactivate" else "Activate"
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (on) ThemeHelper.btnNeutral(this@MainActivity) else t.primary(this@MainActivity))
            setTextColor(Color.WHITE)
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