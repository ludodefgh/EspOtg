package com.espotg.flasher

import com.espotg.core.EspLoaderError
import com.espotg.core.EspLoaderException
import com.espotg.core.LogLevel
import com.espotg.core.TargetChip

fun interface NativeLogListener {
    fun onLog(level: LogLevel, message: String)
}

/**
 * Kotlin façade over the esp-serial-flasher C library (vendored in
 * third_party/esp-serial-flasher, wrapped by android_port.c/jni_bridge.c - see
 * CLAUDE.md "JNI port design"). One instance owns one native loader context tied
 * to one [transport]; not thread-safe by design (see the threading invariant in
 * android_port.h) - all calls on a given instance must happen from the same
 * dedicated flashing thread/coroutine.
 */
class EspLoaderNative(
    private val transport: UsbSerialTransport,
    private val logListener: NativeLogListener? = null,
) : AutoCloseable {

    private val handle: Long = nativeCreate()

    init {
        check(handle != 0L) { "Failed to initialize esp-serial-flasher native context" }
    }

    private fun checked(nativeErrorValue: Int) {
        val error = EspLoaderError.fromNativeValue(nativeErrorValue)
        if (error != EspLoaderError.SUCCESS) {
            throw EspLoaderException(error)
        }
    }

    /** Syncs with the ROM bootloader (or the RAM stub, if [useStub]) - a prerequisite for every other call. */
    fun connect(syncTimeoutMs: Int = 100, trials: Int = 10, useStub: Boolean = true) {
        checked(nativeConnect(handle, syncTimeoutMs, trials, useStub))
    }

    fun getTargetChip(): TargetChip = TargetChip.fromNativeValue(nativeGetTargetChip(handle))

    /** The chip's efuse MAC address - the source-of-truth device identity, see ChipIdentity. */
    fun readMac(): ByteArray {
        val mac = ByteArray(6)
        checked(nativeReadMac(handle, mac))
        return mac
    }

    fun changeBaudRate(baud: Int) = checked(nativeChangeBaudRate(handle, baud))

    fun flashStart(offset: Long, imageSize: Long, blockSize: Int, skipVerify: Boolean) =
        checked(nativeFlashStart(handle, offset, imageSize, blockSize, skipVerify))

    fun flashWrite(payload: ByteArray, size: Int = payload.size) =
        checked(nativeFlashWrite(handle, payload, size))

    fun flashFinish() = checked(nativeFlashFinish(handle))

    fun flashDeflateStart(offset: Long, imageSize: Long, compressedSize: Long, blockSize: Int) =
        checked(nativeFlashDeflateStart(handle, offset, imageSize, compressedSize, blockSize))

    fun flashDeflateWrite(payload: ByteArray, size: Int = payload.size) =
        checked(nativeFlashDeflateWrite(handle, payload, size))

    fun flashDeflateFinish() = checked(nativeFlashDeflateFinish(handle))

    /**
     * Verifies flash content against a known MD5 - needed after a deflate flash,
     * which skips the usual inline MD5 check. [expectedMd5] must be the digest as
     * **32 lowercase-hex ASCII bytes**, not the raw 16-byte digest - that's what
     * esp_loader_flash_verify_known_md5 memcmp's against.
     */
    fun verifyMd5(address: Long, size: Long, expectedMd5: ByteArray) {
        require(expectedMd5.size == 32) { "expectedMd5 must be 32 hex-ASCII bytes, got ${expectedMd5.size}" }
        checked(nativeVerifyMd5(handle, address, size, expectedMd5))
    }

    /** Reads [length] bytes of the target's flash starting at [address] (works with or without the stub). */
    fun flashRead(address: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        checked(nativeFlashRead(handle, address, out, length))
        return out
    }

    /** Detects the attached flash chip's size in bytes. */
    fun detectFlashSize(): Long {
        val out = LongArray(1)
        checked(nativeDetectFlashSize(handle, out))
        return out[0]
    }

    fun resetTarget() = nativeResetTarget(handle)

    override fun close() {
        nativeDestroy(handle)
    }

    // --- Called from native code (android_port.c) via JNI. Do not rename/re-sign
    // without updating the matching JNI method-ID lookups there, and note these
    // are kept alive across R8/minification by consumer-rules.pro. ---

    @Suppress("unused")
    private fun onNativeRead(buffer: ByteArray, size: Int, timeoutMs: Int): Int =
        transport.read(buffer, size, timeoutMs)

    @Suppress("unused")
    private fun onNativeWrite(buffer: ByteArray, size: Int, timeoutMs: Int): Int =
        transport.write(buffer, size, timeoutMs)

    @Suppress("unused")
    private fun onNativeSetBaudRate(baud: Int): Int = transport.setBaudRate(baud)

    @Suppress("unused")
    private fun onNativeEnterBootloader() = transport.enterBootloader()

    @Suppress("unused")
    private fun onNativeResetTarget() = transport.resetTarget()

    @Suppress("unused")
    private fun onNativeLog(level: Int, message: String) {
        logListener?.onLog(LogLevel.entries.getOrElse(level) { LogLevel.INFO }, message)
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeConnect(handle: Long, syncTimeoutMs: Int, trials: Int, useStub: Boolean): Int
    private external fun nativeGetTargetChip(handle: Long): Int
    private external fun nativeReadMac(handle: Long, outMac: ByteArray): Int
    private external fun nativeChangeBaudRate(handle: Long, baud: Int): Int
    private external fun nativeFlashStart(handle: Long, offset: Long, imageSize: Long, blockSize: Int, skipVerify: Boolean): Int
    private external fun nativeFlashWrite(handle: Long, payload: ByteArray, size: Int): Int
    private external fun nativeFlashFinish(handle: Long): Int
    private external fun nativeFlashDeflateStart(handle: Long, offset: Long, imageSize: Long, compressedSize: Long, blockSize: Int): Int
    private external fun nativeFlashDeflateWrite(handle: Long, payload: ByteArray, size: Int): Int
    private external fun nativeFlashDeflateFinish(handle: Long): Int
    private external fun nativeVerifyMd5(handle: Long, address: Long, size: Long, expectedMd5: ByteArray): Int
    private external fun nativeFlashRead(handle: Long, address: Long, out: ByteArray, length: Int): Int
    private external fun nativeDetectFlashSize(handle: Long, outSize: LongArray): Int
    private external fun nativeResetTarget(handle: Long)

    companion object {
        init {
            System.loadLibrary("flasher_native")
        }
    }
}
