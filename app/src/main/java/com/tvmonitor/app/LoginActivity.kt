package com.tvmonitor.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.tvmonitor.app.util.FacebookSession

class LoginActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isLoggedIn()) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_login)
        val webView = findViewById<WebView>(R.id.loginWebView)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (isLoggedIn()) {
                    CookieManager.getInstance().flush()
                    goToMain()
                }
            }

            /**
             * Keeps the app alive when the renderer process is killed.
             *
             * Without this override, Android kills the whole app when the process
             * running the page dies - and then blames WebView for it, offering to
             * uninstall WebView updates system-wide. The Facebook login page is
             * heavy enough for this to happen on a phone under memory pressure,
             * which is exactly when it was seen: right after signing in.
             *
             * The detach below is not tidiness, it is the difference between
             * recovering and crashing anyway. This WebView is in the layout.
             * destroy() frees its native side, but the view object stays in the
             * hierarchy, and the next traversal - which recreate() guarantees,
             * because tearing the activity down walks every view in it - calls
             * onDetachedFromWindow on a WebView whose native half is gone. That
             * is a SIGSEGV in native code: no Java exception, nothing for the
             * crash recorder to catch, and Android blaming WebView for it. So
             * the first attempt at surviving a renderer death re-created the
             * very failure it was written to stop, which is why the app went on
             * closing after the fix and the recorder stayed empty.
             */
            override fun onRenderProcessGone(
                view: WebView, detail: android.webkit.RenderProcessGoneDetail
            ): Boolean {
                try {
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.stopLoading()
                    view.destroy()
                } catch (e: Exception) {
                    // Already gone; there is nothing left to release.
                }
                // Signing in again is a far smaller cost than the app closing.
                recreate()
                return true
            }
        }

        webView.loadUrl("https://www.facebook.com/login")
    }

    private fun isLoggedIn(): Boolean = FacebookSession.isSignedIn()

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
