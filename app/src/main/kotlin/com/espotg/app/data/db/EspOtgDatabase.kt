package com.espotg.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [DeviceProfileEntity::class], version = 1, exportSchema = false)
abstract class EspOtgDatabase : RoomDatabase() {
    abstract fun deviceProfileDao(): DeviceProfileDao

    companion object {
        @Volatile
        private var instance: EspOtgDatabase? = null

        fun getInstance(context: Context): EspOtgDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EspOtgDatabase::class.java,
                    "espotg.db",
                ).build().also { instance = it }
            }
    }
}
