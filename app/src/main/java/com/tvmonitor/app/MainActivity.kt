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
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.tvmonitor.app.adapter.ListingAdapter
import com.tvmonitor.app.data.AppDatabase
import com.tvmonitor.app.service.MonitorService
import com.tvmonitor.app.util.ExitReasons

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ListingAdapter
    private lateinit var toggleBtn: Button
    private lateinit var statusText: TextView
    private lateinit var emptyText: TextView
    private lateinit var logoutBtn: Button
    private lateinit var filtersBtn: Button

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
        filtersBtn = findViewById(R.id.filtersBtn)
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

        toggleBtn.setOnClickListener { toggleMonitoring() }
        logoutBtn.setOnClickListener { logout() }
        filtersBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        requestNotifPermission()
        showLastFailureIfAny()
        updateUI()
    }

    /**
     * Shows why the app last closed, once, in a dialog that can be copied out.
     *
     * Two sources, because one of them was never going to be enough. The
     * uncaught-exception handler catches bugs in this app's own Kotlin. It
     * stayed empty through every occurrence of the app closing - which was read
     * as "no crash happened" when what it actually means is that the process
     * died in a way no Java handler can see: a native crash inside WebView, an
     * ANR, or the low-memory killer. So the system's own record is read too.
     *
     * Android's answer to any of these is a prompt offering to uninstall WebView
     * updates system-wide, which changes every app on the phone and fixes
     * nothing when the fault is here. With no way to attach a debugger to this
     * phone, a reading the trader can send back is the only route from "it keeps
     * stopping" to a fix.
     */
    private fun showLastFailureIfAny() {
        val crash = App.lastCrash(this)?.also { App.clearCrash(this) }
        val exit = ExitReasons.unreportedExit(this)

        // The stack trace is the more useful of the two whenever there is one,
        // so it leads. The system's reason is still appended: it says whether
        // the trace is what actually killed the process or just preceded it.
        val report = listOfNotNull(crash, exit).joinToString("\n\n----\n\n")
        if (report.isBlank()) return

        // Which build this came from. Without it, a trace has to be matched to a
        // build by counting line numbers in three candidates - which is exactly
        // what reading the last one required.
        val stamped = "build $appVersion\n\n$report"

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("The app closed unexpectedly")
            .setMessage(stamped.take(4000))
            .setPositiveButton("Copy") { _, _ ->
                val cb = getSystemService(android.content.ClipboardManager::class.java)
                cb.setPrimaryClip(android.content.ClipData.newPlainText("crash", stamped))
            }
            .setNegativeButton("Close", null)
            .show()
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
            "Checking the Liverpool and Manchester TV feeds in turn, one every 2.5 minutes"
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

    /** The CI run this APK was built from, e.g. "1.0.8". "1.0.dev" if local. */
    private val appVersion: String
        get() = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

    private fun logout() {
        MonitorService.stop(this)
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
