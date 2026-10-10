package com.libraryz.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.libraryz.data.PickedFile
import com.libraryz.data.Work
import com.libraryz.data.api.ApiException
import com.libraryz.data.api.DuplicateEditionException
import com.libraryz.data.formatBytes
import com.libraryz.data.isFilePickerSupported
import com.libraryz.data.rememberFilePicker
import com.libraryz.theme.LibraryZ
import com.libraryz.ui.components.Banner
import com.libraryz.ui.components.BannerTone
import com.libraryz.ui.components.BookCover
import com.libraryz.ui.components.CoverSize
import com.libraryz.ui.components.FormatBadge
import com.libraryz.ui.components.bylineOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

/** The server's upload cap (the edition handler and Kong both enforce it). */
const val MAX_UPLOAD_BYTES = 500L * 1024 * 1024

/** Formats the server accepts (sanitize.Validate's allowlist). */
val UploadFormats = listOf("PDF", "EPUB", "TXT")

private const val FORMATS_HINT = "PDF, EPUB or TXT · up to 500 MB"

/** Something wrong with the chosen file, caught before uploading. */
sealed interface FileProblem {
    data class TooLarge(val size: Long) : FileProblem
    data class Unsupported(val format: String) : FileProblem
}

fun checkUploadFile(name: String, size: Long): FileProblem? {
    val format = inferFormatFromName(name)
    return when {
        format !in UploadFormats -> FileProblem.Unsupported(format)
        size > MAX_UPLOAD_BYTES -> FileProblem.TooLarge(size)
        else -> null
    }
}

fun FileProblem.message(): String = when (this) {
    is FileProblem.TooLarge -> "This file is ${formatBytes(size)}. The limit is 500 MB."
    is FileProblem.Unsupported ->
        if (format.isEmpty()) "That file type isn’t supported. Use PDF, EPUB or TXT."
        else "$format files aren’t supported. Use PDF, EPUB or TXT."
}

/** Why an upload didn't make it, mapped from the server's response. */
sealed interface UploadFailure {
    /** The same bytes are already an edition; [workId] is where (null if it was taken down). */
    data class Duplicate(val workId: String?, val message: String) : UploadFailure
    data object RateLimited : UploadFailure
    data object TooLarge : UploadFailure
    data object Unsupported : UploadFailure
    /** The server rejected the file's contents (PDF/EPUB safety check, bad UTF-8, …). */
    data class FailedCheck(val message: String) : UploadFailure
    data class Other(val message: String) : UploadFailure
}

/**
 * Maps an upload error to what the sheet shows. [afterSend] is true once
 * the whole file reached the server, so a 400 then means its contents were
 * rejected rather than the request being malformed.
 */
fun classifyUploadError(e: Throwable, afterSend: Boolean): UploadFailure = when {
    e is DuplicateEditionException -> UploadFailure.Duplicate(e.workId, e.message ?: "This file is already in the library.")
    e is ApiException && e.status == 429 -> UploadFailure.RateLimited
    e is ApiException && e.status == 413 -> UploadFailure.TooLarge
    e is ApiException && e.status == 415 -> UploadFailure.Unsupported
    e is ApiException && e.status == 400 && afterSend -> UploadFailure.FailedCheck(e.userMessage)
    e is ApiException -> UploadFailure.Other(e.userMessage)
    else -> UploadFailure.Other("Can’t reach the library. Check your connection.")
}

private sealed interface Phase {
    data object Form : Phase
    data class Sending(val fraction: Float) : Phase
    data object Checking : Phase
    data class Done(val workId: String) : Phase
    data class Rejected(val message: String) : Phase
}

/**
 * Add a book (new work + first edition) or an edition of [target].
 * Full-screen on phones, a dialog on desktop. [onSubmit] performs the
 * upload, reporting send progress, and returns the work's id.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadSheet(
    mode: UploadMode,
    isDesktop: Boolean,
    target: Work?,
    onDismiss: () -> Unit,
    onSubmit: suspend (UploadSubmission, onProgress: (Float) -> Unit) -> String,
    onViewBook: (workId: String) -> Unit,
    onUnsupportedFilePicker: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var authors by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var format by remember { mutableStateOf("PDF") }
    var picked by remember { mutableStateOf<PickedFile?>(null) }
    var failure by remember { mutableStateOf<UploadFailure?>(null) }
    var phase by remember { mutableStateOf<Phase>(Phase.Form) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val launchPicker = rememberFilePicker {
        picked = it
        format = inferFormatFromName(it.name).takeIf { f -> f in UploadFormats } ?: format
        failure = null
    }
    val choose = { if (isFilePickerSupported) launchPicker() else onUnsupportedFilePicker() }
    val fileProblem = picked?.let { checkUploadFile(it.name, it.sizeBytes) }
    val yearValid = year.isBlank() || year.trim().toIntOrNull() != null
    val canSubmit = picked != null && fileProblem == null && yearValid &&
        (mode == UploadMode.AddEdition || title.isNotBlank()) && failure !is UploadFailure.RateLimited
    val bookTitle = if (mode == UploadMode.NewWork) title.trim().ifEmpty { "Your book" } else target?.title ?: "This book"

    fun submit() {
        val file = picked ?: return
        if (!canSubmit) return
        failure = null
        phase = Phase.Sending(0f)
        val submission = when (mode) {
            UploadMode.NewWork -> UploadSubmission.NewWork(
                title = title.trim(),
                authors = authors.trim().ifEmpty { null },
                language = language.trim().ifEmpty { null },
                publicationYear = year.trim().toIntOrNull(),
                file = file,
            )
            UploadMode.AddEdition -> UploadSubmission.AddEdition(
                format = format,
                language = language.trim().ifEmpty { null },
                file = file,
            )
        }
        job = scope.launch {
            try {
                val workId = onSubmit(submission) { f ->
                    phase = if (f >= 1f) Phase.Checking else Phase.Sending(f)
                }
                phase = Phase.Done(workId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                when (val f = classifyUploadError(e, afterSend = phase == Phase.Checking)) {
                    is UploadFailure.FailedCheck -> phase = Phase.Rejected(f.message)
                    else -> {
                        failure = f
                        phase = Phase.Form
                    }
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        phase = Phase.Form
    }

    fun reset() {
        picked = null
        failure = null
        phase = Phase.Form
        if (mode == UploadMode.NewWork) {
            title = ""; authors = ""; language = ""; year = ""
        }
    }

    val sheetTitle = when (phase) {
        Phase.Form -> if (mode == UploadMode.NewWork) "Add a book" else "Add an edition"
        is Phase.Sending -> "Uploading"
        Phase.Checking -> "Checking"
        is Phase.Rejected -> "Upload stopped"
        is Phase.Done -> ""
    }
    val busy = phase is Phase.Sending || phase == Phase.Checking
    val close = { if (busy) cancel(); onDismiss() }

    val content = @Composable {
        when (val p = phase) {
            Phase.Form -> UploadForm(
                mode = mode,
                target = target,
                isDesktop = isDesktop,
                picked = picked,
                fileProblem = fileProblem,
                failure = failure,
                title = title, onTitle = { title = it },
                authors = authors, onAuthors = { authors = it },
                language = language, onLanguage = { language = it },
                year = year, onYear = { year = it }, yearValid = yearValid,
                format = format, onFormat = { format = it },
                onChoose = choose,
                onRemoveFile = { picked = null; failure = null },
                onViewBook = onViewBook,
            )
            is Phase.Sending, Phase.Checking -> ProgressView(
                mode = mode,
                bookTitle = bookTitle,
                authors = if (mode == UploadMode.NewWork) authors else target?.authors,
                file = picked,
                format = format,
                fraction = (p as? Phase.Sending)?.fraction,
                onCancel = ::cancel,
            )
            is Phase.Rejected -> RejectedView(
                mode = mode,
                bookTitle = bookTitle,
                authors = if (mode == UploadMode.NewWork) authors else target?.authors,
                file = picked,
                message = p.message,
                onChooseAnother = { picked = null; phase = Phase.Form; choose() },
                onClose = onDismiss,
            )
            is Phase.Done -> DoneView(
                mode = mode,
                bookTitle = bookTitle,
                format = format,
                onView = { onViewBook(p.workId) },
                onAnother = ::reset,
            )
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = !busy),
    ) {
        if (isDesktop) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.width(600.dp).heightIn(max = 760.dp).padding(vertical = 24.dp),
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(sheetTitle, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = close) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
                    if (phase == Phase.Form) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (mode == UploadMode.NewWork) "Creates the book and its first edition · about 10 uploads an hour"
                                else "About 10 uploads an hour",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onDismiss) { Text("Cancel") }
                            Button(onClick = ::submit, enabled = canSubmit) { Text("Upload") }
                        }
                    }
                }
            }
        } else {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                    Row(
                        Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = close) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                        Text(
                            sheetTitle,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium),
                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                        )
                        if (phase == Phase.Form) {
                            TextButton(onClick = ::submit, enabled = canSubmit) { Text("Upload") }
                        }
                    }
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 4.dp),
                    ) { content() }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadForm(
    mode: UploadMode,
    target: Work?,
    isDesktop: Boolean,
    picked: PickedFile?,
    fileProblem: FileProblem?,
    failure: UploadFailure?,
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
            UploadFailure.RateLimited -> Banner(
                BannerTone.Calm,
                Icons.Rounded.HourglassTop,
                title = "Upload limit reached",
                body = "You can upload about 10 files an hour. Your details are kept here; try again later.",
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
        }

        when (mode) {
            UploadMode.NewWork -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionTitle("Book details")
                OutlinedTextField(
                    value = title, onValueChange = onTitle,
                    label = { Text("Title *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = authors, onValueChange = onAuthors,
                    label = { Text("Authors") }, singleLine = true,
                    supportingText = { Text("Separate several authors with semicolons (;)") },
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
private fun BookHeader(bookTitle: String, authors: String?, file: PickedFile?) {
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
private fun ProgressView(
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
private fun RejectedView(
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
private fun DoneView(mode: UploadMode, bookTitle: String, format: String, onView: () -> Unit, onAnother: () -> Unit) {
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

/** Picks a format string from a filename's extension ("" when there is none). */
fun inferFormatFromName(name: String): String =
    name.substringAfterLast('.', "").uppercase()
