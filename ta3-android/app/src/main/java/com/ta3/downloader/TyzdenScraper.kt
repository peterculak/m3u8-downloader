package com.ta3.downloader

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Locale

object TyzdenScraper {
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

    suspend fun fetchEpisodes(show: Show, maxPages: Int = 3): List<Episode> = withContext(Dispatchers.IO) {
        val episodes = mutableListOf<Episode>()
        for (page in 1..maxPages) {
            val url = if (page == 1) show.url else "${show.url}$page/"
            val html = try { get(url) } catch (e: Exception) { continue }
            
            val doc = Jsoup.parse(html)
            val articleElements = doc.select("div.teaser--list")
            
            for (element in articleElements) {
                val linkEl = element.selectFirst("a.teaser__link--main")
                val episodeUrl = linkEl?.attr("href") ?: continue
                if (episodeUrl.isEmpty()) continue
                
                val titleEl = element.selectFirst("h1.teaser__title")
                val title = titleEl?.text()?.trim() ?: continue
                
                val dateEl = element.select("span.theme-highlight").lastOrNull()
                var dateStr = dateEl?.text()?.trim() ?: ""
                
                // Format date from dd.MM.yyyy to yyyy-MM-dd
                if (dateStr.isNotEmpty()) {
                    try {
                        val parsed = SimpleDateFormat("dd.MM.yyyy", Locale.US).parse(dateStr)
                        if (parsed != null) {
                            dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(parsed)
                        }
                    } catch (e: Exception) {}
                }
                
                episodes.add(Episode(
                    title = title,
                    date = dateStr,
                    url = if (episodeUrl.startsWith("/")) "https://www.tyzden.sk$episodeUrl" else episodeUrl,
                    showName = show.name
                ))
            }
        }
        episodes
    }

    suspend fun resolveMp3Url(episodeUrl: String): String = withContext(Dispatchers.IO) {
        val html = get(episodeUrl)
        
        // Look for an iframe whose src points to podbean.com player-v2
        val iframeRegex = Regex("""src=["'](https://www\.podbean\.com/player-v2/[^"']+)["']""", RegexOption.IGNORE_CASE)
        val match = iframeRegex.find(html) ?: throw Exception("Could not find Podbean player-v2 iframe in the article page.")
        
        val playerV2Url = match.groupValues[1]
        
        // Extract episode ID from URL params ?i=...
        val iParamMatch = Regex("""[?&]i=([^&"']+)""").find(playerV2Url) ?: throw Exception("Could not extract episode ID from player URL: $playerV2Url")
        val iParam = iParamMatch.groupValues[1]
        
        val apiUrl = "https://www.podbean.com/player/$iParam?scode=&pfauth=&referrer=&touchable=false&type=classic"
        
        val req = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", UA)
            .header("Referer", "https://www.podbean.com/")
            .build()
            
        val jsonStr = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} for Podbean API")
            resp.body?.string() ?: ""
        }
        
        val json = gson.fromJson(jsonStr, JsonObject::class.java)
        val episodesArr = json.getAsJsonArray("episodes")
        if (episodesArr == null || episodesArr.size() == 0) {
            throw Exception("No episodes found in Podbean API response.")
        }
        
        val ep = episodesArr.get(0).asJsonObject
        val resource = ep.get("resource")?.asString 
            ?: ep.get("downloadLink")?.asString 
            ?: ep.get("fallbackResource")?.asString
            ?: throw Exception("No resource URL found in episode data.")
            
        resource
    }
}
