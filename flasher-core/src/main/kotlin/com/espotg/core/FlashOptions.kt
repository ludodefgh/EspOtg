package com.espotg.core

import kotlinx.serialization.Serializable

/**
 * SPI flash parameters. Compiled ESP images embed these in their 8-byte header
 * (magic 0xE9, then flash_mode at byte 2 and size<<4|freq at byte 3) - so KEEP
 * (esptool's default) means "trust what the build produced, don't touch the
 * header", and an explicit value means "patch the image header before writing",
 * exactly like esptool's --flash_mode/--flash_freq/--flash_size flags.
 */
enum class SpiMode(val headerValue: Int?) {
    KEEP(null),
    QIO(0),
    QOUT(1),
    DIO(2),
    DOUT(3),
}

enum class SpiFreq(val label: String, val headerValue: Int?) {
    KEEP("keep", null),
    MHZ_80("80 MHz", 0xF),
    MHZ_40("40 MHz", 0x0),
    MHZ_26("26 MHz", 0x1),
    MHZ_20("20 MHz", 0x2),
}

enum class FlashSize(val label: String, val headerValue: Int?) {
    KEEP("keep", null),
    MB_1("1MB", 0x0),
    MB_2("2MB", 0x1),
    MB_4("4MB", 0x2),
    MB_8("8MB", 0x3),
    MB_16("16MB", 0x4),
    MB_32("32MB", 0x5),
}

/**
 * Flash options mirroring esptool.py's flags, exposed as toggles/pickers in the UI
 * rather than being hardcoded - this is the feature set the user specifically
 * called out as missing from the "reliable" competing app. SPI settings default
 * to KEEP (like esptool): the values compiled into the image header win unless
 * explicitly overridden.
 */
@Serializable
data class FlashOptions(
    val syncBaudRate: Int = 115_200,
    val flashBaudRate: Int = 460_800,
    val spiMode: SpiMode = SpiMode.KEEP,
    val spiFreq: SpiFreq = SpiFreq.KEEP,
    val flashSize: FlashSize = FlashSize.KEEP,
    val autoBootloaderReset: Boolean = true,
    val compression: Boolean = true,
    val verifyAfterWrite: Boolean = true,
)

/** ESP image header constants/helpers shared by the header-patching logic. */
object EspImageHeader {
    const val MAGIC = 0xE9

    /**
     * Returns a copy of [data] with the SPI mode/freq/size fields patched in the
     * image header, esptool-style - or [data] unchanged if it isn't an ESP image
     * (no 0xE9 magic) or if all three options are KEEP.
     */
    fun patch(data: ByteArray, spiMode: SpiMode, spiFreq: SpiFreq, flashSize: FlashSize): ByteArray {
        if (data.size < 4 || (data[0].toInt() and 0xFF) != MAGIC) {
            return data
        }
        if (spiMode.headerValue == null && spiFreq.headerValue == null && flashSize.headerValue == null) {
            return data
        }
        val patched = data.copyOf()
        spiMode.headerValue?.let { patched[2] = it.toByte() }
        val sizeFreq = patched[3].toInt() and 0xFF
        val newFreq = spiFreq.headerValue ?: (sizeFreq and 0x0F)
        val newSize = flashSize.headerValue ?: ((sizeFreq shr 4) and 0x0F)
        patched[3] = ((newSize shl 4) or newFreq).toByte()
        return patched
    }
}
