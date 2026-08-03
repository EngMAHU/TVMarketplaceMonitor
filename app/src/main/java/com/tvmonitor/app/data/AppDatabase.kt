package com.tvmonitor.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Listing::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun listingDao(): ListingDao

    companion object {
        /**
         * Adds the listing's real age.
         *
         * A migration rather than a wipe: the table holds a week of listings the
         * trader may still be working through, and losing them to a version bump
         * would be a self-inflicted bug. Existing rows get NULL, which is
         * honest - their age was never recorded and cannot be worked out now.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE listings ADD COLUMN ageMinutes INTEGER")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tv_monitor.db"
                ).addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
    }
}
