package com.espotg.app.ui.flash

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.espotg.app.ui.AppViewModel
import com.espotg.app.ui.components.FlashOptionsPanel
import com.espotg.app.ui.components.HexOffsetField
import com.espotg.app.ui.components.LogConsole
import com.espotg.core.EspBinaryInfo
import com.espotg.core.EspImageType
import com.espotg.core.FlashEntry
import com.espotg.core.FlashEntryProgress
import com.espotg.core.FlashStepState
import com.espotg.core.HexOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashScreen(appViewModel: AppViewModel, onOpenMonitor: () -> Unit) {
    val plan by appViewModel.currentPlan.collectAsStateWithLifecycle()
    val flashRunning by appViewModel.flashRunning.collectAsStateWithLifecycle()
    val progress by appViewModel.flashEngine.progress.collectAsStateWithLifecycle()
    var showOptions by remember { mutableStateOf(false) }
    val logs by appViewModel.sessionLogs.collectAsStateWithLifecycle()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { appViewModel.addBinary(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Flash") },
                actions = { TextButton(onClick = onOpenMonitor) { Text("Monitor") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add binary") },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (plan.entries.isEmpty()) {
                    Text("No binaries added yet. Tap \"Add binary\" to pick one or more .bin files.")
                } else {
                    plan.entries.forEach { entry ->
                        FlashEntryRow(
                            entry = entry,
                            progress = progress.entries.firstOrNull { it.entryId == entry.id },
                            onOffsetChange = { appViewModel.updateEntryOffset(entry.id, it) },
                            onRemove = { appViewModel.removeEntry(entry.id) },
                        )
                        HorizontalDivider()
                    }
                }

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showOptions = !showOptions }) {
                    Text(if (showOptions) "Hide flash options" else "Flash options")
                }
                if (showOptions) {
                    FlashOptionsPanel(
                        options = plan.options,
                        onOptionsChange = { transform -> appViewModel.updateOptions(transform) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { appViewModel.startFlash() },
                    enabled = !flashRunning && plan.entries.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (flashRunning) "Flashing..." else "Flash")
                }
                Spacer(Modifier.height(8.dp))
            }

            if (logs.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Flash log", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { appViewModel.clearSessionLogs() }) { Text("Clear") }
                }
            }
            LogConsole(lines = logs, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun FlashEntryRow(
    entry: FlashEntry,
    progress: FlashEntryProgress?,
    onOffsetChange: (HexOffset) -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.displayName, style = MaterialTheme.typography.bodyLarge)
                Text("${entry.sizeBytes} bytes", style = MaterialTheme.typography.bodySmall)
            }
            HexOffsetField(value = entry.offset, onValueChange = onOffsetChange, modifier = Modifier.width(120.dp))
            IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
        }
        entry.info?.let { BinaryInfoBadges(it) }
        if (progress != null && progress.state != FlashStepState.PENDING) {
            LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BinaryInfoBadges(info: EspBinaryInfo) {
    val badges = buildList {
        when (info.type) {
            EspImageType.APP -> add("App" to MaterialTheme.colorScheme.tertiaryContainer)
            EspImageType.BOOTLOADER -> add("Bootloader" to MaterialTheme.colorScheme.secondaryContainer)
            EspImageType.ESP_IMAGE -> add("ESP image" to MaterialTheme.colorScheme.secondaryContainer)
            EspImageType.DATA -> add("Raw data" to MaterialTheme.colorScheme.surfaceVariant)
        }
        info.chipName?.let { add(it to MaterialTheme.colorScheme.primaryContainer) }
        info.projectName?.let { add(it to MaterialTheme.colorScheme.primaryContainer) }
        info.appVersion?.let { add(it to MaterialTheme.colorScheme.tertiaryContainer) }
        info.idfVersion?.let { add("IDF ${it.removePrefix("v")}" to MaterialTheme.colorScheme.surfaceVariant) }
        info.compileDate?.let { date ->
            val stamp = info.compileTime?.let { "$date $it" } ?: date
            add(stamp to MaterialTheme.colorScheme.surfaceVariant)
        }
        listOfNotNull(info.flashMode, info.flashFreq, info.flashSize).takeIf { it.isNotEmpty() }?.let {
            add(it.joinToString(" · ") to MaterialTheme.colorScheme.surfaceVariant)
        }
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        badges.forEach { (label, color) -> InfoBadge(label, color) }
    }
}

@Composable
private fun InfoBadge(label: String, containerColor: Color) {
    Surface(color = containerColor, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
