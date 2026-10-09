package com.halo.floatingbutton

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    private val dpf get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * dpf).toInt()

    private val ink = Color.parseColor("#161A21")
    private val muted = Color.parseColor("#5F6B7A")
    private val faint = Color.parseColor("#8B96A5")
    private val cardBg = Color.parseColor("#F4F6F9")

    private var currentColor = Prefs.GREY
    private var currentAlpha = 40

    private lateinit var previewCircle: FrameLayout
    private val slotValueViews = arrayOfNulls<TextView>(4)
    private val swatchRings = HashMap<Int, FrameLayout>()
    private var lpValueView: TextView? = null
    private var animValueView: TextView? = null
    private val animOptions = listOf("pop" to "Pop", "spring" to "Spring", "scale" to "Scale", "none" to "None")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Customise Halo"
        Prefs.seedDefaults(this)
        currentColor = Prefs.rawColor(this)
        currentAlpha = Prefs.alpha(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(32))
        }

        // live preview
        previewCircle = FrameLayout(this)
        val rings = ImageView(this).apply { setImageResource(R.drawable.halo_rings) }
        previewCircle.addView(rings, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
        val previewWrap = FrameLayout(this).apply {
            addView(previewCircle, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
        }
        root.addView(previewWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))

        root.addView(sectionLabel("Button size"))
        root.addView(buildSizeSlider())

        root.addView(sectionLabel("Transparency"))
        root.addView(buildTransparency())

        root.addView(sectionLabel("Button colour"))
        root.addView(buildColours())

        root.addView(sectionLabel("Labels"))
        root.addView(buildLabelsToggle())

        root.addView(sectionLabel("Button actions — tap a slot to change"))
        for (i in 0..3) root.addView(buildSlotRow(i))

        root.addView(sectionLabel("Long-press the button"))
        root.addView(buildLongPressRow())

        root.addView(sectionLabel("Behaviour"))
        root.addView(buildBootToggle())
        root.addView(buildSnapToggle())
        root.addView(buildHapticsToggle())

        root.addView(sectionLabel("Open animation"))
        root.addView(buildAnimRow())

        val note = TextView(this).apply {
            text = "Changes apply to the floating button straight away."
            textSize = 12f
            setTextColor(faint)
            setPadding(dp(2), dp(18), dp(2), 0)
        }
        root.addView(note)

        root.addView(sectionLabel("Updates"))
        root.addView(buildUpdateRow())

        scroll.addView(root)
        setContentView(scroll)
        updatePreview()
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(faint)
        setPadding(dp(2), dp(26), dp(2), dp(10))
    }

    // ---------- transparency ----------
    private fun buildTransparency(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val value = TextView(this).apply {
            text = "$currentAlpha%"
            textSize = 14f
            setTextColor(ink)
            setPadding(0, 0, 0, dp(6))
        }
        val seek = SeekBar(this).apply {
            max = 80
            progress = (currentAlpha - 20).coerceIn(0, 80)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    currentAlpha = p + 20
                    value.text = "$currentAlpha%"
                    Prefs.setAlpha(this@SettingsActivity, currentAlpha)
                    updatePreview()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        row.addView(value)
        row.addView(seek)
        return row
    }

    // ---------- colour ----------
    private val colours: List<Pair<String, Int>> = listOf(
        "System" to Prefs.SYSTEM_COLOR,
        "Grey" to Prefs.GREY,
        "Orange" to Prefs.ORANGE,
        "Blue" to 0xFF2D6CDF.toInt(),
        "Teal" to 0xFF0E9AA7.toInt(),
        "Green" to 0xFF1F9D6B.toInt(),
        "Purple" to 0xFF7A3FF2.toInt(),
        "Pink" to 0xFFE23D8B.toInt(),
        "Red" to 0xFFE23D3D.toInt(),
        "Graphite" to 0xFF374151.toInt()
    )

    private fun displayColour(value: Int) =
        if (value == Prefs.SYSTEM_COLOR) Prefs.systemAccent(this) else value

    private fun buildColours(): View {
        val hsv = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        colours.forEach { (name, value) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), 0, dp(4), 0)
            }
            val ringWrap = FrameLayout(this)
            val circle = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(displayColour(value))
                }
            }
            ringWrap.addView(circle, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            ringWrap.setOnClickListener { selectColour(value) }
            swatchRings[value] = ringWrap
            val label = TextView(this).apply {
                text = name
                textSize = 10f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            }
            cell.addView(ringWrap, LinearLayout.LayoutParams(dp(52), dp(52)))
            cell.addView(label)
            row.addView(cell)
        }
        hsv.addView(row)
        applyRingSelection()
        return hsv
    }

    private fun selectColour(value: Int) {
        currentColor = value
        Prefs.setColor(this, value)
        applyRingSelection()
        updatePreview()
    }

    private fun applyRingSelection() {
        swatchRings.forEach { (value, ring) ->
            ring.background = if (value == currentColor) GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), ink)
            } else null
        }
    }

    // ---------- slots ----------
    private fun buildSlotRow(i: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(cardBg)
                cornerRadius = dp(13).toFloat()
            }
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setOnClickListener { chooseSlot(i) }
        }
        val slotName = TextView(this).apply {
            text = "Slot ${i + 1}"
            textSize = 12f
            setTextColor(faint)
        }
        val value = TextView(this).apply {
            text = slotLabel(Prefs.slot(this@SettingsActivity, i))
            textSize = 15f
            setTextColor(ink)
            setPadding(dp(12), 0, dp(12), 0)
        }
        slotValueViews[i] = value
        val change = TextView(this).apply {
            text = "Change ›"
            textSize = 13f
            setTextColor(Prefs.ORANGE)
        }
        row.addView(slotName)
        row.addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(change)
        return LinearLayout(this).apply {
            addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) })
        }
    }

    private fun chooseSlot(i: Int) {
        val options = arrayOf("Torch", "Screenshot", "Lock", "Settings", "Choose an app…")
        AlertDialog.Builder(this)
            .setTitle("Slot ${i + 1}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> setSlot(i, "torch")
                    1 -> setSlot(i, "shot")
                    2 -> setSlot(i, "lock")
                    3 -> setSlot(i, "settings")
                    4 -> chooseApp(i)
                }
            }
            .show()
    }

    private fun chooseApp(i: Int) {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { (it.loadLabel(pm)?.toString() ?: it.activityInfo.packageName) to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) return
        val labels = apps.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose an app")
            .setItems(labels) { _, which ->
                setSlot(i, "app:" + apps[which].second)
            }
            .show()
    }

    private fun setSlot(i: Int, value: String) {
        Prefs.setSlot(this, i, value)
        slotValueViews[i]?.text = slotLabel(value)
    }

    private fun slotLabel(def: String): String = when {
        def == "torch" -> "Torch"
        def == "shot" -> "Screenshot"
        def == "lock" -> "Lock"
        def == "settings" -> "Settings"
        def.startsWith("app:") -> appLabel(def.substring(4))
        else -> def
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    // ---------- labels toggle ----------
    private fun buildLabelsToggle(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(cardBg)
                cornerRadius = dp(13).toFloat()
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val label = TextView(this).apply {
            text = "Show labels under icons"
            textSize = 15f
            setTextColor(ink)
        }
        val sw = android.widget.Switch(this).apply {
            isChecked = Prefs.labels(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setLabels(this@SettingsActivity, checked)
            }
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        return row
    }

    // ---------- haptics ----------
    private fun buildHapticsToggle(): View {
        val row = cardRow()
        val label = TextView(this).apply {
            text = "Haptic feedback (vibration)"
            textSize = 15f; setTextColor(ink)
        }
        val sw = android.widget.Switch(this).apply {
            isChecked = Prefs.haptics(this@SettingsActivity)
            setOnCheckedChangeListener { _, c -> Prefs.setHaptics(this@SettingsActivity, c) }
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        return wrap(row)
    }

    // ---------- edge-snap ----------
    private fun buildSnapToggle(): View {
        val row = cardRow()
        val label = TextView(this).apply {
            text = "Edge-snap (stick to nearest edge)"
            textSize = 15f; setTextColor(ink)
        }
        val sw = android.widget.Switch(this).apply {
            isChecked = Prefs.snap(this@SettingsActivity)
            setOnCheckedChangeListener { _, c -> Prefs.setSnap(this@SettingsActivity, c) }
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        return wrap(row)
    }

    // ---------- open animation ----------
    private fun animLabel(id: String) = animOptions.firstOrNull { it.first == id }?.second ?: "Pop"

    private fun buildAnimRow(): View {
        val row = cardRow()
        val name = TextView(this).apply { text = "Style"; textSize = 12f; setTextColor(faint) }
        val value = TextView(this).apply {
            text = animLabel(Prefs.anim(this@SettingsActivity))
            textSize = 15f; setTextColor(ink); setPadding(dp(12), 0, dp(12), 0)
        }
        animValueView = value
        val change = TextView(this).apply { text = "Change ›"; textSize = 13f; setTextColor(Prefs.ORANGE) }
        row.addView(name)
        row.addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(change)
        row.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Open animation")
                .setItems(animOptions.map { it.second }.toTypedArray()) { _, w ->
                    Prefs.setAnim(this, animOptions[w].first)
                    animValueView?.text = animOptions[w].second
                }.show()
        }
        return wrap(row)
    }

    // ---------- button size ----------
    private fun buildSizeSlider(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val size0 = Prefs.size(this)
        val value = TextView(this).apply {
            text = "${size0}dp"
            textSize = 14f
            setTextColor(ink)
            setPadding(0, 0, 0, dp(6))
        }
        val seek = SeekBar(this).apply {
            max = 32  // 44..76
            progress = (size0 - 44).coerceIn(0, 32)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + 44
                    value.text = "${v}dp"
                    Prefs.setSize(this@SettingsActivity, v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        row.addView(value)
        row.addView(seek)
        return row
    }

    // ---------- long-press ----------
    private fun lpLabel(def: String) = if (def == "nothing") "Nothing" else slotLabel(def)

    private fun buildLongPressRow(): View {
        val row = cardRow()
        val name = TextView(this).apply {
            text = "Action"; textSize = 12f; setTextColor(faint)
        }
        val value = TextView(this).apply {
            text = lpLabel(Prefs.longPress(this@SettingsActivity))
            textSize = 15f; setTextColor(ink); setPadding(dp(12), 0, dp(12), 0)
        }
        lpValueView = value
        val change = TextView(this).apply {
            text = "Change ›"; textSize = 13f; setTextColor(Prefs.ORANGE)
        }
        row.addView(name)
        row.addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(change)
        row.setOnClickListener { chooseLongPress() }
        return wrap(row)
    }

    private fun chooseLongPress() {
        val options = arrayOf("Torch", "Screenshot", "Lock", "Settings", "Choose an app…", "Nothing")
        AlertDialog.Builder(this)
            .setTitle("Long-press action")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> setLp("torch"); 1 -> setLp("shot"); 2 -> setLp("lock")
                    3 -> setLp("settings"); 4 -> chooseLpApp(); 5 -> setLp("nothing")
                }
            }.show()
    }

    private fun chooseLpApp() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { (it.loadLabel(pm)?.toString() ?: it.activityInfo.packageName) to it.activityInfo.packageName }
            .distinctBy { it.second }.sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Choose an app")
            .setItems(apps.map { it.first }.toTypedArray()) { _, w -> setLp("app:" + apps[w].second) }
            .show()
    }

    private fun setLp(v: String) {
        Prefs.setLongPress(this, v)
        lpValueView?.text = lpLabel(v)
    }

    // ---------- start on boot ----------
    private fun buildBootToggle(): View {
        val row = cardRow()
        val label = TextView(this).apply {
            text = "Start on boot"; textSize = 15f; setTextColor(ink)
        }
        val sw = android.widget.Switch(this).apply {
            isChecked = Prefs.boot(this@SettingsActivity)
            setOnCheckedChangeListener { _, c -> Prefs.setBoot(this@SettingsActivity, c) }
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        return wrap(row)
    }

    // ---------- updates ----------
    private fun buildUpdateRow(): View {
        val current = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
        val row = cardRow()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = "Check for updates"; textSize = 15f; setTextColor(ink) })
        col.addView(TextView(this).apply {
            text = "You're on v$current"; textSize = 11f; setTextColor(faint)
        })
        val go = TextView(this).apply { text = "Check ›"; textSize = 13f; setTextColor(Prefs.ORANGE) }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(go)
        row.setOnClickListener { checkForUpdates(current) }
        return wrap(row)
    }

    private fun checkForUpdates(current: String) {
        android.widget.Toast.makeText(this, "Checking…", android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            val latest = fetchLatestVersion()
            runOnUiThread {
                when {
                    latest == null -> {
                        android.widget.Toast.makeText(this, "Couldn't check — opening download page", android.widget.Toast.LENGTH_SHORT).show()
                        openUrl("https://github.com/Jagjipru/halo-floating-button/releases/latest")
                    }
                    isNewer(latest, current) -> {
                        android.widget.Toast.makeText(this, "Update available: v$latest", android.widget.Toast.LENGTH_LONG).show()
                        openUrl("https://github.com/Jagjipru/halo-floating-button/releases/latest/download/Halo.apk")
                    }
                    else -> android.widget.Toast.makeText(this, "You're on the latest (v$current)", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun fetchLatestVersion(): String? = try {
        val url = URL("https://api.github.com/repos/Jagjipru/halo-floating-button/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000; readTimeout = 7000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        if (conn.responseCode in 200..299) {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)
            val name = obj.optString("name") + " " + obj.optString("tag_name")
            Regex("(\\d+\\.\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.get(1)
        } else null
    } catch (e: Exception) { null }

    private fun isNewer(latest: String, current: String): Boolean {
        fun parts(s: String) = s.split(".").map { it.toIntOrNull() ?: 0 }
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun openUrl(u: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) {}
    }

    // shared card row helpers
    private fun cardRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; setColor(cardBg); cornerRadius = dp(13).toFloat()
        }
        setPadding(dp(14), dp(11), dp(14), dp(11))
    }

    private fun wrap(inner: View) = LinearLayout(this).apply {
        addView(inner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ---------- preview ----------
    private fun updatePreview() {
        previewCircle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(displayColour(currentColor))
        }
        previewCircle.alpha = currentAlpha / 100f
    }
}
