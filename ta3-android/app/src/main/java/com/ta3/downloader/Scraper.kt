package com.ta3.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Scraper {

    private val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} for $url")
            resp.body?.string() ?: ""
        }
    }

    /**
     * Fetch episode list for a show — mirrors the CLI's articleRegex approach exactly.
     * Parses raw HTML looking for:  <article ... data-date="YYYY-MM-DD" ...>
     * Then extracts /relacia/... href and heading text.
     */
    suspend fun fetchEpisodes(show: Show): List<Episode> = withContext(Dispatchers.IO) {
        val html = get(show.url)

        val episodes = mutableListOf<Episode>()

        // Same regex the CLI uses: match article tags with a data-date attribute
        val articleRegex = Regex(
            """<article[^>]+data-date=["']([^"']+)["'][^>]*>([\s\S]*?)</article>""",
            RegexOption.IGNORE_CASE
        )

        for (match in articleRegex.findAll(html)) {
            val date = match.groupValues[1]
            val content = match.groupValues[2]

            // Extract /relacia/, /clanok/ or /podcast/ link
            val urlMatch = Regex("""href=["'](/(?:relacia|clanok|podcast)/[^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(content) ?: continue
            val url = "https://www.ta3.com${urlMatch.groupValues[1]}"

            // Extract title from heading
            var title = ""
            val titleMatch = Regex("""<h[2-4][^>]*>[\s\S]*?<a[^>]*>([\s\S]*?)</a>[\s\S]*?</h[2-4]>""", RegexOption.IGNORE_CASE)
                .find(content)
                ?: Regex("""<h[2-4][^>]*>([\s\S]*?)</h[2-4]>""", RegexOption.IGNORE_CASE).find(content)

            if (titleMatch != null) {
                title = titleMatch.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
            } else {
                val altMatch = Regex("""alt=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(content)
                title = altMatch?.groupValues?.get(1)?.trim() ?: "Episode"
            }

            if (title.isEmpty()) continue

            // Extract time from <span class="article_date">dnes 15:28</span>
            // Works for "dnes HH:MM", "včera HH:MM", or any text containing a time
            var time = ""
            val dateSpanMatch = Regex(
                """<span[^>]+class=["'][^"']*article_date[^"']*["'][^>]*>([\s\S]*?)</span>""",
                RegexOption.IGNORE_CASE
            ).find(content)
            if (dateSpanMatch != null) {
                val spanText = dateSpanMatch.groupValues[1]
                    .replace(Regex("<[^>]+>"), "").trim()
                val timeMatch = Regex("""(\d{1,2}:\d{2})""").find(spanText)
                if (timeMatch != null) {
                    // Normalise to HH:MM (zero-pad single-digit hours)
                    val parts = timeMatch.groupValues[1].split(":")
                    time = "${parts[0].padStart(2, '0')}:${parts[1]}"
                }
            }

            episodes.add(Episode(title = title, date = date, time = time, url = url, showName = show.name))
        }

        episodes
    }

    /**
     * Resolve the m3u8 or mp3 stream URL for an episode.
     * 1. Check for Transistor podcast embed (mp3).
     * 2. Fetch detail page, extract videoId
     * 3. Fetch vod-source.js, get src template
     * 4. Substitute videoId into template (m3u8)
     */
    suspend fun resolveM3u8(episodeUrl: String): String = withContext(Dispatchers.IO) {
        // 1. Fetch episode detail page
        val detailHtml = get(episodeUrl)

        // Check for Transistor podcast embed
        val transistorMatch = Regex("""src=["'](https://share\.transistor\.fm/e/[^"']+)["']""").find(detailHtml)
        if (transistorMatch != null) {
            val embedUrl = transistorMatch.groupValues[1]
            val embedHtml = get(embedUrl)
            val mp3Match = Regex("""(https?://[a-zA-Z0-9./\\_-]+\.mp3)""").find(embedHtml)
            if (mp3Match != null) {
                return@withContext mp3Match.groupValues[1].replace("\\/", "/")
            }
        }

        // 2. Extract videoId
        val videoIdMatch = Regex(""""videoId"\s*:\s*"([^"]+)"""").find(detailHtml)
            ?: Regex("""videoId\s*:\s*'([^']+)'""").find(detailHtml)
            ?: throw Exception("No videoId or podcast found on page: $episodeUrl")
        val videoId = videoIdMatch.groupValues[1]

        // 3. Fetch the livebox vod-source.js to get fresh auth token
        val jsSource = get("https://embed.livebox.cz/ta3_v2/vod-source.js")

        val srcMatch = Regex(""""src"\s*:\s*"([^"]+)"""").find(jsSource)
            ?: throw Exception("No src template found in vod-source.js")

        // 4. Build final m3u8 URL
        "https:" + srcMatch.groupValues[1].replace("{0}", videoId)
    }

    private fun getWithUrl(url: String): Pair<String, String> {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} for $url")
            val finalUrl = resp.request.url.toString()
            finalUrl to (resp.body?.string() ?: "")
        }
    }

    /**
     * Given an m3u8 URL, resolve to a flat list of .ts segment URLs.
     * Handles master playlist -> variant -> segments and handles HTTP redirects 
     * which change the base URL for relative paths.
     */
    suspend fun resolveSegments(m3u8Url: String): List<String> = withContext(Dispatchers.IO) {
        val (finalMasterUrl, text) = getWithUrl(m3u8Url)
        var baseUrl = finalMasterUrl.substringBeforeLast("/") + "/"

        var playlistText = text
        var currentBase = baseUrl

        if (text.contains("#EXT-X-STREAM-INF")) {
            val variantLine = text.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: throw Exception("No variant stream found")
            val variantUrl = if (variantLine.trim().startsWith("http")) variantLine.trim()
            else baseUrl + variantLine.trim()

            val (finalVariantUrl, variantBody) = getWithUrl(variantUrl)
            playlistText = variantBody
            currentBase = finalVariantUrl.substringBeforeLast("/") + "/"
        }

        playlistText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { if (it.startsWith("http")) it else currentBase + it }
    }
}
