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
        val streamsUrl = "https://www.youtube.com/channel/${channel.channelId}/streams"
        Log.d(TAG, "Fetching streams HTML: \$streamsUrl")

        val request = Request.Builder()
            .url(streamsUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "CONSENT=YES+cb.20210328-17-p0.en+FX+478")
            .build()
            
        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP \${response.code} fetching \$streamsUrl")
            response.body?.string() ?: ""
        }

        val episodes = mutableListOf<Episode>()
        
        // Use indexOf and substring instead of Regex to prevent StackOverflowError on massive HTML strings
        val startMarker = "var ytInitialData = "
        val startIndex = html.indexOf(startMarker)
        val jsonString = if (startIndex != -1) {
            val jsonStart = startIndex + startMarker.length
            val endMarker = ";</script>"
            val endIndex = html.indexOf(endMarker, jsonStart)
            if (endIndex != -1) {
                html.substring(jsonStart, endIndex)
            } else null
        } else null
        
        fun parseRelativeDate(relativeStr: String): String {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            if (relativeStr.isBlank()) return sdf.format(java.util.Date())
            val cal = java.util.Calendar.getInstance()
            val text = relativeStr.lowercase()
            
            if (text.contains("day")) {
                val num = Regex("""(\d+)\s+day""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                cal.add(java.util.Calendar.DAY_OF_YEAR, -num)
            } else if (text.contains("week")) {
                val num = Regex("""(\d+)\s+week""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                cal.add(java.util.Calendar.DAY_OF_YEAR, -(num * 7))
            } else if (text.contains("month")) {
                val num = Regex("""(\d+)\s+month""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                cal.add(java.util.Calendar.MONTH, -num)
            } else if (text.contains("year")) {
                val num = Regex("""(\d+)\s+year""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                cal.add(java.util.Calendar.YEAR, -num)
            }
            // hours/minutes/seconds are left as today
            return sdf.format(cal.time)
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
                                    episodes.add(
                                        Episode(
                                            title = title,
                                            date = parseRelativeDate(relativeTime),
                                            time = "",
                                            url = "https://www.youtube.com/watch?v=$videoId",
                                            showName = channel.name
                                        )
                                    )
                                }
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
                                    relativeTime = renderer.getAsJsonObject("publishedTimeText")
                                        .getAsJsonArray("runs").get(0).asJsonObject.get("text").asString
                                } catch (e: Exception) {}
                            }
                            
                            if (videoId.isNotEmpty() && title.isNotEmpty() && !seenIds.contains(videoId)) {
                                seenIds.add(videoId)
                                episodes.add(
                                    Episode(
                                        title = title,
                                        date = parseRelativeDate(relativeTime),
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

        Log.d(TAG, "Found \${episodes.size} episodes for \${channel.displayName}")
        episodes
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

        val url = bestStream.content ?: throw Exception("Audio stream URL is null for $videoUrl")
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
}
