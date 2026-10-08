package com.halo.floatingbutton

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var actionBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.seedDefaults(this)

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(40), px(24), px(24))
        }

        val title = TextView(this).apply {
            text = "Halo"
            textSize = 30f
            setTextColor(Color.parseColor("#161A21"))
        }
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            ""
        }
        val subtitle = TextView(this).apply {
            text = if (versionName.isNullOrBlank()) "Floating button" else "Floating button · v$versionName"
            textSize = 15f
            setTextColor(Color.parseColor("#5F6B7A"))
            setPadding(0, px(6), 0, px(28))
        }
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, px(28))
        }
        actionBtn = Button(this).apply {
            text = "Enable floating button"
            setOnClickListener { onAction() }
        }

        val customiseBtn = Button(this).apply {
            text = "Customise Halo (colour, transparency, actions)"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        val accessBtn = Button(this).apply {
            text = "Enable lock & screenshot (Accessibility)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        val accessNote = TextView(this).apply {
            text = "Optional. Torch and volume work without this. Lock and screenshot need Halo turned on under Accessibility."
            textSize = 12f
            setTextColor(Color.parseColor("#8B96A5"))
            setPadding(0, px(10), 0, 0)
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(
            actionBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            customiseBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(14) }
        )
        root.addView(
            accessBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(10) }
        )
        root.addView(accessNote)

        // ---------- About ----------
        val aboutLabel = TextView(this).apply {
            text = "ABOUT"
            textSize = 12f
            setTextColor(Color.parseColor("#8B96A5"))
            setPadding(0, px(36), 0, px(10))
        }
        val creator = TextView(this).apply {
            text = "Created by Jagjit Singh"
            textSize = 16f
            setTextColor(Color.parseColor("#161A21"))
        }
        val tagline = TextView(this).apply {
            text = "Halo — a personal floating control button"
            textSize = 12f
            setTextColor(Color.parseColor("#8B96A5"))
            setPadding(0, px(4), 0, px(14))
        }
        val emailLabel = TextView(this).apply {
            text = "Suggestions & bugs"
            textSize = 12f
            setTextColor(Color.parseColor("#8B96A5"))
        }
        val email = TextView(this).apply {
            text = "jagjitsinghpruthi@gmail.com"
            textSize = 15f
            setTextColor(Color.parseColor("#F2552C"))
            setPadding(0, px(2), 0, 0)
            setOnClickListener {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:jagjitsinghpruthi@gmail.com"))
                    .putExtra(Intent.EXTRA_SUBJECT, "Halo — feedback")
                try {
                    startActivity(Intent.createChooser(intent, "Send email"))
                } catch (e: Exception) {
                    // no email app available
                }
            }
        }
        root.addView(aboutLabel)
        root.addView(creator)
        root.addView(tagline)
        root.addView(emailLabel)
        root.addView(email)

        setContentView(root)
    }

    private fun onAction() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
        val svc = Intent(this, FloatingButtonService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
        moveTaskToBack(true)
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            status.text = "Overlay permission: granted ✓\nTap below to show the button."
            status.setTextColor(Color.parseColor("#1F9D6B"))
            actionBtn.text = "Show floating button"
        } else {
            status.text =
                "Overlay permission: needed.\nTap below, then allow “Display over other apps”, and return here."
            status.setTextColor(Color.parseColor("#C0392B"))
            actionBtn.text = "Grant permission"
        }
    }
}
