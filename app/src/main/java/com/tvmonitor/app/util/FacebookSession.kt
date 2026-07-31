package com.tvmonitor.app.util

import android.webkit.CookieManager

/**
 * Whether Facebook still regards this device as signed in.
 *
 * One definition, read by both the login screen and the monitor. Only the login
 * screen used to ask; the service never did, and that gap is why an expired
 * session was invisible. A logged-out reader is served a Marketplace page that
 * renders perfectly and contains no listings - the same thing rate limiting
 * produces - so the service reported "Facebook returning nothing" and went on
 * scanning a signed-out page indefinitely.
 */
object FacebookSession {

    /**
     * c_user carries the account id and is present only while signed in.
     * Facebook drops it on sign-out and when a session expires, which is the
     * case that matters here: nothing announces it, the app is in the
     * background, and the page gives no sign of it either.
     *
     * Reading it costs no request, so it is safe to check before every scan.
     */
    fun isSignedIn(): Boolean =
        CookieManager.getInstance()
            .getCookie("https://www.facebook.com")
            ?.contains("c_user") == true
}
