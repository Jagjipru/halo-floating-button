package com.halo.floatingbutton

import android.content.Context
import android.os.Build

/** Stored settings for the floating button. */
object Prefs {
    private const val FILE = "halo_prefs"
    const val KEY_ALPHA = "alpha"       // Int 20..100
    const val KEY_COLOR = "color"       // ARGB int, or SYSTEM_COLOR sentinel
    const val KEY_SLOT = "slot_"        // slot_0 .. slot_3 -> String
    const val KEY_LABELS = "labels"
    const val KEY_POSX = "posx"
    const val KEY_POSY = "posy"
    const val KEY_SIZE = "size"         // button diameter in dp
    const val KEY_BOOT = "boot"
    const val KEY_LONGPRESS = "longpress"
    const val KEY_SNAP = "snap"
    const val KEY_ANIM = "anim"
    const val KEY_HAPTICS = "haptics"
    const val KEY_HIDE_MODE = "hide_mode"   // none | timer | restart | app
    const val KEY_HIDE_UNTIL = "hide_until" // epoch ms (timer mode)
    const val KEY_LASTCHECK = "lastcheck"   // epoch ms of last update check
    const val KEY_LATEST = "latest_seen"    // last version string seen online
    private const val KEY_SEEDED = "seeded_v1"

    const val SYSTEM_COLOR = 0           // sentinel: follow the device accent
    val GREY = 0xFF6B7280.toInt()
    val ORANGE = 0xFFF2552C.toInt()

    // "torch", "shot", "lock", "settings", or "app:<packageName>"
    val DEFAULT_SLOTS = arrayOf("torch", "shot", "lock", "settings")

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun alpha(c: Context) = sp(c).getInt(KEY_ALPHA, 40)
    fun setAlpha(c: Context, v: Int) = sp(c).edit().putInt(KEY_ALPHA, v).apply()

    fun labels(c: Context) = sp(c).getBoolean(KEY_LABELS, false)
    fun setLabels(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_LABELS, v).apply()

    fun rawColor(c: Context) = sp(c).getInt(KEY_COLOR, GREY)
    fun setColor(c: Context, v: Int) = sp(c).edit().putInt(KEY_COLOR, v).apply()

    fun resolvedColor(c: Context): Int {
        val raw = rawColor(c)
        return if (raw == SYSTEM_COLOR) systemAccent(c) else raw
    }

    fun systemAccent(c: Context): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            c.resources.getColor(android.R.color.system_accent1_500, c.theme)
        } else {
            GREY
        }

    fun slot(c: Context, i: Int): String =
        sp(c).getString(KEY_SLOT + i, DEFAULT_SLOTS[i]) ?: DEFAULT_SLOTS[i]

    fun setSlot(c: Context, i: Int, v: String) =
        sp(c).edit().putString(KEY_SLOT + i, v).apply()

    fun posX(c: Context) = sp(c).getInt(KEY_POSX, -1)
    fun posY(c: Context) = sp(c).getInt(KEY_POSY, -1)
    fun setPos(c: Context, x: Int, y: Int) =
        sp(c).edit().putInt(KEY_POSX, x).putInt(KEY_POSY, y).apply()

    fun size(c: Context) = sp(c).getInt(KEY_SIZE, 56).coerceIn(44, 76)
    fun setSize(c: Context, v: Int) = sp(c).edit().putInt(KEY_SIZE, v).apply()

    fun boot(c: Context) = sp(c).getBoolean(KEY_BOOT, true)
    fun setBoot(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_BOOT, v).apply()

    fun longPress(c: Context): String = sp(c).getString(KEY_LONGPRESS, "lock") ?: "lock"
    fun setLongPress(c: Context, v: String) = sp(c).edit().putString(KEY_LONGPRESS, v).apply()

    fun snap(c: Context) = sp(c).getBoolean(KEY_SNAP, true)
    fun setSnap(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_SNAP, v).apply()

    fun haptics(c: Context) = sp(c).getBoolean(KEY_HAPTICS, true)
    fun setHaptics(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_HAPTICS, v).apply()

    // "pop", "spring", "scale", "none"
    fun anim(c: Context): String = sp(c).getString(KEY_ANIM, "pop") ?: "pop"
    fun setAnim(c: Context, v: String) = sp(c).edit().putString(KEY_ANIM, v).apply()

    // ----- temporary hide -----
    fun hideMode(c: Context): String = sp(c).getString(KEY_HIDE_MODE, "none") ?: "none"
    fun setHideMode(c: Context, v: String) = sp(c).edit().putString(KEY_HIDE_MODE, v).apply()
    fun hideUntil(c: Context) = sp(c).getLong(KEY_HIDE_UNTIL, 0L)
    fun clearHide(c: Context) = sp(c).edit().putString(KEY_HIDE_MODE, "none").putLong(KEY_HIDE_UNTIL, 0L).apply()
    fun setHide(c: Context, mode: String, until: Long) =
        sp(c).edit().putString(KEY_HIDE_MODE, mode).putLong(KEY_HIDE_UNTIL, until).apply()

    // ----- update check cache -----
    fun lastCheck(c: Context) = sp(c).getLong(KEY_LASTCHECK, 0L)
    fun setLastCheck(c: Context, v: Long) = sp(c).edit().putLong(KEY_LASTCHECK, v).apply()
    fun latestSeen(c: Context): String = sp(c).getString(KEY_LATEST, "") ?: ""
    fun setLatestSeen(c: Context, v: String) = sp(c).edit().putString(KEY_LATEST, v).apply()

    /** One-time: apply the grey / 40% default even to existing installs. */
    fun seedDefaults(c: Context) {
        if (!sp(c).getBoolean(KEY_SEEDED, false)) {
            sp(c).edit()
                .putInt(KEY_COLOR, GREY)
                .putInt(KEY_ALPHA, 40)
                .putBoolean(KEY_SEEDED, true)
                .apply()
        }
    }

    fun registerListener(c: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp(c).registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(c: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp(c).unregisterOnSharedPreferenceChangeListener(l)
}
