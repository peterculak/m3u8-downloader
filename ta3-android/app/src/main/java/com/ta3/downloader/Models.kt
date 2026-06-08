package com.ta3.downloader

// ─── Data Models ──────────────────────────────────────────────────────────────

data class Show(
    val name: String,
    val displayName: String,
    val url: String
)

data class Episode(
    val title: String,
    val date: String,
    val url: String,       // episode page URL
    val showName: String
)

data class DownloadedFile(
    val episodeUrl: String,
    val title: String,
    val date: String,
    val showName: String,
    val localPath: String,
    val fileSizeBytes: Long,
    val downloadedAt: Long = System.currentTimeMillis()
)

enum class DownloadStatus {
    IDLE,
    RESOLVING,
    DOWNLOADING,
    DONE,
    FAILED
}

data class ActiveDownload(
    val episodeUrl: String,
    val title: String,
    val showName: String,
    val progress: Float,        // 0f..1f
    val status: DownloadStatus,
    val errorMessage: String? = null
)

// ─── TA3 Show Definitions (same URLs as ta3-cli/config.json) ─────────────────

val TA3_SHOWS = listOf(
    Show(
        name = "hlavne-spravy",
        displayName = "Hlavné správy",
        url = "https://www.ta3.com/hlavne-spravy"
    ),
    Show(
        name = "tlacove-besedy",
        displayName = "Tlačové besedy",
        url = "https://www.ta3.com/tlacove-besedy"
    )
)
