package com.ta3.downloader

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Deferred
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        val startMs = System.currentTimeMillis()
        AppLogger.init(applicationContext)
        AppLogger.isEnabled = settings.loggingEnabled
        AppLogger.i(TAG, "Worker START id=$id tags=$tags attempt=$runAttemptCount retryOnly=${inputData.getBoolean("is_retry_only", false)} network=${NetworkLogger.describe(applicationContext)}")
        try {
            if (runLock.isLocked) AppLogger.i(TAG, "Worker id=$id waiting for another run to finish")
            return runLock.withLock { doWorkInner() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            val reason = if (android.os.Build.VERSION.SDK_INT >= 31) " reason=$stopReason" else ""
            val inFlight = try { downloadManager.loadPendingDownloads().map { it.title } } catch (_: Exception) { emptyList() }
            AppLogger.w(TAG, "Worker STOPPED/cancelled (id=$id)$reason, ${inFlight.size} pending: $inFlight")
            // Killed mid-run: make sure the leftovers get retried without waiting for an app open.
            val cancelledByApp = android.os.Build.VERSION.SDK_INT >= 31 && stopReason == 1 // STOP_REASON_CANCELLED_BY_APP
            if (!cancelledByApp && inFlight.isNotEmpty()) scheduleWifiRetry(applicationContext)
            throw e
        } finally {
            AppLogger.i(TAG, "Worker END id=$id after ${(System.currentTimeMillis() - startMs) / 1000}s")
        }
    }

    private suspend fun doWorkInner(): Result {
        
        try {
            NotificationHelper.createChannel(applicationContext)
            setForeground(NotificationHelper.createForegroundInfo(applicationContext, "TA3: Checking for new episodes..."))
        } catch (e: Exception) {
            // Ignore if foreground service fails
        }

        // Honour the master auto-download toggle
        if (!settings.autoDownloadEnabled) {
            AppLogger.d(TAG, "Auto-download disabled — skipping")
            return Result.success()
        }

        if (settings.autoDeleteEnabled) {
            try {
                downloadManager.cleanupOldDownloads(settings.autoDeleteDays)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to cleanup old downloads", e)
            }
        }

        // Load tombstone of previously auto-deleted episode URLs.
        // No episode in this set will ever be automatically re-downloaded.
        val deletedUrls = try { downloadManager.loadDeletedUrls() } catch (e: Exception) { emptySet() }
        AppLogger.d(TAG, "Tombstone: ${deletedUrls.size} previously auto-deleted URL(s) will be skipped")

        val enabledShows = settings.enabledShows()
        if (enabledShows.isEmpty()) {
            AppLogger.d(TAG, "No shows enabled — skipping")
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
                    AppLogger.w(TAG, "Dropping stale pending download (not today): ${p.title}")
                    downloadManager.clearPending(p.episodeUrl)
                    continue
                }
                
                val job = async {
                    val episode = Episode(title=p.title, date=p.date, time=p.time, url=p.episodeUrl, showName=p.showName)
                    try {
                        AppLogger.d(TAG, "Retrying pending download: ${episode.title} (Attempt ${p.attemptCount + 1})")
                        downloadManager.markPending(episode, p.directUrl)
                        DownloadStateTracker.addDownload(episode.url, episode.title, episode.showName)
                        
                        if (p.directUrl != null) {
                            downloadManager.downloadDirectMp4(episode, p.directUrl) { progress ->
                                DownloadStateTracker.updateProgress(episode.url, progress, DownloadStatus.DOWNLOADING)
                            }
                        } else if (episode.url.contains("youtube.com") || episode.url.contains("youtu.be") || CustomChannelManager.getAllYouTubeChannels().any { it.name == episode.showName }) {
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
                        AppLogger.d(TAG, "Done retrying: ${episode.title}")
                        
                        kotlinx.coroutines.delay(1500)
                        DownloadStateTracker.removeDownload(episode.url)
                    } catch (e: NoVideoException) {
                        AppLogger.i(TAG, "No video on page yet, dropping retry: ${episode.title} (${e.pageUrl})")
                        downloadManager.clearPending(episode.url)
                        DownloadStateTracker.removeDownload(episode.url)
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to retry download ${episode.title}: ${e.message}")
                        // Members-only videos can never succeed; stop the 5-minute retry loop.
                        if (e.message?.contains("only available for members") == true) downloadManager.clearPending(episode.url)
                        else downloadManager.markFailed(episode.url)
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
                        AppLogger.d(TAG, "Fetching episodes for ${show.displayName}")
                        val episodes = Scraper.fetchEpisodes(show)
                        AppLogger.d(TAG, "${show.displayName}: ${episodes.size} episodes fetched, ${episodes.count { it.date == today }} from today")

                        // Only download today's episodes
                        val recent = episodes.filter { it.date == today }

                        for (episode in recent) {
                            // Skip if already downloaded, currently pending retry, or previously auto-deleted
                            if (downloadManager.isDownloaded(episode.url) || pendingUrls.contains(episode.url)) {
                                AppLogger.d(TAG, "Already downloaded or pending retry: ${episode.title}")
                                continue
                            }
                            if (deletedUrls.contains(episode.url)) {
                                AppLogger.w(TAG, "Skipping tombstoned episode (was auto-deleted): ${episode.title}")
                                continue
                            }

                            val job = async {
                                try {
                                    AppLogger.d(TAG, "Downloading: ${episode.title}")
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
                                    AppLogger.d(TAG, "Done: ${episode.title}")
                                    
                                    kotlinx.coroutines.delay(1500)
                                    DownloadStateTracker.removeDownload(episode.url)
                                } catch (e: NoVideoException) {
                                    AppLogger.i(TAG, "No video on page yet, skipping (will recheck next run): ${episode.title} (${e.pageUrl})")
                                    downloadManager.clearPending(episode.url)
                                    DownloadStateTracker.removeDownload(episode.url)
                                } catch (e: Exception) {
                                    AppLogger.e(TAG, "Failed to download ${episode.title}: ${e.message}")
                                    downloadManager.markFailed(episode.url)
                                    DownloadStateTracker.updateError(episode.url, e.message)
                                    kotlinx.coroutines.delay(4000)
                                    DownloadStateTracker.removeDownload(episode.url)
                                }
                            }
                            jobs.add(job)
                        }

                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to fetch ${show.displayName}: ${e.message}")
                    }
                }
            }

            val enabledStvrShows = settings.enabledStvrShows()
            for (show in enabledStvrShows) {
                try {
                    AppLogger.d(TAG, "Fetching episodes for STVR show: ${show.displayName}")
                    val episodes = StvScraper.fetchEpisodes(show, maxPages = 1)

                    val minDurationSeconds = settings.getMinDurationMinutes(show.name) * 60
                    // Only download today's episodes that meet duration criteria
                    val recent = episodes.filter { it.date == today }
                        .filter {
                            if (it.durationSeconds > 0 && it.durationSeconds < minDurationSeconds) {
                                AppLogger.i(TAG, "Skipping short STVR video (duration ${it.durationSeconds}s < ${minDurationSeconds}s): ${it.title}")
                                false
                            } else {
                                true
                            }
                        }

                    for (episode in recent) {
                        // Skip if already downloaded or currently retrying
                        if (downloadManager.isDownloaded(episode.url) || pendingUrls.contains(episode.url)) {
                            AppLogger.d(TAG, "Already downloaded or pending retry: ${episode.title}")
                            continue
                        }
                        if (deletedUrls.contains(episode.url)) {
                            AppLogger.w(TAG, "Skipping tombstoned STVR episode (was auto-deleted): ${episode.title}")
                            continue
                        }

                        val job = async {
                            try {
                                AppLogger.d(TAG, "Downloading STVR: ${episode.title}")
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
                                AppLogger.d(TAG, "Done STVR: ${episode.title}")

                                kotlinx.coroutines.delay(1500)
                                DownloadStateTracker.removeDownload(episode.url)
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "Failed to download STVR ${episode.title}: ${e.message}")
                                downloadManager.markFailed(episode.url)
                                DownloadStateTracker.updateError(episode.url, e.message)
                                kotlinx.coroutines.delay(4000)
                                DownloadStateTracker.removeDownload(episode.url)
                            }
                        }
                        jobs.add(job)
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to fetch STVR ${show.displayName}: ${e.message}")
                }
            }
            
            // --- Tyzden shows ---
            val enabledTyzdenShows = settings.enabledTyzdenShows()
            for (show in enabledTyzdenShows) {
                try {
                    AppLogger.d(TAG, "Fetching episodes for .týždeň show: ${show.displayName}")
                    val episodes = TyzdenScraper.fetchEpisodes(show, maxPages = 1)
                    val recent = episodes.filter { it.date == today }

                    for (episode in recent) {
                        // Skip if already downloaded or currently retrying
                        if (downloadManager.isDownloaded(episode.url) || pendingUrls.contains(episode.url)) {
                            AppLogger.d(TAG, "Already downloaded or pending retry: ${episode.title}")
                            continue
                        }
                        if (deletedUrls.contains(episode.url)) {
                            AppLogger.w(TAG, "Skipping tombstoned .týždeň episode (was auto-deleted): ${episode.title}")
                            continue
                        }

                        val job = async {
                            try {
                                AppLogger.d(TAG, "Downloading .týždeň: ${episode.title}")
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
                                AppLogger.d(TAG, "Done .týždeň: ${episode.title}")

                                kotlinx.coroutines.delay(1500)
                                DownloadStateTracker.removeDownload(episode.url)
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "Failed to download .týždeň ${episode.title}: ${e.message}")
                                downloadManager.markFailed(episode.url)
                                DownloadStateTracker.updateError(episode.url, e.message)
                                kotlinx.coroutines.delay(4000)
                                DownloadStateTracker.removeDownload(episode.url)
                            }
                        }
                        jobs.add(job)
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to fetch .týždeň ${show.displayName}: ${e.message}")
                }
            }

            // --- YouTube channels ---
            val enabledYtChannels = settings.enabledYouTubeChannels()
            for (channel in enabledYtChannels) {
                try {
                    AppLogger.d(TAG, "Fetching episodes for YouTube channel: ${channel.displayName}")
                    val episodes = YouTubeScraper.fetchEpisodes(channel)

                    val minDurationSeconds = settings.getMinDurationMinutes(channel.name) * 60
                    // Only download today's episodes that meet duration criteria
                    val recent = episodes.filter { it.date == today }
                        .filter {
                            if (it.durationSeconds > 0 && it.durationSeconds < minDurationSeconds) {
                                AppLogger.i(TAG, "Skipping short YouTube video (duration ${it.durationSeconds}s < ${minDurationSeconds}s): ${it.title}")
                                false
                            } else {
                                true
                            }
                        }

                    for (episode in recent) {
                        if (downloadManager.isDownloaded(episode.url) || pendingUrls.contains(episode.url)) {
                            AppLogger.d(TAG, "Already downloaded or pending retry: ${episode.title}")
                            continue
                        }
                        if (deletedUrls.contains(episode.url)) {
                            AppLogger.w(TAG, "Skipping tombstoned YouTube episode (was auto-deleted): ${episode.title}")
                            continue
                        }

                        val job = async {
                            try {
                                AppLogger.d(TAG, "Downloading YouTube: ${episode.title}")
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
                                AppLogger.d(TAG, "Done YouTube: ${episode.title}")

                                kotlinx.coroutines.delay(1500)
                                DownloadStateTracker.removeDownload(episode.url)
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "Failed to download YouTube ${episode.title}: ${e.message}")
                                downloadManager.markFailed(episode.url)
                                DownloadStateTracker.updateError(episode.url, e.message)
                                kotlinx.coroutines.delay(4000)
                                DownloadStateTracker.removeDownload(episode.url)
                            }
                        }
                        jobs.add(job)
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to fetch YouTube ${channel.displayName}: ${e.message}")
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
            AppLogger.d(TAG, "Some downloads still pending — scheduling WiFi retry")
            scheduleWifiRetry(applicationContext)
        }

        if (downloaded.isNotEmpty()) {
            NotificationHelper.notifyDownloadsComplete(applicationContext, downloaded.size, downloaded)
        }

        AppLogger.i(TAG, "AutoDownloadWorker done — downloaded ${downloaded.size} episodes")
        return Result.success()
    }

    companion object {
        private const val TAG = "AutoDownloadWorker"
        /** Only one check/download run at a time, so concurrent workers never fetch the same episode twice. */
        private val runLock = Mutex()
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
            AppLogger.d(TAG, "WiFi retry scheduled (fires 5 min after WiFi reconnects), wifiOnly=$wifiOnly")
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

            // Re-scheduling with UPDATE on every app open resets the period and can cancel a run
            // in progress, so only update when interval / network constraint actually changed.
            val prefs = context.getSharedPreferences("ta3_settings", Context.MODE_PRIVATE)
            val config = "$intervalHours/$wifiOnly"
            val changed = prefs.getString("periodic_config", null) != config
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                if (changed) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            if (changed) prefs.edit().putString("periodic_config", config).apply()
            AppLogger.d(TAG, "Periodic work every $intervalHours hour(s), wifiOnly=$wifiOnly (${if (changed) "updated" else "kept existing schedule"})")
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
                ExistingWorkPolicy.KEEP, // don't cancel a check/download already in progress
                request
            )
            AppLogger.d(TAG, "Enqueued immediate download check, wifiOnly=$wifiOnly")
        }

        fun cancel(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(WORK_NAME)
            wm.cancelUniqueWork(WORK_NAME_IMMEDIATE)
            wm.cancelUniqueWork(WORK_NAME_RETRY)
            AppLogger.d(TAG, "Cancelled all auto-download work")
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
