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

        // Honour the master auto-download toggle
        if (!settings.autoDownloadEnabled) {
            Log.d(TAG, "Auto-download disabled — skipping")
            return Result.success()
        }

        if (settings.autoDeleteEnabled) {
            try {
                downloadManager.cleanupOldDownloads(settings.autoDeleteDays)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cleanup old downloads", e)
            }
        }

        // Load tombstone of previously auto-deleted episode URLs.
        // No episode in this set will ever be automatically re-downloaded.
        val deletedUrls = try { downloadManager.loadDeletedUrls() } catch (e: Exception) { emptySet() }
        Log.d(TAG, "Tombstone: ${deletedUrls.size} previously auto-deleted URL(s) will be skipped")

        val enabledShows = settings.enabledShows()
        if (enabledShows.isEmpty()) {
            Log.d(TAG, "No shows enabled — skipping")
            return Result.success()
        }

        val downloaded = mutableListOf<String>() // show display names of successful downloads

        kotlinx.coroutines.coroutineScope {
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

            // --- Retry pending downloads ---
            val today = todayString()
            val pending = downloadManager.loadPendingDownloads()
            val pendingUrls = pending.map { it.episodeUrl }.toSet()
            for (p in pending) {
                // Stop retrying if the episode is no longer from today
                if (p.date != today) {
                    Log.w(TAG, "Dropping stale pending download (not today): ${p.title}")
                    downloadManager.clearPending(p.episodeUrl)
                    continue
                }
                
                val job = async {
                    val episode = Episode(title=p.title, date=p.date, time=p.time, url=p.episodeUrl, showName=p.showName)
                    try {
                        Log.d(TAG, "Retrying pending download: ${episode.title} (Attempt ${p.attemptCount + 1})")
                        downloadManager.markPending(episode, p.directUrl)
                        DownloadStateTracker.addDownload(episode.url, episode.title, episode.showName)
                        
                        if (p.directUrl != null) {
                            downloadManager.downloadDirectMp4(episode, p.directUrl) { progress ->
                                DownloadStateTracker.updateProgress(episode.url, progress, DownloadStatus.DOWNLOADING)
                            }
                        } else if (episode.url.contains("youtube.com") || episode.url.contains("youtu.be") || YOUTUBE_CHANNELS.any { it.name == episode.showName }) {
                            downloadManager.downloadYouTubeAudio(episode) { progress ->
                                DownloadStateTracker.updateProgress(episode.url, progress, DownloadStatus.DOWNLOADING)
                            }
                        } else {
                            downloadManager.download(episode) { progress ->
                                DownloadStateTracker.updateProgress(episode.url, progress, DownloadStatus.DOWNLOADING)
                            }
                        }
                        
                        downloadManager.markComplete(episode.url)
                        DownloadStateTracker.updateProgress(episode.url, 1f, DownloadStatus.DONE)
                        synchronized(downloaded) {
                            if (!downloaded.contains(episode.showName)) {
                                downloaded.add(episode.showName)
                            }
                        }
                        Log.d(TAG, "Done retrying: ${episode.title}")
                        
                        kotlinx.coroutines.delay(1500)
                        DownloadStateTracker.removeDownload(episode.url)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to retry download ${episode.title}: ${e.message}")
                        downloadManager.markFailed(episode.url)
                        DownloadStateTracker.updateError(episode.url, e.message)
                        kotlinx.coroutines.delay(4000)
                        DownloadStateTracker.removeDownload(episode.url)
                    }
                }
                jobs.add(job)
            }

            val isRetryOnly = inputData.getBoolean("is_retry_only", false)
            if (!isRetryOnly) {
                for (show in enabledShows) {
                    try {
                        Log.d(TAG, "Fetching episodes for ${show.displayName}")
                        val episodes = Scraper.fetchEpisodes(show)

                        // Only download today's episodes
                        val recent = episodes.filter { it.date == today }

                        for (episode in recent) {
                            // Skip if already downloaded, currently pending retry, or previously auto-deleted
                            if (downloadManager.isDownloaded(episode.url) || pendingUrls.contains(episode.url)) {
                                Log.d(TAG, "Already downloaded or pending retry: ${episode.title}")
                                continue
                            }
                            if (deletedUrls.contains(episode.url)) {
                                Log.w(TAG, "Skipping tombstoned episode (was auto-deleted): ${episode.title}")
                                continue
                            }

                            val job = async {
                                try {
                                    Log.d(TAG, "Downloading: ${episode.title}")
                                    downloadManager.markPending(episode)
                                    DownloadStateTracker.addDownload(episode.url, episode.title, show.displayName)
                                    
                                    downloadManager.download(episode) { progress ->
                                        DownloadStateTracker.updateProgress(episode.url, progress, DownloadStatus.DOWNLOADING)
                                    }
                                    
                                    downloadManager.markComplete(episode.url)
                                    DownloadStateTracker.updateProgress(episode.url, 1f, DownloadStatus.DONE)
                                    synchronized(downloaded) {
                                        if (!downloaded.contains(show.displayName)) {
                                            downloaded.add(show.displayName)
                                        }
                                    }
                                    Log.d(TAG, "Done: ${episode.title}")
                                    
                                    kotlinx.coroutines.delay(1500)
                                    DownloadStateTracker.removeDownload(episode.url)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to download ${episode.title}: ${e.message}")
                                    downloadManager.markFailed(episode.url)
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
            }

            val enabledStvrShows = settings.enabledStvrShows()
            for (show in enabledStvrShows) {
                try {
                    Log.d(TAG, "Fetching episodes for STVR show: ${show.displayName}")
                    val episodes = StvScraper.fetchEpisodes(show, maxPages = 1)

                    val minDurationSeconds = settings.getMinDurationMinutes(show.name) * 60
                    // Only download today's episodes that meet duration criteria
                    val recent = episodes.filter { it.date == today }
                        .filter {
                            if (it.durationSeconds > 0 && it.durationSeconds < minDurationSeconds) {
                                Log.i(TAG, "Skipping short STVR video (duration ${it.durationSeconds}s < ${minDurationSeconds}s): ${it.title}")
                                false
                            } else {
                                true
                            }
                        }

                    for (episode in recent) {
                        // Skip if already downloaded or currently retrying
                        if (downloadManager.isDownloaded(episode.url) || pendingUrls.contains(episode.url)) {
                            Log.d(TAG, "Already downloaded or pending retry: ${episode.title}")
                            continue
                        }
                        if (deletedUrls.contains(episode.url)) {
                            Log.w(TAG, "Skipping tombstoned STVR episode (was auto-deleted): ${episode.title}")
                            continue
                        }

                        val job = async {
                            try {
                                Log.d(TAG, "Downloading STVR: ${episode.title}")
                                downloadManager.markPending(episode)
                                DownloadStateTracker.addDownload(episode.url, episode.title, show.displayName)

                                downloadManager.download(episode) { progress ->
                                    DownloadStateTracker.updateProgress(episode.url, progress, DownloadStatus.DOWNLOADING)
                                }

                                downloadManager.markComplete(episode.url)
                                DownloadStateTracker.updateProgress(episode.url, 1f, DownloadStatus.DONE)
                                synchronized(downloaded) {
                                    if (!downloaded.contains(show.displayName)) {
                                        downloaded.add(show.displayName)
                                    }
                                }
                                Log.d(TAG, "Done STVR: ${episode.title}")

                                kotlinx.coroutines.delay(1500)
                                DownloadStateTracker.removeDownload(episode.url)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to download STVR ${episode.title}: ${e.message}")
                                downloadManager.markFailed(episode.url)
                                DownloadStateTracker.updateError(episode.url, e.message)
                                kotlinx.coroutines.delay(4000)
                                DownloadStateTracker.removeDownload(episode.url)
                            }
                        }
                        jobs.add(job)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch STVR ${show.displayName}: ${e.message}")
                }
            }

            // --- YouTube channels ---
            val enabledYtChannels = settings.enabledYouTubeChannels()
            for (channel in enabledYtChannels) {
                try {
                    Log.d(TAG, "Fetching episodes for YouTube channel: ${channel.displayName}")
                    val episodes = YouTubeScraper.fetchEpisodes(channel)

                    val minDurationSeconds = settings.getMinDurationMinutes(channel.name) * 60
                    // Only download today's episodes that meet duration criteria
                    val recent = episodes.filter { it.date == today }
                        .filter {
                            if (it.durationSeconds > 0 && it.durationSeconds < minDurationSeconds) {
                                Log.i(TAG, "Skipping short YouTube video (duration ${it.durationSeconds}s < ${minDurationSeconds}s): ${it.title}")
                                false
                            } else {
                                true
                            }
                        }

                    for (episode in recent) {
                        if (downloadManager.isDownloaded(episode.url) || pendingUrls.contains(episode.url)) {
                            Log.d(TAG, "Already downloaded or pending retry: ${episode.title}")
                            continue
                        }
                        if (deletedUrls.contains(episode.url)) {
                            Log.w(TAG, "Skipping tombstoned YouTube episode (was auto-deleted): ${episode.title}")
                            continue
                        }

                        val job = async {
                            try {
                                Log.d(TAG, "Downloading YouTube: ${episode.title}")
                                downloadManager.markPending(episode)
                                DownloadStateTracker.addDownload(episode.url, episode.title, channel.displayName)

                                downloadManager.downloadYouTubeAudio(episode) { progress ->
                                    DownloadStateTracker.updateProgress(episode.url, progress, DownloadStatus.DOWNLOADING)
                                }

                                downloadManager.markComplete(episode.url)
                                DownloadStateTracker.updateProgress(episode.url, 1f, DownloadStatus.DONE)
                                synchronized(downloaded) {
                                    if (!downloaded.contains(channel.displayName)) {
                                        downloaded.add(channel.displayName)
                                    }
                                }
                                Log.d(TAG, "Done YouTube: ${episode.title}")

                                kotlinx.coroutines.delay(1500)
                                DownloadStateTracker.removeDownload(episode.url)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to download YouTube ${episode.title}: ${e.message}")
                                downloadManager.markFailed(episode.url)
                                DownloadStateTracker.updateError(episode.url, e.message)
                                kotlinx.coroutines.delay(4000)
                                DownloadStateTracker.removeDownload(episode.url)
                            }
                        }
                        jobs.add(job)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch YouTube ${channel.displayName}: ${e.message}")
                }
            }

            // Wait for all concurrent downloads to finish
            jobs.forEach { it.await() }
        }

        // If any of today's episodes are still pending (failed this run), schedule a
        // WiFi-triggered retry. WorkManager will fire it automatically when WiFi reconnects,
        // even if the app is not running.
        val stillPending = downloadManager.loadPendingDownloads().any { it.date == todayString() }
        if (stillPending) {
            Log.d(TAG, "Some downloads still pending — scheduling WiFi retry")
            scheduleWifiRetry(applicationContext)
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
        const val WORK_NAME_RETRY = "ta3_retry_pending"

        /**
         * Schedule a one-time retry that fires automatically when WiFi reconnects.
         * Uses KEEP policy — if a retry is already queued, leave it alone.
         * Has a 5-minute initial delay to avoid hammering a flaky connection.
         */
        fun scheduleWifiRetry(context: Context) {
            val wifiOnly = AppSettings(context).wifiOnlyDownload
            val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = OneTimeWorkRequestBuilder<AutoDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(networkType)
                        .build()
                )
                .setInputData(workDataOf("is_retry_only" to true))
                .setInitialDelay(5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_RETRY,
                ExistingWorkPolicy.KEEP, // Don't reset delay if one is already queued
                request
            )
            Log.d(TAG, "WiFi retry scheduled (fires 5 min after WiFi reconnects), wifiOnly=$wifiOnly")
        }

        /**
         * Schedule (or reschedule) the periodic worker.
         * Calling this replaces any existing scheduled work so interval changes take effect immediately.
         */
        fun schedule(context: Context, intervalHours: Int = AppSettings.DEFAULT_INTERVAL_HOURS) {
            val wifiOnly = AppSettings(context).wifiOnlyDownload
            val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = PeriodicWorkRequestBuilder<AutoDownloadWorker>(
                intervalHours.toLong(), TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(networkType)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.d(TAG, "Scheduled periodic work every $intervalHours hour(s), wifiOnly=$wifiOnly")
        }

        /**
         * Run an immediate one-time check right now (called on every app open).
         * Uses KEEP policy so if it's already running from a previous open, it won't restart.
         */
        fun runNow(context: Context) {
            val wifiOnly = AppSettings(context).wifiOnlyDownload
            val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = OneTimeWorkRequestBuilder<AutoDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(networkType)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_IMMEDIATE,
                ExistingWorkPolicy.REPLACE, // always re-run so new episodes are caught on every app open
                request
            )
            Log.d(TAG, "Enqueued immediate download check, wifiOnly=$wifiOnly")
        }

        fun cancel(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(WORK_NAME)
            wm.cancelUniqueWork(WORK_NAME_IMMEDIATE)
            wm.cancelUniqueWork(WORK_NAME_RETRY)
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
    }
}
