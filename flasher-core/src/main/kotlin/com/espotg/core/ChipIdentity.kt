package com.espotg.core

/**
 * Identifies a physical ESP device so its last-used [FlashPlan] can be reloaded
 * next time it's plugged in.
 *
 * [macAddress] is the source of truth: it's read straight from the chip's efuses
 * during the bootloader sync that's mandatory before any flash operation anyway,
 * so it's always available and uniquely tied to that specific chip.
 *
 * [usbSerialNumber] is a secondary, optional key: some USB-serial adapters expose
 * a serial number descriptor that's readable before a full connection/sync, which
 * lets the UI suggest "reload last profile?" immediately on USB attach - the MAC
 * lookup then confirms/corrects that guess once the connection completes.
 */
data class ChipIdentity(
    val macAddress: String,
    val chipType: TargetChip,
    val usbSerialNumber: String? = null,
)

/** A single line for the live log console shown during flashing and monitoring. */
data class LogLine(
    val timestampMs: Long,
    val level: LogLevel,
    val message: String,
)

enum class LogLevel { NONE, ERROR, WARN, INFO, DEBUG }
