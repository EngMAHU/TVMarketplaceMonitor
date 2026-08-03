package com.tvmonitor.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "listings")
data class Listing(
    @PrimaryKey val id: String,
    val title: String,
    val price: String,
    val imageUrl: String,
    val location: String,
    val url: String,
    val discoveredAt: Long = System.currentTimeMillis(),
    val seen: Boolean = false,

    /**
     * How old the listing was when it was found, in minutes, from Facebook's own
     * creation_time. Null when that was never learned.
     *
     * Not the same thing as discoveredAt, and the difference is the entire point
     * of the app. discoveredAt says when this phone noticed; it says nothing
     * about whether the listing had been sitting there for an hour first. The
     * card showed "Just now" for a fifty-minute-old listing on that basis, which
     * is exactly the wrong thing to tell someone whose stock sells in ten
     * minutes. scan.js has always worked this out and it was thrown away between
     * the scan and the database.
     */
    val ageMinutes: Int? = null
)
