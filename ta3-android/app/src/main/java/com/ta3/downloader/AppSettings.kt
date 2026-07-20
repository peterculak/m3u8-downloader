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

    fun getMinDurationMinutes(showName: String): Int =
        prefs.getInt("min_duration_$showName", 0)

    fun setMinDurationMinutes(showName: String, minutes: Int) =
        prefs.edit { putInt("min_duration_$showName", minutes) }

    /** Returns a list of shows that are enabled for auto-download. */
    fun enabledShows(): List<Show> =
        TA3_SHOWS.filter { isShowEnabled(it.name) }

    fun enabledStvrShows(): List<Show> =
        STVR_SHOWS.filter { isShowEnabled(it.name) }

    fun enabledTyzdenShows(): List<Show> =
        TYZDEN_SHOWS.filter { isShowEnabled(it.name) }

    fun enabledYouTubeChannels(): List<YouTubeChannel> =
        CustomChannelManager.getAllYouTubeChannels().filter { isShowEnabled(it.name) }

    // ─── Reordering ──────────────────────────────────────────────────────────────

    var mainTabOrder: List<String>
        get() = prefs.getString("main_tab_order", null)?.split(",")?.filter { it.isNotBlank() } ?: listOf("EPISODES", "STVR", "YOUTUBE", "DOWNLOADS", "PREHRAJ", "SETTINGS")
        set(v) = prefs.edit { putString("main_tab_order", v.joinToString(",")) }

    var ta3ShowOrder: List<String>
        get() = prefs.getString("ta3_show_order", null)?.split(",")?.filter { it.isNotBlank() } ?: TA3_SHOWS.map { it.name }
        set(v) = prefs.edit { putString("ta3_show_order", v.joinToString(",")) }

    var stvrShowOrder: List<String>
        get() = prefs.getString("stvr_show_order", null)?.split(",")?.filter { it.isNotBlank() } ?: STVR_SHOWS.map { it.name }
        set(v) = prefs.edit { putString("stvr_show_order", v.joinToString(",")) }

    var ytChannelOrder: List<String>
        get() = prefs.getString("yt_channel_order", null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = prefs.edit { putString("yt_channel_order", v.joinToString(",")) }

    var sectionOrder: List<String>
        get() {
            val saved = prefs.getString("section_order", null)?.split(",")?.filter { it.isNotBlank() }
            if (saved != null) {
                val order = saved.filter { it != "tabs" }.toMutableList()
                if (!order.contains("tyzden")) {
                    val ta3Idx = order.indexOf("ta3")
                    if (ta3Idx >= 0) order.add(ta3Idx + 1, "tyzden")
                    else order.add("tyzden")
                }
                if (!order.contains("prehraj")) order.add("prehraj")
                return order
            }
            return listOf("ta3", "tyzden", "stvr", "yt", "prehraj")
        }
        set(v) = prefs.edit { putString("section_order", v.joinToString(",")) }

    // ─── First-run flag ─────────────────────────────────────────────────────────

    var autoDownloadEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD, true)
        set(v) = prefs.edit { putBoolean(KEY_AUTO_DOWNLOAD, v) }

    /** If true, background downloads only run on Wi-Fi (UNMETERED). Default: true. */
    var wifiOnlyDownload: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(v) = prefs.edit { putBoolean(KEY_WIFI_ONLY, v) }

    // ─── Auto-delete ────────────────────────────────────────────────────────────

    var autoDeleteEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DELETE, true)
        set(v) = prefs.edit { putBoolean(KEY_AUTO_DELETE, v) }

    var autoDeleteDays: Int
        get() = prefs.getInt(KEY_AUTO_DELETE_DAYS, 7)
        set(v) = prefs.edit { putInt(KEY_AUTO_DELETE_DAYS, v) }

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
        private const val KEY_AUTO_DELETE = "auto_delete_enabled"
        private const val KEY_AUTO_DELETE_DAYS = "auto_delete_days"
        private const val KEY_PREHRAJ_EMAIL = "prehraj_email"
        private const val KEY_PREHRAJ_PASSWORD = "prehraj_password"
        const val DEFAULT_INTERVAL_HOURS = 1
        val INTERVAL_OPTIONS = listOf(1, 2, 3, 6, 12, 24)
        val AUTO_DELETE_DAYS_OPTIONS = listOf(3, 7, 14, 30)
        val MIN_DURATION_OPTIONS = listOf(0, 5, 10, 15, 30)
    }
}
