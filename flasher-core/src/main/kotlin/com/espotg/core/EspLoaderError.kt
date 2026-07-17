package com.espotg.core

/**
 * Mirrors `esp_loader_error_t` (include/esp_loader_error.h) *exactly* - same
 * order, same values - since native code returns the raw ordinal of that C enum.
 */
enum class EspLoaderError(val nativeValue: Int) {
    SUCCESS(0),
    FAIL(1),
    TIMEOUT(2),
    IMAGE_SIZE(3),
    INVALID_MD5(4),
    INVALID_PARAM(5),
    INVALID_TARGET(6),
    UNSUPPORTED_CHIP(7),
    UNSUPPORTED_FUNC(8),
    INVALID_RESPONSE(9);

    companion object {
        fun fromNativeValue(value: Int): EspLoaderError =
            entries.firstOrNull { it.nativeValue == value }
                ?: throw IllegalArgumentException("Unknown esp_loader_error_t value: $value")
    }
}

/**
 * Typed exception thrown by the flasher-native JNI layer when a native call
 * returns anything other than ESP_LOADER_SUCCESS.
 */
class EspLoaderException(
    val error: EspLoaderError,
    message: String = error.name,
) : Exception(message)
