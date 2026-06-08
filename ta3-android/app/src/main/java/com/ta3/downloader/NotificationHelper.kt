package com.ta3.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {

    private const val CHANNEL_ID = "ta3_downloads"
    private const val CHANNEL_NAME = "TA3 Downloads"
    private const val NOTIF_ID_DOWNLOAD_DONE = 1001

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifies when new TA3 episodes are downloaded"
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    fun notifyDownloadsComplete(context: Context, count: Int, showNames: List<String>) {
        val title = if (count == 1) "TA3: 1 new episode downloaded"
                    else "TA3: $count new episodes downloaded"
        val body = showNames.distinct().joinToString(", ")

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_DOWNLOAD_DONE, notif)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — silently skip
        }
    }

    fun createForegroundInfo(context: Context, title: String): androidx.work.ForegroundInfo {
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOngoing(true)
            .build()
        
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            androidx.work.ForegroundInfo(1002, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            androidx.work.ForegroundInfo(1002, notif)
        }
    }
}
