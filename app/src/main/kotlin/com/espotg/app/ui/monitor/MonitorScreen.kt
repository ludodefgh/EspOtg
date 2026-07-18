package com.espotg.app.ui.monitor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.espotg.app.ui.AppViewModel
import com.espotg.app.ui.components.LogConsole
import com.espotg.core.LogLevel
import com.espotg.core.LogLine
import com.hoho.android.usbserial.driver.UsbSerialDriver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(appViewModel: AppViewModel) {
    val selectedDriver by appViewModel.selectedDriver.collectAsStateWithLifecycle()
    val drivers by appViewModel.usbRepository.availableDrivers.collectAsStateWithLifecycle()
    val selectError by appViewModel.monitorSelectError.collectAsStateWithLifecycle()
    val lines by appViewModel.monitorLines.collectAsStateWithLifecycle()
    val running by appViewModel.monitorRunning.collectAsStateWithLifecycle()
    var baudText by remember { mutableStateOf("115200") }

    LaunchedEffect(Unit) { appViewModel.refreshDevices() }

    Scaffold(topBar = { TopAppBar(title = { Text("Serial monitor") }) }) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            if (selectedDriver == null) {
                Text("Select a USB device to monitor", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (selectError != null) {
                    Text(selectError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                if (drivers.isEmpty()) {
                    Text("No USB-serial device detected. Plug in your ESP board.")
                } else {
                    LazyColumn {
                        items(drivers) { driver ->
                            MonitorDeviceRow(driver, onClick = { appViewModel.selectDriverForMonitor(driver) })
                        }
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = baudText,
                        onValueChange = { baudText = it },
                        label = { Text("Baud rate") },
                        singleLine = true,
                        enabled = !running,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (running) appViewModel.stopMonitor()
                            else baudText.toIntOrNull()?.let { appViewModel.startMonitor(it) }
                        },
                    ) {
                        Text(if (running) "Stop" else "Start")
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { appViewModel.setMonitorControlLines(dtr = true); appViewModel.setMonitorControlLines(dtr = false) }) {
                        Text("Pulse DTR")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { appViewModel.setMonitorControlLines(rts = true); appViewModel.setMonitorControlLines(rts = false) }) {
                        Text("Pulse RTS")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { appViewModel.clearMonitor() }) {
                        Text("Clear")
                    }
                }

                Spacer(Modifier.height(8.dp))
                LogConsole(
                    lines = lines.map { LogLine(timestampMs = 0L, level = LogLevel.NONE, message = it) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun MonitorDeviceRow(driver: UsbSerialDriver, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(driver.device.deviceName) },
        supportingContent = { Text("VID ${driver.device.vendorId} / PID ${driver.device.productId}") },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
