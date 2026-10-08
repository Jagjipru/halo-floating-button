package com.halo.floatingbutton

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager
    private lateinit var cameraManager: CameraManager
    private val handler = Handler(Looper.getMainLooper())

    private var collapsedView: View? = null
    private var expandedRoot: View? = null
    private var isOpen = false

    private var buttonX = 0
    private var buttonY = 0
    private var torchOn = false

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val ink = Color.parseColor("#161A21")
    private val light = Color.parseColor("#EDEFF3")

    private fun buttonColor() = Prefs.resolvedColor(this)
    private fun buttonAlpha() = Prefs.alpha(this) / 100f

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            handler.post { if (!isOpen) refreshCollapsed() }
        }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        buttonX = resources.displayMetrics.widthPixels - dp(56) - dp(16)
        buttonY = resources.displayMetrics.heightPixels / 2
        startAsForeground()
        Prefs.registerListener(this, prefsListener)
        showCollapsed()
    }

    private fun startAsForeground() {
        val channelId = "halo_overlay"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Halo", NotificationManager.IMPORTANCE_MIN)
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Halo")
            .setContentText("Floating button is active")
            .setSmallIcon(R.drawable.ic_launcher)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun circleBg(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun roundBg(color: Int, radiusPx: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusPx
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

    // ---------- collapsed ----------
    private fun showCollapsed() {
        val size = dp(56)
        val button = ImageView(this).apply {
            background = circleBg(buttonColor())
            setImageResource(R.drawable.halo_rings)
            alpha = buttonAlpha()
        }
        val params = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = buttonX; y = buttonY
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var moved = false
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = startX + dx; params.y = startY + dy
                    windowManager.updateViewLayout(button, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    buttonX = params.x; buttonY = params.y
                    if (!moved) expand()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(button, params)
        collapsedView = button
    }

    private fun refreshCollapsed() {
        collapsedView?.let { runCatching { windowManager.removeView(it) } }
        collapsedView = null
        showCollapsed()
    }

    // ---------- expanded ----------
    private fun expand() {
        if (isOpen) return
        isOpen = true
        collapsedView?.let { runCatching { windowManager.removeView(it) } }
        collapsedView = null

        val color = buttonColor()
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val s = dp(56)
        val sat = dp(44)
        val cx = buttonX + s / 2
        val cy = buttonY + s / 2

        // Dynamic layout: a four-corner diamond when the button is free in the
        // middle, otherwise fan the actions inward based on which edge or corner
        // the button is docked against, so it looks right wherever it sits.
        val m = dp(96)
        val nearLeft = cx < m
        val nearRight = cx > screenW - m
        val nearTop = cy < m
        val nearBottom = cy > screenH - m
        val base: Double? = when {                 // 0=right, 90=down, 180=left, 270=up
            nearTop && nearRight -> 135.0          // top-right  -> fan down-left
            nearTop && nearLeft -> 45.0            // top-left   -> fan down-right
            nearBottom && nearRight -> 225.0       // bottom-right -> fan up-left
            nearBottom && nearLeft -> 315.0        // bottom-left  -> fan up-right
            nearRight -> 180.0                     // right edge -> fan left
            nearLeft -> 0.0                        // left edge  -> fan right
            nearTop -> 90.0                        // top edge   -> fan down
            nearBottom -> 270.0                    // bottom edge-> fan up
            else -> null                           // free       -> diamond
        }
        val volumeAbove = nearBottom
        val rArc = dp(82).toDouble()
        val diamondR = dp(72) * 0.72
        val offsets: List<Pair<Float, Float>> = if (base == null) {
            listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)
                .map { (diamondR * it.first).toFloat() to (diamondR * it.second).toFloat() }
        } else {
            doubleArrayOf(-75.0, -25.0, 25.0, 75.0).map { off ->
                val a = Math.toRadians(base + off)
                (rArc * Math.cos(a)).toFloat() to (rArc * Math.sin(a)).toFloat()
            }
        }
        val effR = if (base == null) diamondR else rArc

        val root = FrameLayout(this)
        val scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#5C000000"))
            setOnClickListener { collapse() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // centre close button
        val centre = FrameLayout(this).apply { background = circleBg(color) }
        val closeIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_close); setColorFilter(Color.WHITE)
        }
        centre.addView(closeIcon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        centre.setOnClickListener { collapse() }
        root.addView(centre, FrameLayout.LayoutParams(s, s).apply {
            leftMargin = buttonX; topMargin = buttonY
        })

        // satellites
        val showLabels = Prefs.labels(this)
        for (i in 0..3) {
            val def = Prefs.slot(this, i)
            val circle = FrameLayout(this)
            val icon = ImageView(this)
            val isApp = def.startsWith("app:")
            val torchActive = def == "torch" && torchOn

            circle.background = circleBg(if (torchActive) color else Color.WHITE)
            val labelText: String
            if (isApp) {
                val pkg = def.substring(4)
                val d = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
                if (d != null) icon.setImageDrawable(d)
                else { icon.setImageResource(R.drawable.ic_settings); icon.setColorFilter(ink) }
                labelText = appLabel(pkg)
            } else {
                val res = when (def) {
                    "torch" -> R.drawable.ic_torch
                    "shot" -> R.drawable.ic_screenshot
                    "lock" -> R.drawable.ic_lock
                    else -> R.drawable.ic_settings
                }
                icon.setImageResource(res)
                icon.setColorFilter(if (torchActive) Color.WHITE else ink)
                labelText = when (def) {
                    "torch" -> "Torch"; "shot" -> "Screenshot"; "lock" -> "Lock"; else -> "Settings"
                }
            }
            val iconSize = if (isApp) dp(28) else dp(22)
            circle.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))

            val satCx = cx + offsets[i].first
            val satCy = cy + offsets[i].second

            if (showLabels) {
                val colW = dp(64)
                val column = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                column.addView(circle, LinearLayout.LayoutParams(sat, sat))
                column.addView(satLabel(labelText))
                column.setOnClickListener { onSlot(def, circle, icon, color) }
                val leftM = clamp((satCx - colW / 2).toInt(), dp(6), screenW - colW - dp(6))
                val topM = clamp((satCy - sat / 2).toInt(), dp(30), screenH - sat - dp(28))
                root.addView(column, FrameLayout.LayoutParams(colW, FrameLayout.LayoutParams.WRAP_CONTENT)
                    .apply { leftMargin = leftM; topMargin = topM })
            } else {
                circle.setOnClickListener { onSlot(def, circle, icon, color) }
                val leftM = clamp((satCx - sat / 2).toInt(), dp(8), screenW - sat - dp(8))
                val topM = clamp((satCy - sat / 2).toInt(), dp(30), screenH - sat - dp(8))
                root.addView(circle, FrameLayout.LayoutParams(sat, sat)
                    .apply { leftMargin = leftM; topMargin = topM })
            }
        }

        // volume bar: speaker icon, −, slide track, +, live %
        val barW = dp(232)
        val barH = dp(46)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(Color.WHITE, dp(23).toFloat())
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

        val speaker = ImageView(this).apply {
            setImageResource(R.drawable.ic_volume); setColorFilter(ink)
        }
        val minus = circleButton(R.drawable.ic_minus)
        val plus = circleButton(R.drawable.ic_plus)
        val percent = TextView(this).apply {
            setTextColor(ink); textSize = 12f; gravity = Gravity.CENTER
        }

        val fill = View(this).apply { background = roundBg(color, dp(3).toFloat()) }
        val spacer = View(this)
        val barInner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundBg(light, dp(3).toFloat())
        }
        barInner.addView(fill, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT))
        barInner.addView(spacer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT))

        val touchArea = FrameLayout(this)
        touchArea.addView(barInner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(6), Gravity.CENTER_VERTICAL))

        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val updateVol = {
            val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            (fill.layoutParams as LinearLayout.LayoutParams).weight = vol.toFloat()
            (spacer.layoutParams as LinearLayout.LayoutParams).weight = (maxVol - vol).toFloat()
            fill.requestLayout(); spacer.requestLayout()
            percent.text = "${vol * 100 / maxVol}%"
        }
        touchArea.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (v.width > 0) {
                        val frac = (e.x / v.width).coerceIn(0f, 1f)
                        runCatching {
                            audioManager.setStreamVolume(
                                AudioManager.STREAM_MUSIC, (frac * maxVol).roundToInt(), 0)
                        }
                        updateVol()
                    }
                    true
                }
                else -> true
            }
        }
        holdRepeat(minus) {
            runCatching {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            }
            updateVol()
        }
        holdRepeat(plus) {
            runCatching {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            }
            updateVol()
        }
        updateVol()

        bar.addView(speaker, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) })
        bar.addView(minus, LinearLayout.LayoutParams(dp(30), dp(30)))
        bar.addView(touchArea, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
            leftMargin = dp(8); rightMargin = dp(8)
        })
        bar.addView(plus, LinearLayout.LayoutParams(dp(30), dp(30)))
        bar.addView(percent, LinearLayout.LayoutParams(dp(42), LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = dp(6) })

        val volGap = effR + (if (showLabels) dp(32) else dp(12))
        val vLeft = clamp((cx - barW / 2), dp(8), screenW - barW - dp(8))
        val vTop = clamp(
            (if (volumeAbove) cy - volGap - barH else cy + volGap).toInt(),
            dp(34), screenH - barH - dp(8)
        )
        root.addView(bar, FrameLayout.LayoutParams(barW, barH).apply {
            leftMargin = vLeft; topMargin = vTop
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }

        windowManager.addView(root, params)
        expandedRoot = root
    }

    private fun circleButton(iconRes: Int): FrameLayout {
        val fl = FrameLayout(this).apply { background = circleBg(light) }
        val icon = ImageView(this).apply { setImageResource(iconRes); setColorFilter(ink) }
        fl.addView(icon, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        return fl
    }

    /** Fires [action] on press, then repeats while the finger is held down. */
    private fun holdRepeat(view: View, action: () -> Unit) {
        val repeater = object : Runnable {
            override fun run() { action(); handler.postDelayed(this, 80) }
        }
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { action(); handler.postDelayed(repeater, 400); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(repeater); true
                }
                else -> false
            }
        }
    }

    private fun satLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 9f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setShadowLayer(3f, 0f, 1f, Color.parseColor("#CC000000"))
        layoutParams = LinearLayout.LayoutParams(dp(62), LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(3) }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun collapse() {
        if (!isOpen) return
        isOpen = false
        expandedRoot?.let { runCatching { windowManager.removeView(it) } }
        expandedRoot = null
        showCollapsed()
    }

    // ---------- actions ----------
    private fun onSlot(def: String, satView: FrameLayout, iconView: ImageView, color: Int) {
        if (def.startsWith("app:")) { launchApp(def.substring(4)); return }
        when (def) {
            "torch" -> toggleTorch(satView, iconView, color)
            "settings" -> {
                startActivity(Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                collapse()
            }
            "lock" -> {
                val svc = HaloAccessibilityService.instance
                if (svc == null) { promptAccessibility(); collapse(); return }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                }
                collapse()
            }
            "shot" -> {
                val svc = HaloAccessibilityService.instance
                if (svc == null) { promptAccessibility(); collapse(); return }
                collapse()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    handler.postDelayed({
                        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                    }, 350)
                } else {
                    toast("Screenshot needs Android 11+")
                }
            }
        }
    }

    private fun launchApp(pkg: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } else {
            toast("Can't open that app")
        }
        collapse()
    }

    private fun toggleTorch(satView: FrameLayout, iconView: ImageView, color: Int) {
        val id = flashCameraId()
        if (id == null) { toast("No flashlight on this device"); return }
        try {
            torchOn = !torchOn
            cameraManager.setTorchMode(id, torchOn)
            satView.background = circleBg(if (torchOn) color else Color.WHITE)
            iconView.setColorFilter(if (torchOn) Color.WHITE else ink)
        } catch (e: Exception) {
            torchOn = false
            toast("Torch unavailable right now")
        }
    }

    private fun flashCameraId(): String? = try {
        cameraManager.cameraIdList.firstOrNull { camId ->
            cameraManager.getCameraCharacteristics(camId)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: Exception) { null }

    private fun promptAccessibility() {
        toast("Turn on Halo under Settings ▸ Accessibility to use this")
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun clamp(v: Int, lo: Int, hi: Int) = if (hi < lo) lo else v.coerceIn(lo, hi)

    override fun onDestroy() {
        super.onDestroy()
        Prefs.unregisterListener(this, prefsListener)
        collapsedView?.let { runCatching { windowManager.removeView(it) } }
        expandedRoot?.let { runCatching { windowManager.removeView(it) } }
        collapsedView = null
        expandedRoot = null
    }
}
