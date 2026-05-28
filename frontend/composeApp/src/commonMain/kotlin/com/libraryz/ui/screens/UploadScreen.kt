package com.libraryz.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.libraryz.data.PickedFile
import com.libraryz.data.isFilePickerSupported
import com.libraryz.data.rememberFilePicker
import kotlinx.coroutines.launch

enum class UploadMode { NewWork, AddEdition }

sealed interface UploadSubmission {
    val file: PickedFile

    data class NewWork(
        val title: String,
        val authors: String?,
        val language: String?,
        val publicationYear: Int?,
        override val file: PickedFile,
    ) : UploadSubmission

    data class AddEdition(
        val format: String,
        val language: String?,
        override val file: PickedFile,
    ) : UploadSubmission
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadSheet(
    mode: UploadMode,
    isDesktop: Boolean,
    onDismiss: () -> Unit,
    onSubmit: suspend (UploadSubmission) -> Unit,
    onUnsupportedFilePicker: () -> Unit,
) {
    val body: @Composable () -> Unit = {
        UploadBody(
            mode = mode,
            onCancel = onDismiss,
            onSubmit = onSubmit,
            onUnsupportedFilePicker = onUnsupportedFilePicker,
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
    onSubmit: suspend (UploadSubmission) -> Unit,
    onUnsupportedFilePicker: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var authors by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var format by remember { mutableStateOf("PDF") }
    var picked by remember { mutableStateOf<PickedFile?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val launchPicker = rememberFilePicker { picked = it; error = null }

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
                OutlinedTextField(
                    value = format, onValueChange = { format = it.uppercase() },
                    label = { Text("Format") }, singleLine = true,
                    supportingText = { Text("PDF / EPUB / AZW3 / DJVU / CBZ / TXT") },
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
            picked = picked,
            onChoose = {
                if (isFilePickerSupported) {
                    launchPicker()
                } else {
                    onUnsupportedFilePicker()
                }
            },
        )

        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }

        if (uploading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onCancel, enabled = !uploading) { Text("Cancel") }
            Button(
                enabled = picked != null && !uploading &&
                    (mode == UploadMode.AddEdition || title.isNotBlank()),
                onClick = {
                    val file = picked ?: return@Button
                    if (uploading) return@Button
                    error = null
                    uploading = true
                    val submission: UploadSubmission = when (mode) {
                        UploadMode.NewWork -> UploadSubmission.NewWork(
                            title = title.trim(),
                            authors = authors.trim().ifEmpty { null },
                            language = language.trim().ifEmpty { null },
                            publicationYear = year.trim().toIntOrNull(),
                            file = file,
                        )
                        UploadMode.AddEdition -> UploadSubmission.AddEdition(
                            format = format.trim().ifEmpty { "PDF" },
                            language = language.trim().ifEmpty { null },
                            file = file,
                        )
                    }
                    scope.launch {
                        try {
                            onSubmit(submission)
                            // onDismiss is invoked by the caller after success
                        } catch (e: Throwable) {
                            error = e.message ?: "Upload failed"
                        } finally {
                            uploading = false
                        }
                    }
                },
            ) { Text("Upload") }
        }
    }
}

@Composable
private fun FilePickerRow(
    picked: PickedFile?,
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
                    text = picked?.name ?: "No file chosen",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (picked != null)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (picked != null) {
                    Text(
                        text = picked.prettySize,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = onChoose) { Text("Choose file") }
        }
    }
}

/** Picks a format string from a filename. Falls back to PDF. */
fun inferFormatFromName(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "pdf" -> "PDF"
        "epub" -> "EPUB"
        "azw3" -> "AZW3"
        "djvu" -> "DJVU"
        "cbz" -> "CBZ"
        "txt" -> "TXT"
        else -> ext.uppercase().ifEmpty { "PDF" }
    }
}
