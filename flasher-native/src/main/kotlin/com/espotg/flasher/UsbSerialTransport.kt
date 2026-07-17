package com.espotg.flasher

/**
 * The raw I/O contract [EspLoaderNative]'s native port calls back into for
 * everything that touches actual hardware. Implemented in `app` on top of
 * usb-serial-for-android; kept here (not in flasher-core) because its shape is
 * dictated by exactly what android_port.c needs to call, not by app-level design.
 */
interface UsbSerialTransport {
    /** Reads up to [size] bytes into [buffer] within [timeoutMs]. Returns bytes actually read (0 on timeout, negative on error). */
    fun read(buffer: ByteArray, size: Int, timeoutMs: Int): Int

    /** Writes up to [size] bytes from [buffer] within [timeoutMs]. Returns bytes actually written (0 on timeout, negative on error). */
    fun write(buffer: ByteArray, size: Int, timeoutMs: Int): Int

    /** Changes the underlying serial baud rate. Returns 0 on success, non-zero on failure. */
    fun setBaudRate(baud: Int): Int

    /**
     * Performs the DTR/RTS strap sequence that resets the chip into the ROM
     * bootloader. When the user has disabled auto-reset, this is a no-op - the
     * UI is expected to have already told them to press BOOT manually.
     */
    fun enterBootloader()

    /** Performs a plain reset (no bootloader strapping) - used after flashing finishes. */
    fun resetTarget()
}
