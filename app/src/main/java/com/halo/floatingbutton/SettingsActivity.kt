package com.halo.floatingbutton

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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

class SettingsActivity : AppCompatActivity() {

    private val dpf get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * dpf).toInt()

    private val ink = Color.parseColor("#161A21")
    private val muted = Color.parseColor("#5F6B7A")
    private val faint = Color.parseColor("#8B96A5")
    private val cardBg = Color.parseColor("#F4F6F9")

    private var currentColor = Prefs.ORANGE
    private var currentAlpha = 85

    private lateinit var previewCircle: FrameLayout
    private val slotValueViews = arrayOfNulls<TextView>(4)
    private val swatchRings = HashMap<Int, FrameLayout>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Customise Halo"
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

        root.addView(sectionLabel("Transparency"))
        root.addView(buildTransparency())

        root.addView(sectionLabel("Button colour"))
        root.addView(buildColours())

        root.addView(sectionLabel("Button actions — tap a slot to change"))
        for (i in 0..3) root.addView(buildSlotRow(i))

        val note = TextView(this).apply {
            text = "Changes apply to the floating button straight away."
            textSize = 12f
            setTextColor(faint)
            setPadding(dp(2), dp(18), dp(2), 0)
        }
        root.addView(note)

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

    // ---------- preview ----------
    private fun updatePreview() {
        previewCircle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(displayColour(currentColor))
        }
        previewCircle.alpha = currentAlpha / 100f
    }
}
