package com.halo.floatingbutton

import android.content.Context
import android.os.Build

/** Stored settings for the floating button: transparency, colour, and the four slots. */
object Prefs {
    private const val FILE = "halo_prefs"
    const val KEY_ALPHA = "alpha"      // Int 20..100
    const val KEY_COLOR = "color"      // ARGB int, or SYSTEM_COLOR sentinel
    const val KEY_SLOT = "slot_"       // slot_0 .. slot_3 -> String

    const val SYSTEM_COLOR = 0          // sentinel: follow the device accent
    val ORANGE = 0xFFF2552C.toInt()

    // "torch", "shot", "lock", "settings", or "app:<packageName>"
    val DEFAULT_SLOTS = arrayOf("torch", "shot", "lock", "settings")

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun alpha(c: Context) = sp(c).getInt(KEY_ALPHA, 85)
    fun setAlpha(c: Context, v: Int) = sp(c).edit().putInt(KEY_ALPHA, v).apply()

    fun rawColor(c: Context) = sp(c).getInt(KEY_COLOR, ORANGE)
    fun setColor(c: Context, v: Int) = sp(c).edit().putInt(KEY_COLOR, v).apply()

    fun resolvedColor(c: Context): Int {
        val raw = rawColor(c)
        return if (raw == SYSTEM_COLOR) systemAccent(c) else raw
    }

    fun systemAccent(c: Context): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            c.resources.getColor(android.R.color.system_accent1_500, c.theme)
        } else {
            ORANGE
        }

    fun slot(c: Context, i: Int): String =
        sp(c).getString(KEY_SLOT + i, DEFAULT_SLOTS[i]) ?: DEFAULT_SLOTS[i]

    fun setSlot(c: Context, i: Int, v: String) =
        sp(c).edit().putString(KEY_SLOT + i, v).apply()

    fun registerListener(c: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp(c).registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(c: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp(c).unregisterOnSharedPreferenceChangeListener(l)
}
