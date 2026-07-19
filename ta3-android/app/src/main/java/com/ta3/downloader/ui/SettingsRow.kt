package com.ta3.downloader.ui

import com.ta3.downloader.*

sealed class SettingsRow(val rowKey: String) {
    data class Static(val id: String) : SettingsRow(id)
    data class SectionHeader(val sectionId: String, val title: String, val isExpanded: Boolean) : SettingsRow("header_$sectionId")
    data class Ta3ShowItem(val show: Show, val isExpanded: Boolean) : SettingsRow("ta3_${show.name}")
    data class StvrShowItem(val show: Show, val isExpanded: Boolean) : SettingsRow("stvr_${show.name}")
    data class YtChannelItem(val channel: YouTubeChannel, val isExpanded: Boolean) : SettingsRow("yt_${channel.name}")
    data class PrehrajSettingsItem(val isExpanded: Boolean) : SettingsRow("prehraj_content")
}
