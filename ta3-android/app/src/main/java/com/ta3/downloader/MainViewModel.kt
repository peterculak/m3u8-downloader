package com.ta3.downloader

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    // STVR
    val selectedStvrShow: Show = STVR_SHOWS[0],
    val stvrEpisodes: List<Episode> = emptyList(),
    val stvrLoadingEpisodes: Boolean = false,
    val stvrEpisodeLoadError: String? = null,
    val stvrSearchQuery: String = "",
    val stvrShowEnabledMap: Map<String, Boolean> = STVR_SHOWS.associate { it.name to true },
    // Settings
    val syncIntervalHours: Int = AppSettings.DEFAULT_INTERVAL_HOURS,
    val autoDownloadEnabled: Boolean = true,
    val wifiOnlyDownload: Boolean = true,
    val autoDeleteEnabled: Boolean = true,
    val autoDeleteDays: Int = 7,
    val showEnabledMap: Map<String, Boolean> = TA3_SHOWS.associate { it.name to true },
    val prehrajEmail: String = "",
    val prehrajPassword: String = "",
    // Prehraj.to tab
    val prehrajSearchQuery: String = "",
    val prehrajSearchResults: List<PrehrajMovie> = emptyList(),
    val prehrajSearching: Boolean = false,
    val prehrajSearchError: String? = null,
    val prehrajLoginStatus: PrehrajLoginStatus = PrehrajLoginStatus.LOGGED_OUT,
    val prehrajLoginError: String? = null,
    val prehrajResolvedUrls: Map<String, String> = emptyMap(),
    // YouTube
    val selectedYtChannel: YouTubeChannel = YOUTUBE_CHANNELS[0],
    val ytEpisodes: List<Episode> = emptyList(),
    val ytLoadingEpisodes: Boolean = false,
    val ytEpisodeLoadError: String? = null,
    val ytSearchQuery: String = "",
    val ytChannelEnabledMap: Map<String, Boolean> = YOUTUBE_CHANNELS.associate { it.name to true },
    val pendingShareUrl: String? = null
)

enum class Tab { EPISODES, STVR, YOUTUBE, DOWNLOADS, SETTINGS, PREHRAJ }

enum class PrehrajLoginStatus { LOGGED_OUT, LOGGING_IN, LOGGED_IN, FAILED }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val downloadManager = DownloadManager(application)
    private val settings = AppSettings(application)
    private val workManager = WorkManager.getInstance(application)

    private val _state = MutableStateFlow(
        UiState(
            syncIntervalHours = settings.syncIntervalHours,
            autoDownloadEnabled = settings.autoDownloadEnabled,
            wifiOnlyDownload = settings.wifiOnlyDownload,
            autoDeleteEnabled = settings.autoDeleteEnabled,
            autoDeleteDays = settings.autoDeleteDays,
            showEnabledMap = TA3_SHOWS.associate { it.name to settings.isShowEnabled(it.name) },
            stvrShowEnabledMap = STVR_SHOWS.associate { it.name to settings.isShowEnabled(it.name) },
            ytChannelEnabledMap = YOUTUBE_CHANNELS.associate { it.name to settings.isShowEnabled(it.name) },
            prehrajEmail = settings.prehrajEmail,
            prehrajPassword = settings.prehrajPassword
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        loadRegistry()
        observeActiveDownloads()
        fetchEpisodes(TA3_SHOWS[0])
        fetchStvrEpisodes(STVR_SHOWS[0])
        fetchYtEpisodes(YOUTUBE_CHANNELS[0])
        // Auto-login to prehraj.to on startup if credentials are saved
        if (settings.prehrajEmail.isNotEmpty() && settings.prehrajPassword.isNotEmpty()) {
            loginPrehraj()
        }
        
        // Restore auto-download state from settings
        if (settings.autoDownloadEnabled) {
            AutoDownloadWorker.schedule(getApplication(), settings.syncIntervalHours)
        }
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
     * Enqueue TA3 episode download via WorkManager.
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

    fun clearAllDownloads() {
        viewModelScope.launch {
            downloadManager.clearAllDownloads()
            _state.update { it.copy(downloadedFiles = emptyList()) }
        }
    }

    fun cancelDownload(episodeUrl: String) {
        DownloadEpisodeWorker.cancel(getApplication(), episodeUrl)
        DownloadStateTracker.removeDownload(episodeUrl)
        viewModelScope.launch {
            downloadManager.clearPending(episodeUrl)
        }
    }

    fun promptSharedYouTubeVideo(url: String) {
        _state.update { it.copy(pendingShareUrl = url) }
    }

    fun dismissSharedShare() {
        _state.update { it.copy(pendingShareUrl = null) }
    }

    fun downloadSharedYouTubeVideo(url: String, isVideo: Boolean = false) {
        viewModelScope.launch {
            try {
                // Fetch basic metadata from the URL using NewPipeExtractor
                val episode = YouTubeScraper.resolveSharedVideoDetails(url)
                
                // Add it to our registry and trigger the worker exactly as AutoDownloader does
                downloadManager.markPending(episode)
                DownloadEpisodeWorker.enqueue(getApplication(), episode, isVideo = isVideo)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to resolve shared video: ${e.message}")
            }
        }
    }

    // ─── Prehraj.to ────────────────────────────────────────────────────────────

    fun loginPrehraj(
        email: String = _state.value.prehrajEmail,
        password: String = _state.value.prehrajPassword
    ) {
        if (email.isEmpty() || password.isEmpty()) {
            _state.update { it.copy(prehrajLoginStatus = PrehrajLoginStatus.FAILED, prehrajLoginError = "Enter email and password in Settings first") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(prehrajLoginStatus = PrehrajLoginStatus.LOGGING_IN, prehrajLoginError = null) }
            try {
                PrehrajScraper.login(email, password)
                _state.update { it.copy(prehrajLoginStatus = PrehrajLoginStatus.LOGGED_IN, prehrajLoginError = null) }
                Log.d("MainViewModel", "Prehraj.to login successful")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Prehraj.to login failed: ${e.message}")
                _state.update { it.copy(prehrajLoginStatus = PrehrajLoginStatus.FAILED, prehrajLoginError = e.message) }
            }
        }
    }

    fun setPrehrajSearchQuery(q: String) = _state.update { it.copy(prehrajSearchQuery = q) }

    fun searchPrehraj() {
        val query = _state.value.prehrajSearchQuery.trim()
        if (query.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(prehrajSearching = true, prehrajSearchError = null, prehrajSearchResults = emptyList()) }
            try {
                val results = PrehrajScraper.search(query)
                _state.update { it.copy(prehrajSearchResults = results, prehrajSearching = false) }
            } catch (e: Exception) {
                _state.update { it.copy(prehrajSearching = false, prehrajSearchError = "Search failed: ${e.message}") }
            }
        }
    }

    /**
     * Resolve the direct MP4 URL for a prehraj.to movie and store it in state.
     */
    fun extractPrehrajUrl(movie: PrehrajMovie) {
        if (_state.value.activeDownloads.containsKey(movie.pageUrl)) return
        viewModelScope.launch {
            DownloadStateTracker.addDownload(movie.pageUrl, movie.title, "prehraj")
            try {
                val directUrl = PrehrajScraper.resolveVideoUrl(movie.pageUrl)
                _state.update { it.copy(prehrajResolvedUrls = it.prehrajResolvedUrls + (movie.pageUrl to directUrl)) }
                DownloadStateTracker.removeDownload(movie.pageUrl)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to resolve prehraj video: ${e.message}")
                DownloadStateTracker.updateError(movie.pageUrl, e.message)
                kotlinx.coroutines.delay(4000)
                DownloadStateTracker.removeDownload(movie.pageUrl)
            }
        }
    }

    /**
     * Enqueue a download for a prehraj.to movie using its already resolved URL.
     */
    fun downloadPrehrajMovie(movie: PrehrajMovie, directUrl: String) {
        if (_state.value.activeDownloads.containsKey(movie.pageUrl)) return
        val episode = Episode(
            title = movie.title,
            date = movie.year,
            url = movie.pageUrl,
            showName = "prehraj"
        )
        DownloadEpisodeWorker.enqueue(getApplication(), episode, directUrl)
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

    fun setAutoDeleteEnabled(enabled: Boolean) {
        settings.autoDeleteEnabled = enabled
        _state.update { it.copy(autoDeleteEnabled = enabled) }
    }

    fun setAutoDeleteDays(days: Int) {
        settings.autoDeleteDays = days
        _state.update { it.copy(autoDeleteDays = days) }
    }

    suspend fun runManualCleanup(): Int {
        val count = downloadManager.cleanupOldDownloads(_state.value.autoDeleteDays)
        if (count > 0) {
            loadRegistry() // Refresh UI after cleanup
        }
        return count
    }

    fun setShowEnabled(showName: String, enabled: Boolean) {
        settings.setShowEnabled(showName, enabled)
        _state.update { it.copy(showEnabledMap = it.showEnabledMap + (showName to enabled)) }
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        settings.wifiOnlyDownload = wifiOnly
        _state.update { it.copy(wifiOnlyDownload = wifiOnly) }
        // Re-schedule so the new network constraint takes effect immediately
        if (settings.autoDownloadEnabled) {
            AutoDownloadWorker.schedule(getApplication(), settings.syncIntervalHours)
        }
    }

    fun setPrehrajEmail(email: String) {
        settings.prehrajEmail = email
        _state.update { it.copy(prehrajEmail = email) }
    }

    fun setPrehrajPassword(password: String) {
        settings.prehrajPassword = password
        _state.update { it.copy(prehrajPassword = password) }
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

    // ─── STVR Shows ────────────────────────────────────────────────────────────

    fun selectStvrShow(show: Show) {
        _state.update { it.copy(selectedStvrShow = show) }
        fetchStvrEpisodes(show)
    }

    fun fetchStvrEpisodes(show: Show = _state.value.selectedStvrShow) {
        viewModelScope.launch {
            _state.update { it.copy(stvrLoadingEpisodes = true, stvrEpisodeLoadError = null) }
            try {
                val episodes = StvScraper.fetchEpisodes(show, maxPages = 5)
                _state.update { it.copy(stvrEpisodes = episodes, stvrLoadingEpisodes = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        stvrLoadingEpisodes = false,
                        stvrEpisodeLoadError = "Failed to load STVR episodes: ${e.message}"
                    )
                }
            }
        }
    }

    fun startStvrDownload(episode: Episode) {
        if (_state.value.activeDownloads.containsKey(episode.url)) return
        DownloadEpisodeWorker.enqueue(getApplication(), episode)
    }

    fun setStvrSearchQuery(q: String) = _state.update { it.copy(stvrSearchQuery = q) }

    fun setStvrShowEnabled(showName: String, enabled: Boolean) {
        settings.setShowEnabled(showName, enabled)
        _state.update { it.copy(stvrShowEnabledMap = it.stvrShowEnabledMap + (showName to enabled)) }
    }

    val filteredStvrEpisodes: List<Episode>
        get() {
            val q = _state.value.stvrSearchQuery.lowercase()
            return if (q.isEmpty()) _state.value.stvrEpisodes
            else _state.value.stvrEpisodes.filter {
                it.title.lowercase().contains(q) || it.date.contains(q)
            }
        }

    // ─── YouTube Channels ──────────────────────────────────────────────────────

    fun selectYtChannel(channel: YouTubeChannel) {
        _state.update { it.copy(selectedYtChannel = channel) }
        fetchYtEpisodes(channel)
    }

    fun fetchYtEpisodes(channel: YouTubeChannel = _state.value.selectedYtChannel) {
        viewModelScope.launch {
            _state.update { it.copy(ytLoadingEpisodes = true, ytEpisodeLoadError = null) }
            try {
                val episodes = YouTubeScraper.fetchEpisodes(channel)
                _state.update { it.copy(ytEpisodes = episodes, ytLoadingEpisodes = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        ytLoadingEpisodes = false,
                        ytEpisodeLoadError = "Failed to load YouTube episodes: ${e.message}"
                    )
                }
            }
        }
    }

    fun startYtDownload(episode: Episode) {
        if (_state.value.activeDownloads.containsKey(episode.url)) return
        DownloadEpisodeWorker.enqueue(getApplication(), episode)
    }

    fun setYtSearchQuery(q: String) = _state.update { it.copy(ytSearchQuery = q) }

    fun setYtChannelEnabled(channelName: String, enabled: Boolean) {
        settings.setShowEnabled(channelName, enabled)
        _state.update { it.copy(ytChannelEnabledMap = it.ytChannelEnabledMap + (channelName to enabled)) }
    }

    val filteredYtEpisodes: List<Episode>
        get() {
            val q = _state.value.ytSearchQuery.lowercase()
            return if (q.isEmpty()) _state.value.ytEpisodes
            else _state.value.ytEpisodes.filter {
                it.title.lowercase().contains(q) || it.date.contains(q)
            }
        }
}
