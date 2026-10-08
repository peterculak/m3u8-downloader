package com.ta3.downloader

import android.app.Application

class TA3App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppLogger.isEnabled = AppSettings(this).loggingEnabled
        AppLogger.i("TA3App", "Process started pid=${android.os.Process.myPid()}")
        NetworkLogger.start(this)
    }
}
