package com.espotg.app.ui.connect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.espotg.app.ui.AppViewModel
import com.espotg.app.ui.ConnectionStatus
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

    LaunchedEffect(Unit) { appViewModel.refreshDevices() }
    LaunchedEffect(status) {
        if (status is ConnectionStatus.Identified) onConnected()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EspOtg") },
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
