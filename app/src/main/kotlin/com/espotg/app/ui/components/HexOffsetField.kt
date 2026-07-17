package com.espotg.app.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.espotg.core.HexOffset

/**
 * Free-typed hex offset entry (e.g. "0x1000") - deliberately not a dropdown of
 * fixed presets, per the explicit ask that motivated this whole project: the
 * competing app users liked let you type any offset, not just pick from a list.
 */
@Composable
fun HexOffsetField(
    value: HexOffset,
    onValueChange: (HexOffset) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Offset",
) {
    var text by remember(value) { mutableStateOf(value.toHexString()) }
    var isError by remember(value) { mutableStateOf(false) }

    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            HexOffset.parse(newText).fold(
                onSuccess = {
                    isError = false
                    onValueChange(it)
                },
                onFailure = { isError = true },
            )
        },
        label = { Text(label) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = modifier,
    )
}
