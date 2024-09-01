package com.libraryz.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

enum class UploadMode { NewWork, AddEdition }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadSheet(
    mode: UploadMode,
    isDesktop: Boolean,
    onDismiss: () -> Unit,
    onUpload: () -> Unit,
    onChooseFile: () -> Unit,
) {
    val body: @Composable () -> Unit = {
        UploadBody(
            mode = mode,
            onCancel = onDismiss,
            onUpload = onUpload,
            onChooseFile = onChooseFile,
        )
    }

    if (isDesktop) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {},
            text = { body() },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) { body() }
        }
    }
}

@Composable
private fun UploadBody(
    mode: UploadMode,
    onCancel: () -> Unit,
    onUpload: () -> Unit,
    onChooseFile: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var authors by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var format by remember { mutableStateOf("PDF") }
    var fileName by remember { mutableStateOf<String?>(null) }
    var fileSize by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }

    Column(
        modifier = Modifier.widthIn(min = 320.dp, max = 480.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = if (mode == UploadMode.NewWork) "New work" else "Add edition",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        when (mode) {
            UploadMode.NewWork -> {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Title") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = authors, onValueChange = { authors = it },
                    label = { Text("Authors") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = language, onValueChange = { language = it },
                        label = { Text("Language") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = year, onValueChange = { year = it },
                        label = { Text("Year") }, singleLine = true,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                }
            }
            UploadMode.AddEdition -> {
                // Lightweight dropdown placeholder — a real dropdown lands when
                // the API client comes online and the format list is wired.
                OutlinedTextField(
                    value = format, onValueChange = { format = it.uppercase() },
                    label = { Text("Format") }, singleLine = true,
                    supportingText = { Text("PDF / EPUB / MOBI / AZW3 / DJVU / CBZ / TXT") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = language, onValueChange = { language = it },
                    label = { Text("Language (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        FilePickerRow(
            fileName = fileName,
            fileSize = fileSize,
            onChoose = {
                onChooseFile()
                // Demo selection so the row populates without a real file picker.
                fileName = if (mode == UploadMode.NewWork) "k&r-2e.pdf" else "sicp-2e.pdf"
                fileSize = "14.2 MB"
            },
        )

        if (progress != null) {
            Column {
                LinearProgressIndicator(
                    progress = { progress!! },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Uploading…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${(progress!! * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = {
                    // Stub local progress so the affordance is visible end-to-end.
                    progress = 0.62f
                    onUpload()
                },
                enabled = fileName != null,
            ) { Text("Upload") }
        }
    }
}

@Composable
private fun FilePickerRow(
    fileName: String?,
    fileSize: String?,
    onChoose: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName ?: "No file chosen",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (fileName != null)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (fileSize != null) {
                    Text(
                        text = fileSize,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = onChoose) { Text("Choose file") }
        }
    }
}
