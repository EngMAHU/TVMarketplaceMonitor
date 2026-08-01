package com.tvmonitor.app

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.tvmonitor.app.util.Filters
import com.tvmonitor.app.util.Settings

/**
 * Lets the trader change what counts as worth alerting on.
 *
 * The matching rules - what is a television, what is furniture, what is a
 * wall-mounting advert - stay in the code, because they were earned against a
 * day of real listings and a text box is no way to maintain them. What is
 * exposed here is the part that is a judgement rather than a fact: how old, how
 * dear, how small, and what words to skip.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var maxAge: EditText
    private lateinit var minInches: EditText
    private lateinit var maxPrice: EditText
    private lateinit var blockWords: EditText
    private lateinit var excludeExtra: EditText
    private lateinit var requireKnownAge: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        maxAge = findViewById(R.id.maxAgeInput)
        minInches = findViewById(R.id.minInchesInput)
        maxPrice = findViewById(R.id.maxPriceInput)
        blockWords = findViewById(R.id.blockWordsInput)
        excludeExtra = findViewById(R.id.excludeExtraInput)
        requireKnownAge = findViewById(R.id.requireKnownAgeSwitch)

        show(Settings.load(this))

        findViewById<Button>(R.id.saveBtn).setOnClickListener { save() }
        findViewById<Button>(R.id.resetBtn).setOnClickListener {
            show(Settings.DEFAULTS)
            Toast.makeText(this, "Back to the original settings - tap SAVE to keep them", Toast.LENGTH_LONG).show()
        }
    }

    private fun show(f: Filters) {
        maxAge.setText(f.maxAgeMinutes.toString())
        minInches.setText(f.minInches.toString())
        maxPrice.setText(f.maxPrice.toString())
        blockWords.setText(f.blockWords)
        excludeExtra.setText(f.excludeExtra)
        requireKnownAge.isChecked = f.requireKnownAge
    }

    private fun save() {
        // An empty or unparseable box means "leave this test off" rather than
        // zero-by-accident, except for the age window, where zero would silently
        // reject every listing there is. That one falls back to the default.
        val age = number(maxAge, Settings.DEFAULTS.maxAgeMinutes).coerceAtLeast(1)

        Settings.save(
            this,
            Filters(
                minInches = number(minInches, 0).coerceIn(0, 200),
                maxPrice = number(maxPrice, 0).coerceAtLeast(0),
                maxAgeMinutes = age,
                requireKnownAge = requireKnownAge.isChecked,
                blockWords = blockWords.text.toString().trim(),
                excludeExtra = excludeExtra.text.toString().trim()
            )
        )
        Toast.makeText(this, "Saved. Applies on the next check.", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun number(field: EditText, fallback: Int): Int =
        field.text.toString().trim().toIntOrNull() ?: fallback
}
