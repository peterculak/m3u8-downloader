package com.ta3.downloader

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Placeholder service declared in the manifest.
 * Downloads are currently handled in coroutines within MainViewModel.
 * This can be promoted to a ForegroundService for background downloads in the future.
 */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
