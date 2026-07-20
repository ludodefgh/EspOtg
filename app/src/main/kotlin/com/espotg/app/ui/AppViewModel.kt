package com.espotg.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.espotg.app.data.DeviceProfile
import com.espotg.app.data.DeviceProfileRepository
import com.espotg.app.data.GitHubReleaseRepository
import com.espotg.app.data.db.EspOtgDatabase
import com.espotg.app.flash.FlashEngine
import com.espotg.app.usb.PortOccupant
import com.espotg.app.usb.UsbDeviceRepository
import com.espotg.app.usb.UsbPortCoordinator
import com.espotg.core.ChipIdentity
import com.espotg.core.DeviceFirmwareInfo
import com.espotg.core.EspBinaryInfo
import com.espotg.core.FlashEntry
import com.espotg.core.FlashOptions
import com.espotg.core.FlashPlan
import com.espotg.core.HexOffset
import com.espotg.core.LogLevel
import com.espotg.core.LogLine
import com.espotg.core.OffsetPresets
import com.espotg.core.TargetChip
import com.espotg.core.github.GitHubAsset
import com.espotg.core.github.GitHubRelease
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
    private val githubRepository = GitHubReleaseRepository(application.cacheDir)

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
            _deviceInfo.value = null
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

    // One-shot "a device just connected" signal - the Connect screen navigates to
    // Flash on this, NOT on connectionStatus being Identified (otherwise opening
    // the Devices screen from the drawer while already connected would bounce
    // straight back to Flash).
    private val _connectedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val connectedEvent: SharedFlow<Unit> = _connectedEvent

    private val _selectedDriver = MutableStateFlow<UsbSerialDriver?>(null)
    val selectedDriver: StateFlow<UsbSerialDriver?> = _selectedDriver

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val _currentPlan = MutableStateFlow(FlashPlan(entries = emptyList(), options = FlashOptions()))
    val currentPlan: StateFlow<FlashPlan> = _currentPlan

    private val _flashRunning = MutableStateFlow(false)
    val flashRunning: StateFlow<Boolean> = _flashRunning

    private val _deviceInfo = MutableStateFlow<DeviceFirmwareInfo?>(null)
    val deviceInfo: StateFlow<DeviceFirmwareInfo?> = _deviceInfo
    private val _deviceInfoLoading = MutableStateFlow(false)
    val deviceInfoLoading: StateFlow<Boolean> = _deviceInfoLoading

    // --- Linked GitHub repo + releases (issue #2) ---
    private val _boundRepo = MutableStateFlow<String?>(null)
    val boundRepo: StateFlow<String?> = _boundRepo
    private val _releases = MutableStateFlow<List<GitHubRelease>>(emptyList())
    val releases: StateFlow<List<GitHubRelease>> = _releases
    private val _releasesLoading = MutableStateFlow(false)
    val releasesLoading: StateFlow<Boolean> = _releasesLoading
    private val _releasesError = MutableStateFlow<String?>(null)
    val releasesError: StateFlow<String?> = _releasesError
    private val _downloadingAsset = MutableStateFlow<String?>(null)
    val downloadingAsset: StateFlow<String?> = _downloadingAsset

    /** MAC of the currently identified device, or null - needed to persist the repo binding. */
    private val connectedMac: String?
        get() = (_connectionStatus.value as? ConnectionStatus.Identified)?.identity?.macAddress

    fun bindRepo(input: String) {
        val ref = githubRepository.parseRepoRef(input) ?: run {
            _releasesError.value = "Not a valid repo (use owner/repo or a github.com URL)"
            return
        }
        val mac = connectedMac ?: run {
            _releasesError.value = "Connect and identify a device before linking a repo"
            logToSession("Connect and identify a device before linking a repo")
            return
        }
        viewModelScope.launch {
            val existing = profileRepository.findByMac(mac)
            val identity = (_connectionStatus.value as? ConnectionStatus.Identified)?.identity
            val serial = identity?.usbSerialNumber
                ?: _selectedDriver.value?.device?.let { dev ->
                    withContext(Dispatchers.IO) { usbRepository.readUsbSerialNumber(dev) }
                }
            val profile = existing?.copy(gitRepo = ref.slug) ?: DeviceProfile(
                macAddress = mac,
                chipType = identity?.chipType ?: TargetChip.ESP32,
                usbSerialNumber = serial,
                label = null,
                flashOptions = _currentPlan.value.options,
                flashEntries = emptyList(),
                lastUsedAtEpochMs = System.currentTimeMillis(),
                gitRepo = ref.slug,
            )
            profileRepository.save(profile)
            _boundRepo.value = ref.slug
            _releasesError.value = null
        }
    }

    fun unbindRepo() {
        val mac = connectedMac ?: return
        viewModelScope.launch {
            profileRepository.findByMac(mac)?.let { profileRepository.save(it.copy(gitRepo = null)) }
            _boundRepo.value = null
            _releases.value = emptyList()
        }
    }

    fun fetchReleases() {
        val slug = _boundRepo.value ?: return
        val ref = githubRepository.parseRepoRef(slug) ?: return
        _releasesLoading.value = true
        _releasesError.value = null
        viewModelScope.launch {
            try {
                _releases.value = githubRepository.fetchReleases(ref)
            } catch (e: Exception) {
                _releasesError.value = e.message ?: "Failed to fetch releases"
            } finally {
                _releasesLoading.value = false
            }
        }
    }

    /** Downloads a release asset and adds it to the flash queue as a normal entry. */
    fun downloadAndAddAsset(release: GitHubRelease, asset: GitHubAsset) {
        _downloadingAsset.value = asset.name
        viewModelScope.launch {
            try {
                val file = githubRepository.downloadAsset(asset, release.tagName)
                addBinaryFromFile(file, asset.name)
            } catch (e: Exception) {
                _releasesError.value = "Download failed: ${e.message}"
            } finally {
                _downloadingAsset.value = null
            }
        }
    }

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
            _deviceInfo.value = null
            _boundRepo.value = null
        }
        _selectedDriver.value = driver
        _connectionStatus.value = ConnectionStatus.RequestingPermission
        viewModelScope.launch {
            val granted = usbRepository.requestPermission(driver.device)
            if (!granted) {
                _connectionStatus.value = ConnectionStatus.PermissionDenied
                return@launch
            }

            // Speculative pre-fill from the USB serial number (readable before the
            // MAC-yielding bootloader sync). Non-authoritative: USB serials aren't
            // unique across cheap CP210x/CH340 boards, so it's reconciled against
            // the MAC below and discarded if this chip has no profile of its own.
            val serial = withContext(Dispatchers.IO) { usbRepository.readUsbSerialNumber(driver.device) }
            var speculative = false
            serial?.let { s ->
                profileRepository.findByUsbSerialNumber(s)?.let { applyProfile(it); speculative = true }
            }

            // Identify opens the port - gate it on the coordinator so it can't run
            // concurrently with an active flash or monitor session on the same port.
            if (!UsbPortCoordinator.tryAcquire(PortOccupant.FLASHER)) {
                _connectionStatus.value = ConnectionStatus.Failed("Serial port is busy - stop the monitor or flash first")
                return@launch
            }
            _connectionStatus.value = ConnectionStatus.Identifying
            try {
                val identity = withContext(Dispatchers.IO) {
                    flashEngine.identify(driver, autoReset = _autoBootloaderReset.value)
                }
                // MAC is the source of truth. Apply the MAC-matched profile if any;
                // otherwise discard any speculative serial-matched plan so a
                // different chip's binaries can't linger and get flashed.
                val macProfile = profileRepository.findByMac(identity.macAddress)
                when {
                    macProfile != null -> applyProfile(macProfile)
                    speculative -> resetPlan()
                }
                _releases.value = emptyList()
                _connectionStatus.value = ConnectionStatus.Identified(identity, loadedProfile = macProfile != null)
                _connectedEvent.tryEmit(Unit)
            } catch (e: Exception) {
                _connectionStatus.value = ConnectionStatus.Failed(e.message ?: "Connection failed")
            } finally {
                UsbPortCoordinator.release(PortOccupant.FLASHER)
            }
        }
    }

    private fun applyProfile(profile: DeviceProfile) {
        _currentPlan.value = FlashPlan(entries = profile.flashEntries, options = profile.flashOptions)
        _boundRepo.value = profile.gitRepo
    }

    /** Clears the flash queue back to an empty plan with default options. */
    private fun resetPlan() {
        _currentPlan.value = FlashPlan(entries = emptyList(), options = FlashOptions())
        _boundRepo.value = null
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

        // Reading only the header region is enough for parsing (app_desc ends at
        // ~0xB0, bootloader_desc at ~0x60) and avoids loading a multi-MB image
        // into memory just to show its tags.
        val info = runCatching {
            context.contentResolver.openInputStream(uri)?.use { parseHeader(it) }
        }.getOrNull()

        addEntry(uri.toString(), displayName, size, info)
    }

    /** Adds a downloaded/local file (e.g. a GitHub release asset) to the flash queue. */
    fun addBinaryFromFile(file: File, displayName: String) {
        val info = runCatching { file.inputStream().use { parseHeader(it) } }.getOrNull()
        addEntry(Uri.fromFile(file).toString(), displayName, file.length(), info)
    }

    private fun parseHeader(stream: java.io.InputStream): EspBinaryInfo? {
        val head = ByteArray(1024)
        val read = stream.read(head)
        return if (read > 0) EspBinaryInfo.parse(head.copyOf(read)) else null
    }

    private fun addEntry(uri: String, displayName: String, size: Long, info: EspBinaryInfo?) {
        val chipType = (_connectionStatus.value as? ConnectionStatus.Identified)?.identity?.chipType ?: TargetChip.ESP32
        val usedOffsets = _currentPlan.value.entries.map { it.offset }
        val nextOffset = OffsetPresets.forChip(chipType).map { it.second }.firstOrNull { it !in usedOffsets } ?: HexOffset.ZERO
        val entry = FlashEntry(uri = uri, displayName = displayName, sizeBytes = size, offset = nextOffset, info = info)
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

    /** Reads back the firmware metadata currently installed on the connected chip. */
    fun readDeviceInfo() {
        val driver = _selectedDriver.value ?: run {
            logToSession("Cannot read device info: no USB device selected")
            return
        }
        if (!UsbPortCoordinator.tryAcquire(PortOccupant.FLASHER)) {
            logToSession("Cannot read device info: serial port is busy")
            return
        }
        _deviceInfoLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _deviceInfo.value = flashEngine.readDeviceInfo(driver, autoReset = _autoBootloaderReset.value)
            } catch (_: Exception) {
                // Already surfaced via flashEngine.logs.
            } finally {
                _deviceInfoLoading.value = false
                UsbPortCoordinator.release(PortOccupant.FLASHER)
            }
        }
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
                // Preserve the existing gitRepo binding (Upsert overwrites the whole
                // row) and reuse the serial already read during the flash instead of
                // a second blocking control transfer.
                profileRepository.save(
                    DeviceProfile(
                        macAddress = identity.macAddress,
                        chipType = identity.chipType,
                        usbSerialNumber = identity.usbSerialNumber,
                        label = null,
                        flashOptions = plan.options,
                        flashEntries = plan.entries,
                        lastUsedAtEpochMs = System.currentTimeMillis(),
                        gitRepo = _boundRepo.value,
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

    fun pulseMonitorDtr() = pulseControlLine { port, level -> port.setDTR(level) }

    fun pulseMonitorRts() = pulseControlLine { port, level -> port.setRTS(level) }

    /**
     * Asserts a control line, holds it for [PULSE_WIDTH_MS], then releases it -
     * off the main thread (USB control transfers block) and with a real, defined
     * pulse width, unlike a synchronous true-then-false on the UI thread.
     */
    private fun pulseControlLine(set: (UsbSerialPort, Boolean) -> Unit) {
        val port = monitorPort ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                set(port, true)
                delay(PULSE_WIDTH_MS)
                set(port, false)
            }
        }
    }

    private companion object {
        const val MAX_SESSION_LOG_LINES = 3000
        const val PULSE_WIDTH_MS = 100L
    }
}
