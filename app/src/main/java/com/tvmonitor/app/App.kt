package com.tvmonitor.app

import android.app.Application
import android.content.Context
import com.tvmonitor.app.util.NotificationHelper
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {

    companion object {
        private const val CRASH_FILE = "last-crash.txt"

        /** The last crash, or null. Read and shown by MainActivity, then cleared. */
        fun lastCrash(context: Context): String? {
            val f = File(context.filesDir, CRASH_FILE)
            return if (f.exists()) f.readText() else null
        }

        fun clearCrash(context: Context) {
            File(context.filesDir, CRASH_FILE).delete()
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        installCrashRecorder()
    }

    /**
     * Writes the stack trace of any uncaught exception to a file, then lets the
     * default handler carry on and kill the process as usual.
     *
     * Android's own response to a crash inside a WebView is to offer to uninstall
     * WebView updates, which is a system-wide change affecting every app on the
     * phone and fixes nothing when the fault is in this code. It also says
     * nothing about what actually went wrong. With no device to attach a debugger
     * to, a stack trace the user can read off the screen is the difference
     * between fixing the bug and guessing at it.
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date())
                File(filesDir, CRASH_FILE).writeText(
                    "$when_\nthread: ${thread.name}\n\n$sw"
                )
            } catch (ignored: Throwable) {
                // Recording the crash must never itself crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
