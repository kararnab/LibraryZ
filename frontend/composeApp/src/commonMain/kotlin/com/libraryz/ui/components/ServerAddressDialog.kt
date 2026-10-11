package com.libraryz.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.libraryz.data.api.DefaultBaseUrl
import com.libraryz.data.api.parseServerUrl

/**
 * Edits the server address. Blank or invalid input saves as the default
 * ([DefaultBaseUrl]); the field says so before you save. [onSave] gets the
 * raw text, so the caller decides (with [parseServerUrl]) what to use.
 */
@Composable
fun ServerAddressDialog(
    current: String,
    signedIn: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    val parsed = parseServerUrl(text)
    val hint = when {
        text.isBlank() -> "Empty: uses the default, $DefaultBaseUrl"
        parsed == null -> "Not a valid address: saving uses the default, $DefaultBaseUrl"
        else -> "For example http://192.168.1.20:8080 or https://library.example.org"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (signedIn) {
                    Text(
                        "You may need to sign in again on the new server.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = text.isNotBlank() && parsed == null,
                    supportingText = { Text(hint) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(text) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = {
            if (current != DefaultBaseUrl) {
                TextButton(onClick = { onSave("") }) { Text("Use default") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

