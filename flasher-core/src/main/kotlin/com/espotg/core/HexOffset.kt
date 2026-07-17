package com.espotg.core

import kotlinx.serialization.Serializable

/**
 * A flash offset/address, always representable and displayed as hex.
 *
 * Users type offsets freely (e.g. "0x1000", "1000", "0X8000") instead of picking
 * from a fixed dropdown of presets - presets are offered as suggestions that just
 * fill this same free-text field.
 */
@Serializable
@JvmInline
value class HexOffset private constructor(val value: Long) {

    fun toHexString(): String = "0x%X".format(value)

    override fun toString(): String = toHexString()

    companion object {
        val ZERO = HexOffset(0L)

        fun of(value: Long): HexOffset {
            require(value >= 0) { "Offset must not be negative: $value" }
            return HexOffset(value)
        }

        /**
         * Parses a user-typed offset. Accepts optional "0x"/"0X" prefix, otherwise
         * assumes hex digits (matching how esptool/flash offsets are conventionally
         * written and read back), case-insensitive, surrounding whitespace ignored.
         */
        fun parse(raw: String): Result<HexOffset> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                return Result.failure(IllegalArgumentException("Offset cannot be empty"))
            }
            val hexDigits = if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                trimmed.substring(2)
            } else {
                trimmed
            }
            if (hexDigits.isEmpty() || !hexDigits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                return Result.failure(IllegalArgumentException("Not a valid hex offset: \"$raw\""))
            }
            return runCatching { HexOffset(java.lang.Long.parseLong(hexDigits, 16)) }
        }
    }
}
