package com.espotg.core

/** One entry of an ESP partition table (`esp_partition_info_t`, 32 bytes on flash). */
data class EspPartition(
    val type: Int,
    val subtype: Int,
    val offset: Long,
    val size: Long,
    val label: String,
) {
    /** type 0 = app (factory/ota/test); type 1 = data (nvs, phy, spiffs, ...). */
    val isApp: Boolean get() = type == TYPE_APP

    companion object {
        const val TYPE_APP = 0x00
        const val TYPE_DATA = 0x01
    }
}

object EspPartitionTable {
    /** Default flash address of the partition table. */
    const val DEFAULT_OFFSET = 0x8000L

    /** Max partition-table region size (0xC00 = 3 KiB = 96 entries). */
    const val MAX_SIZE = 0xC00

    private const val ENTRY_SIZE = 32
    private const val ENTRY_MAGIC = 0x50AA // valid partition entry
    private const val MD5_MAGIC = 0xEBEB   // trailing MD5 checksum pseudo-entry

    /**
     * Parses partition entries out of a partition-table region read from flash.
     * Stops at the first non-partition entry (end padding of 0xFF, or the MD5
     * checksum marker). Returns an empty list if the data doesn't look like a
     * partition table at all.
     */
    fun parse(data: ByteArray): List<EspPartition> {
        val out = mutableListOf<EspPartition>()
        var off = 0
        while (off + ENTRY_SIZE <= data.size) {
            val magic = u16(data, off)
            if (magic != ENTRY_MAGIC) break // MD5 marker, 0xFFFF padding, or garbage
            out += EspPartition(
                type = data[off + 2].toInt() and 0xFF,
                subtype = data[off + 3].toInt() and 0xFF,
                offset = u32(data, off + 4),
                size = u32(data, off + 8),
                label = cstr(data, off + 12, 16),
            )
            off += ENTRY_SIZE
        }
        return out
    }

    /**
     * Flash address of the 2nd-stage bootloader for a given chip. ESP32/ESP32-S2
     * place it at 0x1000; all newer chips (S3, C-series, H-series, P4) at 0x0.
     */
    fun bootloaderOffset(chip: TargetChip): Long = when (chip) {
        TargetChip.ESP8266, TargetChip.ESP32, TargetChip.ESP32S2 -> 0x1000L
        else -> 0x0L
    }

    private fun u16(d: ByteArray, off: Int): Int =
        (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(d: ByteArray, off: Int): Long =
        (d[off].toLong() and 0xFF) or
            ((d[off + 1].toLong() and 0xFF) shl 8) or
            ((d[off + 2].toLong() and 0xFF) shl 16) or
            ((d[off + 3].toLong() and 0xFF) shl 24)

    private fun cstr(d: ByteArray, off: Int, maxLen: Int): String {
        val end = minOf(off + maxLen, d.size)
        var nul = off
        while (nul < end && d[nul].toInt() != 0) nul++
        return String(d, off, nul - off, Charsets.UTF_8).trim()
    }
}
