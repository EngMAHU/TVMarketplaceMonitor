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

                webViewClient = object : WebViewClient() {
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
                        handler.postDelayed({
                            // Wrapped because this runs on the main thread from a
                            // WebView callback: anything thrown here closes the
                            // app, and Android reports that as a WebView fault.
                            try {
                                if (assetError != null) {
                                    isChecking = false
                                    return@postDelayed
                                }
                                view.evaluateJavascript(filtersJs, null)
                                view.evaluateJavascript(
                                    scanJs.replace("__CONFIG__", configJson()), null
                                )
                            } catch (e: Exception) {
                                // Free the latch, or no further check is ever run.
                                isChecking = false
                                handler.postDelayed(checkRunnable, CHECK_INTERVAL)
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
                            isChecking = false
                            handler.postDelayed(checkRunnable, CHECK_INTERVAL)
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
    private fun configJson(): String = JSONObject().apply {
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
        if (isChecking) {
            handler.postDelayed(checkRunnable, CHECK_INTERVAL)
            return
        }
        isChecking = true
        val url = URLS[urlIndex % URLS.size]
        urlIndex++
        handler.post { webView?.loadUrl(url) }
    }

    inner class ScraperInterface {
        @JavascriptInterface
        fun onListingsFound(json: String) {
            scope.launch {
                try { processResults(json) } catch (e: Exception) { e.printStackTrace() }
                finally {
                    isChecking = false
                    checkCount++
                    updateServiceNotification()
                    handler.postDelayed(checkRunnable, CHECK_INTERVAL)
                }
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
        val status = if (blankStreak >= 3)
            "$time - Facebook returning nothing ($blankStreak)" else time
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(
            NotificationHelper.SERVICE_ID,
            NotificationHelper.serviceNotification(this, count, status)
        )
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        handler.post { webView?.destroy(); webView = null }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
