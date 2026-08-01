package com.tvmonitor.app.util

import android.content.Context
import android.net.Uri
import kotlin.math.roundToInt

/**
 * The rules the scanner judges a listing by, and the feeds it reads them from.
 *
 * These were fixed in the code until now - v15.1's settings, hard-coded, because
 * a faithful port was what was asked for. That is defensible for the matching
 * rules, which took a day of real listings to get right, but not for any of
 * this: which towns, how far, how old, how dear is the trader's judgement about
 * their own trade, and it changes with the season and the stock.
 */
data class Filters(
    /** Marketplace city slugs, comma separated. Read in turn, one per check. */
    val cities: String,
    /** Catchment around each city, in miles. Facebook wants kilometres. */
    val radiusMiles: Int,
    /**
     * Whether to read Marketplace SEARCH rather than the TV category feed.
     *
     * This is the difference between "newest first" working and not. A category
     * page has no sort control: it ignores sortBy entirely and returns an
     * unordered feed, which is why the parameter has been on every URL since
     * v15.1 and has never once done anything. Search honours it.
     */
    val useSearch: Boolean,
    /** What to search for when useSearch is on. */
    val searchQuery: String,
    /** Seconds between page loads. Not per city - see [perCitySeconds]. */
    val checkIntervalSeconds: Int,

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
) {

    /** The city slugs, cleaned. Never empty - an empty list would scan nothing. */
    fun cityList(): List<String> =
        cities.split(",")
            .map { it.trim().lowercase().replace(Regex("[^a-z0-9]"), "") }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("liverpool") }

    /**
     * How often any ONE city is actually refreshed.
     *
     * The interval is per page load, and the cities are read in turn, so two
     * cities at five minutes means each is looked at every ten. That
     * multiplication is the easiest thing here to get wrong and the most
     * expensive: listings sell in about ten minutes, so a setting that reads
     * as "every 5 minutes" can quietly mean "after it has already gone".
     */
    fun perCitySeconds(): Int = checkIntervalSeconds * cityList().size

    /** Page loads per hour - the number Facebook rate limits on. */
    fun loadsPerHour(): Int =
        if (checkIntervalSeconds <= 0) 0 else (3600 / checkIntervalSeconds)

    /** The feeds to read, in the order they are read. */
    fun urls(): List<String> {
        val km = (radiusMiles * 1.60934).roundToInt().coerceIn(1, 500)
        return cityList().map { city ->
            if (useSearch) {
                // sortBy is honoured here, which is the whole point of search.
                "https://www.facebook.com/marketplace/$city/search/" +
                    "?query=${Uri.encode(searchQuery.ifBlank { "tv" })}" +
                    "&sortBy=creation_time_descend" +
                    "&daysSinceListed=1&radius=$km&exact=false"
            } else {
                "https://www.facebook.com/marketplace/$city/tvs/" +
                    "?sortBy=creation_time_descend&daysSinceListed=1" +
                    "&radius=$km&exact=false"
            }
        }
    }
}

object Settings {

    private const val PREFS = "tvmonitor"

    /**
     * Where a trader who has never opened the filters screen starts.
     *
     * Not quite v15.1 any more, and the two departures are deliberate. Search
     * replaces the category feed, because "newest first" does nothing on a
     * category page and everything on a search. The window is 30 minutes rather
     * than 60, at the trader's request.
     */
    val DEFAULTS = Filters(
        cities = "liverpool, manchester",
        radiusMiles = 70,
        useSearch = true,
        searchQuery = "tv",
        // 24 page loads an hour. The desktop build ran at 120 and Facebook
        // answered "You're Temporarily Blocked"; this is a fifth of that.
        checkIntervalSeconds = 150,

        minInches = 0,
        maxPrice = 0,
        maxAgeMinutes = 30,
        requireKnownAge = true,
        blockWords = "stand, stands, bracket, brackets, mount, mounts, mounted, " +
            "firestick, firesticks, fire stick, fire tv stick, fire sticks",
        excludeExtra = ""
    )

    /** Below this the account is being risked for very little extra freshness. */
    const val MIN_INTERVAL_SECONDS = 60
    const val MAX_INTERVAL_SECONDS = 1800

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Filters {
        val p = prefs(context)
        return Filters(
            cities = p.getString("cities", null) ?: DEFAULTS.cities,
            radiusMiles = p.getInt("radiusMiles", DEFAULTS.radiusMiles),
            useSearch = p.getBoolean("useSearch", DEFAULTS.useSearch),
            searchQuery = p.getString("searchQuery", null) ?: DEFAULTS.searchQuery,
            checkIntervalSeconds = p.getInt(
                "checkIntervalSeconds", DEFAULTS.checkIntervalSeconds
            ).coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS),

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
            .putString("cities", f.cities)
            .putInt("radiusMiles", f.radiusMiles)
            .putBoolean("useSearch", f.useSearch)
            .putString("searchQuery", f.searchQuery)
            .putInt(
                "checkIntervalSeconds",
                f.checkIntervalSeconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)
            )
            .putInt("minInches", f.minInches)
            .putInt("maxPrice", f.maxPrice)
            .putInt("maxAgeMinutes", f.maxAgeMinutes)
            .putBoolean("requireKnownAge", f.requireKnownAge)
            .putString("blockWords", f.blockWords)
            .putString("excludeExtra", f.excludeExtra)
            .apply()
    }
}
