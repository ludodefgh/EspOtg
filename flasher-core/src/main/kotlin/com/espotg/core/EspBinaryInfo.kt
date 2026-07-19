package com.espotg.core

import kotlinx.serialization.Serializable

enum class EspImageType {
    /** Application image: has an esp_app_desc_t (magic 0xABCD5432). */
    APP,

    /** Second-stage bootloader: has an esp_bootloader_desc_t (magic byte 0x50). */
    BOOTLOADER,

    /** Valid ESP image header (0xE9) but no recognizable description struct. */
    ESP_IMAGE,

    /** Not an ESP image (partition table, NVS, SPIFFS, raw data, ...). */
    DATA,
}

/**
 * Metadata parsed straight out of a firmware binary's own header structures - the
 * same info the competing apps surface when you pick a file. Everything comes from
 * the ESP image header (`esp_image_header_t`) plus `esp_app_desc_t` /
 * `esp_bootloader_desc_t`; no network or external service. Offsets/struct layouts
 * verified against ESP-IDF v5.5 headers and real built binaries.
 */
@Serializable
data class EspBinaryInfo(
    val type: EspImageType,
    val chipName: String? = null,
    val projectName: String? = null,
    val appVersion: String? = null,
    val idfVersion: String? = null,
    /** Compile date, e.g. "Jul 19 2026". */
    val compileDate: String? = null,
    /** Compile time, e.g. "11:09:14" (apps only; bootloaders fold date+time into compileDate). */
    val compileTime: String? = null,
    val flashMode: String? = null,
    val flashFreq: String? = null,
    val flashSize: String? = null,
) {
    companion object {
        private const val IMAGE_MAGIC = 0xE9
        private const val APP_DESC_MAGIC = 0xABCD5432L
        private const val BOOTLOADER_DESC_MAGIC = 0x50
        private const val DESC_OFFSET = 0x20 // image header (24) + first segment header (8)

        fun parse(data: ByteArray): EspBinaryInfo {
            if (data.size < 24 || (data[0].toInt() and 0xFF) != IMAGE_MAGIC) {
                return EspBinaryInfo(EspImageType.DATA)
            }

            val chipName = chipName(u16(data, 12))
            val flashMode = flashMode(data[2].toInt() and 0xFF)
            val sizeFreq = data[3].toInt() and 0xFF
            val flashFreq = flashFreq(sizeFreq and 0x0F)
            val flashSize = flashSize((sizeFreq shr 4) and 0x0F)

            val header = { type: EspImageType ->
                EspBinaryInfo(type, chipName = chipName, flashMode = flashMode, flashFreq = flashFreq, flashSize = flashSize)
            }

            if (data.size >= DESC_OFFSET + 4 && u32(data, DESC_OFFSET) == APP_DESC_MAGIC) {
                return header(EspImageType.APP).copy(
                    appVersion = cstr(data, DESC_OFFSET + 0x10, 32),
                    projectName = cstr(data, DESC_OFFSET + 0x30, 32),
                    compileTime = cstr(data, DESC_OFFSET + 0x50, 16),
                    compileDate = cstr(data, DESC_OFFSET + 0x60, 16),
                    idfVersion = cstr(data, DESC_OFFSET + 0x70, 32),
                )
            }
            if (data.size > DESC_OFFSET && (data[DESC_OFFSET].toInt() and 0xFF) == BOOTLOADER_DESC_MAGIC) {
                return header(EspImageType.BOOTLOADER).copy(
                    appVersion = u32(data, DESC_OFFSET + 4).takeIf { it != 0L }?.toString(),
                    idfVersion = cstr(data, DESC_OFFSET + 8, 32),
                    compileDate = cstr(data, DESC_OFFSET + 8 + 32, 24),
                )
            }
            return header(EspImageType.ESP_IMAGE)
        }

        private fun u16(d: ByteArray, off: Int): Int =
            (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8)

        private fun u32(d: ByteArray, off: Int): Long =
            (d[off].toLong() and 0xFF) or
                ((d[off + 1].toLong() and 0xFF) shl 8) or
                ((d[off + 2].toLong() and 0xFF) shl 16) or
                ((d[off + 3].toLong() and 0xFF) shl 24)

        private fun cstr(d: ByteArray, off: Int, maxLen: Int): String? {
            if (off >= d.size) return null
            val end = minOf(off + maxLen, d.size)
            var nul = off
            while (nul < end && d[nul].toInt() != 0) nul++
            val s = String(d, off, nul - off, Charsets.UTF_8).trim()
            return s.ifEmpty { null }
        }

        private fun chipName(chipId: Int): String? = when (chipId) {
            0x0000 -> "ESP32"
            0x0002 -> "ESP32-S2"
            0x0005 -> "ESP32-C3"
            0x0009 -> "ESP32-S3"
            0x000C -> "ESP32-C2"
            0x000D -> "ESP32-C6"
            0x0010 -> "ESP32-H2"
            0x0012 -> "ESP32-P4"
            0x0014 -> "ESP32-C61"
            0x0017 -> "ESP32-C5"
            0x0019 -> "ESP32-H21"
            0x001C -> "ESP32-H4"
            else -> null
        }

        private fun flashMode(mode: Int): String? = when (mode) {
            0 -> "QIO"
            1 -> "QOUT"
            2 -> "DIO"
            3 -> "DOUT"
            4 -> "FAST_READ"
            5 -> "SLOW_READ"
            else -> null
        }

        private fun flashFreq(nibble: Int): String? = when (nibble) {
            0x0 -> "40 MHz"
            0x1 -> "26 MHz"
            0x2 -> "20 MHz"
            0xF -> "80 MHz"
            else -> null
        }

        private fun flashSize(nibble: Int): String? = when (nibble) {
            0x0 -> "1MB"
            0x1 -> "2MB"
            0x2 -> "4MB"
            0x3 -> "8MB"
            0x4 -> "16MB"
            0x5 -> "32MB"
            0x6 -> "64MB"
            0x7 -> "128MB"
            else -> null
        }
    }
}
