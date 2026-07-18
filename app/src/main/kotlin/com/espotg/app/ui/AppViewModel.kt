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
import com.espotg.core.FlashEntry
import com.espotg.core.FlashOptions
import com.espotg.core.FlashPlan
import com.espotg.core.HexOffset
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

        val entry = FlashEntry(uri = uri.toString(), displayName = displayName, sizeBytes = size, offset = nextOffset)
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
        val driver = _selectedDriver.value ?: return
        val plan = _currentPlan.value
        if (plan.entries.isEmpty()) return
        if (!UsbPortCoordinator.tryAcquire(PortOccupant.FLASHER)) return

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

    fun startMonitor(baudRate: Int) {
        val driver = _selectedDriver.value ?: return
        if (!UsbPortCoordinator.tryAcquire(PortOccupant.MONITOR)) return

        monitorJob = viewModelScope.launch(Dispatchers.IO) {
            val lineBuilder = StringBuilder()
            try {
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
}
