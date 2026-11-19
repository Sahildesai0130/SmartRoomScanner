package com.cp36.roomscanner.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cp36.roomscanner.domain.model.LengthUnit
import com.cp36.roomscanner.domain.model.UnitFormat

/** Asks for one length in the user's unit and returns it in metres. */
@Composable
fun LengthDialog(
    title: String,
    label: String,
    unit: LengthUnit,
    initialMetres: Double?,
    note: String? = null,
    confirmText: String = "OK",
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(initialMetres?.let { UnitFormat.toInput(it, unit) } ?: "") }
    val value = UnitFormat.parse(text, unit)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (note != null) {
                    Text(note)
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    suffix = { Text(unit.inputSuffix) },
                    singleLine = true,
                    isError = text.isNotBlank() && value == null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (unit == LengthUnit.FT_IN) KeyboardType.Text else KeyboardType.Decimal
                    ),
                    supportingText = if (unit == LengthUnit.FT_IN) ({ Text("e.g. 12.5 or 12' 6") }) else null,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = value != null, onClick = { value?.let(onConfirm) }) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmText: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
