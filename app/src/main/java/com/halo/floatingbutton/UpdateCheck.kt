package com.halo.floatingbutton

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Shared helpers for the GitHub release update check. */
object UpdateCheck {
    const val API = "https://api.github.com/repos/Jagjipru/halo-floating-button/releases/latest"
    const val APK = "https://github.com/Jagjipru/halo-floating-button/releases/latest/download/Halo.apk"
    const val PAGE = "https://github.com/Jagjipru/halo-floating-button/releases/latest"

    /** Latest version string from the public release, or null if it couldn't be read. */
    fun fetchLatestVersion(): String? = try {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
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

    fun isNewer(latest: String, current: String): Boolean {
        fun parts(s: String) = s.split(".").map { it.toIntOrNull() ?: 0 }
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
