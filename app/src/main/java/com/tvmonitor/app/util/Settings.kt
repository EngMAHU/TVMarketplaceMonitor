package com.tvmonitor.app.util

import android.content.Context

/**
 * The rules the scanner judges a listing by.
 *
 * These were fixed in the code until now - v15.1's settings, hard-coded, because
 * a faithful port was what was asked for. That is defensible for the matching
 * rules, which took a day of real listings to get right, but not for the
 * numbers: what counts as too old, too dear or too small is the trader's
 * judgement about their own trade, and it changes with the season and the stock.
 */
data class Filters(
    /** Smallest screen the trader will consider, in inches. 0 disables the test. */
    val minInches: Int,
    /** Most the trader will pay. 0 disables the test. */
    val maxPrice: Int,
    /** How old a listing may be and still be worth waking someone for. */
    val maxAgeMinutes: Int,
    /**
     * Whether a listing whose age Facebook did not disclose is held back.
     *
     * On: nothing undated is announced. Off: undated listings are alerted with
     * an unknown age, which is how days-old stock reached the trader on the
     * desktop build - but it also means seeing listings that would be missed.
     */
    val requireKnownAge: Boolean,
    /** Bundle words, comma separated. Negations are respected: "no stand" passes. */
    val blockWords: String,
    /** Anything else the trader wants skipped, comma separated. */
    val excludeExtra: String
)

object Settings {

    private const val PREFS = "tvmonitor"

    /**
     * v15.1's settings, which is what every build so far has run.
     *
     * Kept as the defaults deliberately: a trader who changes nothing gets
     * exactly the behaviour they have been testing, and RESET returns here.
     */
    val DEFAULTS = Filters(
        minInches = 0,
        maxPrice = 0,
        maxAgeMinutes = 60,
        requireKnownAge = true,
        blockWords = "stand, stands, bracket, brackets, mount, mounts, mounted, " +
            "firestick, firesticks, fire stick, fire tv stick, fire sticks",
        excludeExtra = ""
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Filters {
        val p = prefs(context)
        return Filters(
            minInches = p.getInt("minInches", DEFAULTS.minInches),
            maxPrice = p.getInt("maxPrice", DEFAULTS.maxPrice),
            maxAgeMinutes = p.getInt("maxAgeMinutes", DEFAULTS.maxAgeMinutes),
            requireKnownAge = p.getBoolean("requireKnownAge", DEFAULTS.requireKnownAge),
            blockWords = p.getString("blockWords", null) ?: DEFAULTS.blockWords,
            excludeExtra = p.getString("excludeExtra", null) ?: DEFAULTS.excludeExtra
        )
    }

    fun save(context: Context, f: Filters) {
        prefs(context).edit()
            .putInt("minInches", f.minInches)
            .putInt("maxPrice", f.maxPrice)
            .putInt("maxAgeMinutes", f.maxAgeMinutes)
            .putBoolean("requireKnownAge", f.requireKnownAge)
            .putString("blockWords", f.blockWords)
            .putString("excludeExtra", f.excludeExtra)
            .apply()
    }
}
