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
    val time: String = "",   // HH:MM extracted from article_date span, empty if not available
    val url: String,       // episode page URL
    val showName: String,
    val durationSeconds: Int = 0
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

data class PendingDownload(
    val episodeUrl: String,
    val title: String,
    val date: String,
    val time: String = "",
    val showName: String,
    val directUrl: String? = null,
    val enqueuedAt: Long = System.currentTimeMillis(),
    val attemptCount: Int = 0
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

// ─── Prehraj.to Models ────────────────────────────────────────────────────────

data class PrehrajMovie(
    val title: String,
    val year: String = "",
    val pageUrl: String,        // https://prehraj.to/<slug>/<id>
    val thumbnailUrl: String = ""
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
    ),
    Show(
        name = "tema-dna",
        displayName = "Téma dňa",
        url = "https://www.ta3.com/tema-dna"
    ),
    Show(
        name = "kral-na-tahu",
        displayName = "Kráľ na ťahu",
        url = "https://www.ta3.com/kral-na-tahu"
    ),
    Show(
        name = "v-politike",
        displayName = "V politike",
        url = "https://www.ta3.com/v-politike"
    ),
    Show(
        name = "o-tom-potom",
        displayName = "O tom potom",
        url = "https://www.ta3.com/o-tom-potom"
    ),
    Show(
        name = "podcast-tema-z-europarlamentu",
        displayName = "Téma z europarlamentu",
        url = "https://www.ta3.com/podcast-tema-z-europarlamentu"
    ),
    Show(
        name = "udalosti-tyzdna",
        displayName = "Udalosti tyzdna",
        url = "https://www.ta3.com/udalosti-tyzdna"
    )
)

// ─── STVR Show Definitions ──────────────────────────────────────────────────

val STVR_SHOWS = listOf(
    Show(
        name = "odpovede-s-ankou-zitnou",
        displayName = "Odpovede s Ankou Žitnou",
        url = "https://www.stvr.sk/televizia/archiv/22386"
    ),
    Show(
        name = "komentare-dna",
        displayName = "Komentáre dňa",
        url = "https://www.stvr.sk/televizia/archiv/20116"
    ),
    Show(
        name = "o-5-minut-12",
        displayName = "O 5 minút 12",
        url = "https://www.stvr.sk/televizia/archiv/14036"
    ),
    Show(
        name = "sobotne-dialogy",
        displayName = "Sobotné dialógy",
        url = "https://www.stvr.sk/televizia/archiv/12354"
    )
)

// ─── YouTube Channel Definitions ─────────────────────────────────────────────

data class YouTubeChannel(
    val name: String,           // internal key, e.g. "brano-zavodsky"
    val displayName: String,    // shown in UI, e.g. "Braňo Závodský"
    val channelId: String,      // YouTube channel ID starting with UC...
    val channelUrl: String,     // full YouTube channel URL
    val tab: String = "streams" // which tab to scrape: "streams" or "videos"
)

val YOUTUBE_CHANNELS = listOf(
    YouTubeChannel(
        name = "brano-zavodsky",
        displayName = "Braňo Závodský",
        channelId = "UCW1pHS8ZHpI33xnOhBvSGyQ",
        channelUrl = "https://www.youtube.com/@Bra%C5%88oZ%C3%A1vodsk%C3%BDNa%C5%BEivo"
    ),
    YouTubeChannel(
        name = "bardy-a-kacer",
        displayName = "Bárdy & Káčer",
        channelId = "UC2kzV2zMZxLE1ywlO1e3GTg",
        channelUrl = "https://www.youtube.com/@BardyAndKacer",
        tab = "videos"
    ),
    YouTubeChannel(
        name = "portalmarker",
        displayName = "Marker.sk",
        channelId = "UC28hsAjds3mQzScBtYtkGsQ",
        channelUrl = "https://www.youtube.com/@PortalMarker",
        tab = "videos"
    )
)
