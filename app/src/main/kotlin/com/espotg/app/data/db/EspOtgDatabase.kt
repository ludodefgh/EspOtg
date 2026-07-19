package com.espotg.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DeviceProfileEntity::class], version = 2, exportSchema = false)
abstract class EspOtgDatabase : RoomDatabase() {
    abstract fun deviceProfileDao(): DeviceProfileDao

    companion object {
        /** v2 adds device_profile.gitRepo (device ↔ GitHub repo binding). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE device_profile ADD COLUMN gitRepo TEXT")
            }
        }

        @Volatile
        private var instance: EspOtgDatabase? = null

        fun getInstance(context: Context): EspOtgDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EspOtgDatabase::class.java,
                    "espotg.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
