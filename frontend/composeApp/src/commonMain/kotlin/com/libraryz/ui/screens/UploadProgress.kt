package com.libraryz.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.libraryz.data.PickedFile
import com.libraryz.data.formatBytes

private enum class StepState { Todo, Active, Done, Failed }

/** One row of the vertical stepper: a status dot on a rail, then its content. */
@Composable
private fun Step(state: StepState, icon: ImageVector, last: Boolean, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (state) {
        StepState.Done -> scheme.primary to scheme.onPrimary
        StepState.Active -> scheme.primaryContainer to scheme.onPrimaryContainer
        StepState.Failed -> scheme.error to scheme.onError
        StepState.Todo -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(32.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(bg),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp)) }
            if (!last) {
                Box(
                    Modifier.padding(vertical = 4.dp).width(2.dp).height(40.dp)
                        .background(if (state == StepState.Done) scheme.primary else scheme.outlineVariant),
                )
            }
        }
        Column(Modifier.weight(1f).padding(top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}

private fun checkCopy(format: String, size: Long): String {
    val big = size > 50L * 1024 * 1024
    return if (format == "PDF") {
        "PDFs are checked on the server before anyone can open them. " +
            if (big) "A file this size can take a few minutes." else "Large files can take a few minutes."
    } else {
        "Files are checked on the server before anyone can open them."
    }
}

@Composable
internal fun ProgressView(
    mode: UploadMode,
    bookTitle: String,
    authors: String?,
    file: PickedFile?,
    format: String,
    fraction: Float?,
    onCancel: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val size = file?.sizeBytes ?: 0L
    Column(verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)) {
        BookHeader(bookTitle, authors, file)
        Column {
            if (fraction != null) {
                Step(StepState.Active, Icons.Rounded.Upload, last = false) {
                    Text("Uploading", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                        LinearProgressIndicator(progress = { fraction }, drawStopIndicator = {}, modifier = Modifier.weight(1f))
                        Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = muted)
                    }
                    Text("${formatBytes((size * fraction).toLong())} of ${formatBytes(size)}", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                Step(StepState.Todo, Icons.Rounded.Shield, last = false) {
                    Text("Safety check", style = MaterialTheme.typography.titleMedium, color = muted)
                    Text("Starts when the upload finishes", style = MaterialTheme.typography.bodySmall, color = muted)
                }
            } else {
                Step(StepState.Done, Icons.Rounded.Check, last = false) {
                    Text("Uploaded", style = MaterialTheme.typography.titleMedium)
                    Text("${formatBytes(size)} sent", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                Step(StepState.Active, Icons.Rounded.Shield, last = false) {
                    Text("Safety check", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    Text(checkCopy(format, size), style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
            Step(StepState.Todo, Icons.Rounded.Done, last = true) {
                Text(
                    if (mode == UploadMode.NewWork) "Added to the catalog" else "Added to Editions",
                    style = MaterialTheme.typography.titleMedium,
                    color = muted,
                )
            }
        }
        TextButton(onClick = onCancel) { Text("Cancel upload") }
    }
}

@Composable
internal fun RejectedView(
    mode: UploadMode,
    bookTitle: String,
    authors: String?,
    file: PickedFile?,
    message: String,
    onChooseAnother: () -> Unit,
    onClose: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)) {
        BookHeader(bookTitle, authors, file)
        Column {
            Step(StepState.Done, Icons.Rounded.Check, last = false) {
                Text("Uploaded", style = MaterialTheme.typography.titleMedium)
                Text("${formatBytes(file?.sizeBytes ?: 0)} sent", style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Step(StepState.Failed, Icons.Rounded.Close, last = false) {
                Text("Didn’t pass the safety check", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(
                    "The server won’t share this file with readers, so it wasn’t added. Try a different copy of the book.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
                Text("Reason: $message", style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Step(StepState.Todo, Icons.Rounded.Done, last = true) {
                Text(
                    if (mode == UploadMode.NewWork) "Not added to the catalog" else "Not added to Editions",
                    style = MaterialTheme.typography.titleMedium,
                    color = muted,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onChooseAnother) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Choose another file")
            }
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}

@Composable
internal fun DoneView(mode: UploadMode, bookTitle: String, format: String, onView: () -> Unit, onAnother: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.padding(bottom = 8.dp).size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.TaskAlt, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp))
        }
        Text(
            if (mode == UploadMode.NewWork) "$bookTitle is in the catalog" else "New edition of $bookTitle added",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            "Your ${formatName(format)} passed the safety check and is ready to read.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onView, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("View book", style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = onAnother, modifier = Modifier.fillMaxWidth()) { Text("Upload another") }
    }
}

private fun formatName(format: String) = if (format == "TXT") "text file" else format
