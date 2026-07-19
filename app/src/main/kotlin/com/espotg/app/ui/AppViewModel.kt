package com.espotg.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.espotg.app.data.DeviceProfile
import com.espotg.app.data.DeviceProfileRepository
import com.espotg.app.data.db.EspOtgDatabase
import com.espotg.app.flash.FlashEngine
import com.espotg.app.usb.PortOccupant
import com.espotg.app.usb.UsbDeviceRepository
import com.espotg.app.usb.UsbPortCoordinator
import com.espotg.core.ChipIdentity
import com.espotg.core.EspBinaryInfo
import com.espotg.core.FlashEntry
import com.espotg.core.FlashOptions
import com.espotg.core.FlashPlan
import com.espotg.core.HexOffset
import com.espotg.core.LogLevel
import com.espotg.core.LogLine
import com.espotg.core.OffsetPresets
import com.espotg.core.TargetChip
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data object RequestingPermission : ConnectionStatus
    data object PermissionDenied : ConnectionStatus
    data object Identifying : ConnectionStatus
    data class Identified(val identity: ChipIdentity, val loadedProfile: Boolean) : ConnectionStatus
    data class Failed(val message: String) : ConnectionStatus
}

/**
 * Single shared ViewModel for the whole nav graph (Connect/Flash/Monitor/Profiles) -
 * deliberately not split into one ViewModel per screen, to avoid re-plumbing the
 * same USB/profile/flash-session state through nav arguments (UsbSerialDriver/
 * UsbSerialPort aren't things you'd want to serialize into a route anyway).
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    val usbRepository = UsbDeviceRepository(application)
    val profileRepository = DeviceProfileRepository(EspOtgDatabase.getInstance(application).deviceProfileDao())
    val flashEngine = FlashEngine(usbRepository)

    /**
     * Session-scoped log buffer, shared by every screen (Connect/Flash both
     * render it) - lives as long as the device session, survives navigation,
     * cleared only when a different device is selected or explicitly via
     * [clearSessionLogs]. Previously each screen collected flashEngine.logs
     * into its own local buffer, so logs emitted while another screen was
     * displayed were simply lost.
     */
    private val _sessionLogs = MutableStateFlow<List<LogLine>>(emptyList())
    val sessionLogs: StateFlow<List<LogLine>> = _sessionLogs

    // Auto-refreshes the device list on USB attach/detach and drops a selected
    // device that's been unplugged - without this the UsbSerialDriver held in
    // _selectedDriver goes stale across a detach/reattach and the next openPort()
    // on it crashes (the monitor reconnect crash).
    private val attachDetachReceiver = usbRepository.registerAttachDetachReceiver { onUsbDevicesChanged() }

    init {
        viewModelScope.launch {
            flashEngine.logs.collect { line ->
                // Bounded: drop oldest past the cap so a busy flash session can't
                // grow the buffer (and recomposition cost) without limit.
                _sessionLogs.update { logs ->
                    if (logs.size >= MAX_SESSION_LOG_LINES) {
                        logs.drop(logs.size - MAX_SESSION_LOG_LINES + 1) + line
                    } else {
                        logs + line
                    }
                }
            }
        }
    }

    private fun onUsbDevicesChanged() {
        val selected = _selectedDriver.value ?: return
        val stillPresent = usbRepository.availableDrivers.value.any {
            it.device.deviceId == selected.device.deviceId
        }
        if (!stillPresent) {
            // The selected device was unplugged: tear down any monitor session and
            // force re-selection so a fresh driver (with fresh permission) is used.
            stopMonitor()
            _selectedDriver.value = null
            if (_connectionStatus.value is ConnectionStatus.Identified) {
                _connectionStatus.value = ConnectionStatus.Idle
            }
        }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(attachDetachReceiver) }
        super.onCleared()
    }

    fun clearSessionLogs() {
        _sessionLogs.value = emptyList()
    }

    private fun logToSession(message: String) {
        _sessionLogs.update { it + LogLine(System.currentTimeMillis(), LogLevel.WARN, message) }
    }

    private val _selectedDriver = MutableStateFlow<UsbSerialDriver?>(null)
    val selectedDriver: StateFlow<UsbSerialDriver?> = _selectedDriver

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val _currentPlan = MutableStateFlow(FlashPlan(entries = emptyList(), options = FlashOptions()))
    val currentPlan: StateFlow<FlashPlan> = _currentPlan

    private val _flashRunning = MutableStateFlow(false)
    val flashRunning: StateFlow<Boolean> = _flashRunning

    private val _monitorLines = MutableStateFlow<List<String>>(emptyList())
    val monitorLines: StateFlow<List<String>> = _monitorLines
    private val _monitorRunning = MutableStateFlow(false)
    val monitorRunning: StateFlow<Boolean> = _monitorRunning
    private var monitorJob: Job? = null
    private var monitorPort: UsbSerialPort? = null

    fun refreshDevices() = usbRepository.refresh()

    private val _autoBootloaderReset = MutableStateFlow(true)
    val autoBootloaderReset: StateFlow<Boolean> = _autoBootloaderReset

    fun setAutoBootloaderReset(enabled: Boolean) {
        _autoBootloaderReset.value = enabled
    }

    /**
     * Requests permission, does a quick chip/MAC identify pass, and loads any
     * saved profile for that device. When [autoBootloaderReset] is off, the user
     * is expected to have already put the chip in download mode manually.
     */
    fun connectAndIdentify(driver: UsbSerialDriver) {
        if (_selectedDriver.value?.device?.deviceId != driver.device.deviceId) {
            clearSessionLogs()
        }
        _selectedDriver.value = driver
        _connectionStatus.value = ConnectionStatus.RequestingPermission
        viewModelScope.launch {
            val granted = usbRepository.requestPermission(driver.device)
            if (!granted) {
                _connectionStatus.value = ConnectionStatus.PermissionDenied
                return@launch
            }

            usbRepository.readUsbSerialNumber(driver.device)?.let { serial ->
                profileRepository.findByUsbSerialNumber(serial)?.let { applyProfile(it) }
            }

            _connectionStatus.value = ConnectionStatus.Identifying
            try {
                val identity = withContext(Dispatchers.IO) {
                    flashEngine.identify(driver, autoReset = _autoBootloaderReset.value)
                }
                val loadedProfile = profileRepository.findByChipIdentity(identity)?.also { applyProfile(it) } != null
                _connectionStatus.value = ConnectionStatus.Identified(identity, loadedProfile)
            } catch (e: Exception) {
                _connectionStatus.value = ConnectionStatus.Failed(e.message ?: "Connection failed")
            }
        }
    }

    private fun applyProfile(profile: DeviceProfile) {
        _currentPlan.value = FlashPlan(entries = profile.flashEntries, options = profile.flashOptions)
    }

    fun addBinary(uri: Uri) {
        val context = getApplication<Application>()
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "binary.bin"
        val size = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        }.getOrDefault(0L)

        val chipType = (_connectionStatus.value as? ConnectionStatus.Identified)?.identity?.chipType ?: TargetChip.ESP32
        val usedOffsets = _currentPlan.value.entries.map { it.offset }
        val nextOffset = OffsetPresets.forChip(chipType).map { it.second }.firstOrNull { it !in usedOffsets } ?: HexOffset.ZERO

        // Reading only the header region is enough for parsing (app_desc ends at
        // ~0xB0, bootloader_desc at ~0x60) and avoids loading a multi-MB image
        // into memory just to show its tags.
        val info = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val head = ByteArray(1024)
                val read = stream.read(head)
                if (read > 0) EspBinaryInfo.parse(head.copyOf(read)) else null
            }
        }.getOrNull()

        val entry = FlashEntry(uri = uri.toString(), displayName = displayName, sizeBytes = size, offset = nextOffset, info = info)
        _currentPlan.update { it.copy(entries = it.entries + entry) }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val context = getApplication<Application>()
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return null
    }

    fun removeEntry(id: String) {
        _currentPlan.update { it.copy(entries = it.entries.filterNot { e -> e.id == id }) }
    }

    fun updateEntryOffset(id: String, offset: HexOffset) {
        _currentPlan.update { plan -> plan.copy(entries = plan.entries.map { if (it.id == id) it.copy(offset = offset) else it }) }
    }

    fun updateOptions(transform: (FlashOptions) -> FlashOptions) {
        _currentPlan.update { it.copy(options = transform(it.options)) }
    }

    fun loadProfileIntoPlan(profile: DeviceProfile) = applyProfile(profile)

    fun deleteProfile(profile: DeviceProfile) {
        viewModelScope.launch { profileRepository.delete(profile) }
    }

    fun startFlash() {
        val driver = _selectedDriver.value ?: run {
            logToSession("Cannot flash: no USB device selected")
            return
        }
        val plan = _currentPlan.value
        if (plan.entries.isEmpty()) {
            logToSession("Cannot flash: no binaries in the queue")
            return
        }
        if (!UsbPortCoordinator.tryAcquire(PortOccupant.FLASHER)) {
            logToSession("Cannot flash: serial port is busy (stop the monitor first)")
            return
        }

        _flashRunning.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val identity = flashEngine.runFlashPlan(getApplication(), driver, plan)
                profileRepository.save(
                    DeviceProfile(
                        macAddress = identity.macAddress,
                        chipType = identity.chipType,
                        usbSerialNumber = usbRepository.readUsbSerialNumber(driver.device),
                        label = null,
                        flashOptions = plan.options,
                        flashEntries = plan.entries,
                        lastUsedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                _connectionStatus.value = ConnectionStatus.Identified(identity, loadedProfile = true)
            } catch (_: Exception) {
                // Already surfaced via flashEngine.logs; nothing further to do here.
            } finally {
                _flashRunning.value = false
                UsbPortCoordinator.release(PortOccupant.FLASHER)
            }
        }
    }

    private val _monitorSelectError = MutableStateFlow<String?>(null)
    val monitorSelectError: StateFlow<String?> = _monitorSelectError

    /**
     * Lets MonitorScreen pick a device on its own, independent of the Connect
     * screen's identify flow - Monitor doesn't need a bootloader handshake, just
     * USB permission on a port. Without this, Monitor was only reachable after a
     * successful (or at least attempted) connect on the Connect screen, since
     * that's the only other place [_selectedDriver] got set.
     */
    fun selectDriverForMonitor(driver: UsbSerialDriver) {
        viewModelScope.launch {
            _monitorSelectError.value = null
            val granted = usbRepository.requestPermission(driver.device)
            if (!granted) {
                _monitorSelectError.value = "USB permission denied"
                return@launch
            }
            _selectedDriver.value = driver
        }
    }

    fun startMonitor(baudRate: Int) {
        val driver = _selectedDriver.value ?: run {
            _monitorLines.update { it + "Cannot start: no device selected" }
            return
        }
        // A monitor session that ended on detach may not have released the port
        // yet; if it's genuinely busy, tell the user instead of silently no-op'ing.
        if (!UsbPortCoordinator.tryAcquire(PortOccupant.MONITOR)) {
            _monitorLines.update { it + "Cannot start: serial port is busy" }
            return
        }

        monitorJob = viewModelScope.launch(Dispatchers.IO) {
            val lineBuilder = StringBuilder()
            try {
                // openPort can throw (stale/detached device, permission revoked on
                // reattach, etc.). Anything thrown here MUST be caught: an
                // uncaught exception in this launched coroutine crashes the app -
                // exactly the "reconnect then Start" crash.
                val port = usbRepository.openPort(driver)
                port.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                monitorPort = port
                _monitorRunning.value = true

                val buffer = ByteArray(4096)
                while (isActive) {
                    val n = try {
                        port.read(buffer, buffer.size, 500)
                    } catch (_: IOException) {
                        break
                    }
                    if (n > 0) {
                        for (ch in String(buffer, 0, n, Charsets.UTF_8)) {
                            when (ch) {
                                '\n' -> {
                                    _monitorLines.update { it + lineBuilder.toString() }
                                    lineBuilder.clear()
                                }
                                '\r' -> Unit
                                else -> lineBuilder.append(ch)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _monitorLines.update { it + "Monitor error: ${e.message} - unplug/replug and re-select the device" }
            } finally {
                if (lineBuilder.isNotEmpty()) {
                    _monitorLines.update { it + lineBuilder.toString() }
                }
                runCatching { monitorPort?.close() }
                monitorPort = null
                _monitorRunning.value = false
                UsbPortCoordinator.release(PortOccupant.MONITOR)
            }
        }
    }

    fun stopMonitor() {
        monitorJob?.cancel()
    }

    fun clearMonitor() {
        _monitorLines.value = emptyList()
    }

    fun setMonitorControlLines(dtr: Boolean? = null, rts: Boolean? = null) {
        val port = monitorPort ?: return
        runCatching {
            dtr?.let { port.setDTR(it) }
            rts?.let { port.setRTS(it) }
        }
    }

    private companion object {
        const val MAX_SESSION_LOG_LINES = 3000
    }
}
