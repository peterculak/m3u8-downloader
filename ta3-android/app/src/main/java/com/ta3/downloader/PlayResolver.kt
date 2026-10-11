package com.ta3.downloader

/** Finds a URL that an external video/audio player can stream directly for an episode of any tab. */
object PlayResolver {
    suspend fun resolve(episode: Episode): String {
        val isYoutube = episode.url.contains("youtube.com") || episode.url.contains("youtu.be") ||
            CustomChannelManager.getAllYouTubeChannels().any { it.name == episode.showName }
        return when {
            isYoutube -> YouTubeScraper.resolvePlayableUrl(episode.url)
            STVR_SHOWS.any { it.name == episode.showName } -> StvScraper.resolveM3u8(episode.url)
            TYZDEN_SHOWS.any { it.name == episode.showName } -> TyzdenScraper.resolveMp3Url(episode.url)
            else -> Scraper.resolveM3u8(episode.url)
        }
    }
}
