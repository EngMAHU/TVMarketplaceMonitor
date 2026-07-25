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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tvmonitor.app.data.AppDatabase
import com.tvmonitor.app.data.Listing
import com.tvmonitor.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MonitorService : Service() {

    companion object {
        private const val CHECK_INTERVAL = 60_000L
        private const val RENDER_WAIT = 10_000L
        private const val MARKETPLACE_URL =
            "https://www.facebook.com/marketplace/liverpool/search" +
            "?query=tv&sortBy=creation_time_descend&radius=97&exact=false"

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

    private val checkRunnable = object : Runnable {
        override fun run() {
            performCheck()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        db = AppDatabase.getInstance(this)

        val notification = NotificationHelper.serviceNotification(this, 0, "Starting...")
        startForeground(NotificationHelper.SERVICE_ID, notification)

        acquireWakeLock()
        initWebView()
        handler.post(checkRunnable)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "tvmonitor::monitor"
        ).apply { acquire() }
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
                    override fun onPageFinished(view: WebView, url: String) {
                        handler.postDelayed({
                            view.evaluateJavascript(extractionScript(), null)
                        }, RENDER_WAIT)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView, request: WebResourceRequest
                    ): Boolean = false
                }
            }
        }
    }

    private fun performCheck() {
        if (isChecking) {
            handler.postDelayed(checkRunnable, CHECK_INTERVAL)
            return
        }
        isChecking = true
        handler.post {
            webView?.loadUrl(MARKETPLACE_URL)
        }
    }

    inner class ScraperInterface {
        @JavascriptInterface
        fun onListingsFound(json: String) {
            scope.launch {
                try {
                    processResults(json)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isChecking = false
                    checkCount++
                    updateServiceNotification()
                    handler.postDelayed(checkRunnable, CHECK_INTERVAL)
                }
            }
        }
    }

    private suspend fun processResults(json: String) {
        if (json == "[]" || json.isBlank()) return

        val listings = parseListings(json)
        if (listings.isEmpty()) return

        val existingIds = db.listingDao().getAllIds().toSet()
        val newListings = listings.filter { it.id !in existingIds }

        if (newListings.isNotEmpty()) {
            db.listingDao().insertAll(newListings)
            NotificationHelper.notifyNewListings(this, newListings)
        }

        val weekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        db.listingDao().deleteOlderThan(weekAgo)
    }

    private fun parseListings(json: String): List<Listing> {
        val result = mutableListOf<Listing>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id", "")
                if (id.isBlank()) continue
                result.add(
                    Listing(
                        id = id,
                        title = obj.optString("title", "TV Listing"),
                        price = obj.optString("price", ""),
                        imageUrl = obj.optString("imageUrl", ""),
                        location = obj.optString("location", "Liverpool area"),
                        url = obj.optString("url",
                            "https://www.facebook.com/marketplace/item/$id")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    private suspend fun updateServiceNotification() {
        val count = db.listingDao().count()
        val time = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date())
        val notification = NotificationHelper.serviceNotification(this, count, time)
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NotificationHelper.SERVICE_ID, notification)
    }

    private fun extractionScript(): String = """
        (function() {
            try {
                var listings = [];
                var seen = {};
                var links = document.querySelectorAll('a[href*="/marketplace/item/"]');

                for (var i = 0; i < links.length; i++) {
                    var link = links[i];
                    var href = link.getAttribute('href') || '';
                    var match = href.match(/\/marketplace\/item\/(\d+)/);
                    if (!match || seen[match[1]]) continue;
                    seen[match[1]] = true;

                    var imgs = link.querySelectorAll('img');
                    var imageUrl = '';
                    for (var k = 0; k < imgs.length; k++) {
                        var src = imgs[k].getAttribute('src') || '';
                        if (src && src.startsWith('http') && src.indexOf('emoji') === -1) {
                            imageUrl = src;
                            break;
                        }
                    }

                    var spans = link.querySelectorAll('span');
                    var texts = [];
                    for (var m = 0; m < spans.length; m++) {
                        var t = (spans[m].innerText || spans[m].textContent || '').trim();
                        if (t.length > 0 && t.length < 200 && texts.indexOf(t) === -1) {
                            texts.push(t);
                        }
                    }

                    var price = '', title = '', location = '';
                    for (var n = 0; n < texts.length; n++) {
                        var txt = texts[n];
                        if (!price && (txt.match(/^[£$€\d]/) || txt.toLowerCase() === 'free')) {
                            price = txt;
                        } else if (!title && txt.length > 2 && !txt.match(/^\d+\s*(miles?|km)/i)) {
                            title = txt;
                        } else if (title && !location && txt.length > 2) {
                            location = txt;
                        }
                    }

                    listings.push({
                        id: match[1],
                        title: title || 'TV Listing',
                        price: price || 'See listing',
                        imageUrl: imageUrl,
                        location: location || 'Liverpool area',
                        url: 'https://www.facebook.com/marketplace/item/' + match[1]
                    });
                }

                Android.onListingsFound(JSON.stringify(listings));
            } catch(e) {
                Android.onListingsFound('[]');
            }
        })();
    """.trimIndent()

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        handler.post {
            webView?.destroy()
            webView = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
