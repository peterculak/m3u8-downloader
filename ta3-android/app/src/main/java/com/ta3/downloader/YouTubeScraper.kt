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
    private fun todayString(): String {
        val cal = java.util.Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun parseDurationToSeconds(durationText: String?): Int {
        if (durationText.isNullOrEmpty()) return 0
        val parts = durationText.trim().split(":")
        return when (parts.size) {
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            else -> 0
        }
    }

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
        Log.d(TAG, "fetchEpisodes: channel=${channel.name} today=${todayString()} htmlBytes=${html.length}")

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
            val fromJson = findRelativeTimeInRawJson(jsonString ?: "", videoId)
            if (fromJson.isNotBlank()) return fromJson
            // Last resort: check raw JSON for LIVE_BADGE / isLive near this videoId.
            // A brand-new stream may have no relative-time text yet but will have a live badge.
            // Without this check parseRelativeDate("") returns "1970-01-01" and the episode is silently dropped.
            val anchor = (jsonString ?: "").indexOf("\"videoId\":\"$videoId\"")
            if (anchor != -1) {
                val chunk = (jsonString ?: "").substring(anchor, minOf(anchor + 2000, (jsonString ?: "").length))
                if (chunk.contains("LIVE_BADGE", ignoreCase = true) ||
                    chunk.contains("\"style\":\"LIVE\"", ignoreCase = true) ||
                    chunk.contains("\"isLive\":true", ignoreCase = true)) {
                    Log.d(TAG, "  $videoId: no time text found but LIVE badge detected — treating as today")
                    return "live"
                }
            }
            Log.w(TAG, "  $videoId: no time found anywhere — will resolve to 1970-01-01 and be skipped")
            return ""
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
                                    val parsedDate = parseRelativeDate(resolvedTime)
                                    val durationSeconds = extractDurationSeconds(obj)
                                    Log.d(TAG, "  lockup videoId=$videoId rawTime='$relativeTime' resolved='$resolvedTime' date=$parsedDate dur=$durationSeconds title='${title.take(60)}'")
                                    episodes.add(
                                        Episode(
                                            title = title,
                                            date = parsedDate,
                                            time = "",
                                            url = "https://www.youtube.com/watch?v=$videoId",
                                            showName = channel.name,
                                            durationSeconds = durationSeconds
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
                                val parsedDate = parseRelativeDate(resolvedTime)
                                val durationSeconds = extractDurationSeconds(obj)
                                Log.d(TAG, "  videoWithContext videoId=$videoId rawTime='$relativeTime' resolved='$resolvedTime' date=$parsedDate dur=$durationSeconds title='${title.take(60)}'")
                                episodes.add(
                                    Episode(
                                        title = title,
                                        date = parsedDate,
                                        time = "",
                                        url = "https://www.youtube.com/watch?v=$videoId",
                                        showName = channel.name,
                                        durationSeconds = durationSeconds
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
                                val parsedDate = parseRelativeDate(resolvedTime)
                                val durationSeconds = extractDurationSeconds(obj)
                                Log.d(TAG, "  videoRenderer videoId=$videoId rawTime='$relativeTime' resolved='$resolvedTime' date=$parsedDate dur=$durationSeconds title='${title.take(60)}'")
                                episodes.add(
                                    Episode(
                                        title = title,
                                        date = parsedDate,
                                        time = "",
                                        url = "https://www.youtube.com/watch?v=$videoId",
                                        showName = channel.name,
                                        durationSeconds = durationSeconds
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
            Log.w(TAG, "ytInitialData not found on streams page — channel=${channel.name}")
        }

        val todayCount = episodes.count { it.date == todayString() }
        Log.d(TAG, "fetchEpisodes done: channel=${channel.name} total=${episodes.size} matchToday=$todayCount today=${todayString()}")
        if (todayCount == 0 && episodes.isNotEmpty()) {
            val mostRecent = episodes.map { it.date }.filter { it != "1970-01-01" }.maxOrNull() ?: "none"
            Log.w(TAG, "  No episodes match today — most recent date is $mostRecent")
        }
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

    /** Locate published-time text near a video ID inside the raw JSON blob.
     *
     * IMPORTANT: Only search for the `publishedTimeText` field — NOT a generic `"text"` field.
     * The broad pattern previously used here matched channel names (e.g. "Braňo Závodský Naživo"),
     * episode titles, and accessibility labels that happened to contain Slovak/English keywords
     * ("Naživo", "rok", "mesiac", "Stream", etc.). That caused parseRelativeDate to return
     * today's date for all episodes, triggering a mass re-download after every auto-delete.
     */
    private fun findRelativeTimeInRawJson(rawJson: String, videoId: String): String {
        if (rawJson.isBlank()) return ""

        val anchor = rawJson.indexOf("\"videoId\":\"$videoId\"")
        if (anchor == -1) return ""

        val chunk = rawJson.substring(anchor, minOf(anchor + 4000, rawJson.length))

        // Pattern 1: publishedTimeText.simpleText  e.g. {"publishedTimeText":{"simpleText":"Streamed 3 weeks ago"}}
        val simpleTextPattern = Regex(""""publishedTimeText"\s*:\s*\{\s*"simpleText"\s*:\s*"([^"]+)"""")
        simpleTextPattern.find(chunk)?.let { return it.groupValues[1] }

        // Pattern 2: publishedTimeText.runs[]  e.g. {"publishedTimeText":{"runs":[{"text":"Streamed 3 weeks ago"}]}}
        val runsPattern = Regex(""""publishedTimeText"[^}]{0,200}"runs"\s*:\s*\[\s*\{\s*"text"\s*:\s*"([^"]+)"""")
        runsPattern.find(chunk)?.let { return it.groupValues[1] }

        return ""
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

        val englishStreams = audioStreams.filter { it.audioLocale?.language == "en" }
        val targetStreams = if (englishStreams.isNotEmpty()) englishStreams else audioStreams

        // Prefer m4a/AAC streams for best compatibility with -c:a copy in FFmpeg
        val bestStream: AudioStream = targetStreams
            .filter { it.format?.name?.contains("m4a", ignoreCase = true) == true ||
                      it.format?.name?.contains("aac", ignoreCase = true) == true ||
                      it.format?.mimeType?.contains("mp4", ignoreCase = true) == true }
            .maxByOrNull { it.averageBitrate }
            ?: targetStreams.maxByOrNull { it.averageBitrate }
            ?: targetStreams.first()

        var url = bestStream.content ?: throw Exception("Audio stream URL is null for $videoUrl")
        
        // YouTube often embeds &range=0-65535 into the URL to force players to only fetch the initialization chunk.
        // We must strip this out so our OkHttp downloader can fetch the FULL file and manage its own chunking.
        url = url.replace(Regex("&range=[0-9]+-[0-9]+"), "")
                 .replace(Regex("\\?range=[0-9]+-[0-9]+&"), "?")
                 .replace(Regex("\\?range=[0-9]+-[0-9]+$"), "")

        Log.d(TAG, "Resolved audio URL (bitrate=${bestStream.averageBitrate}): ${url.take(80)}...")
        Pair(url, durationMs)
    }

    /**
     * Resolve the highest quality video-only stream and audio stream for a YouTube video.
     */
    suspend fun resolveHighestQualityVideoAndAudio(videoUrl: String): Triple<String, String, Long> = withContext(Dispatchers.IO) {
        ensureInitialized()
        Log.d(TAG, "Resolving highest quality video and audio URLs for: $videoUrl")

        val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
        val durationMs = streamInfo.duration * 1000L

        // Find best audio stream
        val audioStreams = streamInfo.audioStreams
        if (audioStreams.isEmpty()) {
            throw Exception("No audio streams found for $videoUrl")
        }

        val englishStreams = audioStreams.filter { it.audioLocale?.language == "en" }
        val targetStreams = if (englishStreams.isNotEmpty()) englishStreams else audioStreams

        val bestAudio = targetStreams
            .filter { it.format?.name?.contains("m4a", ignoreCase = true) == true ||
                      it.format?.name?.contains("aac", ignoreCase = true) == true ||
                      it.format?.mimeType?.contains("mp4", ignoreCase = true) == true }
            .maxByOrNull { it.averageBitrate }
            ?: targetStreams.maxByOrNull { it.averageBitrate }
            ?: targetStreams.first()

        var aUrl = bestAudio.content ?: throw Exception("Audio stream URL is null for $videoUrl")
        aUrl = aUrl.replace(Regex("&range=[0-9]+-[0-9]+"), "")
                   .replace(Regex("\\?range=[0-9]+-[0-9]+&"), "?")
                   .replace(Regex("\\?range=[0-9]+-[0-9]+$"), "")

        // Find best video stream
        var bestVideo = streamInfo.videoOnlyStreams?.maxByOrNull { 
            it.resolution?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0 
        }
        if (bestVideo == null) {
            // Fallback to muxed streams if no video-only streams exist
            bestVideo = streamInfo.videoStreams?.maxByOrNull { 
                it.resolution?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0 
            }
        }
        if (bestVideo == null) {
            throw Exception("No video streams found for $videoUrl")
        }

        var vUrl = bestVideo.content ?: throw Exception("Video stream URL is null for $videoUrl")
        vUrl = vUrl.replace(Regex("&range=[0-9]+-[0-9]+"), "")
                   .replace(Regex("\\?range=[0-9]+-[0-9]+&"), "?")
                   .replace(Regex("\\?range=[0-9]+-[0-9]+$"), "")

        Log.d(TAG, "Resolved video URL (resolution=${bestVideo.resolution}): ${vUrl.take(80)}...")
        Log.d(TAG, "Resolved audio URL (bitrate=${bestAudio.averageBitrate}): ${aUrl.take(80)}...")
        
        Triple(vUrl, aUrl, durationMs)
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
    private fun extractDurationSeconds(obj: com.google.gson.JsonObject): Int {
        val str = obj.toString()
        val durationMatch = Regex("""\"(?:thumbnailOverlayTimeStatusRenderer|lengthText)\".*?\"(?:simpleText|text)\"\s*:\s*\"([0-9:]+)\"""").find(str)
        if (durationMatch != null) {
            return parseDurationToSeconds(durationMatch.groupValues[1])
        }
        val a11yMatch = Regex("""\"label\"\s*:\s*\"([^\"]+)\"""").find(str)
        if (a11yMatch != null) {
            return parseA11yDurationToSeconds(a11yMatch.groupValues[1])
        }
        return 0
    }

    private fun parseA11yDurationToSeconds(text: String): Int {
        var total = 0
        val hMatch = Regex("""(\d+)\s+hour""").find(text)
        if (hMatch != null) total += hMatch.groupValues[1].toInt() * 3600
        
        val mMatch = Regex("""(\d+)\s+minute""").find(text)
        if (mMatch != null) total += mMatch.groupValues[1].toInt() * 60
        
        val sMatch = Regex("""(\d+)\s+second""").find(text)
        if (sMatch != null) total += sMatch.groupValues[1].toInt()
        
        return total
    }
}
