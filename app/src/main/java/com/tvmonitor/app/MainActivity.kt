package com.tvmonitor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.tvmonitor.app.adapter.ListingAdapter
import com.tvmonitor.app.data.AppDatabase
import com.tvmonitor.app.service.MonitorService
import com.tvmonitor.app.util.ScanStatus
import com.tvmonitor.app.util.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ListingAdapter
    private lateinit var toggleBtn: Button
    private lateinit var statusText: TextView
    private lateinit var emptyText: TextView
    private lateinit var logoutBtn: Button
    private lateinit var scanSourceText: TextView
    private lateinit var scanCountsText: TextView
    private lateinit var scanRejectsText: TextView

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toggleBtn = findViewById(R.id.toggleBtn)
        statusText = findViewById(R.id.statusText)
        emptyText = findViewById(R.id.emptyText)
        logoutBtn = findViewById(R.id.logoutBtn)
        scanSourceText = findViewById(R.id.scanSourceText)
        scanCountsText = findViewById(R.id.scanCountsText)
        scanRejectsText = findViewById(R.id.scanRejectsText)

        val requireAgeSwitch = findViewById<SwitchCompat>(R.id.requireAgeSwitch)
        requireAgeSwitch.isChecked = Settings.requireKnownAge(this)
        requireAgeSwitch.setOnCheckedChangeListener { _, checked ->
            // The service reads this fresh on every scan, so there is nothing to
            // restart - the next check two and a half minutes from now uses it.
            Settings.setRequireKnownAge(this, checked)
        }
        val recyclerView = findViewById<RecyclerView>(R.id.listingsRecycler)
        val swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)

        adapter = ListingAdapter { listing ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(listing.url)))
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        swipeRefresh.setOnRefreshListener {
            swipeRefresh.isRefreshing = false
        }

        val db = AppDatabase.getInstance(this)
        db.listingDao().getAllLive().observe(this) { listings ->
            adapter.submitList(listings)
            emptyText.visibility = if (listings.isEmpty()) View.VISIBLE else View.GONE
        }

        ScanStatus.latest.observe(this) { report -> showScanReport(report) }

        toggleBtn.setOnClickListener { toggleMonitoring() }
        logoutBtn.setOnClickListener { logout() }

        requestNotifPermission()
        showLastCrashIfAny()
        updateUI()
    }

    /**
     * Shows the stack trace of the previous crash, once, in a dialog that can be
     * copied out.
     *
     * Android's answer to a crash inside a WebView is a prompt offering to
     * uninstall WebView updates system-wide - which changes every app on the
     * phone and fixes nothing when the fault is here. With no way to attach a
     * debugger to this phone, a trace the user can read back is the only route
     * from "it closes" to a fix.
     */
    private fun showLastCrashIfAny() {
        val crash = App.lastCrash(this) ?: return
        App.clearCrash(this)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("The app closed unexpectedly")
            .setMessage(crash.take(3000))
            .setPositiveButton("Copy") { _, _ ->
                val cb = getSystemService(android.content.ClipboardManager::class.java)
                cb.setPrimaryClip(android.content.ClipData.newPlainText("crash", crash))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * What the last scan saw, in the card that used to hold three hardcoded
     * lines - one of which described a search radius the app does not use and a
     * check interval it never ran at.
     *
     * The rejection counts are the point. Six listings found means nothing on
     * its own: six out of eight is a market, six out of ninety is a filter doing
     * most of the work, and until now the app showed the same thing either way.
     */
    private fun showScanReport(report: ScanStatus.Report?) {
        if (report == null) return
        val time = SimpleDateFormat("HH:mm", Locale.UK).format(Date(report.at))

        scanSourceText.text = "${report.source} - $time"
        scanCountsText.text = when {
            report.error != null -> "Scan failed: ${report.error}"
            // Not "no TVs". The page rendered and held no cards at all, which is
            // what Facebook serves when it is rate limiting or refusing.
            report.blank -> "Page came back empty - no cards at all"
            else -> "${report.total} cards read, ${report.kept} passed the filters"
        }
        scanRejectsText.text =
            if (report.blank || report.error != null) "" else report.breakdown()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun toggleMonitoring() {
        if (MonitorService.isRunning) {
            MonitorService.stop(this)
        } else {
            MonitorService.start(this)
        }
        toggleBtn.postDelayed({ updateUI() }, 500)
    }

    private fun updateUI() {
        val running = MonitorService.isRunning
        toggleBtn.text = if (running) "STOP MONITORING" else "START MONITORING"
        toggleBtn.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (running) R.color.stop_red else R.color.start_green
            )
        )
        statusText.text = if (running)
            "Four feeds in turn - Liverpool and Manchester, search and category"
        else
            "Monitor is stopped. Tap below to start."
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun logout() {
        MonitorService.stop(this)
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
