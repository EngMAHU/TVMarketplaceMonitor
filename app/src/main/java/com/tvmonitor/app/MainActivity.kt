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

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ListingAdapter
    private lateinit var toggleBtn: Button
    private lateinit var statusText: TextView
    private lateinit var emptyText: TextView
    private lateinit var logoutBtn: Button

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

        requestNotifPermission()
        updateUI()
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
            "Monitoring Facebook Marketplace for TVs near Liverpool (60mi) every minute"
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
