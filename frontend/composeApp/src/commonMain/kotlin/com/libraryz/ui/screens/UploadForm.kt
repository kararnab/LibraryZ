package com.libraryz.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.libraryz.data.PickedFile
import com.libraryz.data.UploadQuota
import com.libraryz.data.Work
import com.libraryz.theme.LibraryZ
import com.libraryz.ui.components.AuthorChipsField
import com.libraryz.ui.components.Banner
import com.libraryz.ui.components.BannerTone
import com.libraryz.ui.components.BookCover
import com.libraryz.ui.components.CoverSize
import com.libraryz.ui.components.FormatBadge
import com.libraryz.ui.components.bylineOf

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UploadForm(
    mode: UploadMode,
    target: Work?,
    isDesktop: Boolean,
    picked: PickedFile?,
    fileProblem: FileProblem?,
    failure: UploadFailure?,
    quota: UploadQuota?,
    secondsLeft: Long?,
    title: String, onTitle: (String) -> Unit,
    authors: String, onAuthors: (String) -> Unit,
    language: String, onLanguage: (String) -> Unit,
    year: String, onYear: (String) -> Unit, yearValid: Boolean,
    format: String, onFormat: (String) -> Unit,
    onChoose: () -> Unit,
    onRemoveFile: () -> Unit,
    onViewBook: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(bottom = 24.dp)) {
        if (mode == UploadMode.AddEdition && target != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    BookCover(target.title, target.authors, if (isDesktop) CoverSize.M else CoverSize.S)
                    Column {
                        Text("NEW EDITION OF", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(target.title, style = LibraryZ.tokens.bookTitle)
                        val byline = bylineOf(target)
                        if (byline.isNotEmpty()) {
                            Text(byline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        when (failure) {
            is UploadFailure.Duplicate -> DuplicateBanner(failure, onViewBook)
            is UploadFailure.RateLimited -> Banner(
                BannerTone.Calm,
                Icons.Rounded.HourglassTop,
                title = "Upload limit reached",
                body = rateLimitedMessage(failure, picked?.sizeBytes, secondsLeft),
            )
            UploadFailure.TooLarge -> Banner(BannerTone.Error, Icons.Rounded.Error, "This file is too large or complex for the server to process.")
            UploadFailure.Unsupported -> Banner(BannerTone.Error, Icons.Rounded.Error, "The server doesn’t accept this format. Use PDF, EPUB or TXT.")
            is UploadFailure.Other -> Banner(BannerTone.Error, Icons.Rounded.Error, failure.message)
            is UploadFailure.FailedCheck, null -> Unit
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("File")
            if (picked == null) {
                DropZone(onClick = onChoose)
            } else {
                FileCard(picked, if (mode == UploadMode.AddEdition) format else inferFormatFromName(picked.name), error = fileProblem != null, onRemove = onRemoveFile)
                if (fileProblem != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                        Icon(Icons.Rounded.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Text(fileProblem.message(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = onChoose) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Choose another file")
                    }
                } else {
                    Text(FORMATS_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
            quota?.let(::quotaHint)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }

        when (mode) {
            UploadMode.NewWork -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionTitle("Book details")
                OutlinedTextField(
                    value = title, onValueChange = onTitle,
                    label = { Text("Title *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                AuthorChipsField(
                    value = authors, onValueChange = onAuthors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = language, onValueChange = onLanguage,
                        label = { Text("Language") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = year, onValueChange = onYear,
                        label = { Text("Year") }, singleLine = true,
                        isError = !yearValid,
                        supportingText = if (!yearValid) ({ Text("Whole number") }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(if (isDesktop) 160.dp else 120.dp),
                    )
                }
                if (!isDesktop) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                        Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Text(
                            "This adds the book and its first edition. You can upload about 10 files an hour.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            UploadMode.AddEdition -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                var open by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                    OutlinedTextField(
                        value = format,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Format") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                        supportingText = picked?.let { f ->
                            val ext = f.name.substringAfterLast('.', "").lowercase()
                            if (ext.isNotEmpty()) ({ Text("Detected from .$ext · change it if that’s wrong") }) else null
                        },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        UploadFormats.forEach { f ->
                            DropdownMenuItem(
                                text = { Text(f) },
                                trailingIcon = { Text(".${f.lowercase()}", style = MaterialTheme.typography.bodySmall) },
                                onClick = { onFormat(f); open = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = language, onValueChange = onLanguage,
                    label = { Text("Language (optional)") },
                    placeholder = { Text("Same as the book") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DuplicateBanner(failure: UploadFailure.Duplicate, onViewBook: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (failure.workId != null) {
                        Text("This exact file is already in the catalog", style = MaterialTheme.typography.labelLarge)
                        Text("Someone uploaded the same file before. Nothing new was added.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(failure.message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (failure.workId != null) {
                Surface(
                    onClick = { onViewBook(failure.workId) },
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Open the book that has it", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** A dashed target that opens the file picker. */
@Composable
private fun DropZone(onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                )
            }
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.UploadFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        Text("Choose a file", style = MaterialTheme.typography.titleMedium)
        Text(FORMATS_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FileCard(file: PickedFile, format: String, error: Boolean, onRemove: (() -> Unit)?) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FormatBadge(format)
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(file.prettySize, style = MaterialTheme.typography.bodySmall)
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = "Remove file") }
            }
        }
    }
}

@Composable
internal fun BookHeader(bookTitle: String, authors: String?, file: PickedFile?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        BookCover(bookTitle, authors, CoverSize.S)
        Column {
            Text(bookTitle, style = LibraryZ.tokens.bookTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (file != null) {
                Text(
                    "${file.name} · ${file.prettySize}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
