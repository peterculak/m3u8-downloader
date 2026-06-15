package com.ta3.downloader

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // Registry lives in internal storage (always accessible to this app)
    private val registryFile: File
        get() = File(context.filesDir, "downloads_registry.json")

    /**
     * Public Downloads folder:  /sdcard/Download/TA3/<showName>/
     * Visible to any file manager or media app on the device.
     */
    private fun showDownloadDir(showName: String): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(base, "TA3/$showName").also { it.mkdirs() }
    }

    private fun prehrajDownloadDir(): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(base, "Prehraj").also { it.mkdirs() }
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
            File(entry.localPath).delete()
            registry.remove(entry)
            saveRegistryInternal(registry)
        }
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

        // 1. Resolve the m3u8 URL
        val m3u8Url = Scraper.resolveM3u8(episode.url)
        onProgress(0.05f)

        // 3. Build output path: /sdcard/Download/TA3/<showName>/<date>-<title>.m4a
        val safeTitle = episode.title
            .replace(Regex("[^a-zA-Z0-9áäčďéíľĺňóôŕšťúýžÁÄČĎÉÍĽĹŇÓÔŔŠŤÚÝŽ ._-]"), "")
            .trim()
            .replace(" ", "_")
            .take(80)
        val fileName = "${episode.date}-${safeTitle}.m4a"
        val outFile = File(showDownloadDir(episode.showName), fileName)

        if (outFile.exists()) outFile.delete()

        // 4. Download and re-encode to m4a using FFmpegKit (exactly like CLI)
        val mediaInfo = com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(m3u8Url)
        val durationStr = mediaInfo.mediaInformation?.duration
        val durationMs = (durationStr?.toDoubleOrNull() ?: 0.0) * 1000.0

        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            val session = com.arthenica.ffmpegkit.FFmpegKit.executeAsync(
                "-y -i \"$m3u8Url\" -vn -c:a copy \"${outFile.absolutePath}\"",
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
     * Tell the Android media scanner about the new file so it shows up
     * immediately in VLC, Poweramp, etc. without requiring a manual rescan.
     */
    private fun notifyMediaStore(file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Downloads.SIZE, file.length())
                }
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(file.absolutePath), arrayOf("audio/mp4"), null
                )
            }
        } catch (_: Exception) { /* non-fatal */ }
    }

    private fun notifyMediaStoreVideo(file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    put(MediaStore.Downloads.SIZE, file.length())
                }
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null
                )
            }
        } catch (_: Exception) { /* non-fatal */ }
    }
}
