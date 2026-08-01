package com.tvmonitor.app.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.tvmonitor.app.data.AppDatabase
import com.tvmonitor.app.data.Listing
import com.tvmonitor.app.util.FacebookSession
import com.tvmonitor.app.util.NotificationHelper
import com.tvmonitor.app.util.ScanStatus
import com.tvmonitor.app.util.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MonitorService : Service() {

    companion object {
        // 2.5 minutes between loads, alternating the two sources - which is
        // v15.1's own rate: it walked two sources every five minutes, so each was
        // read every five minutes and the pair cost about 24 page loads an hour.
        //
        // Not raised, for a reason worth stating plainly: the desktop build ran at
        // 120 loads an hour and Facebook returned "You're Temporarily Blocked. It
        // looks like you were misusing this feature by going too fast." This phone
        // is the only channel the trader has left that still works, so the cost of
        // losing it is everything.
        private const val CHECK_INTERVAL = 150_000L

        // Facebook renders Marketplace results client-side; the HTML shell arrives
        // with nothing in it. Ten seconds is what the desktop scanner needed.
        private const val RENDER_WAIT = 10_000L

        // A check that has reported nothing after this long is not merely slow.
        // scan.js does not run until RENDER_WAIT has passed, so this leaves fifty
        // seconds for a load that normally takes a few.
        private const val CHECK_TIMEOUT = 60_000L

        // While signed out there is nothing worth asking Facebook for, so the
        // cookie is re-read on this timer and no page is loaded at all.
        private const val SIGNED_OUT_RETRY = 60_000L

        private data class Source(val name: String, val url: String)

        // Four feeds, read one per interval, so the request rate is exactly what
        // it was: one page load every 150 seconds and no more. What changed is
        // what gets loaded.
        //
        // v15.1's two sources are category feeds, and the cost of that was
        // measured rather than guessed: category pages carry no sort control and
        // ignore sortBy, so the feed comes back unordered. Across 358 alerts on
        // the desktop the youngest listing a category feed ever produced was
        // seven minutes old, median thirteen. The trader's own manual SEARCH
        // returns listings two to three minutes old. Listings sell in about ten,
        // which is the whole problem: a median of thirteen is a tool reporting
        // sales rather than finding them.
        //
        // A search feed honours sortBy, so the newest listing is the first one on
        // the page. Reading each feed every ten minutes instead of five still
        // beats that comfortably - a sorted feed read every ten minutes surfaces
        // a listing at a median of five to eight minutes old, against thirteen -
        // and it does so without asking Facebook for anything more often.
        //
        // The category feeds stay in the rotation rather than being replaced, for
        // one specific reason: a search for "tv" matches on the title, so a
        // listing called only "Television" reaches the category feed and not the
        // search. classify() accepts those, so dropping the category feeds would
        // quietly lose listings the filters were built to catch.
        //
        // None of this could be checked from here - Facebook is unreachable from
        // the machine this was written on - so ScanStatus reports every feed by
        // name. A search feed returning nothing shows up in the app within one
        // cycle instead of being mistaken for a quiet afternoon.
        private val SOURCES = listOf(
            Source(
                "Liverpool search",
                "https://www.facebook.com/marketplace/liverpool/search/" +
                "?query=tv&sortBy=creation_time_descend&daysSinceListed=1" +
                "&radius=113&exact=false"
            ),
            Source(
                "Manchester search",
                "https://www.facebook.com/marketplace/manchester/search/" +
                "?query=tv&sortBy=creation_time_descend&daysSinceListed=1" +
                "&radius=30&exact=false"
            ),

            // v15.1's own two, unchanged, sortBy included even though a category
            // page ignores it. Kept as the faithful port they were asked to be.
            Source(
                "Liverpool category",
                "https://www.facebook.com/marketplace/liverpool/tvs/" +
                "?sortBy=creation_time_descend&daysSinceListed=1&radius=113&exact=false"
            ),
            Source(
                "Manchester category",
                "https://www.facebook.com/marketplace/manchester/tvs/" +
                "?sortBy=creation_time_descend&daysSinceListed=1&radius=30&exact=false"
            )
        )

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, MonitorService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }

        var isRunning = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: AppDatabase
    private var webView: WebView? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isChecking = false
    private var checkCount = 0
    private var blankStreak = 0
    private var urlIndex = 0
    private var rendererDeaths = 0
    private var stalls = 0
    private var signedOut = false

    // Which feed the check in flight is reading. Reported with its counts, so a
    // source that has stopped producing can be told from a quiet market.
    @Volatile private var currentSource = ""

    // Whether capture.js is guaranteed to run before the page's own scripts.
    // False means this WebView cannot do that and the late fallback is in use,
    // which is worth knowing when no listing is carrying a timestamp.
    private var documentStartCapture = false

    // The last scan's counts, so the ongoing notification can name the reason
    // for a silence rather than just showing the time it last stayed silent.
    @Volatile private var lastReport: ScanStatus.Report? = null

    // Which check is in flight. Every check gets a new number, and only a report
    // carrying the current one is allowed to end it - see finishCheck.
    private var checkGeneration = 0

    private val checkRunnable = object : Runnable {
        override fun run() { performCheck() }
    }

    // Loaded once. filters.js is copied verbatim from the desktop extension, so
    // the two cannot drift apart on what counts as a television.
    private val filtersJs by lazy { readAsset("filters.js") }
    private val scanJs by lazy { readAsset("scan.js") }
    private val captureJs by lazy { readAsset("capture.js") }

    // A missing or unreadable asset must not take the whole service down. These
    // are read lazily from inside a WebView callback on the main thread, so an
    // exception here would surface as the app simply closing, with Android then
    // blaming WebView for it.
    private fun readAsset(name: String): String =
        try {
            assets.open(name).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            assetError = "could not read $name: ${e.message}"
            ""
        }

    private var assetError: String? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        db = AppDatabase.getInstance(this)
        startForeground(
            NotificationHelper.SERVICE_ID,
            NotificationHelper.serviceNotification(this, 0, "Starting...")
        )
        acquireWakeLock()
        initWebView()
        handler.post(checkRunnable)
    }

    /**
     * Registers capture.js to run before any of the page's own scripts.
     *
     * This is the difference between the app alerting and the app being silent,
     * and it is worth being exact about why.
     *
     * capture.js has to replace window.fetch before Facebook makes its first
     * GraphQL call. Miss it and the response holding the newest listings has
     * come and gone unwatched, so no listing gets a creation_time. Every listing
     * then has an unknown age, and with requireKnownAge set - which it is,
     * deliberately - scan.js holds every single one. The scan works, the filters
     * work, the page reads fine, and nothing is ever announced.
     *
     * It used to be injected from onPageStarted, which does not guarantee that.
     * onPageStarted fires as the main frame begins loading, and a script
     * evaluated there is routinely lost when the new document commits, or lands
     * after the page's own scripts have already run. addDocumentStartJavaScript
     * is the API that exists for precisely this and gives the guarantee outright:
     * it runs on every navigation, before any page script, in every matching
     * frame.
     *
     * Not every WebView supports it, so the flag records whether the guarantee is
     * actually in place and onPageStarted stays as a fallback for the rest.
     */
    private fun installCapture(view: WebView) {
        documentStartCapture = false
        if (captureJs.isBlank()) return
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(
                    view, captureJs, setOf("https://www.facebook.com")
                )
                documentStartCapture = true
            }
        } catch (e: Exception) {
            // Left false, so onPageStarted injects the old way instead.
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvmonitor::monitor")
            .apply { acquire() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        handler.post {
            webView = WebView(applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = false
                settings.blockNetworkImage = true
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/125.0.0.0 Mobile Safari/537.36"

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                addJavascriptInterface(ScraperInterface(), "Android")
                installCapture(this)

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        view: WebView, url: String?, favicon: android.graphics.Bitmap?
                    ) {
                        // Fallback only. installCapture registers the real thing;
                        // this covers the case where the device's WebView is too
                        // old to support document-start scripts, where injecting
                        // late is better than not at all. capture.js guards itself
                        // with __tvcap__, so running twice costs nothing.
                        if (!documentStartCapture) {
                            try {
                                view.evaluateJavascript(captureJs, null)
                            } catch (e: Exception) {
                            }
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        handler.postDelayed({
                            // Wrapped because this runs on the main thread from a
                            // WebView callback: anything thrown here closes the
                            // app, and Android reports that as a WebView fault.
                            try {
                                if (assetError != null) {
                                    finishCheck(checkGeneration, stalled = true)
                                    return@postDelayed
                                }
                                view.evaluateJavascript(filtersJs, null)
                                view.evaluateJavascript(
                                    scanJs.replace("__CONFIG__", configJson(checkGeneration)),
                                    null
                                )
                            } catch (e: Exception) {
                                // Free the latch, or no further check is ever run.
                                finishCheck(checkGeneration, stalled = true)
                            }
                        }, RENDER_WAIT)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView, request: WebResourceRequest
                    ): Boolean = false

                    /**
                     * The renderer died. Returning true keeps this app alive.
                     *
                     * This is the whole bug. A WebView runs the page in a separate
                     * renderer process, and when that process is killed - which
                     * Android does readily to a background app holding a page as
                     * heavy as Facebook - the default behaviour is to kill the app
                     * that owned it. Android then reports it as "The installed
                     * version of WebView caused TV Monitor to crash" and offers to
                     * uninstall WebView updates, which is a system-wide change that
                     * fixes nothing, because WebView was never at fault.
                     *
                     * It is also why the crash recorder added earlier stayed empty:
                     * no Java exception is ever thrown, so an uncaught-exception
                     * handler has nothing to catch.
                     */
                    override fun onRenderProcessGone(
                        view: WebView, detail: android.webkit.RenderProcessGoneDetail
                    ): Boolean {
                        rendererDeaths++
                        // The dead WebView can never be reused; it must be detached
                        // and destroyed before a replacement is built.
                        handler.post {
                            try { view.destroy() } catch (e: Exception) { }
                            if (webView === view) webView = null
                            initWebView()
                            finishCheck(checkGeneration, stalled = true)
                        }
                        return true
                    }
                }
            }
        }
    }

    // v15.1's settings exactly, at the trader's request. Deliberately NOT the
    // later ones: no minimum screen size, no price cap, a 60-minute window rather
    // than 45, and the shorter block list from before "faulty", "broken",
    // "cracked", "for parts" were added. Undated listings are held rather than
    // announced, which v15.1 also did.
    private fun configJson(generation: Int): String = JSONObject().apply {
        // Carried through the scan and handed back in its report, so a reply from
        // a check that was already given up on can be told apart from a live one.
        put("gen", generation)
        put("minInches", 0)
        put("maxPrice", 0)
        put("maxAgeMinutes", 60)
        // Read fresh each scan, so switching it off in the app takes effect on
        // the next check rather than needing the service restarted.
        // Qualified: inside apply, a bare "this" is the JSONObject being built.
        put("requireKnownAge", Settings.requireKnownAge(this@MonitorService))
        put(
            "blockWords",
            "stand, stands, bracket, brackets, mount, mounts, mounted, " +
            "firestick, firesticks, fire stick, fire tv stick, fire sticks"
        )
        put("excludeExtra", "")
    }.toString()

    private fun performCheck() {
        // The session can expire at any time and nothing announces it. A
        // signed-out reader gets a Marketplace page that renders correctly and
        // holds no listings, which is indistinguishable from rate limiting, so
        // without this the monitor would go on scanning a logged-out page and
        // report it as Facebook being quiet. The cookie costs no request.
        if (!FacebookSession.isSignedIn()) {
            if (!signedOut) {
                signedOut = true
                NotificationHelper.notifySignedOut(this)
            }
            scope.launch { updateServiceNotification() }
            handler.postDelayed(checkRunnable, SIGNED_OUT_RETRY)
            return
        }
        if (signedOut) {
            signedOut = false
            NotificationHelper.clearSignedOut(this)
        }

        if (isChecking) {
            handler.postDelayed(checkRunnable, CHECK_INTERVAL)
            return
        }
        isChecking = true
        val generation = ++checkGeneration
        val source = SOURCES[urlIndex % SOURCES.size]
        urlIndex++
        currentSource = source.name

        // The watchdog, and the reason this method now hands out generations.
        //
        // Every route out of a check used to depend on something arriving: the
        // scan reporting, the page finishing, the renderer dying. When none of
        // them did - a load that hangs, a bridge that never fires - isChecking
        // stayed latched forever. The service survived that in the worst possible
        // shape: alive, holding a wake lock, still showing "TV Monitor Active",
        // and never scanning again. Nothing about it looked wrong from outside.
        handler.postDelayed({ finishCheck(generation, stalled = true) }, CHECK_TIMEOUT)

        // A null WebView here means one is being rebuilt after a renderer death.
        // Nothing loads, and the watchdog above is what notices.
        handler.post { webView?.loadUrl(source.url) }
    }

    /**
     * Ends the check in flight and books the next one.
     *
     * Every exit from a check comes through here - a scan that reported, a
     * renderer that died, a page that threw, a load that produced nothing - so
     * that exactly one of them can win it.
     *
     * The generation is what makes that true. Without it, a scan replying five
     * seconds after the watchdog had given up would end the check that had just
     * started in its place, and the next reply would start a second loop
     * alongside the first. A stale number is ignored instead.
     *
     * Main thread only: it touches the handler and the check state.
     */
    private fun finishCheck(generation: Int, stalled: Boolean) {
        if (!isChecking || generation != checkGeneration) return
        checkGeneration++
        isChecking = false
        if (stalled) stalls++ else { stalls = 0; checkCount++ }
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, CHECK_INTERVAL)
        scope.launch { updateServiceNotification() }
    }

    inner class ScraperInterface {
        @JavascriptInterface
        fun onListingsFound(json: String) {
            // Sent back by scan.js from the config it was given. A report with no
            // generation, or a stale one, is still worth processing - the listings
            // in it are real - but it is not allowed to end the current check.
            val generation = try {
                JSONObject(json).optInt("gen", -1)
            } catch (e: Exception) {
                -1
            }
            scope.launch {
                try { processResults(json) } catch (e: Exception) { e.printStackTrace() }
                finally { handler.post { finishCheck(generation, stalled = false) } }
            }
        }
    }

    private suspend fun processResults(json: String) {
        if (json.isBlank()) return
        val root = try { JSONObject(json) } catch (e: Exception) { return }

        // A fully rendered page with no results is what rate limiting looks like -
        // there is no error, just an empty grid. Saying so on the ongoing
        // notification is the only warning the trader gets that the phone is going
        // the way the laptop did.
        if (root.optBoolean("blank", false)) {
            blankStreak++
            reportScan(root, kept = 0, blank = true)
            return
        }
        blankStreak = 0

        val array = root.optJSONArray("listings") ?: return
        // Reported before the early return below. A page full of cards that the
        // filters rejected down to nothing is the case most worth seeing, and it
        // is exactly the one that used to leave no trace anywhere in the app.
        reportScan(root, kept = array.length(), blank = false)
        if (array.length() == 0) return

        val listings = ArrayList<Listing>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isBlank()) continue
            listings.add(
                Listing(
                    id = id,
                    title = o.optString("title", "TV Listing"),
                    price = o.optString("price", ""),
                    imageUrl = "",
                    location = o.optString("location", ""),
                    url = o.optString(
                        "url", "https://www.facebook.com/marketplace/item/$id"
                    )
                )
            )
        }
        if (listings.isEmpty()) return

        val existingIds = db.listingDao().getAllIds().toSet()
        val fresh = listings.filter { it.id !in existingIds }
        if (fresh.isNotEmpty()) {
            db.listingDao().insertAll(fresh)
            NotificationHelper.notifyNewListings(this, fresh)
        }

        val weekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        db.listingDao().deleteOlderThan(weekAgo)
    }

    /**
     * Publishes what the scan counted. scan.js has always sent these; nothing
     * ever read them, so the app could show that six listings got through and
     * never that sixty were thrown away to get there.
     */
    private fun reportScan(root: JSONObject, kept: Int, blank: Boolean) {
        val s = root.optJSONObject("stats")
        val report = ScanStatus.Report(
            at = System.currentTimeMillis(),
            source = currentSource,
            blank = blank,
            total = s?.optInt("total", 0) ?: 0,
            kept = kept,
            notTv = s?.optInt("notTv", 0) ?: 0,
            tooFar = s?.optInt("tooFar", 0) ?: 0,
            tooOld = s?.optInt("tooOld", 0) ?: 0,
            noDate = s?.optInt("noDate", 0) ?: 0,
            tooSmall = s?.optInt("tooSmall", 0) ?: 0,
            tooDear = s?.optInt("tooDear", 0) ?: 0,
            noTitle = s?.optInt("noTitle", 0) ?: 0,
            error = root.optString("error", "").ifBlank { null }
        )
        lastReport = report
        ScanStatus.post(report)
    }

    private suspend fun updateServiceNotification() {
        val count = db.listingDao().count()
        val time = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date())

        // The ongoing notification is the only thing the trader sees while the
        // phone sits in a pocket, so every way this can be failing has to be able
        // to reach it. Ordered worst first.
        val r = lastReport
        val state = when {
            assetError != null -> "not working: $assetError"
            signedOut -> "signed out - sign in to resume"
            stalls > 0 -> "$time - no reply from page ($stalls)"
            blankStreak >= 3 -> "$time - Facebook returning nothing ($blankStreak)"

            // The silent failure, said out loud. Cards were read and every one
            // was held for having no date, which means no alert will ever fire
            // no matter how many televisions are listed. Without this line it
            // looks exactly like a quiet market, and it is the state the app was
            // most likely to sit in for a whole day without anyone knowing.
            r != null && r.kept == 0 && r.noDate > 0 && r.noDate >= r.total - r.notTv ->
                "$time - ${r.noDate} found but undated, all held"

            // Cards on the page and not one of them readable: Facebook changed
            // the shape of a listing card and the scan cannot see titles at all.
            r != null && r.kept == 0 && r.noTitle > 0 && r.noTitle >= r.total ->
                "$time - page changed, ${r.noTitle} cards unreadable"

            r != null && r.error != null -> "$time - scan failed: ${r.error}"
            else -> time
        }
        // Renderer deaths are survived rather than fatal now, but a phone killing
        // the page repeatedly is worth knowing about before it becomes constant.
        val recovered = if (rendererDeaths > 0) " | recovered ${rendererDeaths}x" else ""

        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(
            NotificationHelper.SERVICE_ID,
            NotificationHelper.serviceNotification(this, count, state + recovered)
        )
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        // It is ongoing, so it would otherwise outlive the monitor it describes.
        NotificationHelper.clearSignedOut(this)
        handler.post { webView?.destroy(); webView = null }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
