package com.ta3.downloader

import com.google.gson.Gson
import com.google.gson.JsonObject
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit

/**
 * Scraper for YouTube channels using the public RSS feed for episode listing
 * and NewPipeExtractor for resolving direct audio stream URLs.
 */
object YouTubeScraper {

    private const val TAG = "YouTubeScraper"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile
    private var initialized = false

    /**
     * Initialize NewPipeExtractor with our OkHttp-based downloader.
     * Safe to call multiple times — only initializes once.
     */
    fun ensureInitialized() {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    NewPipe.init(DownloaderImpl.getInstance())
                    initialized = true
                    Log.d(TAG, "NewPipe initialized")
                }
            }
        }
    }

    /**
     * Fetch recent videos from a YouTube channel's "Streams" tab.
     * Uses a direct HTML request and extracts video info from ytInitialData.
     * This avoids the RSS feed (which only lists uploaded clips, not live streams).
     */
    suspend fun fetchEpisodes(channel: YouTubeChannel): List<Episode> = withContext(Dispatchers.IO) {
        val streamsUrl = "https://www.youtube.com/channel/${channel.channelId}/${channel.tab}"
        Log.d(TAG, "Fetching streams HTML: $streamsUrl")

        val request = Request.Builder()
            .url(streamsUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "CONSENT=YES+cb.20210328-17-p0.en+FX+478")
            .build()
            
        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} fetching $streamsUrl")
            response.body?.string() ?: ""
        }

        val episodes = mutableListOf<Episode>()

        // Use indexOf and substring instead of Regex to prevent StackOverflowError on massive HTML strings
        val jsonString = extractYtInitialDataJson(html)
        
        fun parseRelativeDate(relativeStr: String): String {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            if (relativeStr.isBlank()) return "1970-01-01" // Default to old date so we don't accidentally download everything
            
            val cal = java.util.Calendar.getInstance()
            val text = relativeStr.lowercase()
            
            // Extract the first number found in the string
            val num = Regex("""(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            
            if (text.contains("day") || text.contains("deň") || text.contains("dňom") ||
                text.contains("dňami") || text.contains("dní") || text.contains("dnem")) {
                cal.add(java.util.Calendar.DAY_OF_YEAR, -num)
            } else if (text.contains("week") || text.contains("týždeň") || text.contains("týždňami") ||
                text.contains("týždne") || text.contains("týždňom")) {
                cal.add(java.util.Calendar.DAY_OF_YEAR, -(num * 7))
            } else if (text.contains("month") || text.contains("mesiac") || text.contains("mesiacmi") || text.contains("mesiace")) {
                cal.add(java.util.Calendar.MONTH, -num)
            } else if (text.contains("year") || text.contains("rok") || text.contains("rokmi") || text.contains("roky")) {
                cal.add(java.util.Calendar.YEAR, -num)
            } else if (text.contains("hour") || text.contains("hodin") || text.contains("minute") ||
                text.contains("minút") || text.contains("second") || text.contains("sekund")) {
                // Keep as today
            } else if (text.contains("live") || text.contains("naživo") || text.contains("premiéra") ||
                text.contains("premiere") || text.contains("streamed") || text.contains("streamované")) {
                // Keep as today — e.g. "Streamed 10 hours ago" / "Streamované pred 10 hodinami"
            } else {
                // If we completely fail to parse it, do NOT assume it's today! 
                // Return an old date so we don't trigger mass downloads of old episodes.
                Log.w(TAG, "Could not parse relative time: $relativeStr")
                return "1970-01-01"
            }
            
            return sdf.format(cal.time)
        }

        fun resolveRelativeTime(relativeTime: String, videoId: String): String {
            if (relativeTime.isNotBlank()) return relativeTime
            return findRelativeTimeInRawJson(jsonString ?: "", videoId)
        }
        
        if (jsonString != null) {
            val gson = Gson()
            val json = gson.fromJson(jsonString, JsonObject::class.java)
            
            val seenIds = mutableSetOf<String>()
            
            val queue = java.util.LinkedList<com.google.gson.JsonElement>()
            queue.add(json)
            
            while (queue.isNotEmpty()) {
                val element = queue.poll() ?: continue
                if (element.isJsonArray) {
                    element.asJsonArray.forEach { queue.add(it) }
                } else if (element.isJsonObject) {
                    val obj = element.asJsonObject
                    if (obj.has("lockupViewModel")) {
                        try {
                            val lockup = obj.getAsJsonObject("lockupViewModel")
                            val title = lockup.getAsJsonObject("metadata")
                                .getAsJsonObject("lockupMetadataViewModel")
                                .getAsJsonObject("title")
                                .get("content").asString
                            val url = lockup.getAsJsonObject("rendererContext")
                                .getAsJsonObject("commandContext")
                                .getAsJsonObject("onTap")
                                .getAsJsonObject("innertubeCommand")
                                .getAsJsonObject("commandMetadata")
                                .getAsJsonObject("webCommandMetadata")
                                .get("url").asString
                            
                            var relativeTime = ""
                            try {
                                val parts = lockup.getAsJsonObject("metadata")
                                    .getAsJsonObject("lockupMetadataViewModel")
                                    .getAsJsonObject("metadata")
                                    .getAsJsonObject("contentMetadataViewModel")
                                    .getAsJsonArray("metadataRows")
                                    .get(0).asJsonObject.getAsJsonArray("metadataParts")
                                if (parts.size() > 0) {
                                    // The last part is always the relative publish time (e.g., "Streamed 2 days ago")
                                    relativeTime = parts.get(parts.size() - 1).asJsonObject.getAsJsonObject("text").get("content").asString
                                }
                            } catch (e: Exception) {}
                            
                            val videoIdMatch = Regex("""/watch\?v=([^&]+)""").find(url)
                            if (videoIdMatch != null && title.isNotEmpty()) {
                                val videoId = videoIdMatch.groupValues[1]
                                if (!seenIds.contains(videoId)) {
                                    seenIds.add(videoId)
                                    val resolvedTime = resolveRelativeTime(relativeTime, videoId)
                                    episodes.add(
                                        Episode(
                                            title = title,
                                            date = parseRelativeDate(resolvedTime),
                                            time = "",
                                            url = "https://www.youtube.com/watch?v=$videoId",
                                            showName = channel.name
                                        )
                                    )
                                }
                            }
                        } catch (e: Exception) {}
                    } else if (obj.has("videoWithContextRenderer")) {
                        try {
                            val renderer = obj.getAsJsonObject("videoWithContextRenderer")
                            val videoId = renderer.get("videoId").asString
                            val title = renderer.getAsJsonObject("headline")
                                .getAsJsonArray("runs").get(0).asJsonObject.get("text").asString

                            var relativeTime = ""
                            try {
                                relativeTime = renderer.getAsJsonObject("publishedTimeText").get("simpleText").asString
                            } catch (e: Exception) {
                                try {
                                    val runs = renderer.getAsJsonObject("publishedTimeText").getAsJsonArray("runs")
                                    var combined = ""
                                    for (i in 0 until runs.size()) {
                                        combined += runs.get(i).asJsonObject.get("text").asString
                                    }
                                    relativeTime = combined
                                } catch (e: Exception) {}
                            }

                            if (videoId.isNotEmpty() && title.isNotEmpty() && !seenIds.contains(videoId)) {
                                seenIds.add(videoId)
                                val resolvedTime = resolveRelativeTime(relativeTime, videoId)
                                episodes.add(
                                    Episode(
                                        title = title,
                                        date = parseRelativeDate(resolvedTime),
                                        time = "",
                                        url = "https://www.youtube.com/watch?v=$videoId",
                                        showName = channel.name
                                    )
                                )
                            }
                        } catch (e: Exception) {}
                    } else if (obj.has("videoRenderer")) {
                        try {
                            val renderer = obj.getAsJsonObject("videoRenderer")
                            val videoId = renderer.get("videoId").asString
                            val title = renderer.getAsJsonObject("title")
                                .getAsJsonArray("runs").get(0).asJsonObject.get("text").asString
                                
                            var relativeTime = ""
                            try {
                                relativeTime = renderer.getAsJsonObject("publishedTimeText").get("simpleText").asString
                            } catch (e: Exception) {
                                try {
                                    val runs = renderer.getAsJsonObject("publishedTimeText").getAsJsonArray("runs")
                                    var combined = ""
                                    for (i in 0 until runs.size()) {
                                        combined += runs.get(i).asJsonObject.get("text").asString
                                    }
                                    relativeTime = combined
                                } catch (e: Exception) {}
                            }
                            
                            if (videoId.isNotEmpty() && title.isNotEmpty() && !seenIds.contains(videoId)) {
                                seenIds.add(videoId)
                                val resolvedTime = resolveRelativeTime(relativeTime, videoId)
                                episodes.add(
                                    Episode(
                                        title = title,
                                        date = parseRelativeDate(resolvedTime),
                                        time = "",
                                        url = "https://www.youtube.com/watch?v=$videoId",
                                        showName = channel.name
                                    )
                                )
                            }
                        } catch (e: Exception) {}
                    } else {
                        obj.entrySet().forEach { queue.add(it.value) }
                    }
                }
            }
        } else {
            Log.w(TAG, "ytInitialData not found on streams page")
        }

        Log.d(TAG, "Found ${episodes.size} episodes for ${channel.displayName}")
        episodes
    }

    /**
     * Extract ytInitialData JSON from a YouTube channel page.
     * Desktop pages embed raw JSON; mobile pages hex-encode it inside a JS string literal.
     */
    private fun extractYtInitialDataJson(html: String): String? {
        val startMarker = "var ytInitialData = "
        val startIndex = html.indexOf(startMarker)
        if (startIndex == -1) return null

        val jsonStart = startIndex + startMarker.length
        val endMarker = ";</script>"
        val endIndex = html.indexOf(endMarker, jsonStart)
        if (endIndex == -1) return null

        var raw = html.substring(jsonStart, endIndex).trim()
        if (raw.startsWith("'") && raw.endsWith("'")) {
            raw = decodeHexEscapedJsString(raw.substring(1, raw.length - 1))
        }
        return raw
    }

    /** Decode mobile YouTube's `\x7b\x22...` string encoding back to UTF-8 JSON. */
    private fun decodeHexEscapedJsString(encoded: String): String {
        return encoded.replace(Regex("""\\x([0-9a-fA-F]{2})""")) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
    }

    /** Regex fallback: locate published-time text near a video ID inside the raw JSON blob. */
    private fun findRelativeTimeInRawJson(rawJson: String, videoId: String): String {
        if (rawJson.isBlank()) return ""

        val anchor = rawJson.indexOf("\"videoId\":\"$videoId\"")
        if (anchor == -1) return ""

        val chunk = rawJson.substring(anchor, minOf(anchor + 4000, rawJson.length))
        
        // Try to match common YouTube time formats in both English and Slovak
        val timePattern = Regex(""""text"\s*:\s*"([^"]*(ago|pred|hodin|minút|sekúnd|dň|týžd|mesiac|rok|Stream|Premi)[^"]*)"""", RegexOption.IGNORE_CASE)
        val match = timePattern.find(chunk)
        if (match != null) {
            return match.groupValues[1]
        }
        
        // Fallback for simpleText
        val simpleTextPattern = Regex(""""publishedTimeText"\s*:\s*\{\s*"simpleText"\s*:\s*"([^"]+)"""")
        return simpleTextPattern.find(chunk)?.groupValues?.get(1) ?: ""
    }

    /**
     * Resolve the best audio-only stream URL for a YouTube video.
     * Uses NewPipeExtractor to extract the direct audio URL that can be passed to FFmpeg.
     */
    suspend fun resolveAudioUrlAndDuration(videoUrl: String): Pair<String, Long> = withContext(Dispatchers.IO) {
        ensureInitialized()
        Log.d(TAG, "Resolving audio URL for: $videoUrl")

        val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
        val audioStreams = streamInfo.audioStreams
        val durationMs = streamInfo.duration * 1000L

        if (audioStreams.isEmpty()) {
            throw Exception("No audio streams found for $videoUrl")
        }

        // Prefer m4a/AAC streams for best compatibility with -c:a copy in FFmpeg
        val bestStream: AudioStream = audioStreams
            .filter { it.format?.name?.contains("m4a", ignoreCase = true) == true ||
                      it.format?.name?.contains("aac", ignoreCase = true) == true ||
                      it.format?.mimeType?.contains("mp4", ignoreCase = true) == true }
            .maxByOrNull { it.averageBitrate }
            ?: audioStreams.maxByOrNull { it.averageBitrate }
            ?: audioStreams.first()

        var url = bestStream.content ?: throw Exception("Audio stream URL is null for $videoUrl")
        
        // YouTube often embeds &range=0-65535 into the URL to force players to only fetch the initialization chunk.
        // We must strip this out so our OkHttp downloader can fetch the FULL file and manage its own chunking.
        url = url.replace(Regex("&range=[0-9]+-[0-9]+"), "")
                 .replace(Regex("\\?range=[0-9]+-[0-9]+&"), "?")
                 .replace(Regex("\\?range=[0-9]+-[0-9]+$"), "")

        Log.d(TAG, "Resolved audio URL (bitrate=${bestStream.averageBitrate}): ${url.take(80)}...")
        Pair(url, durationMs)
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; Feedparser)")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} fetching $url")
            response.body?.string() ?: ""
        }
    }

    /**
     * Quickly fetch basic details for a shared YouTube URL without needing to scrape the whole channel.
     * Generates an Episode object with the correct Title and Channel Name to pass to the downloader.
     */
    suspend fun resolveSharedVideoDetails(videoUrl: String): Episode = withContext(Dispatchers.IO) {
        ensureInitialized()
        Log.d(TAG, "Resolving shared video details for: $videoUrl")

        val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
        
        val title = streamInfo.name ?: "Neznáme video"
        val uploader = streamInfo.uploaderName ?: "Zdieľané video"
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

        Episode(
            title = title,
            date = today,
            time = "",
            url = videoUrl,
            showName = uploader
        )
    }
}
