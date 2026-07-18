package com.espotg.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.espotg.core.FlashOptions
import com.espotg.core.FlashSize
import com.espotg.core.SpiFreq
import com.espotg.core.SpiMode

@Composable
fun FlashOptionsPanel(
    options: FlashOptions,
    onOptionsChange: ((FlashOptions) -> FlashOptions) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                label = "Sync baud",
                value = options.syncBaudRate,
                onValueChange = { v -> onOptionsChange { it.copy(syncBaudRate = v) } },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label = "Flash baud",
                value = options.flashBaudRate,
                onValueChange = { v -> onOptionsChange { it.copy(flashBaudRate = v) } },
                modifier = Modifier.weight(1f),
            )
        }

        EnumDropdown(
            label = "SPI mode",
            options = SpiMode.entries,
            selected = options.spiMode,
            labelFor = { if (it == SpiMode.KEEP) "keep (from image)" else it.name },
            onSelect = { v -> onOptionsChange { it.copy(spiMode = v) } },
            modifier = Modifier.fillMaxWidth(),
        )

        EnumDropdown(
            label = "SPI frequency",
            options = SpiFreq.entries,
            selected = options.spiFreq,
            labelFor = { it.label },
            onSelect = { v -> onOptionsChange { it.copy(spiFreq = v) } },
            modifier = Modifier.fillMaxWidth(),
        )

        EnumDropdown(
            label = "Flash size",
            options = FlashSize.entries,
            selected = options.flashSize,
            labelFor = { it.label },
            onSelect = { v -> onOptionsChange { it.copy(flashSize = v) } },
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()

        ToggleRow(
            label = "Auto bootloader reset",
            checked = options.autoBootloaderReset,
            onCheckedChange = { v -> onOptionsChange { it.copy(autoBootloaderReset = v) } },
        )
        ToggleRow(
            label = "Compression",
            checked = options.compression,
            onCheckedChange = { v -> onOptionsChange { it.copy(compression = v) } },
        )
        ToggleRow(
            label = "Verify after write",
            checked = options.verifyAfterWrite,
            onCheckedChange = { v -> onOptionsChange { it.copy(verifyAfterWrite = v) } },
        )
    }
}

@Composable
private fun NumberField(label: String, value: Int, onValueChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text -> text.toIntOrNull()?.let(onValueChange) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
