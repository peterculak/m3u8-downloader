package com.ta3.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.ta3.downloader.ui.DownloaderApp
import com.ta3.downloader.ui.theme.TA3Theme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — worker still runs, just won't notify */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Set up notification channel (safe to call multiple times)
        NotificationHelper.createChannel(this)

        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Schedule periodic background worker
        val settings = AppSettings(this)
        if (settings.autoDownloadEnabled) {
            AutoDownloadWorker.schedule(this, settings.syncIntervalHours)
        }

        // Only trigger auto-download check on a fresh cold-start from the launcher, 
        // not when rotating the screen or receiving a shared video from YouTube
        if (savedInstanceState == null && intent?.action == Intent.ACTION_MAIN) {
            AutoDownloadWorker.runNow(this)
        }

        handleIntent(intent)

        setContent {
            TA3Theme {
                DownloaderApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            
            // Extract the YouTube URL using regex
            val regex = Regex("""(https?://(?:www\.)?(?:youtube\.com/watch\?v=|youtu\.be/)[a-zA-Z0-9_-]+)""")
            val match = regex.find(sharedText)
            
            val channelRegex = Regex("""(https?://(?:www\.)?youtube\.com/(?:@|channel/)[a-zA-Z0-9_-]+)""")
            val channelMatch = channelRegex.find(sharedText)
            
            if (match != null) {
                val url = match.value
                viewModel.promptSharedYouTubeVideo(url)
            } else if (channelMatch != null) {
                var url = channelMatch.value
                if (!url.endsWith("/videos") && !url.endsWith("/streams")) {
                    url += "/videos"
                }
                
                val nameMatch = Regex("""^(.*?)\s*-?\s*YouTube\s*https?://""").find(sharedText)
                var name = nameMatch?.groupValues?.get(1)?.trim() ?: ""
                
                if (name.isEmpty()) {
                    name = Regex("""@([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: "Neznámy kanál"
                }
                
                viewModel.setPendingSharedChannel(url, name)
            }
        }
    }
}
