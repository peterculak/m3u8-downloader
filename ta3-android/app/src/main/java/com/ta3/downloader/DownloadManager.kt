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
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Registry lives in internal storage (always accessible to this app)
    private val registryFile: File
        get() = File(context.filesDir, "downloads_registry.json")

    /**
     * Public Downloads folder:  /sdcard/Download/TA3/<showName>/
     * Visible to any file manager or media app on the device.
     */
    private fun showDownloadDir(showName: String): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        return File(base, "TA3/$showName").also { it.mkdirs() }
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
     * Tell the Android media scanner about the new file so it shows up
     * immediately in VLC, Poweramp, etc. without requiring a manual rescan.
     */
    private fun notifyMediaStore(file: File) {
        try {
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf("audio/mp4"), null
            )
        } catch (_: Exception) { /* non-fatal */ }
    }
}
