package com.ta3.downloader

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class DownloadManager(private val context: Context) {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "DownloadManager"
        // YouTube DASH init segment / bogus Content-Length when range metadata is wrong
        private const val YOUTUBE_INIT_SEGMENT_BYTES = 65536L
        private const val YOUTUBE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    // Registry lives in internal storage (always accessible to this app)
    private val registryFile: File
        get() = File(context.filesDir, "downloads_registry.json")

    private val pendingRegistryFile: File
        get() = File(context.filesDir, "pending_downloads.json")

    /**
     * Persistent tombstone: URLs that were auto-deleted by cleanupOldDownloads.
     * The auto-downloader checks this set so the same episode is never silently
     * re-downloaded after it has been auto-wiped.
     * Manual downloads from the UI bypass this list intentionally.
     */
    private val deletedUrlsFile: File
        get() = File(context.filesDir, "deleted_episode_urls.json")

    /**
     * Public Downloads folder:  /sdcard/Download/TA3/<showName>/
     * Visible to any file manager or media app on the device.
     */
    private fun showDownloadDir(showName: String): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(base, "TA3/$showName").also { it.mkdirs() }
    }

    private fun stvrDownloadDir(showName: String): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(base, "STVR/$showName").also { it.mkdirs() }
    }

    private fun prehrajDownloadDir(): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(base, "Prehraj").also { it.mkdirs() }
    }

    private fun youtubeDownloadDir(showName: String): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(base, "YouTube/$showName").also { it.mkdirs() }
    }

    private val registryMutex = kotlinx.coroutines.sync.Mutex()

    // ─── Registry ──────────────────────────────────────────────────────────────

    suspend fun loadRegistry(): List<DownloadedFile> = withContext(Dispatchers.IO) {
        registryMutex.withLock {
            loadRegistryInternal()
        }
    }

    private fun loadRegistryInternal(): List<DownloadedFile> {
        try {
            if (!registryFile.exists()) return emptyList()
            val type = object : TypeToken<List<DownloadedFile>>() {}.type
            return gson.fromJson<List<DownloadedFile>>(registryFile.readText(), type)
                ?.filter { File(it.localPath).exists() }  // auto-prune stale entries
                ?: emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private suspend fun saveRegistry(list: List<DownloadedFile>) = withContext(Dispatchers.IO) {
        registryMutex.withLock {
            saveRegistryInternal(list)
        }
    }

    private fun saveRegistryInternal(list: List<DownloadedFile>) {
        registryFile.writeText(gson.toJson(list))
    }

    suspend fun isDownloaded(episodeUrl: String): Boolean {
        val entry = loadRegistry().find { it.episodeUrl == episodeUrl } ?: return false
        return File(entry.localPath).exists()
    }

    suspend fun deleteDownload(episodeUrl: String) = withContext(Dispatchers.IO) {
        registryMutex.withLock {
            val registry = loadRegistryInternal().toMutableList()
            val entry = registry.find { it.episodeUrl == episodeUrl } ?: return@withLock
            deletePhysicalFile(entry.localPath)
            registry.remove(entry)
            saveRegistryInternal(registry)
        }
    }

    suspend fun cleanupOldDownloads(days: Int): Int = withContext(Dispatchers.IO) {
        val cutoffMs = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L)
        registryMutex.withLock {
            val registry = loadRegistryInternal().toMutableList()
            val toRemove = registry.filter { entry ->
                // Never auto-delete YouTube channel downloads.
                // These channels publish infrequently (every few weeks); auto-deleting after
                // 7 days would wipe the only available episode, and then the app could
                // re-download it on the next run creating a delete-redownload loop.
                if (CustomChannelManager.getAllYouTubeChannels().any { it.name == entry.showName }) {
                    Log.d(TAG, "Auto-delete: skipping YouTube episode '${entry.title}' (keep YouTube downloads)")
                    return@filter false
                }
                val file = File(entry.localPath)
                if (!file.exists()) return@filter true // Cleanup orphaned registry entries
                val timeToCompare = if (entry.downloadedAt > 0) entry.downloadedAt else file.lastModified()
                timeToCompare in 1..<cutoffMs
            }
            if (toRemove.isEmpty()) return@withLock 0

            Log.d(TAG, "Cleaning up ${toRemove.size} old downloads older than $days days")
            val tombstone = loadDeletedUrlsInternal().toMutableSet()
            toRemove.forEach { entry ->
                deletePhysicalFile(entry.localPath)
                registry.remove(entry)
                tombstone.add(entry.episodeUrl)   // remember forever — never auto-re-download
                Log.d(TAG, "  deleted + tombstoned: ${entry.title} (${entry.episodeUrl.takeLast(60)})")
            }
            saveRegistryInternal(registry)
            saveDeletedUrlsInternal(tombstone)
            toRemove.size
        }
    }

    // ─── Tombstone (auto-deleted URLs) ────────────────────────────────────────

    /** Load the set of episode URLs that were previously auto-deleted. */
    suspend fun loadDeletedUrls(): Set<String> = withContext(Dispatchers.IO) {
        registryMutex.withLock { loadDeletedUrlsInternal() }
    }

    private fun loadDeletedUrlsInternal(): Set<String> {
        return try {
            if (!deletedUrlsFile.exists()) return emptySet()
            val type = object : com.google.gson.reflect.TypeToken<Set<String>>() {}.type
            gson.fromJson<Set<String>>(deletedUrlsFile.readText(), type) ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun saveDeletedUrlsInternal(urls: Set<String>) {
        deletedUrlsFile.writeText(gson.toJson(urls))
    }


    /** Delete every downloaded file and clear the registry. Useful for testing. */
    suspend fun clearAllDownloads() = withContext(Dispatchers.IO) {
        registryMutex.withLock {
            val registry = loadRegistryInternal()
            registry.forEach { deletePhysicalFile(it.localPath) }
            saveRegistryInternal(emptyList())
            savePendingDownloadsInternal(emptyList())

            // Also aggressively clean up the physical directories in case of orphaned files
            val ta3Base = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TA3")
            if (ta3Base.exists()) {
                ta3Base.walkBottomUp().forEach { if (it.isFile) deletePhysicalFile(it.absolutePath) }
            }
            val stvrBase = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "STVR")
            if (stvrBase.exists()) {
                stvrBase.walkBottomUp().forEach { if (it.isFile) deletePhysicalFile(it.absolutePath) }
            }
            val prehrajBase = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Prehraj")
            if (prehrajBase.exists()) {
                prehrajBase.walkBottomUp().forEach { if (it.isFile) deletePhysicalFile(it.absolutePath) }
            }
            val ytBase = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YouTube")
            if (ytBase.exists()) {
                ytBase.walkBottomUp().forEach { if (it.isFile) deletePhysicalFile(it.absolutePath) }
            }
        }
    }

    private fun deletePhysicalFile(path: String) {
        val f = File(path)
        if (f.exists()) f.delete()
        
        // Scan the deleted path to forcefully purge it from the system's Music database
        try {
            android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        } catch (_: Exception) {}
        
        // If File.delete() failed (e.g. Scoped Storage), try via ContentResolver
        if (f.exists()) {
            try {
                val selection = MediaStore.MediaColumns.DATA + "=?"
                val selectionArgs = arrayOf(path)
                val uri = MediaStore.Files.getContentUri("external")
                context.contentResolver.delete(uri, selection, selectionArgs)
            } catch (_: Exception) {}
        }
    }

    // ─── Pending Downloads ─────────────────────────────────────────────────────

    suspend fun loadPendingDownloads(): List<PendingDownload> = withContext(Dispatchers.IO) {
        registryMutex.withLock {
            loadPendingDownloadsInternal()
        }
    }

    private fun loadPendingDownloadsInternal(): List<PendingDownload> {
        try {
            if (!pendingRegistryFile.exists()) return emptyList()
            val type = object : TypeToken<List<PendingDownload>>() {}.type
            return gson.fromJson(pendingRegistryFile.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun savePendingDownloadsInternal(list: List<PendingDownload>) {
        pendingRegistryFile.writeText(gson.toJson(list))
    }

    suspend fun markPending(episode: Episode, directUrl: String? = null) = withContext(Dispatchers.IO) {
        registryMutex.withLock {
            val list = loadPendingDownloadsInternal().toMutableList()
            val existing = list.find { it.episodeUrl == episode.url }
            if (existing != null) {
                list.remove(existing)
                list.add(existing.copy(
                    attemptCount = existing.attemptCount + 1,
                    directUrl = directUrl ?: existing.directUrl
                ))
            } else {
                list.add(PendingDownload(
                    episodeUrl = episode.url,
                    title = episode.title,
                    date = episode.date,
                    time = episode.time,
                    showName = episode.showName,
                    directUrl = directUrl,
                    attemptCount = 1
                ))
            }
            savePendingDownloadsInternal(list)
        }
    }

    suspend fun clearPending(episodeUrl: String) = withContext(Dispatchers.IO) {
        registryMutex.withLock {
            val list = loadPendingDownloadsInternal().toMutableList()
            val existing = list.find { it.episodeUrl == episodeUrl }
            if (existing != null) {
                list.remove(existing)
                savePendingDownloadsInternal(list)
            }
        }
    }

    suspend fun markComplete(episodeUrl: String) = clearPending(episodeUrl)
    
    suspend fun markFailed(episodeUrl: String) {
        // No-op. attemptCount is incremented in markPending when the attempt begins,
        // ensuring it gets counted even if the process crashes during download.
    }

    // ─── Downloading ───────────────────────────────────────────────────────────

    /**
     * Download an episode to the public Downloads/TA3/<show>/ folder.
     * Files are saved as .ts (MPEG Transport Stream containing AAC audio).
     * VLC, MX Player, and most media apps open .ts files directly.
     */
    suspend fun download(
        episode: Episode,
        onProgress: (Float) -> Unit
    ): DownloadedFile = withContext(Dispatchers.IO) {
        onProgress(0f)

        val isStvr = STVR_SHOWS.any { it.name == episode.showName }

        // 1. Resolve the m3u8 URL
        val m3u8Url = if (isStvr) {
            StvScraper.resolveM3u8(episode.url)
        } else {
            Scraper.resolveM3u8(episode.url)
        }
        onProgress(0.05f)

        // 2. Look up the display name for the show (e.g. "Tlačové besedy")
        val showDisplayName = if (isStvr) {
            STVR_SHOWS.find { it.name == episode.showName }?.displayName
        } else {
            TA3_SHOWS.find { it.name == episode.showName }?.displayName
        } ?: episode.showName

        // 3. Build output path — we don't need date/time in the filename anymore
        //    because it's properly embedded in the metadata for music apps to sort by.
        val safeTitle = episode.title
            .replace(Regex("[^a-zA-Z0-9áäčďéíľĺňóôŕšťúýžÁÄČĎÉÍĽĹŇÓÔŔŠŤÚÝŽ ._-]"), "")
            .trim()
            .replace(" ", "_")
            .take(80)
        val isMp3 = m3u8Url.contains(".mp3", ignoreCase = true)
        val ext = if (isMp3) "mp3" else "m4a"
        val fileName = "${safeTitle}.$ext"
        val outFile = File(if (isStvr) stvrDownloadDir(episode.showName) else showDownloadDir(episode.showName), fileName)

        if (outFile.exists()) outFile.delete()

        // 4. Build full ISO datetime string for the date metadata tag
        val isoDateTime = if (episode.time.isNotEmpty())
            "${episode.date}T${episode.time}:00"
        else
            episode.date

        // Escape quotes in title for shell safety, and prepend date/time so it's visible
        val displayTitle = "${episode.date}${if (episode.time.isNotEmpty()) " ${episode.time}" else ""} - ${episode.title}"
        val safeMetaTitle = displayTitle.replace("\"", "'")
        val safeMetaArtist = showDisplayName.replace("\"", "'")

        // 5. Download and re-encode to m4a using FFmpegKit, embedding metadata so
        //    music apps show correct artist, title, and can sort by date/time.
        val mediaInfo = com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(m3u8Url)
        val durationStr = mediaInfo.mediaInformation?.duration
        val durationMs = (durationStr?.toDoubleOrNull() ?: 0.0) * 1000.0

        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            // Use -map_metadata -1 to discard the 1970 creation_time from the stream
            val session = com.arthenica.ffmpegkit.FFmpegKit.executeAsync(
                "-y -reconnect 1 -reconnect_streamed 1 -reconnect_delay_max 5 -i \"$m3u8Url\" -vn -c:a copy -map_metadata -1 " +
                    "-metadata artist=\"$safeMetaArtist\" " +
                    "-metadata album=\"$safeMetaArtist\" " +
                    "-metadata title=\"$safeMetaTitle\" " +
                    "-metadata date=\"$isoDateTime\" " +
                    "\"${outFile.absolutePath}\"",
                { session ->
                    if (com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                        cont.resumeWith(Result.success(Unit))
                    } else {
                        val failLog = session.failStackTrace ?: "Unknown error"
                        cont.resumeWith(Result.failure(Exception("FFmpeg failed: ${session.returnCode} - $failLog")))
                    }
                },
                { /* log */ },
                { stat ->
                    if (durationMs > 0) {
                        val progress = (stat.time / durationMs).toFloat().coerceIn(0f, 1f)
                        onProgress(0.05f + progress * 0.95f)
                    } else {
                        // fallback if duration is unknown, just bounce progress
                        onProgress(0.5f)
                    }
                }
            )
            cont.invokeOnCancellation {
                com.arthenica.ffmpegkit.FFmpegKit.cancel(session.sessionId)
            }
        }

        // Verify that the output duration matches the expected duration
        if (durationMs > 0 && outFile.exists() && outFile.length() > 0) {
            val outputMediaInfo = com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(outFile.absolutePath)
            val outputDurationStr = outputMediaInfo.mediaInformation?.duration
            val outputDurationMs = (outputDurationStr?.toDoubleOrNull() ?: 0.0) * 1000.0

            if (outputDurationMs > 0) {
                val difference = durationMs - outputDurationMs
                if (difference > 30000) {
                    outFile.delete()
                    throw Exception("Incomplete download: Stream duration is ${durationMs}ms but output is only ${outputDurationMs}ms (diff: ${difference}ms)")
                }
            }
        }

        // Parse date for setting file modification time
        try {
            val format = if (episode.time.isNotEmpty()) {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            } else {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            }
            val dateObj = format.parse(isoDateTime)
            if (dateObj != null) {
                outFile.setLastModified(dateObj.time)
            }
        } catch (_: Exception) {}

        // 5. Notify MediaStore so music apps index the new file immediately
        notifyMediaStore(outFile)

        // 6. Register in registry safely using the mutex
        val record = DownloadedFile(
            episodeUrl = episode.url,
            title = episode.title,
            date = episode.date,
            showName = episode.showName,
            localPath = outFile.absolutePath,
            fileSizeBytes = outFile.length()
        )
        registryMutex.withLock {
            val registry = loadRegistryInternal().filter { it.episodeUrl != episode.url }.toMutableList()
            registry.add(0, record)
            saveRegistryInternal(registry)
        }

        record
    }

    /**
     * Download a prehraj.to movie from a direct MP4 URL using plain HTTP streaming.
     * Saves to public Downloads/Prehraj/<title>.mp4
     * No FFmpeg — the file is already a valid MP4, just stream it down directly.
     */
    suspend fun downloadDirectMp4(
        episode: Episode,
        directUrl: String,
        onProgress: (Float) -> Unit
    ): DownloadedFile = withContext(Dispatchers.IO) {
        onProgress(0f)

        val safeTitle = episode.title
            .replace(Regex("[^a-zA-Z0-9áäčďéíľĺňóôŕšťúýžÁÄČĎÉÍĽĹŇÓÔŔŠŤÚÝŽ ._-]"), "")
            .trim()
            .replace(" ", "_")
            .take(80)
        val fileName = if (episode.date.isNotEmpty()) "${episode.date}-${safeTitle}.mp4"
                       else "${safeTitle}.mp4"
        val outFile = File(prehrajDownloadDir(), fileName)
        if (outFile.exists()) outFile.delete()

        // Stream directly via OkHttp — same approach as the JS POC
        val request = Request.Builder()
            .url(directUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .header("Referer", "https://prehraj.to/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} downloading video")

            val contentLength = response.body?.contentLength() ?: -1L
            var downloaded = 0L

            var lastReportedProgress = 0f
            val buffer = ByteArray(256 * 1024)  // 256 KB chunks — reduces syscall overhead vs 8 KB
            java.io.BufferedOutputStream(FileOutputStream(outFile), 256 * 1024).use { out ->
                val input = response.body!!.byteStream()
                var bytes = input.read(buffer)
                while (bytes >= 0) {
                    out.write(buffer, 0, bytes)
                    downloaded += bytes
                    if (contentLength > 0) {
                        val currentProgress = (downloaded.toFloat() / contentLength).coerceIn(0f, 0.99f)
                        if (currentProgress - lastReportedProgress >= 0.01f) {
                            onProgress(currentProgress)
                            lastReportedProgress = currentProgress
                        }
                    } else {
                        onProgress(0.5f)
                    }
                    bytes = input.read(buffer)
                }
            }
        }

        notifyMediaStoreVideo(outFile)

        val record = DownloadedFile(
            episodeUrl = episode.url,
            title = episode.title,
            date = episode.date,
            showName = episode.showName,
            localPath = outFile.absolutePath,
            fileSizeBytes = outFile.length()
        )
        registryMutex.withLock {
            val registry = loadRegistryInternal().filter { it.episodeUrl != episode.url }.toMutableList()
            registry.add(0, record)
            saveRegistryInternal(registry)
        }

        record
    }

    /**
     * Download a YouTube video's audio track to Downloads/YouTube/<showName>/<title>.m4a
     * Uses NewPipeExtractor to resolve the direct audio stream URL, then FFmpeg to download.
     */
    suspend fun downloadYouTubeAudio(
        episode: Episode,
        onProgress: (Float) -> Unit
    ): DownloadedFile = withContext(Dispatchers.IO) {
        onProgress(0f)

        // 1. Resolve the direct audio stream URL and duration via NewPipeExtractor
        val (audioUrl, durationMs) = YouTubeScraper.resolveAudioUrlAndDuration(episode.url)
        onProgress(0.05f)

        // 2. Look up display name for the channel
        val channel = CustomChannelManager.getAllYouTubeChannels().find { it.name == episode.showName }
        val showDisplayName = channel?.displayName ?: episode.showName

        // 3. Build output path
        val safeTitle = episode.title
            .replace(Regex("[^a-zA-Z0-9áäčďéíľĺňóôŕšťúýžÁÄČĎÉÍĽĹŇÓÔŔŠŤÚÝŽ ._-]"), "")
            .trim()
            .replace(" ", "_")
            .take(80)
        val fileName = "${safeTitle}.m4a"
        val outFile = File(youtubeDownloadDir(episode.showName), fileName)
        if (outFile.exists()) outFile.delete()

        // 4. Build ISO datetime and display title for metadata
        val isoDateTime = if (episode.time.isNotEmpty())
            "${episode.date}T${episode.time}:00"
        else
            episode.date
        val displayTitle = "${episode.date}${if (episode.time.isNotEmpty()) " ${episode.time}" else ""} - ${episode.title}"
        val safeMetaTitle = displayTitle.replace("\"", "'")
        val safeMetaArtist = showDisplayName.replace("\"", "'")

        val tempFile = File(youtubeDownloadDir(episode.showName), "$fileName.tmp")
        if (tempFile.exists()) tempFile.delete()

        val expectedMinBytes = if (durationMs > 0) (durationMs / 1000.0 * 8000).toLong() else 0L
        val success = downloadChunked(audioUrl, tempFile, expectedMinBytes) { p -> onProgress(0.05f + p * 0.90f) }

        if (!success) {
            Log.d(TAG, "Using FFmpeg fallback for YouTube audio download")
            if (tempFile.exists()) tempFile.delete()
            onProgress(0.5f)
            downloadYouTubeStreamWithFfmpeg(audioUrl, tempFile)
        }

        if (tempFile.length() <= YOUTUBE_INIT_SEGMENT_BYTES) {
            throw Exception(
                "YouTube download produced only ${tempFile.length()} bytes — stream URL may be expired or blocked"
            )
        }

        onProgress(0.95f)

        // 6. Add metadata using FFmpeg (local to local copy)
        val session = com.arthenica.ffmpegkit.FFmpegKit.execute(
            "-y -i \"${tempFile.absolutePath}\" -vn -c:a copy -map_metadata -1 " +
                "-metadata artist=\"$safeMetaArtist\" " +
                "-metadata album=\"$safeMetaArtist\" " +
                "-metadata title=\"$safeMetaTitle\" " +
                "-metadata date=\"$isoDateTime\" " +
                "\"${outFile.absolutePath}\""
        )
        tempFile.delete()

        if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
            val failLog = session.failStackTrace ?: "Unknown error"
            throw Exception("FFmpeg metadata application failed: ${session.returnCode} - $failLog")
        }

        onProgress(1.0f)

        // 7. Set file modification time from episode date
        try {
            val format = if (episode.time.isNotEmpty()) {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            } else {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            }
            val dateObj = format.parse(isoDateTime)
            if (dateObj != null) outFile.setLastModified(dateObj.time)
        } catch (_: Exception) {}

        // 8. Notify MediaStore and register in registry
        notifyMediaStore(outFile)

        val record = DownloadedFile(
            episodeUrl = episode.url,
            title = episode.title,
            date = episode.date,
            showName = episode.showName,
            localPath = outFile.absolutePath,
            fileSizeBytes = outFile.length()
        )
        registryMutex.withLock {
            val registry = loadRegistryInternal().filter { it.episodeUrl != episode.url }.toMutableList()
            registry.add(0, record)
            saveRegistryInternal(registry)
        }

        record
    }

    /**
     * Download a YouTube video with high-quality video and audio muxed.
     */
    suspend fun downloadYouTubeVideo(
        episode: Episode,
        onProgress: (Float) -> Unit
    ): DownloadedFile = withContext(Dispatchers.IO) {
        onProgress(0f)

        val (videoUrl, audioUrl, durationMs) = YouTubeScraper.resolveHighestQualityVideoAndAudio(episode.url)
        onProgress(0.05f)

        val channel = CustomChannelManager.getAllYouTubeChannels().find { it.name == episode.showName }
        val showDisplayName = channel?.displayName ?: episode.showName

        val safeTitle = episode.title
            .replace(Regex("[^a-zA-Z0-9áäčďéíľĺňóôŕšťúýžÁÄČĎÉÍĽĹŇÓÔŔŠŤÚÝŽ ._-]"), "")
            .trim()
            .replace(" ", "_")
            .take(80)
        val fileName = "${safeTitle}.mp4"
        val outFile = File(youtubeDownloadDir(episode.showName), fileName)
        if (outFile.exists()) outFile.delete()

        val isoDateTime = if (episode.time.isNotEmpty()) "${episode.date}T${episode.time}:00" else episode.date
        val displayTitle = "${episode.date}${if (episode.time.isNotEmpty()) " ${episode.time}" else ""} - ${episode.title}"
        val safeMetaTitle = displayTitle.replace("\"", "'")
        val safeMetaArtist = showDisplayName.replace("\"", "'")

        val tempVideo = File(youtubeDownloadDir(episode.showName), "${safeTitle}_v.tmp")
        val tempAudio = File(youtubeDownloadDir(episode.showName), "${safeTitle}_a.tmp")
        if (tempVideo.exists()) tempVideo.delete()
        if (tempAudio.exists()) tempAudio.delete()

        val expectedMinAudioBytes = if (durationMs > 0) (durationMs / 1000.0 * 8000).toLong() else 0L
        val expectedMinVideoBytes = if (durationMs > 0) (durationMs / 1000.0 * 50000).toLong() else 0L

        // Download video (allocates 0.05 to 0.75 of progress)
        val vSuccess = downloadChunked(videoUrl, tempVideo, expectedMinVideoBytes) { p -> onProgress(0.05f + p * 0.70f) }
        if (!vSuccess) {
            if (tempVideo.exists()) tempVideo.delete()
            downloadYouTubeStreamWithFfmpeg(videoUrl, tempVideo)
        }
        
        // Download audio (allocates 0.75 to 0.90 of progress)
        val aSuccess = downloadChunked(audioUrl, tempAudio, expectedMinAudioBytes) { p -> onProgress(0.75f + p * 0.15f) }
        if (!aSuccess) {
            if (tempAudio.exists()) tempAudio.delete()
            downloadYouTubeStreamWithFfmpeg(audioUrl, tempAudio)
        }

        if (tempVideo.length() <= YOUTUBE_INIT_SEGMENT_BYTES || tempAudio.length() <= YOUTUBE_INIT_SEGMENT_BYTES) {
            tempVideo.delete()
            tempAudio.delete()
            throw Exception("YouTube download produced truncated streams.")
        }

        onProgress(0.92f)

        // Mux and add metadata
        val session = com.arthenica.ffmpegkit.FFmpegKit.execute(
            "-y -i \"${tempVideo.absolutePath}\" -i \"${tempAudio.absolutePath}\" -c copy -map_metadata -1 " +
                "-metadata artist=\"$safeMetaArtist\" " +
                "-metadata album=\"$safeMetaArtist\" " +
                "-metadata title=\"$safeMetaTitle\" " +
                "-metadata date=\"$isoDateTime\" " +
                "\"${outFile.absolutePath}\""
        )
        tempVideo.delete()
        tempAudio.delete()

        if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
            throw Exception("FFmpeg muxing failed: ${session.returnCode} - ${session.failStackTrace}")
        }

        onProgress(1.0f)

        try {
            val format = if (episode.time.isNotEmpty()) {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            } else {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            }
            val dateObj = format.parse(isoDateTime)
            if (dateObj != null) outFile.setLastModified(dateObj.time)
        } catch (_: Exception) {}

        notifyMediaStoreVideo(outFile)

        val record = DownloadedFile(
            episodeUrl = episode.url,
            title = episode.title,
            date = episode.date,
            showName = episode.showName,
            localPath = outFile.absolutePath,
            fileSizeBytes = outFile.length()
        )
        registryMutex.withLock {
            val registry = loadRegistryInternal().filter { it.episodeUrl != episode.url }.toMutableList()
            registry.add(0, record)
            saveRegistryInternal(registry)
        }

        record
    }

    private suspend fun downloadChunked(url: String, tempFile: File, expectedMinBytes: Long, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val clenMatch = Regex("[?&]clen=(\\d+)").find(url)
        val contentLength = clenMatch?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        Log.d(TAG, "Chunked download clen=$contentLength url=${url.take(120)}...")

        val useOkHttp = contentLength > YOUTUBE_INIT_SEGMENT_BYTES &&
            (expectedMinBytes <= 0 || contentLength >= expectedMinBytes / 4)

        if (!useOkHttp) return@withContext false

        val chunkSize = 2L * 1024 * 1024 // 2MB chunks
        val numChunks = kotlin.math.ceil(contentLength.toDouble() / chunkSize).toInt()
        val downloadedBytes = java.util.concurrent.atomic.AtomicLong(0)

        val raf = java.io.RandomAccessFile(tempFile, "rw")
        raf.setLength(contentLength)

        val dispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(6)

        try {
            kotlinx.coroutines.coroutineScope {
                val jobs = (0 until numChunks).map { i: Int ->
                    async(dispatcher) {
                        val start = i * chunkSize
                        val end = if (i == numChunks - 1) contentLength - 1 else start + chunkSize - 1

                        val request = okhttp3.Request.Builder()
                            .url(url)
                            .header("User-Agent", YOUTUBE_USER_AGENT)
                            .header("Referer", "https://www.youtube.com/")
                            .header("Range", "bytes=$start-$end")
                            .build()

                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                            val bytes = response.body?.bytes() ?: throw Exception("Null body")
                            synchronized(raf) {
                                raf.seek(start)
                                raf.write(bytes)
                            }
                            val currentProg = downloadedBytes.addAndGet(bytes.size.toLong()).toFloat() / contentLength
                            onProgress(currentProg)
                        }
                    }
                }
                jobs.awaitAll()
            }
        } catch (e: Exception) {
            raf.close()
            return@withContext false
        }
        raf.close()

        val downloadedSize = tempFile.length()
        if (downloadedSize <= YOUTUBE_INIT_SEGMENT_BYTES || (contentLength > 0 && downloadedSize < contentLength * 9 / 10)) {
            return@withContext false
        }
        return@withContext true
    }

    private fun downloadYouTubeStreamWithFfmpeg(audioUrl: String, tempFile: File) {
        val headers =
            "User-Agent: $YOUTUBE_USER_AGENT\r\nReferer: https://www.youtube.com/\r\n"
        val session = com.arthenica.ffmpegkit.FFmpegKit.execute(
            "-y -headers \"$headers\" -i \"$audioUrl\" -c copy \"${tempFile.absolutePath}\""
        )
        if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
            throw Exception("FFmpeg failed to download YouTube stream: ${session.failStackTrace}")
        }
    }


    /**
     * Tell the Android media scanner about the new file so it shows up
     * immediately in VLC, Poweramp, etc. without requiring a manual rescan.
     */
    private fun notifyMediaStore(file: File) {
        try {
            // Using MediaScannerConnection.scanFile makes the file show up in Music/Audio apps
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf("audio/mp4"), null
            )
        } catch (_: Exception) { /* non-fatal */ }
    }

    private fun notifyMediaStoreVideo(file: File) {
        try {
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null
            )
        } catch (_: Exception) { /* non-fatal */ }
    }
}
