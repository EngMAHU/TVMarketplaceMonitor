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
    val seen: Boolean = false
)
