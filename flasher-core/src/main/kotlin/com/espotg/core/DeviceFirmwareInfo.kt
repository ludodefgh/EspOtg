package com.espotg.core

/**
 * What's actually installed on a connected ESP right now, read back from its
 * flash (bootloader + partition table + each app partition's `esp_app_desc_t`),
 * as opposed to [EspBinaryInfo] which describes a file about to be flashed.
 */
data class DeviceFirmwareInfo(
    val chip: TargetChip,
    val macAddress: String,
    val flashSizeBytes: Long?,
    val bootloader: EspBinaryInfo?,
    val appPartitions: List<AppPartitionInfo>,
)

data class AppPartitionInfo(
    val label: String,
    val offset: Long,
    /** "factory", "ota_0"..."ota_15", "test", or "app" when unrecognized. */
    val subtypeName: String,
    val info: EspBinaryInfo?,
) {
    companion object {
        fun subtypeName(subtype: Int): String = when (subtype) {
            0x00 -> "factory"
            0x20 -> "test"
            in 0x10..0x1F -> "ota_${subtype - 0x10}"
            else -> "app"
        }
    }
}
