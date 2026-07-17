package com.espotg.core

import kotlinx.serialization.Serializable

enum class SpiMode { QIO, QOUT, DIO, DOUT }

enum class SpiFreq(val mhz: Int) {
    MHZ_80(80),
    MHZ_40(40),
    MHZ_26(26),
    MHZ_20(20),
}

enum class FlashSize(val label: String, val bytes: Long?) {
    KEEP("keep", null),
    MB_1("1MB", 1L * 1024 * 1024),
    MB_2("2MB", 2L * 1024 * 1024),
    MB_4("4MB", 4L * 1024 * 1024),
    MB_8("8MB", 8L * 1024 * 1024),
    MB_16("16MB", 16L * 1024 * 1024),
    MB_32("32MB", 32L * 1024 * 1024),
}

/**
 * Flash options mirroring esptool.py's flags, exposed as toggles/pickers in the UI
 * rather than being hardcoded - this is the feature set the user specifically
 * called out as missing from the "reliable" competing app.
 */
@Serializable
data class FlashOptions(
    val syncBaudRate: Int = 115_200,
    val flashBaudRate: Int = 460_800,
    val spiMode: SpiMode = SpiMode.QIO,
    val spiFreq: SpiFreq = SpiFreq.MHZ_40,
    val flashSize: FlashSize = FlashSize.KEEP,
    val autoBootloaderReset: Boolean = true,
    val compression: Boolean = true,
    val verifyAfterWrite: Boolean = true,
)
