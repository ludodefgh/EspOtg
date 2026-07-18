package com.espotg.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val ACTION_USB_PERMISSION = "com.espotg.app.USB_PERMISSION"

/** Enumerates attached USB-serial devices and handles runtime permission requests. */
class UsbDeviceRepository(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _availableDrivers = MutableStateFlow<List<UsbSerialDriver>>(emptyList())
    val availableDrivers: StateFlow<List<UsbSerialDriver>> = _availableDrivers

    init {
        refresh()
    }

    fun refresh() {
        _availableDrivers.value = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    }

    /** Registers a receiver that refreshes [availableDrivers] on USB attach/detach; caller must unregister it. */
    fun registerAttachDetachReceiver(onChanged: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                refresh()
                onChanged()
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return receiver
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /** The USB serial-number descriptor, if the adapter exposes one and permission is already granted. */
    fun readUsbSerialNumber(device: UsbDevice): String? =
        if (!hasPermission(device)) null else runCatching { device.serialNumber }.getOrNull()

    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (hasPermission(device)) {
            return true
        }
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    context.unregisterReceiver(this)
                    if (intent.action == ACTION_USB_PERMISSION && cont.isActive) {
                        cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            // Must be explicit (setPackage) - Android 14+ throws on a mutable
            // PendingIntent wrapping an implicit Intent. Mutable is required here
            // since the system fills in EXTRA_PERMISSION_GRANTED on this exact Intent.
            val explicitIntent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                explicitIntent,
                PendingIntent.FLAG_MUTABLE,
            )
            usbManager.requestPermission(device, pendingIntent)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        }
    }

    /** Opens the driver's first port. Caller owns the returned port and must close() it. */
    fun openPort(driver: UsbSerialDriver): UsbSerialPort {
        val connection = usbManager.openDevice(driver.device)
            ?: throw IOException("Unable to open USB device (permission denied or device disconnected)")
        val port = driver.ports.first()
        port.open(connection)
        return port
    }

    private fun findAttachedDevice(vendorId: Int, productId: Int): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.vendorId == vendorId && it.productId == productId }

    /**
     * Waits for a device matching [vendorId]/[productId] to (re)appear and opens
     * it - used after a native USB-Serial-JTAG reset pulse, which makes the whole
     * USB link re-enumerate (see [UsbSerialForAndroidTransport]'s `reopenPort`).
     * Re-requests permission if it wasn't carried over across the re-enumeration.
     */
    suspend fun waitAndReopenAfterReset(vendorId: Int, productId: Int, timeoutMs: Long = 3000): UsbSerialPort {
        val deadline = System.currentTimeMillis() + timeoutMs
        var device: UsbDevice?
        do {
            device = findAttachedDevice(vendorId, productId)
            if (device != null) break
            delay(100)
        } while (System.currentTimeMillis() < deadline)

        val found = device ?: throw IOException("USB device did not reappear after reset")
        if (!hasPermission(found) && !requestPermission(found)) {
            throw IOException("USB permission not granted after reset")
        }
        val driver = UsbSerialProber.getDefaultProber().probeDevice(found)
            ?: throw IOException("No serial driver for reopened device")
        return openPort(driver)
    }
}
