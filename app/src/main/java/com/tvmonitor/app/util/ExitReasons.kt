package com.tvmonitor.app.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why the previous run of this app ended, as recorded by the system.
 *
 * The uncaught-exception handler in [com.tvmonitor.app.App] only sees failures
 * that throw. It stayed empty through every occurrence of the app closing, and
 * an empty recorder was read as "no crash happened" when it actually means the
 * process died in a way no Java handler can observe: a native crash inside
 * WebView, the low-memory killer, or an ANR. Those three are indistinguishable
 * from the outside, and Android reports all of them the same way - by offering
 * to uninstall WebView updates.
 *
 * The system keeps its own record and will simply say which it was. That turns
 * the question from a guess into a reading, without a cable or a debugger.
 */
object ExitReasons {

    private const val PREFS = "tvmonitor"
    private const val LAST_SHOWN = "last_exit_shown"

    /**
     * A description of the last abnormal exit that has not been reported yet,
     * or null. Reporting one marks it seen, so it is shown once.
     *
     * Ordinary endings are not abnormal and are skipped: the trader swiping the
     * app away, the system trimming it while nothing was running, an install.
     */
    fun unreportedExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val exit = try {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
        } catch (e: Exception) {
            null
        } ?: return null

        if (!isAbnormal(exit.reason)) return null

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(LAST_SHOWN, 0L) == exit.timestamp) return null
        prefs.edit().putLong(LAST_SHOWN, exit.timestamp).apply()

        return describe(exit)
    }

    /**
     * REASON_EXIT_SELF, REASON_USER_REQUESTED and the package-change reasons are
     * the app being closed or replaced, which is not a fault worth a dialog.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun isAbnormal(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> true
        else -> false
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describe(exit: ApplicationExitInfo): String {
        val when_ = SimpleDateFormat("dd MMM HH:mm:ss", Locale.UK)
            .format(Date(exit.timestamp))

        val lines = StringBuilder()
        lines.append(name(exit.reason)).append('\n')
        lines.append(when_).append('\n')
        lines.append(meaning(exit.reason)).append('\n')

        // Whether it died in the foreground or while sitting in a pocket. A
        // background death points at the system reclaiming memory; a foreground
        // one points at this code.
        lines.append("\nstate: ").append(importance(exit.importance)).append('\n')
        lines.append("memory in use: ").append(exit.pss / 1024).append(" MB\n")

        exit.description?.takeIf { it.isNotBlank() }?.let {
            lines.append("system note: ").append(it).append('\n')
        }

        // Present for ANRs, and for native crashes on Android 12 and later.
        // This is the part that names the actual fault.
        trace(exit)?.let { lines.append('\n').append(it) }

        return lines.toString()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun trace(exit: ApplicationExitInfo): String? = try {
        exit.traceInputStream?.bufferedReader()?.use { it.readText().take(4000) }
    } catch (e: Exception) {
        null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun name(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "Java crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
        ApplicationExitInfo.REASON_ANR -> "Froze (ANR)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Killed for memory"
        ApplicationExitInfo.REASON_SIGNALED -> "Killed by signal"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Using too much"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Failed to start"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "WebView process died"
        else -> "Ended unexpectedly"
    }

    /**
     * What each reason means for this app specifically, because the difference
     * decides what to change next and none of it is guessable from the phone.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun meaning(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH ->
            "A bug in the app's own code. The stack trace names it."
        ApplicationExitInfo.REASON_CRASH_NATIVE ->
            "A crash inside WebView itself, usually the page being " +
            "used after it was thrown away. Not a bug Java can catch."
        ApplicationExitInfo.REASON_ANR ->
            "Something blocked the main thread for too long."
        ApplicationExitInfo.REASON_LOW_MEMORY ->
            "The phone ran out of memory and closed the app to free some. " +
            "Facebook pages are heavy; this is the cost of holding one open."
        ApplicationExitInfo.REASON_SIGNALED ->
            "The system or a battery optimiser killed the process outright."
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
            "The phone judged the app to be using too much battery or memory."
        ApplicationExitInfo.REASON_DEPENDENCY_DIED ->
            "The WebView process died and took the app with it."
        else -> "The system did not say."
    }

    private fun importance(importance: Int): String = when {
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ->
            "on screen"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ->
            "monitoring in the background"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE ->
            "running a service"
        else -> "idle in the background"
    }
}
