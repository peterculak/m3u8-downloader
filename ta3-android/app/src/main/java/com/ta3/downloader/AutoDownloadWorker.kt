package com.ta3.downloader

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Deferred
import java.util.concurrent.TimeUnit
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy

/**
 * WorkManager periodic worker.
 * Runs every N hours (configured in AppSettings), checks for new episodes on all
 * enabled shows, downloads any that haven't been downloaded yet, and fires a
 * notification summarising what was fetched.
 */
class AutoDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val settings = AppSettings(context)
    private val downloadManager = DownloadManager(context)

    override suspend fun doWork(): Result {
        Log.d(TAG, "AutoDownloadWorker started")
        
        try {
            NotificationHelper.createChannel(applicationContext)
            setForeground(NotificationHelper.createForegroundInfo(applicationContext, "TA3: Checking for new episodes..."))
        } catch (e: Exception) {
            // Ignore if foreground service fails
        }


        val enabledShows = settings.enabledShows()
        if (enabledShows.isEmpty()) {
            Log.d(TAG, "No shows enabled — skipping")
            return Result.success()
        }

        val downloaded = mutableListOf<String>() // show display names of successful downloads

        kotlinx.coroutines.coroutineScope {
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

            for (show in enabledShows) {
                try {
                    Log.d(TAG, "Fetching episodes for ${show.displayName}")
                    val episodes = Scraper.fetchEpisodes(show)

                    // Only consider today's episodes
                    val today = todayString()
                    val recent = episodes.filter { it.date == today }

                    for (episode in recent) {
                        // Skip if already downloaded
                        if (downloadManager.isDownloaded(episode.url)) {
                            Log.d(TAG, "Already downloaded: ${episode.title}")
                            continue
                        }

                        val job = async {
                            try {
                                Log.d(TAG, "Downloading: ${episode.title}")
                                DownloadStateTracker.addDownload(episode.url, episode.title, show.displayName)
                                
                                downloadManager.download(episode) { progress ->
                                    DownloadStateTracker.updateProgress(episode.url, progress, DownloadStatus.DOWNLOADING)
                                }
                                
                                DownloadStateTracker.updateProgress(episode.url, 1f, DownloadStatus.DONE)
                                synchronized(downloaded) {
                                    downloaded.add(show.displayName)
                                }
                                Log.d(TAG, "Done: ${episode.title}")
                                
                                kotlinx.coroutines.delay(1500)
                                DownloadStateTracker.removeDownload(episode.url)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to download ${episode.title}: ${e.message}")
                                DownloadStateTracker.updateError(episode.url, e.message)
                                kotlinx.coroutines.delay(4000)
                                DownloadStateTracker.removeDownload(episode.url)
                            }
                        }
                        jobs.add(job)
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch ${show.displayName}: ${e.message}")
                }
            }
            
            // Wait for all concurrent downloads to finish
            jobs.forEach { it.await() }
        }

        if (downloaded.isNotEmpty()) {
            NotificationHelper.notifyDownloadsComplete(applicationContext, downloaded.size, downloaded)
        }

        Log.d(TAG, "AutoDownloadWorker done — downloaded ${downloaded.size} episodes")
        return Result.success()
    }

    companion object {
        private const val TAG = "AutoDownloadWorker"
        const val WORK_NAME = "ta3_auto_download"
        const val WORK_NAME_IMMEDIATE = "ta3_auto_download_immediate"

        /**
         * Schedule (or reschedule) the periodic worker.
         * Calling this replaces any existing scheduled work so interval changes take effect immediately.
         */
        fun schedule(context: Context, intervalHours: Int = AppSettings.DEFAULT_INTERVAL_HOURS) {
            val request = PeriodicWorkRequestBuilder<AutoDownloadWorker>(
                intervalHours.toLong(), TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.d(TAG, "Scheduled periodic work every $intervalHours hour(s)")
        }

        /**
         * Run an immediate one-time check right now (called on every app open).
         * Uses KEEP policy so if it's already running from a previous open, it won't restart.
         */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AutoDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_IMMEDIATE,
                ExistingWorkPolicy.KEEP, // don't restart if already running
                request
            )
            Log.d(TAG, "Enqueued immediate download check")
        }

        fun cancel(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(WORK_NAME)
            wm.cancelUniqueWork(WORK_NAME_IMMEDIATE)
            Log.d(TAG, "Cancelled all auto-download work")
        }

        private fun todayString(): String {
            val cal = java.util.Calendar.getInstance()
            return "%04d-%02d-%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }

        private fun yesterdayString(): String {
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
            return "%04d-%02d-%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }
    }
}
