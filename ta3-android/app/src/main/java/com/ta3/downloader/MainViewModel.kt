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
    // Settings
    val syncIntervalHours: Int = AppSettings.DEFAULT_INTERVAL_HOURS,
    val autoDownloadEnabled: Boolean = true,
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
    val prehrajResolvedUrls: Map<String, String> = emptyMap()
)

enum class Tab { EPISODES, DOWNLOADS, SETTINGS, PREHRAJ }

enum class PrehrajLoginStatus { LOGGED_OUT, LOGGING_IN, LOGGED_IN, FAILED }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val downloadManager = DownloadManager(application)
    private val settings = AppSettings(application)
    private val workManager = WorkManager.getInstance(application)

    private val _state = MutableStateFlow(
        UiState(
            syncIntervalHours = settings.syncIntervalHours,
            autoDownloadEnabled = settings.autoDownloadEnabled,
            showEnabledMap = TA3_SHOWS.associate { it.name to settings.isShowEnabled(it.name) },
            prehrajEmail = settings.prehrajEmail,
            prehrajPassword = settings.prehrajPassword
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        loadRegistry()
        observeActiveDownloads()
        fetchEpisodes(TA3_SHOWS[0])
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

    fun cancelPrehrajDownload(episodeUrl: String) {
        DownloadEpisodeWorker.cancel(getApplication(), episodeUrl)
        DownloadStateTracker.removeDownload(episodeUrl)
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

    fun setShowEnabled(showName: String, enabled: Boolean) {
        settings.setShowEnabled(showName, enabled)
        _state.update { it.copy(showEnabledMap = it.showEnabledMap + (showName to enabled)) }
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
}
