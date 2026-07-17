package com.espotg.app.usb

import com.espotg.flasher.UsbSerialTransport
import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

/**
 * Bridges [UsbSerialTransport] (what android_port.c calls back into via JNI) onto
 * a real, already-opened usb-serial-for-android [UsbSerialPort]. One instance per
 * flashing/monitoring session.
 */
class UsbSerialForAndroidTransport(private val port: UsbSerialPort) : UsbSerialTransport {

    override fun read(buffer: ByteArray, size: Int, timeoutMs: Int): Int =
        try {
            port.read(buffer, size, timeoutMs)
        } catch (_: IOException) {
            -1
        }

    override fun write(buffer: ByteArray, size: Int, timeoutMs: Int): Int =
        try {
            port.write(buffer, size, timeoutMs)
            size
        } catch (e: SerialTimeoutException) {
            e.bytesTransferred
        } catch (_: IOException) {
            -1
        }

    override fun setBaudRate(baud: Int): Int =
        try {
            port.setParameters(baud, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            0
        } catch (_: IOException) {
            -1
        } catch (_: UnsupportedOperationException) {
            -1
        }

    /**
     * esptool's "UnixTightReset" sequence (DTR drives BOOT, RTS drives RESET on
     * the standard CP2102/CH340/FT232 auto-reset circuit) - mirrors
     * `linux_enter_bootloader`'s LINUX_GPIO_DTR_RTS/non-USB-JTAG branch in
     * third_party/esp-serial-flasher/port/linux_port.c almost line-for-line, down
     * to the hold times (100ms/50ms) matching the library's own defaults.
     *
     * Native USB-Serial-JTAG boards (S2/S3/C3/C6...) use the same DTR/RTS
     * assertions, but the chip's own USB peripheral re-enumerates after the reset
     * pulse - Android sees a detach/reattach, which the caller (not this
     * transport) is responsible for handling. Not yet validated on real native-USB
     * hardware, see GitHub issue #4.
     */
    override fun enterBootloader() {
        runCatching {
            port.setDTR(false); port.setRTS(false)
            port.setDTR(true); port.setRTS(true)
            port.setDTR(false); port.setRTS(true)
            Thread.sleep(RESET_HOLD_TIME_MS)
            port.setDTR(true); port.setRTS(false)
            Thread.sleep(BOOT_HOLD_TIME_MS)
            port.setDTR(false); port.setRTS(false)
        }
    }

    /** Plain reset (RESET pulse only, BOOT left alone) - used after flashing finishes. */
    override fun resetTarget() {
        runCatching {
            port.setDTR(false); port.setRTS(true)
            Thread.sleep(RESET_HOLD_TIME_MS)
            port.setDTR(false); port.setRTS(false)
        }
    }

    private companion object {
        /** Matches esp-serial-flasher's SERIAL_FLASHER_RESET_HOLD_TIME_MS/BOOT_HOLD_TIME_MS defaults. */
        const val RESET_HOLD_TIME_MS = 100L
        const val BOOT_HOLD_TIME_MS = 50L
    }
}
