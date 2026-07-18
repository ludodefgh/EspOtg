package com.espotg.app.flash

import android.content.Context
import androidx.core.net.toUri
import com.espotg.app.usb.UsbDeviceRepository
import com.espotg.app.usb.UsbSerialForAndroidTransport
import com.espotg.core.ChipIdentity
import com.espotg.core.EspImageHeader
import com.espotg.core.FlashEntryProgress
import com.espotg.core.FlashOptions
import com.espotg.core.FlashPlan
import com.espotg.core.FlashProgress
import com.espotg.core.FlashStepState
import com.espotg.core.LogLevel
import com.espotg.core.LogLine
import com.espotg.flasher.EspLoaderNative
import com.hoho.android.usbserial.driver.UsbSerialDriver
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.Deflater
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking

/**
 * Orchestrates one flashing (or identify-only) session: opens the driver's port,
 * wires it into [EspLoaderNative] via [UsbSerialForAndroidTransport], drives the
 * connect/flash/verify sequence, and streams progress + live logs to the UI. One
 * instance per session - not reused across drivers.
 */
class FlashEngine(private val usbRepository: UsbDeviceRepository) {

    private val _logs = MutableSharedFlow<LogLine>(replay = 0, extraBufferCapacity = 512)
    val logs: SharedFlow<LogLine> = _logs

    private val _progress = MutableStateFlow(FlashProgress(entries = emptyList(), isRunning = false))
    val progress: StateFlow<FlashProgress> = _progress

    private fun log(level: LogLevel, message: String) {
        _logs.tryEmit(LogLine(System.currentTimeMillis(), level, message))
    }

    private fun updateEntry(id: String, transform: (FlashEntryProgress) -> FlashEntryProgress) {
        _progress.update { p -> p.copy(entries = p.entries.map { if (it.entryId == id) transform(it) else it }) }
    }

    /**
     * Opens [driver]'s port and wraps it in a transport that knows how to reopen
     * itself after a native USB-Serial-JTAG reset re-enumeration (see
     * [UsbSerialForAndroidTransport]). The `reopenPort` lambda bridges to the
     * repository's suspend reconnect logic via [runBlocking] - safe here since
     * every caller of this already runs on Dispatchers.IO, off the main thread.
     */
    private fun openTransport(driver: UsbSerialDriver, autoReset: Boolean): UsbSerialForAndroidTransport {
        val port = usbRepository.openPort(driver)
        return UsbSerialForAndroidTransport(
            initialPort = port,
            autoReset = autoReset,
            reopenPort = {
                runBlocking {
                    usbRepository.waitAndReopenAfterReset(
                        driver.device.vendorId,
                        driver.device.productId,
                        onProgress = { log(LogLevel.DEBUG, it) },
                    )
                }
            },
            logger = { log(LogLevel.DEBUG, it) },
        )
    }

    /**
     * Quick connect + chip/MAC identification, no flashing - used to resolve a
     * saved profile before committing to a plan. When [autoReset] is false, the
     * caller is expected to have already put the chip in download mode manually
     * (hold BOOT, tap RESET, release BOOT) - useful when a board's firmware
     * doesn't support DTR/RTS-triggered auto-reset (seen on native
     * USB-Serial-JTAG boards whose current firmware doesn't implement it).
     */
    suspend fun identify(driver: UsbSerialDriver, syncBaudRate: Int = 115_200, autoReset: Boolean = true): ChipIdentity {
        val transport = openTransport(driver, autoReset)
        transport.setBaudRate(syncBaudRate)
        val loader = EspLoaderNative(transport) { level, message -> log(level, message) }
        try {
            log(LogLevel.INFO, "Connecting...")
            loader.connect(syncTimeoutMs = 100, trials = 10, useStub = false)
            val chipType = loader.getTargetChip()
            val mac = loader.readMac().toMacString()
            log(LogLevel.INFO, "Connected - $chipType, MAC $mac")
            if (autoReset) {
                loader.resetTarget()
            } else {
                // Manual bootloader entry: leave the chip sitting in the ROM
                // bootloader so the flash that typically follows can connect
                // without the user having to redo the BOOT+RESET dance. A reset
                // here would boot it back into its firmware and guarantee the
                // next SYNC times out.
                log(LogLevel.INFO, "Staying in bootloader (manual mode) - ready to flash")
            }
            return ChipIdentity(macAddress = mac, chipType = chipType)
        } finally {
            loader.close()
            transport.closeCurrentPort()
        }
    }

    /** Runs the full flash plan (one or more binaries) and returns the identity of the chip that was flashed. */
    suspend fun runFlashPlan(context: Context, driver: UsbSerialDriver, plan: FlashPlan): ChipIdentity {
        _progress.value = FlashProgress(
            entries = plan.entries.map { FlashEntryProgress(it.id, FlashStepState.PENDING, totalBytes = it.sizeBytes) },
            isRunning = true,
        )

        val transport = openTransport(driver, plan.options.autoBootloaderReset)
        transport.setBaudRate(plan.options.syncBaudRate)
        val loader = EspLoaderNative(transport) { level, message -> log(level, message) }
        try {
            log(LogLevel.INFO, "Connecting...")
            loader.connect(syncTimeoutMs = 100, trials = 10, useStub = true)
            val chipType = loader.getTargetChip()
            val mac = loader.readMac().toMacString()
            log(LogLevel.INFO, "Connected - $chipType, MAC $mac")

            if (plan.options.flashBaudRate != plan.options.syncBaudRate) {
                log(LogLevel.INFO, "Switching to ${plan.options.flashBaudRate} baud")
                loader.changeBaudRate(plan.options.flashBaudRate)
            }

            for (entry in plan.entries) {
                flashEntry(context, loader, entry.id, entry.uri, entry.offset.value, entry.sizeBytes, plan.options)
            }

            log(LogLevel.INFO, "Flash complete, resetting target")
            loader.resetTarget()
            return ChipIdentity(macAddress = mac, chipType = chipType)
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Flash failed: ${e.message}")
            // Full trace in the log too: on minified release builds the message
            // alone can be an opaque "a != java.lang.Long"-style string, and the
            // trace (with LineNumberTable kept, see proguard-rules.pro) is the
            // only way to locate the failure from a user report.
            log(LogLevel.DEBUG, e.stackTraceToString())
            throw e
        } finally {
            loader.close()
            transport.closeCurrentPort()
            _progress.update { it.copy(isRunning = false) }
        }
    }

    private fun flashEntry(
        context: Context,
        loader: EspLoaderNative,
        entryId: String,
        uri: String,
        offset: Long,
        expectedSize: Long,
        options: FlashOptions,
    ) {
        updateEntry(entryId) { it.copy(state = FlashStepState.WRITING) }
        // No String.format here: content URIs are percent-encoded ("%3a" etc.),
        // and running .format() over a string with the filename interpolated in
        // treats those escapes as format specifiers ("%3a" = width-3 hex-float
        // conversion -> IllegalFormatConversionException: a != java.lang.Long,
        // the failure that masqueraded as an R8 bug on the first real flash).
        log(LogLevel.INFO, "Flashing ${uri.substringAfterLast('/')} @ 0x${offset.toString(16).uppercase()}")

        val rawData = context.contentResolver.openInputStream(uri.toUri())?.use { it.readBytes() }
            ?: throw IOException("Cannot open $uri")

        // esptool-style header patch: only applies when the file is an ESP image
        // (0xE9 magic) AND at least one SPI option is set to something other than
        // KEEP - the compiled-in header values win by default.
        val data = EspImageHeader.patch(rawData, options.spiMode, options.spiFreq, options.flashSize)
        if (data !== rawData) {
            log(LogLevel.INFO, "Patched image header SPI settings (mode=${options.spiMode}, freq=${options.spiFreq.label}, size=${options.flashSize.label})")
        }

        if (options.compression) {
            flashCompressed(loader, entryId, offset, data)
        } else {
            flashRaw(loader, entryId, offset, data, options.verifyAfterWrite)
        }

        updateEntry(entryId) { it.copy(state = FlashStepState.DONE, bytesWritten = expectedSize, totalBytes = expectedSize) }
    }

    private fun flashRaw(loader: EspLoaderNative, entryId: String, offset: Long, data: ByteArray, verify: Boolean) {
        loader.flashStart(offset, data.size.toLong(), BLOCK_SIZE, skipVerify = !verify)
        var written = 0
        while (written < data.size) {
            val chunkSize = minOf(BLOCK_SIZE, data.size - written)
            loader.flashWrite(data.copyOfRange(written, written + chunkSize))
            written += chunkSize
            updateEntry(entryId) { it.copy(bytesWritten = written.toLong()) }
        }
        loader.flashFinish()
    }

    private fun flashCompressed(loader: EspLoaderNative, entryId: String, offset: Long, data: ByteArray) {
        // esp-serial-flasher's deflate path doesn't accumulate MD5 itself (see
        // esp_loader_flash_deflate_start docs) - verified explicitly below via
        // esp_loader_flash_verify_known_md5.
        val compressed = deflate(data)
        loader.flashDeflateStart(offset, data.size.toLong(), compressed.size.toLong(), BLOCK_SIZE)
        var written = 0
        while (written < compressed.size) {
            val chunkSize = minOf(BLOCK_SIZE, compressed.size - written)
            loader.flashDeflateWrite(compressed.copyOfRange(written, written + chunkSize))
            written += chunkSize
            val approxUncompressedWritten = (written.toLong() * data.size) / compressed.size.coerceAtLeast(1)
            updateEntry(entryId) { it.copy(bytesWritten = approxUncompressedWritten) }
        }
        loader.flashDeflateFinish()
        loader.verifyMd5(offset, data.size.toLong(), md5(data))
    }

    private fun deflate(data: ByteArray): ByteArray {
        // nowrap=false: esptool sends a ZLIB-WRAPPED stream (zlib.compress, i.e.
        // 0x78 header + adler32 trailer), and the flasher stub inflates expecting
        // that wrapper - the initial nowrap=true (raw deflate) guess produced
        // mid-flash errors on real hardware, see GitHub issue #3.
        val deflater = Deflater(Deflater.BEST_COMPRESSION, /* nowrap = */ false)
        deflater.setInput(data)
        deflater.finish()
        val output = ByteArrayOutputStream(data.size)
        val buffer = ByteArray(8192)
        while (!deflater.finished()) {
            output.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return output.toByteArray()
    }

    private fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    private fun ByteArray.toMacString(): String = joinToString(":") { "%02X".format(it) }

    private companion object {
        const val BLOCK_SIZE = 4096
    }
}
