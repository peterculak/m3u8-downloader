package com.ta3.downloader

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.UUID

/**
 * One-time WorkManager worker for a single episode download triggered from the UI.
 * Survives the app being backgrounded or the process being killed.
 * Reports progress via WorkManager's setProgress API so the UI can observe it.
 */
class DownloadEpisodeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val downloadManager = DownloadManager(context)

    override suspend fun doWork(): Result {
        val episodeUrl = inputData.getString(KEY_URL) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Unknown"
        val date = inputData.getString(KEY_DATE) ?: ""
        val showName = inputData.getString(KEY_SHOW_NAME) ?: ""

        Log.d(TAG, "Starting UI download: $title")
        
        try {
            NotificationHelper.createChannel(applicationContext)
            setForeground(NotificationHelper.createForegroundInfo(applicationContext, "Downloading: $title"))
        } catch (e: Exception) {
            // Ignore if foreground service fails
        }

        DownloadStateTracker.addDownload(episodeUrl, title, showName)
        setProgress(workDataOf(KEY_PROGRESS to 0f, KEY_STATUS to "resolving"))

        return try {
            val episode = Episode(title = title, date = date, url = episodeUrl, showName = showName)
            val directUrl = inputData.getString(KEY_DIRECT_URL)

            downloadManager.markPending(episode, directUrl)

            if (directUrl != null) {
                // Prehraj.to direct MP4 download — skip m3u8 resolution
                downloadManager.downloadDirectMp4(episode, directUrl) { progress ->
                    DownloadStateTracker.updateProgress(episodeUrl, progress, DownloadStatus.DOWNLOADING)
                    setProgressBlocking(workDataOf(KEY_PROGRESS to progress, KEY_STATUS to "downloading"))
                }
            } else {
                // TA3 HLS download
                downloadManager.download(episode) { progress ->
                    DownloadStateTracker.updateProgress(episodeUrl, progress, DownloadStatus.DOWNLOADING)
                    setProgressBlocking(workDataOf(KEY_PROGRESS to progress, KEY_STATUS to "downloading"))
                }
            }

            downloadManager.markComplete(episodeUrl)
            DownloadStateTracker.updateProgress(episodeUrl, 1f, DownloadStatus.DONE)
            setProgress(workDataOf(KEY_PROGRESS to 1f, KEY_STATUS to "done"))
            Log.d(TAG, "Download complete: $title")
            
            // Give UI a moment to show "Done" before removing
            kotlinx.coroutines.delay(1500)
            DownloadStateTracker.removeDownload(episodeUrl)
            
            Result.success(workDataOf(KEY_PROGRESS to 1f, KEY_STATUS to "done", KEY_TITLE to title))
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: $title — ${e.message}")
            downloadManager.markFailed(episodeUrl)
            // Schedule a WiFi-triggered retry — WorkManager will fire it automatically
            // when the phone gets a good WiFi connection, even if the app is not running.
            AutoDownloadWorker.scheduleWifiRetry(applicationContext)
            DownloadStateTracker.updateError(episodeUrl, e.message)
            setProgress(workDataOf(KEY_STATUS to "failed", KEY_ERROR to (e.message ?: "Unknown error"), KEY_TITLE to title))
            
            kotlinx.coroutines.delay(4000)
            DownloadStateTracker.removeDownload(episodeUrl)
            
            Result.failure(workDataOf(KEY_STATUS to "failed", KEY_ERROR to (e.message ?: "Unknown error"), KEY_TITLE to title))
        }
    }

    companion object {
        private const val TAG = "DownloadEpisodeWorker"
        const val KEY_URL = "url"
        const val KEY_TITLE = "title"
        const val KEY_DATE = "date"
        const val KEY_SHOW_NAME = "show_name"
        const val KEY_DIRECT_URL = "direct_url"  // optional — skip m3u8, use direct MP4
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_ERROR = "error"

        /**
         * Enqueue a one-time download for an episode.
         * Returns the WorkRequest UUID so the ViewModel can observe it.
         */
        fun enqueue(context: Context, episode: Episode, directUrl: String? = null): UUID {
            val dataBuilder = Data.Builder()
                .putString(KEY_URL, episode.url)
                .putString(KEY_TITLE, episode.title)
                .putString(KEY_DATE, episode.date)
                .putString(KEY_SHOW_NAME, episode.showName)
            if (directUrl != null) dataBuilder.putString(KEY_DIRECT_URL, directUrl)

            val request = OneTimeWorkRequestBuilder<DownloadEpisodeWorker>()
                .setInputData(dataBuilder.build())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(episode.url)
                .build()

            WorkManager.getInstance(context).enqueue(request)
            return request.id
        }

        fun cancel(context: Context, episodeUrl: String) {
            WorkManager.getInstance(context).cancelAllWorkByTag(episodeUrl)
        }
    }
}

// WorkManager's setProgress is suspend but we need to call it from a progress callback.
// This helper bridges that gap synchronously without blocking the IO thread.
fun CoroutineWorker.setProgressBlocking(data: Data) {
    kotlinx.coroutines.runBlocking { setProgress(data) }
}
