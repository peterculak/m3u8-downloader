package com.ta3.downloader

import android.content.Context
import androidx.core.content.edit

/**
 * Persistent settings stored in SharedPreferences.
 * Configures how often the background worker runs and which shows to auto-download.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("ta3_settings", Context.MODE_PRIVATE)

    // ─── Sync interval ─────────────────────────────────────────────────────────

    /** How often (in hours) the background worker checks for new episodes. */
    var syncIntervalHours: Int
        get() = prefs.getInt(KEY_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS)
        set(v) = prefs.edit { putInt(KEY_INTERVAL_HOURS, v.coerceIn(1, 24)) }

    // ─── Per-show enable/disable ────────────────────────────────────────────────

    /** Returns whether a given show is enabled for auto-download. Default: true. */
    fun isShowEnabled(showName: String): Boolean =
        prefs.getBoolean("show_enabled_$showName", true)

    fun setShowEnabled(showName: String, enabled: Boolean) =
        prefs.edit { putBoolean("show_enabled_$showName", enabled) }

    /** Returns a list of shows that are enabled for auto-download. */
    fun enabledShows(): List<Show> =
        TA3_SHOWS.filter { isShowEnabled(it.name) }

    fun enabledStvrShows(): List<Show> =
        STVR_SHOWS.filter { isShowEnabled(it.name) }

    // ─── First-run flag ─────────────────────────────────────────────────────────

    var autoDownloadEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD, true)
        set(v) = prefs.edit { putBoolean(KEY_AUTO_DOWNLOAD, v) }

    /** If true, background downloads only run on Wi-Fi (UNMETERED). Default: true. */
    var wifiOnlyDownload: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(v) = prefs.edit { putBoolean(KEY_WIFI_ONLY, v) }

    // ─── Prehraj.to credentials ────────────────────────────────────────────────

    var prehrajEmail: String
        get() = prefs.getString("prehraj_email", "fr0z3nk0@gmail.com") ?: "fr0z3nk0@gmail.com"
        set(value) = prefs.edit().putString("prehraj_email", value).apply()

    var prehrajPassword: String
        get() = prefs.getString("prehraj_password", "hawkon-fybcab-1konQy") ?: "hawkon-fybcab-1konQy"
        set(value) = prefs.edit().putString("prehraj_password", value).apply()

    companion object {
        private const val KEY_INTERVAL_HOURS = "sync_interval_hours"
        private const val KEY_AUTO_DOWNLOAD = "auto_download_enabled"
        private const val KEY_WIFI_ONLY = "wifi_only_download"
        private const val KEY_PREHRAJ_EMAIL = "prehraj_email"
        private const val KEY_PREHRAJ_PASSWORD = "prehraj_password"
        const val DEFAULT_INTERVAL_HOURS = 1
        val INTERVAL_OPTIONS = listOf(1, 2, 3, 6, 12, 24)
    }
}
