package com.tvmonitor.app.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ListingDao {

    @Query("SELECT * FROM listings ORDER BY discoveredAt DESC")
    fun getAllLive(): LiveData<List<Listing>>

    @Query("SELECT id FROM listings")
    suspend fun getAllIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(listings: List<Listing>): List<Long>

    @Query("UPDATE listings SET seen = 1 WHERE id = :id")
    suspend fun markSeen(id: String)

    @Query("DELETE FROM listings WHERE discoveredAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM listings")
    suspend fun count(): Int
}
