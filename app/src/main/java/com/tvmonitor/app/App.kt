package com.tvmonitor.app

import android.app.Application
import com.tvmonitor.app.util.NotificationHelper

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }
}
