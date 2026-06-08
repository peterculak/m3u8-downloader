package com.ta3.downloader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Singleton to track running downloads across WorkManager workers and the UI.
 * Since WorkManager in this app runs in the default app process, workers
 * can directly update this singleton and the UI will reflect the changes instantly.
 */
object DownloadStateTracker {
    private val _activeDownloads = MutableStateFlow<Map<String, ActiveDownload>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, ActiveDownload>> = _activeDownloads.asStateFlow()

    fun addDownload(episodeUrl: String, title: String, showName: String) {
        _activeDownloads.update { current ->
            if (current.containsKey(episodeUrl)) return@update current
            current + (episodeUrl to ActiveDownload(episodeUrl, title, showName, 0f, DownloadStatus.RESOLVING))
        }
    }

    fun updateProgress(episodeUrl: String, progress: Float, status: DownloadStatus = DownloadStatus.DOWNLOADING) {
        _activeDownloads.update { current ->
            val updated = current[episodeUrl]?.copy(progress = progress, status = status) ?: return@update current
            current + (episodeUrl to updated)
        }
    }

    fun updateError(episodeUrl: String, errorMessage: String?) {
        _activeDownloads.update { current ->
            val failed = current[episodeUrl]?.copy(status = DownloadStatus.FAILED, errorMessage = errorMessage) ?: return@update current
            current + (episodeUrl to failed)
        }
    }

    fun removeDownload(episodeUrl: String) {
        _activeDownloads.update { it - episodeUrl }
    }
}
