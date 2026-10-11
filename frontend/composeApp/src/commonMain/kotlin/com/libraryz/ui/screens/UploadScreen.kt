package com.libraryz.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.libraryz.data.PickedFile
import com.libraryz.data.UploadQuota
import com.libraryz.data.Work
import com.libraryz.data.isFilePickerSupported
import com.libraryz.data.rememberFilePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
    // GET /me/upload-quota for a file of this size (0 = any next file).
    loadQuota: suspend (size: Long) -> UploadQuota,
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

    var quota by remember { mutableStateOf<UploadQuota?>(null) }
    // When a RateLimited failure clears, and the seconds until then.
    var retryAt by remember { mutableStateOf<TimeMark?>(null) }
    var secondsLeft by remember { mutableStateOf<Long?>(null) }

    fun rateLimited(f: UploadFailure.RateLimited) {
        failure = f
        retryAt = f.retryAfterSeconds?.let { TimeSource.Monotonic.markNow() + it.seconds }
    }

    // Asks the server whether a file of [size] fits now, so a used-up
    // allowance shows before a long upload rather than after it. A failed
    // check isn't an error here; the upload itself will say.
    fun checkQuota(size: Long) {
        scope.launch {
            val q = try {
                loadQuota(size)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                return@launch
            }
            quota = q
            when {
                !q.unlimited && !q.fits -> rateLimited(UploadFailure.RateLimited(q.retryAfterSeconds, q))
                // Only the quota's own verdict is lifted here; a gateway
                // 429 waits out its Retry-After.
                (failure as? UploadFailure.RateLimited)?.quota != null -> {
                    failure = null
                    retryAt = null
                }
            }
        }
    }

    LaunchedEffect(Unit) { checkQuota(0) }

    // Counts down to when the allowance frees up, then checks again.
    LaunchedEffect(retryAt) {
        val at = retryAt ?: run { secondsLeft = null; return@LaunchedEffect }
        while (true) {
            val left = -at.elapsedNow()
            if (left <= Duration.ZERO) break
            secondsLeft = left.inWholeSeconds + 1
            delay(if (left > 90.seconds) 30.seconds else 1.seconds)
        }
        secondsLeft = null
        retryAt = null
        failure = null
        checkQuota(picked?.sizeBytes ?: 0)
    }

    val launchPicker = rememberFilePicker {
        picked = it
        format = inferFormatFromName(it.name).takeIf { f -> f in UploadFormats } ?: format
        // A limit stays up until the check below says this file fits.
        if (failure !is UploadFailure.RateLimited) failure = null
        checkQuota(if (checkUploadFile(it.name, it.sizeBytes) == null) it.sizeBytes else 0)
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
                    is UploadFailure.RateLimited -> {
                        rateLimited(f)
                        phase = Phase.Form
                        // The app's quota explains itself better than a bare 429.
                        checkQuota(file.sizeBytes)
                    }
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
        retryAt = null
        phase = Phase.Form
        checkQuota(0)
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
                quota = quota,
                secondsLeft = secondsLeft,
                title = title, onTitle = { title = it },
                authors = authors, onAuthors = { authors = it },
                language = language, onLanguage = { language = it },
                year = year, onYear = { year = it }, yearValid = yearValid,
                format = format, onFormat = { format = it },
                onChoose = choose,
                onRemoveFile = {
                    picked = null
                    if (failure !is UploadFailure.RateLimited) failure = null
                    checkQuota(0)
                },
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
                                if (mode == UploadMode.NewWork) "Creates the book and its first edition" else "",
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

/** Picks a format string from a filename's extension ("" when there is none). */
fun inferFormatFromName(name: String): String =
    name.substringAfterLast('.', "").uppercase()
