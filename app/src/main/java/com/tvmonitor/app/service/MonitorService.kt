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
import com.tvmonitor.app.data.AppDatabase
import com.tvmonitor.app.data.Listing
import com.tvmonitor.app.util.FacebookSession
import com.tvmonitor.app.util.NotificationHelper
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

        // v15.1's two sources, in v15.1's shape, at the trader's request.
        //
        // Both are category feeds. Worth recording what that costs, because it is
        // measurable and was measured: category pages carry no sort control and
        // ignore sortBy, so the feed is unordered, and across 358 alerts on the
        // desktop the youngest listing a category feed ever produced was seven
        // minutes old with a median of thirteen. The trader's own manual SEARCH
        // returns listings two to three minutes old. Listings sell in about ten.
        //
        // The parameters are kept exactly as v15.1 built them, sortBy included,
        // even though a category page ignores it - this is a faithful port, not
        // an improved one.
        private val URLS = listOf(
            "https://www.facebook.com/marketplace/liverpool/tvs/" +
            "?sortBy=creation_time_descend&daysSinceListed=1&radius=113&exact=false",

            // Centred on Manchester with a 30 km radius, as v15.1 had it.
            "https://www.facebook.com/marketplace/manchester/tvs/" +
            "?sortBy=creation_time_descend&daysSinceListed=1&radius=30&exact=false"
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

    private val mainHandler = Handler(Looper.getMainLooper())
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
        mainHandler.post(checkRunnable)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvmonitor::monitor")
            .apply { acquire() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        mainHandler.post {
            // Built through a local rather than WebView(...).apply { }, and that
            // is the entire bug that closed this app for four builds running.
            //
            // Inside apply the WebView is the implicit receiver, so an
            // unqualified name is resolved against it before the enclosing
            // class. android.view.View has getHandler(), which Kotlin exposes
            // as a property named `handler` - so `handler.postDelayed(...)` in
            // the WebViewClient below never meant this service's Handler. It
            // meant the view's own, and View.getHandler() returns null until
            // the view is attached to a window. This WebView is built in a
            // service and never added to any layout, so it was null every time.
            //
            // What that produced was a NullPointerException on the main thread
            // in onPageFinished, killing the app roughly ten seconds into every
            // scan - which Android reported as WebView having crashed it, and
            // which sent three rounds of fixes after the wrong thing. It also
            // silently disabled the renderer-death recovery, whose first act
            // was another call on that same null handler.
            //
            // A local variable keeps the WebView out of the receiver chain, so
            // a bare name can only mean what it appears to mean. The field is
            // named mainHandler for the same reason: View has no such member,
            // so the mistake cannot be made again by accident.
            val wv = WebView(applicationContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.loadsImagesAutomatically = false
            wv.settings.blockNetworkImage = true
            wv.settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/125.0.0.0 Mobile Safari/537.36"

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

            wv.addJavascriptInterface(ScraperInterface(), "Android")

            wv.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        view: WebView, url: String?, favicon: android.graphics.Bitmap?
                    ) {
                        // The capture has to be installed before Facebook's first
                        // GraphQL call, or the payload holding the newest listings
                        // has come and gone before anything is watching - which is
                        // exactly how an earlier desktop build ended up with no
                        // timestamps at all.
                        try { view.evaluateJavascript(captureJs, null) } catch (e: Exception) { }
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        mainHandler.postDelayed({
                            // Ten seconds is long enough for this WebView to have
                            // been thrown away: a renderer death replaces it, and
                            // stopping the monitor tears it down. destroy() frees
                            // the native half while this callback still holds the
                            // Java object, and calling into one of those is a
                            // SIGSEGV - no Java exception, nothing for the crash
                            // recorder to catch, and Android reporting it as
                            // WebView having crashed the app. The field is the
                            // authority on which WebView is live, so anything that
                            // is no longer it is left alone.
                            if (view !== webView) return@postDelayed

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
                        // The dead WebView can never be reused, so it is torn down
                        // before a replacement is built. Posted rather than done
                        // here: destroying a WebView from inside its own callback
                        // frees an object the caller is still unwinding through.
                        mainHandler.post {
                            destroyWebView(view)
                            initWebView()
                            finishCheck(checkGeneration, stalled = true)
                        }
                        return true
                    }
                }

            // Published only once it is fully built, so that the identity check
            // in onPageFinished cannot match a half-configured WebView.
            webView = wv
        }
    }

    /**
     * Tears a WebView down so that nothing can reach it afterwards.
     *
     * Clearing the field first is the point of this method, not housekeeping.
     * Every delayed callback that holds a WebView checks it against this field
     * before touching it, so clearing it before the destroy is what turns those
     * callbacks into no-ops. Do it the other way round and there is a window in
     * which a pending callback sees a WebView that matches the field and has
     * already been freed.
     *
     * That matters more than the usual amount here because the failure is not an
     * exception. A destroyed WebView is a freed native object; calling into one
     * crashes the process in native code, which no try/catch and no
     * uncaught-exception handler can see. It is the emptiness of the crash
     * recorder, and it is what Android was reporting as WebView's fault.
     *
     * Main thread only.
     */
    private fun destroyWebView(view: WebView?) {
        if (view == null) return
        if (webView === view) webView = null
        try {
            view.stopLoading()
            // Or a page still unloading can call back into a half-torn service.
            view.webViewClient = WebViewClient()
            view.removeJavascriptInterface("Android")
            view.destroy()
        } catch (e: Exception) {
            // Already gone; there is nothing left to release.
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
        put("requireKnownAge", true)
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
            mainHandler.postDelayed(checkRunnable, SIGNED_OUT_RETRY)
            return
        }
        if (signedOut) {
            signedOut = false
            NotificationHelper.clearSignedOut(this)
        }

        if (isChecking) {
            mainHandler.postDelayed(checkRunnable, CHECK_INTERVAL)
            return
        }
        isChecking = true
        val generation = ++checkGeneration
        val url = URLS[urlIndex % URLS.size]
        urlIndex++

        // The watchdog, and the reason this method now hands out generations.
        //
        // Every route out of a check used to depend on something arriving: the
        // scan reporting, the page finishing, the renderer dying. When none of
        // them did - a load that hangs, a bridge that never fires - isChecking
        // stayed latched forever. The service survived that in the worst possible
        // shape: alive, holding a wake lock, still showing "TV Monitor Active",
        // and never scanning again. Nothing about it looked wrong from outside.
        mainHandler.postDelayed({ finishCheck(generation, stalled = true) }, CHECK_TIMEOUT)

        // A null WebView here means one is being rebuilt after a renderer death.
        // Nothing loads, and the watchdog above is what notices.
        mainHandler.post { webView?.loadUrl(url) }
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
        mainHandler.removeCallbacks(checkRunnable)
        mainHandler.postDelayed(checkRunnable, CHECK_INTERVAL)
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
                finally { mainHandler.post { finishCheck(generation, stalled = false) } }
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
            return
        }
        blankStreak = 0

        val array = root.optJSONArray("listings") ?: return
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

    private suspend fun updateServiceNotification() {
        val count = db.listingDao().count()
        val time = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date())

        // The ongoing notification is the only thing the trader sees while the
        // phone sits in a pocket, so every way this can be failing has to be able
        // to reach it. Ordered worst first.
        val state = when {
            assetError != null -> "not working: $assetError"
            signedOut -> "signed out - sign in to resume"
            stalls > 0 -> "$time - no reply from page ($stalls)"
            blankStreak >= 3 -> "$time - Facebook returning nothing ($blankStreak)"
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
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        // It is ongoing, so it would otherwise outlive the monitor it describes.
        NotificationHelper.clearSignedOut(this)
        // Cleared before the post, so that any callback that slips through finds
        // no live WebView rather than a freed one.
        val dying = webView
        webView = null
        mainHandler.post { destroyWebView(dying) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
