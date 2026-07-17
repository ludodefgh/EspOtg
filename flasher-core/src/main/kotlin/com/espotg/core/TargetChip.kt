package com.espotg.core

/**
 * Chip families supported by esp-serial-flasher, mirrored *exactly* (same order,
 * same values) from `target_chip_t` in include/esp_loader.h - the native
 * `nativeGetTargetChip()` call returns the raw numeric value of that C enum, so
 * the ordinal order here must not be "cleaned up" independently of the C side.
 */
enum class TargetChip(val nativeValue: Int) {
    ESP8266(0),
    ESP32(1),
    ESP32S2(2),
    ESP32C3(3),
    ESP32S3(4),
    ESP32C2(5),
    ESP32C5(6),
    ESP32H2(7),
    ESP32C6(8),
    ESP32P4(9),
    ESP32C61(10),
    UNKNOWN(11);

    companion object {
        fun fromNativeValue(value: Int): TargetChip = entries.firstOrNull { it.nativeValue == value } ?: UNKNOWN
    }
}
