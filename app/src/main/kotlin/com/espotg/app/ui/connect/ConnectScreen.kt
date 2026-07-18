package com.espotg.app.ui.connect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.espotg.app.BuildConfig
import com.espotg.app.ui.AppViewModel
import com.espotg.app.ui.ConnectionStatus
import com.espotg.app.ui.components.LogConsole
import com.espotg.core.LogLine
import com.hoho.android.usbserial.driver.UsbSerialDriver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    appViewModel: AppViewModel,
    onConnected: () -> Unit,
    onOpenProfiles: () -> Unit,
) {
    val drivers by appViewModel.usbRepository.availableDrivers.collectAsStateWithLifecycle()
    val status by appViewModel.connectionStatus.collectAsStateWithLifecycle()
    val autoReset by appViewModel.autoBootloaderReset.collectAsStateWithLifecycle()
    val logs = remember { mutableStateListOf<LogLine>() }

    LaunchedEffect(Unit) { appViewModel.refreshDevices() }
    LaunchedEffect(status) {
        if (status is ConnectionStatus.Identified) onConnected()
    }
    LaunchedEffect(Unit) {
        appViewModel.flashEngine.logs.collect { logs.add(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("EspOtg")
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = { TextButton(onClick = onOpenProfiles) { Text("Profiles") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text("Connected USB devices", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (drivers.isEmpty()) {
                Text("No USB-serial device detected. Plug in your ESP board.")
            } else {
                LazyColumn {
                    items(drivers) { driver ->
                        DeviceRow(driver, onClick = { appViewModel.connectAndIdentify(driver) })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto bootloader reset")
                Switch(checked = autoReset, onCheckedChange = { appViewModel.setAutoBootloaderReset(it) })
            }
            if (!autoReset) {
                Text(
                    "Hold BOOT, tap RESET, release BOOT, then tap the device above to connect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            when (val s = status) {
                is ConnectionStatus.RequestingPermission -> Text("Requesting USB permission...")
                is ConnectionStatus.PermissionDenied -> Text(
                    "USB permission denied.",
                    color = MaterialTheme.colorScheme.error,
                )
                is ConnectionStatus.Identifying -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Identifying chip...")
                }
                is ConnectionStatus.Failed -> Text(
                    "Error: ${s.message}",
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }

            if (logs.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Connection log", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                LogConsole(lines = logs, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DeviceRow(driver: UsbSerialDriver, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(driver.device.deviceName) },
        supportingContent = { Text("VID ${driver.device.vendorId} / PID ${driver.device.productId}") },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
