package com.tvmonitor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tvmonitor.app.service.MonitorService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("tvmonitor", Context.MODE_PRIVATE)
            if (prefs.getBoolean("auto_start", false)) {
                MonitorService.start(context)
            }
        }
    }
}
