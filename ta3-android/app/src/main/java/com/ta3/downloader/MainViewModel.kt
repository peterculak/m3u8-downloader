package com.ta3.downloader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val selectedShow: Show = TA3_SHOWS[0],
    val episodes: List<Episode> = emptyList(),
    val downloadedFiles: List<DownloadedFile> = emptyList(),
    val activeDownloads: Map<String, ActiveDownload> = emptyMap(),
    val loadingEpisodes: Boolean = false,
    val episodeLoadError: String? = null,
    val searchQuery: String = "",
    val selectedTab: Tab = Tab.EPISODES,
    // Settings
    val syncIntervalHours: Int = AppSettings.DEFAULT_INTERVAL_HOURS,
    val autoDownloadEnabled: Boolean = true,
    val showEnabledMap: Map<String, Boolean> = TA3_SHOWS.associate { it.name to true }
)

enum class Tab { EPISODES, DOWNLOADS, SETTINGS }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val downloadManager = DownloadManager(application)
    private val settings = AppSettings(application)
    private val workManager = WorkManager.getInstance(application)

    private val _state = MutableStateFlow(
        UiState(
            syncIntervalHours = settings.syncIntervalHours,
            autoDownloadEnabled = settings.autoDownloadEnabled,
            showEnabledMap = TA3_SHOWS.associate { it.name to settings.isShowEnabled(it.name) }
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        loadRegistry()
        observeActiveDownloads()
        fetchEpisodes(TA3_SHOWS[0])
    }

    // ─── Shows ─────────────────────────────────────────────────────────────────

    fun selectShow(show: Show) {
        _state.update { it.copy(selectedShow = show) }
        fetchEpisodes(show)
    }

    // ─── Episode Fetching ──────────────────────────────────────────────────────

    fun fetchEpisodes(show: Show = _state.value.selectedShow) {
        viewModelScope.launch {
            _state.update { it.copy(loadingEpisodes = true, episodeLoadError = null) }
            try {
                val episodes = Scraper.fetchEpisodes(show)
                _state.update { it.copy(episodes = episodes, loadingEpisodes = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loadingEpisodes = false,
                        episodeLoadError = "Failed to load episodes: ${e.message}"
                    )
                }
            }
        }
    }

    // ─── Downloads ─────────────────────────────────────────────────────────────

    private fun loadRegistry() {
        viewModelScope.launch {
            val files = downloadManager.loadRegistry()
            _state.update { it.copy(downloadedFiles = files) }
        }
    }

    private fun observeActiveDownloads() {
        viewModelScope.launch {
            DownloadStateTracker.activeDownloads.collect { activeMap ->
                _state.update { state -> 
                    // When a download finishes, we should reload the registry
                    val anyJustFinished = activeMap.values.any { it.status == DownloadStatus.DONE }
                    if (anyJustFinished) {
                        loadRegistry()
                    }
                    state.copy(activeDownloads = activeMap) 
                }
            }
        }
    }

    /**
     * Enqueue episode download via WorkManager (survives backgrounding/process death).
     */
    fun startDownload(episode: Episode) {
        if (_state.value.activeDownloads.containsKey(episode.url)) return
        DownloadEpisodeWorker.enqueue(getApplication(), episode)
    }

    fun deleteDownload(episodeUrl: String) {
        viewModelScope.launch {
            downloadManager.deleteDownload(episodeUrl)
            _state.update { it.copy(downloadedFiles = it.downloadedFiles.filter { f -> f.episodeUrl != episodeUrl }) }
        }
    }

    // ─── Settings ──────────────────────────────────────────────────────────────

    fun setSyncInterval(hours: Int) {
        settings.syncIntervalHours = hours
        _state.update { it.copy(syncIntervalHours = hours) }
        if (_state.value.autoDownloadEnabled) {
            AutoDownloadWorker.schedule(getApplication(), hours)
        }
    }

    fun setAutoDownloadEnabled(enabled: Boolean) {
        settings.autoDownloadEnabled = enabled
        _state.update { it.copy(autoDownloadEnabled = enabled) }
        if (enabled) AutoDownloadWorker.schedule(getApplication(), settings.syncIntervalHours)
        else AutoDownloadWorker.cancel(getApplication())
    }

    fun setShowEnabled(showName: String, enabled: Boolean) {
        settings.setShowEnabled(showName, enabled)
        _state.update { it.copy(showEnabledMap = it.showEnabledMap + (showName to enabled)) }
    }

    // ─── UI helpers ────────────────────────────────────────────────────────────

    fun setSearchQuery(q: String) = _state.update { it.copy(searchQuery = q) }
    fun selectTab(tab: Tab) = _state.update { it.copy(selectedTab = tab) }

    val filteredEpisodes: List<Episode>
        get() {
            val q = _state.value.searchQuery.lowercase()
            return if (q.isEmpty()) _state.value.episodes
            else _state.value.episodes.filter {
                it.title.lowercase().contains(q) || it.date.contains(q)
            }
        }
}
