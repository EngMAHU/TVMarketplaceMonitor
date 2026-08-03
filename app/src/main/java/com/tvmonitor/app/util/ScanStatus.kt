package com.tvmonitor.app.util

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * What the last scan actually saw, so the trader can judge the filters instead
 * of guessing at them.
 *
 * scan.js has always counted this - how many cards it read and why each one was
 * thrown away - and the service has always dropped the counts on the floor. That
 * left one number visible anywhere in the app: how many listings got through. A
 * quiet hour and a filter rejecting everything look identical from there, and
 * they call for opposite responses.
 *
 * It matters most for the sources. Each feed is read in turn and they do not
 * behave alike, so "which feed produced this" is the difference between a source
 * that is worth its place in the rotation and one that has been returning
 * nothing for a day.
 *
 * In memory only. The service and the UI share a process, so when one goes the
 * other has gone too and there is nothing left to report.
 */
object ScanStatus {

    data class Report(
        val at: Long,
        val source: String,
        val blank: Boolean,
        val total: Int,
        val kept: Int,
        val notTv: Int,
        val tooFar: Int,
        val tooOld: Int,
        val noDate: Int,
        val tooSmall: Int,
        val tooDear: Int,
        // Cards whose title could not be read at all. The distinctive one: it
        // means the page's shape changed, not that the filters disagreed.
        val noTitle: Int,
        // scan.js catches its own exceptions and reports the message rather than
        // dying silently. Nothing read it either, so a scan throwing on every
        // single page looked exactly like Facebook having nothing to sell.
        val error: String?
    ) {
        /** The rejections, worst offender first, and only the ones that fired. */
        fun breakdown(): String {
            val parts = listOfNotNull(
                if (noTitle > 0) "$noTitle unreadable" else null,
                if (notTv > 0) "$notTv not TVs" else null,
                if (tooFar > 0) "$tooFar too far" else null,
                if (tooOld > 0) "$tooOld too old" else null,
                if (noDate > 0) "$noDate undated" else null,
                if (tooSmall > 0) "$tooSmall too small" else null,
                if (tooDear > 0) "$tooDear too dear" else null
            )
            return if (parts.isEmpty()) "nothing rejected" else parts.joinToString(", ")
        }

        /** One feed's outcome, short enough for a list of all of them. */
        fun oneLine(): String = when {
            error != null -> "$source: scan failed"
            blank -> "$source: empty"
            else -> "$source: $total read, $kept passed"
        }
    }

    private val _latest = MutableLiveData<Report?>(null)
    val latest: LiveData<Report?> = _latest

    /**
     * The last result from every feed, not just the most recent one.
     *
     * The single latest report cannot answer the question that matters when
     * nothing is coming through: is one feed broken, or all of them? Those need
     * opposite responses - fix a URL, or look at the login and the page itself -
     * and with four feeds read ten minutes apart, watching them go past one at a
     * time to find out is a poor use of a day.
     */
    private val bySource = LinkedHashMap<String, Report>()
    private val _feeds = MutableLiveData<List<Report>>(emptyList())
    val feeds: LiveData<List<Report>> = _feeds

    fun post(report: Report) {
        _latest.postValue(report)
        synchronized(bySource) {
            bySource[report.source] = report
            _feeds.postValue(bySource.values.toList())
        }
    }
}
