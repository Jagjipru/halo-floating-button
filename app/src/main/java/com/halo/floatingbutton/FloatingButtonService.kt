package com.halo.floatingbutton

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.widget.NumberPicker
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
    private var popupView: View? = null
    private var isOpen = false
    private var hidden = false

    private var buttonX = 0
    private var buttonY = 0
    private var torchOn = false
    private var idleRunnable: Runnable? = null
    private var unhideRunnable: Runnable? = null

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val ink = Color.parseColor("#161A21")
    private val light = Color.parseColor("#EDEFF3")
    private val faintInk = Color.parseColor("#8B96A5")

    private fun buttonColor() = Prefs.resolvedColor(this)
    private fun buttonAlpha() = Prefs.alpha(this) / 100f

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.KEY_COLOR || key == Prefs.KEY_ALPHA || key == Prefs.KEY_SIZE) {
                handler.post { if (!isOpen) refreshCollapsed() }
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UNHIDE) unhide()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        Prefs.seedDefaults(this)
        val sz = dp(Prefs.size(this))
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val savedX = Prefs.posX(this)
        val savedY = Prefs.posY(this)
        buttonX = if (savedX >= 0) savedX.coerceIn(0, sw - sz) else sw - sz - dp(16)
        buttonY = if (savedY >= 0) savedY.coerceIn(dp(40), sh - sz - dp(54)) else sh / 2
        startAsForeground()
        Prefs.registerListener(this, prefsListener)
        applyStartState()
    }

    /** Decide whether to show the button on start, honouring a temporary hide. */
    private fun applyStartState() {
        when (Prefs.hideMode(this)) {
            "timer" -> {
                val until = Prefs.hideUntil(this)
                if (System.currentTimeMillis() >= until) {
                    Prefs.clearHide(this); showCollapsed()
                } else {
                    hidden = true; scheduleUnhideTimers(until); updateNotification(true)
                }
            }
            "restart", "app" -> { hidden = true; updateNotification(true) }
            else -> showCollapsed()
        }
    }

    private val channelId = "halo_overlay"

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Halo", NotificationManager.IMPORTANCE_MIN)
        )
        val notification = buildNotification(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun buildNotification(isHidden: Boolean): Notification {
        val tap = PendingIntent.getService(
            this, 8,
            Intent(this, FloatingButtonService::class.java).setAction(ACTION_UNHIDE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, channelId)
            .setContentTitle("Halo")
            .setContentText(if (isHidden) "Hidden — tap to show" else "Floating button is active")
            .setSmallIcon(R.drawable.ic_notification)
        if (isHidden) b.setContentIntent(tap)
        return b.build()
    }

    private fun updateNotification(isHidden: Boolean) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(1, buildNotification(isHidden))
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
        if (collapsedView != null || isOpen || hidden) return
        val size = dp(Prefs.size(this))
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
        var longFired = false
        val longRunnable = Runnable { if (!moved) { longFired = true; doLongPress() } }

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    moved = false; longFired = false
                    cancelIdle()
                    button.animate().alpha(buttonAlpha()).setDuration(120).start()
                    handler.postDelayed(longRunnable, 450)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        moved = true; handler.removeCallbacks(longRunnable)
                    }
                    val maxX = resources.displayMetrics.widthPixels - size
                    val maxY = resources.displayMetrics.heightPixels - size - dp(54)
                    params.x = (startX + dx).coerceIn(0, maxX)
                    params.y = (startY + dy).coerceIn(dp(40), maxY)
                    windowManager.updateViewLayout(button, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longRunnable)
                    when {
                        longFired -> {
                            buttonX = params.x; buttonY = params.y
                            Prefs.setPos(this, buttonX, buttonY); scheduleIdle()
                        }
                        moved -> {
                            if (Prefs.snap(this)) {
                                snapToEdge(button, params, size)
                            } else {
                                buttonX = params.x; buttonY = params.y
                                Prefs.setPos(this, buttonX, buttonY); scheduleIdle()
                            }
                        }
                        else -> { buttonX = params.x; buttonY = params.y; expand() }
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(button, params)
        collapsedView = button
        scheduleIdle()
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams, size: Int) {
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val cx = params.x + size / 2
        val cy = params.y + size / 2
        val dLeft = cx; val dRight = sw - cx; val dTop = cy; val dBottom = sh - cy
        val minD = minOf(dLeft, dRight, dTop, dBottom)
        var tx = params.x; var ty = params.y
        when (minD) {
            dLeft -> tx = 0
            dRight -> tx = sw - size
            dTop -> ty = dp(40)
            else -> ty = sh - size - dp(54)
        }
        val sx = params.x; val sy = params.y
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            addUpdateListener {
                val f = it.animatedValue as Float
                params.x = (sx + (tx - sx) * f).toInt()
                params.y = (sy + (ty - sy) * f).toInt()
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) {
                buttonX = tx; buttonY = ty
                Prefs.setPos(this@FloatingButtonService, buttonX, buttonY)
                scheduleIdle()
            }
        })
        anim.start()
    }

    private fun idleAlpha() = (buttonAlpha() * 0.5f).coerceAtLeast(0.12f)

    private fun scheduleIdle() {
        cancelIdle()
        val r = Runnable {
            if (!isOpen) collapsedView?.animate()?.alpha(idleAlpha())?.setDuration(500)?.start()
        }
        idleRunnable = r
        handler.postDelayed(r, 3000)
    }

    private fun cancelIdle() {
        idleRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun doLongPress() {
        collapsedView?.animate()?.alpha(buttonAlpha())?.setDuration(120)?.start()
        performAction(Prefs.longPress(this))
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
        val s = dp(Prefs.size(this))
        val sat = dp(44)
        val animViews = ArrayList<View>()
        val cx = buttonX + s / 2
        val cy = buttonY + s / 2

        // Dynamic layout: a four-corner diamond when the button is free in the
        // middle, otherwise fan the actions inward based on which edge or corner
        // the button is docked against, so it looks right wherever it sits.
        val m = dp(96)
        // Fan down when near the top, up when near the bottom (biased toward the
        // screen interior), sideways on the mid-height edges, diamond in the middle.
        val hBias = ((cx - screenW / 2.0) / (screenW / 2.0)).coerceIn(-1.0, 1.0) * 15.0
        val topZone = cy < screenH * 0.30
        val botZone = cy > screenH * 0.70
        val base: Double? = when {                 // 0=right, 90=down, 180=left, 270=up
            topZone -> 90.0 + hBias                // near top    -> fan down
            botZone -> 270.0 - hBias               // near bottom -> fan up
            cx > screenW - m -> 180.0              // right edge  -> fan left
            cx < m -> 0.0                          // left edge   -> fan right
            else -> null                           // middle      -> diamond
        }
        val volumeAbove = botZone
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
                val topM = clamp((satCy - sat / 2).toInt(), dp(40), screenH - sat - dp(28))
                root.addView(column, FrameLayout.LayoutParams(colW, FrameLayout.LayoutParams.WRAP_CONTENT)
                    .apply { leftMargin = leftM; topMargin = topM })
                animViews.add(column)
            } else {
                circle.setOnClickListener { onSlot(def, circle, icon, color) }
                val leftM = clamp((satCx - sat / 2).toInt(), dp(8), screenW - sat - dp(8))
                val topM = clamp((satCy - sat / 2).toInt(), dp(40), screenH - sat - dp(8))
                root.addView(circle, FrameLayout.LayoutParams(sat, sat)
                    .apply { leftMargin = leftM; topMargin = topM })
                animViews.add(circle)
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

        val volGap = effR + dp(30) + (if (showLabels) dp(24) else 0)
        val vLeft = clamp((cx - barW / 2), dp(8), screenW - barW - dp(8))
        val vTop = clamp(
            (if (volumeAbove) cy - volGap - barH else cy + volGap).toInt(),
            dp(34), screenH - barH - dp(8)
        )
        root.addView(bar, FrameLayout.LayoutParams(barW, barH).apply {
            leftMargin = vLeft; topMargin = vTop
        })

        // Hide-Halo pill, anchored bottom-centre
        val hidePill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(Color.WHITE, dp(20).toFloat())
            setPadding(dp(14), dp(9), dp(16), dp(9))
        }
        hidePill.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_hide); setColorFilter(ink)
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) })
        hidePill.addView(TextView(this).apply {
            text = "Hide Halo"; textSize = 13f; setTextColor(ink)
        })
        hidePill.setOnClickListener {
            // leave the menu and open the hide-duration chooser
            isOpen = false
            expandedRoot?.let { runCatching { windowManager.removeView(it) } }
            expandedRoot = null
            showHideChooser()
        }
        root.addView(hidePill, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(30) })
        animViews.add(hidePill)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }

        // open animation (style from settings)
        val animStyle = Prefs.anim(this)
        if (animStyle != "none") {
            scrim.alpha = 0f; centre.alpha = 0f; bar.alpha = 0f
            animViews.forEach { it.alpha = 0f }
        }

        windowManager.addView(root, params)
        expandedRoot = root

        if (animStyle != "none") {
            root.post { runOpenAnimation(animStyle, scrim, centre, bar, animViews) }
        }
    }

    private fun runOpenAnimation(style: String, scrim: View, centre: View, bar: View, sats: List<View>) {
        scrim.animate().alpha(1f).setDuration(140).start()
        if (style == "fade") {
            centre.animate().alpha(1f).setDuration(200).start()
            sats.forEachIndexed { i, v ->
                v.animate().alpha(1f).setStartDelay(i * 18L).setDuration(200).start()
            }
            bar.animate().alpha(1f).setStartDelay(70L).setDuration(200).start()
            return
        }
        val overshoot = style == "spring"
        val interp = if (overshoot) android.view.animation.OvershootInterpolator(2.4f)
                     else android.view.animation.DecelerateInterpolator()
        val dur = if (overshoot) 280L else 170L
        val stagger = if (style == "scale") 0L else 28L
        fun pop(v: View, startScale: Float, delay: Long) {
            v.pivotX = v.width / 2f; v.pivotY = v.height / 2f
            v.scaleX = startScale; v.scaleY = startScale
            v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setInterpolator(interp).setStartDelay(delay).setDuration(dur).start()
        }
        pop(centre, 0.5f, 0L)
        sats.forEachIndexed { i, v -> pop(v, 0.4f, 40L + i * stagger) }
        pop(bar, 0.7f, if (style == "scale") 0L else 150L)
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
        when {
            def.startsWith("app:") -> { launchAppNoCollapse(def.substring(4)); collapse() }
            def == "torch" -> {
                val on = toggleTorchCore()
                if (on != null) {
                    satView.background = circleBg(if (on) color else Color.WHITE)
                    iconView.setColorFilter(if (on) Color.WHITE else ink)
                }
            }
            def == "settings" -> { openSettingsScreen(); collapse() }
            def == "lock" -> { lockAction(); collapse() }
            def == "shot" -> { collapse(); screenshotAction() }
        }
    }

    /** Runs an action by id — shared by a slot tap and the long-press shortcut. */
    private fun performAction(id: String) {
        when {
            id == "nothing" -> {}
            id == "torch" -> { val on = toggleTorchCore(); if (on != null) toast("Torch ${if (on) "on" else "off"}") }
            id == "shot" -> screenshotAction()
            id == "lock" -> lockAction()
            id == "settings" -> openSettingsScreen()
            id.startsWith("app:") -> launchAppNoCollapse(id.substring(4))
        }
    }

    private fun openSettingsScreen() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun lockAction() {
        val svc = HaloAccessibilityService.instance ?: run { promptAccessibility(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }
    }

    private fun screenshotAction() {
        val svc = HaloAccessibilityService.instance ?: run { promptAccessibility(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            handler.postDelayed({
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            }, 350)
        } else {
            toast("Screenshot needs Android 11+")
        }
    }

    private fun launchAppNoCollapse(pkg: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } else {
            toast("Can't open that app")
        }
    }

    private fun toggleTorchCore(): Boolean? {
        val id = flashCameraId() ?: run { toast("No flashlight on this device"); return null }
        return try {
            torchOn = !torchOn
            cameraManager.setTorchMode(id, torchOn)
            torchOn
        } catch (e: Exception) {
            torchOn = false
            toast("Torch unavailable right now")
            null
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

    // ---------- temporary hide ----------
    private val now get() = System.currentTimeMillis()

    private fun popupWindowParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }

    private fun removePopup() {
        popupView?.let { runCatching { windowManager.removeView(it) } }
        popupView = null
    }

    /** A centred white card over a scrim, matching the menu's look. */
    private fun cardPopup(title: String, onCancel: () -> Unit, build: (LinearLayout) -> Unit) {
        removePopup()
        val root = FrameLayout(this)
        val scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#5C000000"))
            setOnClickListener { removePopup(); onCancel() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(Color.WHITE, dp(20).toFloat())
            setPadding(dp(18), dp(18), dp(18), dp(12))
        }
        card.addView(TextView(this).apply {
            text = title; textSize = 16f; setTextColor(ink)
            setPadding(dp(4), 0, 0, dp(12))
        })
        build(card)
        root.addView(card, FrameLayout.LayoutParams(dp(268),
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        windowManager.addView(root, popupWindowParams())
        popupView = root
        card.alpha = 0f; card.scaleX = 0.9f; card.scaleY = 0.9f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .setDuration(170).start()
    }

    private fun pill(label: String, onClick: () -> Unit): View {
        val t = TextView(this).apply {
            text = label; textSize = 15f; setTextColor(ink)
            background = roundBg(light, dp(12).toFloat())
            setPadding(dp(16), dp(13), dp(16), dp(13))
            setOnClickListener { onClick() }
        }
        return t
    }

    private fun LinearLayout.addPill(label: String, onClick: () -> Unit) {
        addView(pill(label, onClick), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
    }

    private fun showHideChooser() = cardPopup("Hide Halo", onCancel = { showCollapsed() }) { card ->
        card.addPill("5 minutes") { removePopup(); hideFor("timer", now + 5 * 60_000L) }
        card.addPill("10 minutes") { removePopup(); hideFor("timer", now + 10 * 60_000L) }
        card.addPill("30 minutes") { removePopup(); hideFor("timer", now + 30 * 60_000L) }
        card.addPill("Custom…") { removePopup(); showHideCustom() }
    }

    private fun showHideCustom() = cardPopup("Hide until…", onCancel = { showCollapsed() }) { card ->
        card.addPill("I restart my phone") { removePopup(); hideFor("restart", 0L) }
        card.addPill("I open Halo again") { removePopup(); hideFor("app", 0L) }
        card.addPill("A custom time…") { removePopup(); showCustomTime() }
    }

    private fun showCustomTime() = cardPopup("Hide for…", onCancel = { showCollapsed() }) { card ->
        val hours = NumberPicker(this).apply {
            minValue = 0; maxValue = 12; value = 0
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val mins = NumberPicker(this).apply {
            minValue = 0; maxValue = 59; value = 5
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val pickers = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        fun unit(label: String) = TextView(this).apply {
            text = label; textSize = 13f; setTextColor(faintInk)
            setPadding(dp(4), 0, dp(14), 0)
        }
        pickers.addView(hours); pickers.addView(unit("h"))
        pickers.addView(mins); pickers.addView(unit("m"))
        card.addView(pickers, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val confirm = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundBg(buttonColor(), dp(12).toFloat())
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        fun totalMs() = (hours.value * 60 + mins.value) * 60_000L
        fun refresh() {
            val ms = totalMs()
            confirm.text = if (ms > 0) "Hide for ${fmtDuration(ms)}" else "Pick a time"
        }
        refresh()
        hours.setOnValueChangedListener { _, _, _ -> refresh() }
        mins.setOnValueChangedListener { _, _, _ -> refresh() }
        confirm.setOnClickListener {
            val ms = totalMs()
            if (ms > 0) { removePopup(); hideFor("timer", now + ms) }
        }
        card.addView(confirm, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })
    }

    private fun hideFor(mode: String, untilMs: Long) {
        isOpen = false
        expandedRoot?.let { runCatching { windowManager.removeView(it) } }
        expandedRoot = null
        collapsedView?.let { runCatching { windowManager.removeView(it) } }
        collapsedView = null
        cancelIdle()
        hidden = true
        Prefs.setHide(this, mode, if (mode == "timer") untilMs else 0L)
        if (mode == "timer") scheduleUnhideTimers(untilMs)
        updateNotification(true)
        toast(when (mode) {
            "restart" -> "Halo hidden until you restart your phone"
            "app" -> "Halo hidden until you reopen Halo"
            else -> "Halo hidden for ${fmtDuration(untilMs - now)}"
        })
    }

    private fun unhide() {
        cancelUnhideAlarm()
        unhideRunnable?.let { handler.removeCallbacks(it) }
        unhideRunnable = null
        Prefs.clearHide(this)
        hidden = false
        updateNotification(false)
        showCollapsed()
    }

    /** Two timers for reliability: an in-app Handler (exact while the phone is
     *  awake / the app is alive) plus an alarm-clock (exact even in Doze). */
    private fun scheduleUnhideTimers(untilMs: Long) {
        unhideRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { unhide() }
        unhideRunnable = r
        handler.postDelayed(r, (untilMs - now).coerceAtLeast(0L))
        scheduleUnhideAlarm(untilMs)
    }

    private fun fmtDuration(ms: Long): String {
        val mins = (ms / 60_000L).toInt().coerceAtLeast(1)
        return if (mins < 60) "$mins min"
        else {
            val h = mins / 60; val m = mins % 60
            if (m == 0) "${h}h" else "${h}h ${m}m"
        }
    }

    private fun unhidePendingIntent(): PendingIntent = PendingIntent.getService(
        this, 7,
        Intent(this, FloatingButtonService::class.java).setAction(ACTION_UNHIDE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun scheduleUnhideAlarm(until: Long) {
        val am = getSystemService(AlarmManager::class.java)
        // setAlarmClock fires at the exact time even in Doze, and needs no
        // special "exact alarm" permission (unlike setExactAndAllowWhileIdle).
        val show = PendingIntent.getActivity(
            this, 9,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(until, show), unhidePendingIntent())
        }
    }

    private fun cancelUnhideAlarm() {
        runCatching { getSystemService(AlarmManager::class.java).cancel(unhidePendingIntent()) }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelIdle()
        unhideRunnable?.let { handler.removeCallbacks(it) }
        Prefs.unregisterListener(this, prefsListener)
        collapsedView?.let { runCatching { windowManager.removeView(it) } }
        expandedRoot?.let { runCatching { windowManager.removeView(it) } }
        popupView?.let { runCatching { windowManager.removeView(it) } }
        collapsedView = null
        expandedRoot = null
        popupView = null
    }

    companion object {
        const val ACTION_UNHIDE = "com.halo.floatingbutton.UNHIDE"
    }
}
