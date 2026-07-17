package com.espotg.core

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One binary in the flash queue: a file (referenced by a persisted SAF URI string)
 * paired with the address it should be written to. Multiple entries make up a
 * [FlashPlan], e.g. bootloader.bin@0x1000 + partitions.bin@0x8000 + app.bin@0x10000.
 */
@Serializable
data class FlashEntry(
    val id: String = UUID.randomUUID().toString(),
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val offset: HexOffset,
    val forceRawData: Boolean = false,
)

@Serializable
data class FlashPlan(
    val entries: List<FlashEntry>,
    val options: FlashOptions,
)

/** Common offset presets, chip-family dependent, to pre-fill (but never lock) the offset field. */
object OffsetPresets {
    fun forChip(chip: TargetChip): List<Pair<String, HexOffset>> = when (chip) {
        TargetChip.ESP8266 -> listOf(
            "app" to HexOffset.of(0x0),
        )
        TargetChip.ESP32 -> listOf(
            "bootloader" to HexOffset.of(0x1000),
            "partition table" to HexOffset.of(0x8000),
            "app" to HexOffset.of(0x10000),
        )
        TargetChip.ESP32S2, TargetChip.ESP32P4 -> listOf(
            "bootloader" to HexOffset.of(0x1000),
            "partition table" to HexOffset.of(0x8000),
            "app" to HexOffset.of(0x10000),
        )
        else -> listOf(
            "bootloader" to HexOffset.of(0x0),
            "partition table" to HexOffset.of(0x8000),
            "app" to HexOffset.of(0x10000),
        )
    }
}
