package com.ta3.downloader

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Calendar
import java.util.concurrent.TimeUnit

object StvScraper {

    private val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val gson = Gson()

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
     * Fetch episodes for an STVR archive show using the calendar API.
     *
     * The STVR archive shows one episode per page and uses a calendar widget
     * to navigate between episodes. The calendar is loaded via:
     *   GET /json/snippet_archive_series_calendar.json?id=<showId>&m=<YYYY-M>
     *
     * Response: {"snippets": {"snippet-calendar-calendar": "<html calendar>"}}
     *
     * Episode links in the calendar HTML:
     *   <a href='/televizia/archiv/<showId>/<episodeId>'>DAY</a>
     * where DAY is the day-of-month number as text.
     *
     * We walk back [maxPages] months from the current month to build the list.
     */
    suspend fun fetchEpisodes(show: Show, maxPages: Int = 5): List<Episode> = withContext(Dispatchers.IO) {
        val showId = show.url.substringAfterLast("/")
        val episodes = mutableListOf<Episode>()

        val cal = Calendar.getInstance()

        for (monthOffset in 0 until maxPages) {
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) + 1  // 1-indexed

            val apiUrl = "https://www.stvr.sk/json/snippet_archive_series_calendar.json?id=$showId&m=$year-$month"
            val rawJson = try {
                get(apiUrl)
            } catch (e: Exception) {
                cal.add(Calendar.MONTH, -1)
                continue
            }

            // Parse the JSON wrapper to get the calendar HTML snippet
            val calHtml: String = try {
                val json = gson.fromJson(rawJson, JsonObject::class.java)
                json.getAsJsonObject("snippets")
                    ?.get("snippet-calendar-calendar")?.asString ?: ""
            } catch (e: Exception) {
                // Fallback: treat raw response as HTML directly
                rawJson
            }

            if (calHtml.isEmpty()) {
                cal.add(Calendar.MONTH, -1)
                continue
            }

            // Extract episode links and their day numbers
            // Pattern: <a href='/televizia/archiv/<showId>/<episodeId>' ...>DAY</a>
            val linkRegex = Regex(
                """href=['"/]+televizia/archiv/$showId/(\d+)['">\s][^>]*>(\d+)<""",
                RegexOption.IGNORE_CASE
            )
            val monthStr = month.toString().padStart(2, '0')

            for (match in linkRegex.findAll(calHtml)) {
                val episodeId = match.groupValues[1]
                val day = match.groupValues[2].padStart(2, '0')
                val dateStr = "$year-$monthStr-$day"
                val episodeUrl = "https://www.stvr.sk/televizia/archiv/$showId/$episodeId"

                episodes.add(
                    Episode(
                        title = "${show.displayName} – $dateStr",
                        date = dateStr,
                        time = "",
                        url = episodeUrl,
                        showName = show.name
                    )
                )
            }

            cal.add(Calendar.MONTH, -1)
        }

        episodes.sortedByDescending { it.date }
    }

    /**
     * Resolve the m3u8 streaming URL for a given STVR episode URL.
     * URL format: https://www.stvr.sk/televizia/archiv/<showId>/<episodeId>
     *
     * API: GET https://www.stvr.sk/json/archive5f.json?id=<episodeId>
     * Response: { "clip": { "sources": [{ "src": "https://...m3u8" }] } }
     */
    suspend fun resolveM3u8(episodeUrl: String): String = withContext(Dispatchers.IO) {
        val episodeId = episodeUrl.substringAfterLast("/").substringBefore("?")
        if (episodeId.isEmpty() || !episodeId.all { it.isDigit() }) {
            throw Exception("Invalid STVR episode URL, cannot extract episodeId: $episodeUrl")
        }

        val apiUrl = "https://www.stvr.sk/json/archive5f.json?id=$episodeId"
        val jsonStr = get(apiUrl)

        val json = gson.fromJson(jsonStr, JsonObject::class.java)
        val clip = json.getAsJsonObject("clip")
            ?: throw Exception("No 'clip' field in JSON response for episode $episodeId")
        val sources = clip.getAsJsonArray("sources")
            ?: throw Exception("No 'sources' array in JSON response for episode $episodeId")
        if (sources.size() == 0) {
            throw Exception("Empty 'sources' array in JSON response for episode $episodeId")
        }
        sources.get(0).asJsonObject.get("src")?.asString
            ?: throw Exception("No 'src' field in stream source for episode $episodeId")
    }
}
