package com.espotg.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One row per physical ESP device, keyed by its efuse MAC address (see
 * ChipIdentity in flasher-core - that's the source-of-truth identity; only
 * `usbSerialNumber` is a secondary lookup key for the pre-connect UX).
 * [lastFlashOptionsJson]/[lastEntriesJson] are kotlinx.serialization JSON blobs
 * of FlashOptions/List<FlashEntry> - plain JSON rather than normalized child
 * tables since the only access pattern is "load/save the whole plan for one
 * device", not querying into its contents.
 */
@Entity(tableName = "device_profile")
data class DeviceProfileEntity(
    @PrimaryKey val macAddress: String,
    val chipType: String,
    val usbSerialNumber: String?,
    val label: String?,
    val lastFlashOptionsJson: String,
    val lastEntriesJson: String,
    val lastUsedAtEpochMs: Long,
    /** Optional "owner/repo" this device is linked to, for browsing/flashing its GitHub releases. */
    val gitRepo: String? = null,
)

@Dao
interface DeviceProfileDao {
    @Query("SELECT * FROM device_profile WHERE macAddress = :macAddress")
    suspend fun findByMac(macAddress: String): DeviceProfileEntity?

    @Query("SELECT * FROM device_profile WHERE usbSerialNumber = :serial LIMIT 1")
    suspend fun findByUsbSerialNumber(serial: String): DeviceProfileEntity?

    @Query("SELECT * FROM device_profile ORDER BY lastUsedAtEpochMs DESC")
    fun observeAll(): Flow<List<DeviceProfileEntity>>

    @Upsert
    suspend fun upsert(entity: DeviceProfileEntity)

    @Delete
    suspend fun delete(entity: DeviceProfileEntity)
}
