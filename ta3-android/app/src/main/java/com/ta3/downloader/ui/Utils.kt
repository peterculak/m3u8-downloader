package com.ta3.downloader.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.ta3.downloader.DownloadedFile
import java.io.File

/**
 * Open a downloaded .ts file with the user's preferred media player (VLC, MX Player, etc.)
 * The file is served via FileProvider so any app can read it regardless of where it's stored.
 */
fun openFileWithPlayer(context: Context, file: DownloadedFile) {
    val f = File(file.localPath)
    if (!f.exists()) return

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        f
    )

    val intent = Intent(Intent.ACTION_VIEW).apply {
        // audio/mp4 is the correct MIME type for .m4a files
        setDataAndType(uri, "audio/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    context.startActivity(Intent.createChooser(intent, "Open with..."))
}

/** Stream a direct video URL in the phone's default video player (chooser if several). */
fun playUrlInExternalPlayer(context: Context, url: String, title: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(android.net.Uri.parse(url), "video/*")
        putExtra("title", title)
        // MX Player-style request headers; prehraj.to's CDN is fetched with this Referer by the downloader too
        putExtra("headers", arrayOf("Referer", "https://prehraj.to/", "User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Prehrať vo videoprehrávači").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Nenašiel sa žiadny prehrávač", android.widget.Toast.LENGTH_SHORT).show()
    }
}

fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576     -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024         -> "%.1f KB".format(bytes / 1_024.0)
    else                   -> "$bytes B"
}
