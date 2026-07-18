package com.espotg.app.usb

import com.espotg.flasher.UsbSerialTransport
import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

/**
 * Bridges [UsbSerialTransport] (what android_port.c calls back into via JNI) onto
 * a real usb-serial-for-android [UsbSerialPort]. One instance per flashing/
 * monitoring session.
 *
 * [reopenPort], if supplied, is called after a native USB-Serial-JTAG reset
 * pulse: that peripheral's whole USB connection re-enumerates on reset (unlike
 * an external UART bridge, where only the target chip resets, not the USB link),
 * so the port this transport was constructed with is dead afterward and a fresh
 * one must be opened before the SYNC handshake can continue. Confirmed on real
 * ESP32 hardware (VID 0x303A/PID 0x1001) - see GitHub issue #4.
 *
 * [logger], if supplied, receives diagnostic messages about the reset/reopen
 * sequence specifically. Failures here get silently absorbed at the JNI boundary
 * (`enter_bootloader` in esp_loader_port_ops_t returns void - there's no way to
 * propagate a real error back through esp_loader_connect(), it just eventually
 * surfaces as a generic ESP_LOADER_ERROR_TIMEOUT on the SYNC step that follows),
 * so this is the only place the *actual* reason ever becomes visible.
 */
class UsbSerialForAndroidTransport(
    initialPort: UsbSerialPort,
    private val autoReset: Boolean = true,
    private val reopenPort: (() -> UsbSerialPort)? = null,
    private val logger: ((String) -> Unit)? = null,
) : UsbSerialTransport {

    var currentPort: UsbSerialPort = initialPort
        private set

    private var lastBaudRate: Int? = null

    private val isNativeUsbJtag: Boolean =
        currentPort.device.vendorId == ESPRESSIF_USB_JTAG_VID && currentPort.device.productId == ESPRESSIF_USB_JTAG_PID

    // Intermediate RX buffer: the SLIP decoder asks for 1 byte at a time, but
    // usb-serial-for-android's read(dest, length, timeout) silently DISCARDS a
    // whole incoming USB packet when `length` is smaller than the packet that
    // arrived (kernel EOVERFLOW -> library returns 0, looking exactly like a
    // timeout). The device answers SYNC with multi-byte packets, so every
    // 1-byte read was throwing the response away - the root cause of the
    // "TIMEOUT with perfect TX and zero RX" symptom on real hardware. Always
    // read from the port with a full-size buffer and serve small requests from
    // the cache instead.
    private val rxBuffer = ByteArray(RX_BUFFER_SIZE)
    private var rxStart = 0
    private var rxEnd = 0

    private fun invalidateRxCache() {
        rxStart = 0
        rxEnd = 0
    }

    fun closeCurrentPort() {
        invalidateRxCache()
        runCatching { currentPort.close() }
    }

    override fun read(buffer: ByteArray, size: Int, timeoutMs: Int): Int {
        if (rxStart >= rxEnd) {
            val n = try {
                currentPort.read(rxBuffer, rxBuffer.size, timeoutMs)
            } catch (_: IOException) {
                return -1
            }
            if (n <= 0) {
                return n
            }
            rxStart = 0
            rxEnd = n
        }
        val toCopy = minOf(size, rxEnd - rxStart)
        System.arraycopy(rxBuffer, rxStart, buffer, 0, toCopy)
        rxStart += toCopy
        return toCopy
    }

    override fun write(buffer: ByteArray, size: Int, timeoutMs: Int): Int =
        try {
            currentPort.write(buffer, size, timeoutMs)
            size
        } catch (e: SerialTimeoutException) {
            e.bytesTransferred
        } catch (_: IOException) {
            -1
        }

    override fun setBaudRate(baud: Int): Int =
        try {
            currentPort.setParameters(baud, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            lastBaudRate = baud
            0
        } catch (_: IOException) {
            -1
        } catch (_: UnsupportedOperationException) {
            -1
        }

    override fun enterBootloader() {
        if (!autoReset) {
            logger?.invoke("Auto bootloader reset disabled - assuming BOOT/RESET were pressed manually")
            return
        }
        if (isNativeUsbJtag) enterBootloaderUsbJtag() else enterBootloaderClassic()
    }

    /**
     * esptool's "UnixTightReset" sequence (DTR drives BOOT, RTS drives RESET on
     * the standard CP2102/CH340/FT232 auto-reset circuit) - mirrors
     * `linux_enter_bootloader`'s LINUX_GPIO_DTR_RTS/non-USB-JTAG branch in
     * third_party/esp-serial-flasher/port/linux_port.c almost line-for-line, down
     * to the hold times (100ms/50ms) matching the library's own defaults.
     */
    private fun enterBootloaderClassic() {
        runCatching {
            currentPort.setDTR(false); currentPort.setRTS(false)
            currentPort.setDTR(true); currentPort.setRTS(true)
            currentPort.setDTR(false); currentPort.setRTS(true)
            Thread.sleep(RESET_HOLD_TIME_MS)
            currentPort.setDTR(true); currentPort.setRTS(false)
            Thread.sleep(BOOT_HOLD_TIME_MS)
            currentPort.setDTR(false); currentPort.setRTS(false)
        }.onFailure { logger?.invoke("Classic reset sequence failed: ${it.message}") }
    }

    /**
     * esptool's "USBJTAGSerialReset" sequence, required for ESP32-C3/S3/C6/H2/P4
     * connected via their built-in USB-Serial-JTAG peripheral: BOOT must be
     * asserted *before* RESET (the firmware latches BOOT at the moment RESET goes
     * low), released through an intermediate (1,1) state to avoid a (0,0) glitch.
     * Mirrors `linux_enter_bootloader`'s `_is_usb_jtag` branch exactly.
     */
    private fun enterBootloaderUsbJtag() {
        logger?.invoke("Native USB-Serial-JTAG device detected, using USBJTAGSerialReset sequence")
        val pulseResult = runCatching {
            currentPort.setDTR(false); currentPort.setRTS(false) // idle
            Thread.sleep(RESET_HOLD_TIME_MS)
            currentPort.setDTR(true); currentPort.setRTS(false) // assert BOOT
            Thread.sleep(RESET_HOLD_TIME_MS)
            currentPort.setDTR(true); currentPort.setRTS(true) // assert RESET (BOOT still asserted)
            currentPort.setDTR(false); currentPort.setRTS(true) // release BOOT via (1,1)
            Thread.sleep(RESET_HOLD_TIME_MS)
            currentPort.setDTR(false); currentPort.setRTS(false) // release RESET
        }
        pulseResult.onFailure { logger?.invoke("USB-JTAG reset pulse failed: ${it.message}") }

        val reopen = reopenPort ?: return
        logger?.invoke("Reset pulse sent, waiting for USB device to re-enumerate...")
        closeCurrentPort()
        runCatching { reopen() }
            .onSuccess { newPort ->
                currentPort = newPort
                invalidateRxCache()
                logger?.invoke("USB device reopened after reset")
                lastBaudRate?.let { setBaudRate(it) }
                // The endpoints on a just-opened CDC-ACM connection aren't
                // necessarily ready to transfer immediately; a short settle
                // delay avoids racing the SYNC handshake that follows right
                // after enterBootloader() returns.
                Thread.sleep(PORT_SETTLE_TIME_MS)
            }
            .onFailure { e ->
                logger?.invoke("Failed to reopen USB device after reset: ${e.message} - subsequent SYNC will time out")
            }
    }

    /** Plain reset (RESET pulse only, BOOT left alone) - used after flashing finishes. */
    override fun resetTarget() {
        runCatching {
            currentPort.setDTR(false); currentPort.setRTS(true)
            Thread.sleep(RESET_HOLD_TIME_MS)
            currentPort.setDTR(false); currentPort.setRTS(false)
        }
        // Native USB-Serial-JTAG re-enumerates here too, but nothing further is
        // sent on this port afterward (the session ends right after reset), so
        // there's nothing worth reopening for - unlike enterBootloader().
    }

    private companion object {
        /** Matches esp-serial-flasher's SERIAL_FLASHER_RESET_HOLD_TIME_MS/BOOT_HOLD_TIME_MS defaults. */
        const val RESET_HOLD_TIME_MS = 100L
        const val BOOT_HOLD_TIME_MS = 50L
        const val PORT_SETTLE_TIME_MS = 300L
        const val RX_BUFFER_SIZE = 4096

        /** Matches ESPRESSIF_USB_JTAG_VID/PID in third_party/esp-serial-flasher/port/linux_port.c. */
        const val ESPRESSIF_USB_JTAG_VID = 0x303A
        const val ESPRESSIF_USB_JTAG_PID = 0x1001
    }
}
