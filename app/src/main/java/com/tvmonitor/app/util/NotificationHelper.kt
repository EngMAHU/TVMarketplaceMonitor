package com.tvmonitor.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.tvmonitor.app.MainActivity
import com.tvmonitor.app.R
import com.tvmonitor.app.data.Listing

object NotificationHelper {

    const val CHANNEL_SERVICE = "monitor_service"
    const val CHANNEL_LISTINGS = "new_listings"
    const val SERVICE_ID = 1
    private var nextId = 100

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE, "Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the marketplace monitor running" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LISTINGS, "New TV Listings",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when new TVs are listed"
                enableVibration(true)
            }
        )
    }

    fun serviceNotification(context: Context, listingCount: Int, lastCheck: String): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_tv)
            .setContentTitle("TV Monitor Active")
            .setContentText("$listingCount TVs found | Last check: $lastCheck")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    fun notifyNewListings(context: Context, listings: List<Listing>) {
        val nm = context.getSystemService(NotificationManager::class.java)

        if (listings.size == 1) {
            val listing = listings[0]
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(listing.url))
            val pi = PendingIntent.getActivity(
                context, nextId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            nm.notify(
                nextId++,
                NotificationCompat.Builder(context, CHANNEL_LISTINGS)
                    .setSmallIcon(R.drawable.ic_tv)
                    .setContentTitle("New TV: ${listing.price}")
                    .setContentText(listing.title)
                    .setSubText(listing.location)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build()
            )
        } else {
            val intent = Intent(context, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                context, nextId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val style = NotificationCompat.InboxStyle()
                .setBigContentTitle("${listings.size} New TVs Found")
            listings.take(5).forEach { style.addLine("${it.price} - ${it.title}") }
            if (listings.size > 5) style.setSummaryText("+${listings.size - 5} more")

            nm.notify(
                nextId++,
                NotificationCompat.Builder(context, CHANNEL_LISTINGS)
                    .setSmallIcon(R.drawable.ic_tv)
                    .setContentTitle("${listings.size} New TVs Listed")
                    .setContentText("Tap to view all listings")
                    .setStyle(style)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build()
            )
        }
    }
}
