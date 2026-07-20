package com.espotg.app.data

import com.espotg.app.data.db.DeviceProfileDao
import com.espotg.app.data.db.DeviceProfileEntity
import com.espotg.core.FlashEntry
import com.espotg.core.FlashOptions
import com.espotg.core.TargetChip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class DeviceProfile(
    val macAddress: String,
    val chipType: TargetChip,
    val usbSerialNumber: String?,
    val label: String?,
    val flashOptions: FlashOptions,
    val flashEntries: List<FlashEntry>,
    val lastUsedAtEpochMs: Long,
    val gitRepo: String? = null,
)

/** Loads/saves the last-used [DeviceProfile] per physical device - see ChipIdentity for the identity model. */
class DeviceProfileRepository(private val dao: DeviceProfileDao) {

    private val json = Json { ignoreUnknownKeys = true }

    fun observeAll(): Flow<List<DeviceProfile>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    /**
     * Pre-connect lookup by USB serial number, before a chip's MAC is known - a
     * non-authoritative hint only (serials aren't unique across cheap boards); the
     * caller reconciles against the MAC via [findByMac] once connected. See
     * ChipIdentity.
     */
    suspend fun findByUsbSerialNumber(serial: String): DeviceProfile? = dao.findByUsbSerialNumber(serial)?.toModel()

    /** Authoritative lookup by efuse MAC - the source of truth for device identity. */
    suspend fun findByMac(mac: String): DeviceProfile? = dao.findByMac(mac)?.toModel()

    suspend fun save(profile: DeviceProfile) = dao.upsert(profile.toEntity())

    suspend fun delete(profile: DeviceProfile) = dao.delete(profile.toEntity())

    private fun DeviceProfileEntity.toModel() = DeviceProfile(
        macAddress = macAddress,
        chipType = TargetChip.valueOf(chipType),
        usbSerialNumber = usbSerialNumber,
        label = label,
        flashOptions = json.decodeFromString<FlashOptions>(lastFlashOptionsJson),
        flashEntries = json.decodeFromString<List<FlashEntry>>(lastEntriesJson),
        lastUsedAtEpochMs = lastUsedAtEpochMs,
        gitRepo = gitRepo,
    )

    private fun DeviceProfile.toEntity() = DeviceProfileEntity(
        macAddress = macAddress,
        chipType = chipType.name,
        usbSerialNumber = usbSerialNumber,
        label = label,
        lastFlashOptionsJson = json.encodeToString(flashOptions),
        lastEntriesJson = json.encodeToString(flashEntries),
        lastUsedAtEpochMs = lastUsedAtEpochMs,
        gitRepo = gitRepo,
    )
}
