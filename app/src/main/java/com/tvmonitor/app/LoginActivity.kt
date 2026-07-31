package com.tvmonitor.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

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
             */
            override fun onRenderProcessGone(
                view: WebView, detail: android.webkit.RenderProcessGoneDetail
            ): Boolean {
                try { view.destroy() } catch (e: Exception) { }
                // Signing in again is a far smaller cost than the app closing.
                recreate()
                return true
            }
        }

        webView.loadUrl("https://www.facebook.com/login")
    }

    private fun isLoggedIn(): Boolean {
        val cookies = CookieManager.getInstance()
            .getCookie("https://www.facebook.com") ?: return false
        return cookies.contains("c_user")
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
