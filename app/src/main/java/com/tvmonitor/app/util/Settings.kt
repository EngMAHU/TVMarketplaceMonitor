package com.tvmonitor.app.util

import android.content.Context

/**
 * The one setting the trader can change from the phone.
 *
 * requireKnownAge is v15.1's behaviour and the right default: a listing whose
 * age is unknown is held rather than announced, because announcing them is how
 * days-old stock reached the trader on the desktop build.
 *
 * It is also a single point of total failure. Knowing a listing's age depends on
 * capture.js replacing window.fetch before Facebook's first GraphQL call and on
 * Facebook still putting creation_time in the reply. If either stops being true,
 * every listing has an unknown age, every one is held, and the app goes silent
 * while looking perfectly healthy - which is exactly what a monitor must never
 * do. Turning this off trades knowing the age for being told at all, and that is
 * the trader's call to make on the day, not a decision to bake into a build.
 */
object Settings {

    private const val PREFS = "tvmonitor"
    private const val KEY_REQUIRE_KNOWN_AGE = "requireKnownAge"

    fun requireKnownAge(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_REQUIRE_KNOWN_AGE, true)

    fun setRequireKnownAge(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REQUIRE_KNOWN_AGE, value)
            .apply()
    }
}
