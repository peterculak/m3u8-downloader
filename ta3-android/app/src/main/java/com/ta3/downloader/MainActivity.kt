package com.ta3.downloader

import android.Manifest
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

        // Always trigger an immediate check on open (today's episodes)
        AutoDownloadWorker.runNow(this)

        setContent {
            TA3Theme {
                DownloaderApp(viewModel = viewModel)
            }
        }
    }
}
