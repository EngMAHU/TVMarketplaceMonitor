package com.tvmonitor.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.tvmonitor.app.util.Filters
import com.tvmonitor.app.util.Settings
import kotlin.math.roundToInt

/**
 * Lets the trader change what is scanned and what counts as worth alerting on.
 *
 * The matching rules - what is a television, what is furniture, what is a
 * wall-mounting advert - stay in the code, because they were earned against a
 * day of real listings and a text box is no way to maintain them. What is
 * exposed here is everything that is a judgement rather than a fact: which
 * towns, how far, how often, how old, how dear, how small, what words to skip.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var cities: EditText
    private lateinit var radius: EditText
    private lateinit var searchQuery: EditText
    private lateinit var intervalField: EditText
    private lateinit var maxAge: EditText
    private lateinit var minInches: EditText
    private lateinit var maxPrice: EditText
    private lateinit var blockWords: EditText
    private lateinit var excludeExtra: EditText
    private lateinit var useSearch: SwitchCompat
    private lateinit var requireKnownAge: SwitchCompat
    private lateinit var rateSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        cities = findViewById(R.id.citiesInput)
        radius = findViewById(R.id.radiusInput)
        searchQuery = findViewById(R.id.searchQueryInput)
        intervalField = findViewById(R.id.intervalInput)
        maxAge = findViewById(R.id.maxAgeInput)
        minInches = findViewById(R.id.minInchesInput)
        maxPrice = findViewById(R.id.maxPriceInput)
        blockWords = findViewById(R.id.blockWordsInput)
        excludeExtra = findViewById(R.id.excludeExtraInput)
        useSearch = findViewById(R.id.useSearchSwitch)
        requireKnownAge = findViewById(R.id.requireKnownAgeSwitch)
        rateSummary = findViewById(R.id.rateSummary)

        show(Settings.load(this))

        // The summary is live because the number that matters is not the one
        // being typed. Two towns at five minutes is ten minutes per town, and
        // listings sell in about ten - so a setting that reads as "every five
        // minutes" can quietly mean "after it has already gone". Better to show
        // that while it is being chosen than to let it be discovered.
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateRateSummary()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        intervalField.addTextChangedListener(watcher)
        cities.addTextChangedListener(watcher)
        updateRateSummary()

        findViewById<Button>(R.id.saveBtn).setOnClickListener { save() }
        findViewById<Button>(R.id.resetBtn).setOnClickListener {
            show(Settings.DEFAULTS)
            updateRateSummary()
            Toast.makeText(
                this, "Back to the recommended settings - tap SAVE to keep them",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun show(f: Filters) {
        cities.setText(f.cities)
        radius.setText(f.radiusMiles.toString())
        searchQuery.setText(f.searchQuery)
        intervalField.setText(minutesText(f.checkIntervalSeconds))
        maxAge.setText(f.maxAgeMinutes.toString())
        minInches.setText(f.minInches.toString())
        maxPrice.setText(f.maxPrice.toString())
        blockWords.setText(f.blockWords)
        excludeExtra.setText(f.excludeExtra)
        useSearch.isChecked = f.useSearch
        requireKnownAge.isChecked = f.requireKnownAge
    }

    /** 150s reads as "2.5", 300s as "5" - not "2.5" and "5.0". */
    private fun minutesText(seconds: Int): String {
        val m = seconds / 60.0
        return if (m == m.roundToInt().toDouble()) m.roundToInt().toString()
        else String.format("%.1f", m)
    }

    private fun updateRateSummary() {
        val f = collect()
        val towns = f.cityList().size
        val perTown = f.perCitySeconds() / 60.0
        val loads = f.loadsPerHour()

        val risk = when {
            loads > 60 -> "  RISKY - the desktop version was blocked at 120/hour."
            loads > 30 -> "  Fine, but this is the busiest worth running."
            else -> "  Comfortably under what got the desktop blocked."
        }
        rateSummary.text =
            "$loads page loads an hour.$risk\n" +
            "With $towns town${if (towns == 1) "" else "s"}, each is checked every " +
            "${minutesText((perTown * 60).roundToInt())} minutes."
    }

    /** The screen as it stands, clamped the same way SAVE will clamp it. */
    private fun collect(): Filters {
        val seconds = (decimal(intervalField, 2.5) * 60).roundToInt()
            .coerceIn(Settings.MIN_INTERVAL_SECONDS, Settings.MAX_INTERVAL_SECONDS)
        return Filters(
            cities = cities.text.toString().trim(),
            radiusMiles = number(radius, Settings.DEFAULTS.radiusMiles).coerceIn(1, 300),
            useSearch = useSearch.isChecked,
            searchQuery = searchQuery.text.toString().trim().ifBlank { "tv" },
            checkIntervalSeconds = seconds,
            minInches = number(minInches, 0).coerceIn(0, 200),
            maxPrice = number(maxPrice, 0).coerceAtLeast(0),
            // Zero here would reject every listing there is, silently. An empty
            // or nonsense box falls back to the default instead.
            maxAgeMinutes = number(maxAge, Settings.DEFAULTS.maxAgeMinutes)
                .coerceAtLeast(1),
            requireKnownAge = requireKnownAge.isChecked,
            blockWords = blockWords.text.toString().trim(),
            excludeExtra = excludeExtra.text.toString().trim()
        )
    }

    private fun save() {
        Settings.save(this, collect())
        Toast.makeText(this, "Saved. Applies on the next check.", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun number(field: EditText, fallback: Int): Int =
        field.text.toString().trim().toIntOrNull() ?: fallback

    private fun decimal(field: EditText, fallback: Double): Double =
        field.text.toString().trim().toDoubleOrNull() ?: fallback
}
