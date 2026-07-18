package com.espotg.app.flash

import android.content.Context
import androidx.core.net.toUri
import com.espotg.app.usb.UsbDeviceRepository
import com.espotg.app.usb.UsbSerialForAndroidTransport
import com.espotg.core.ChipIdentity
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
    private fun openTransport(driver: UsbSerialDriver): UsbSerialForAndroidTransport {
        val port = usbRepository.openPort(driver)
        return UsbSerialForAndroidTransport(
            initialPort = port,
            reopenPort = {
                runBlocking {
                    usbRepository.waitAndReopenAfterReset(driver.device.vendorId, driver.device.productId)
                }
            },
        )
    }

    /** Quick connect + chip/MAC identification, no flashing - used to resolve a saved profile before committing to a plan. */
    suspend fun identify(driver: UsbSerialDriver, syncBaudRate: Int = 115_200): ChipIdentity {
        val transport = openTransport(driver)
        transport.setBaudRate(syncBaudRate)
        val loader = EspLoaderNative(transport) { level, message -> log(level, message) }
        try {
            log(LogLevel.INFO, "Connecting...")
            loader.connect(syncTimeoutMs = 100, trials = 10, useStub = false)
            val chipType = loader.getTargetChip()
            val mac = loader.readMac().toMacString()
            log(LogLevel.INFO, "Connected - $chipType, MAC $mac")
            loader.resetTarget()
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

        val transport = openTransport(driver)
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
        log(LogLevel.INFO, "Flashing ${uri.substringAfterLast('/')} @ 0x%X".format(offset))

        val data = context.contentResolver.openInputStream(uri.toUri())?.use { it.readBytes() }
            ?: throw IOException("Cannot open $uri")

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
        // esp_loader_flash_verify_known_md5. Compatibility of the raw-deflate
        // stream produced here with what the stub expects is not yet validated
        // on real hardware, see GitHub issue #3.
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
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, /* nowrap = */ true)
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
