package com.ta3.downloader

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    
    var isEnabled: Boolean = false

    fun init(context: Context) {
        logFile = File(context.getExternalFilesDir(null), "app_logs.txt")
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        writeToFile("DEBUG", tag, message, null)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        writeToFile("INFO", tag, message, null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        writeToFile("WARN", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        writeToFile("ERROR", tag, message, throwable)
    }

    private fun writeToFile(level: String, tag: String, message: String, throwable: Throwable?) {
        if (!isEnabled) return
        val file = logFile ?: return
        try {
            FileWriter(file, true).use { writer ->
                val time = dateFormat.format(Date())
                writer.append("[$time] $level/$tag: $message\n")
                throwable?.let {
                    writer.append(Log.getStackTraceString(it))
                    writer.append("\n")
                }
            }
        } catch (e: IOException) {
            Log.e("AppLogger", "Failed to write to log file", e)
        }
    }

    fun getLogFile(): File? {
        return logFile
    }

    fun clearLogs() {
        logFile?.let {
            if (it.exists()) {
                it.delete()
            }
            it.createNewFile()
        }
    }
}
