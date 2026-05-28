package com.libraryz.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.libraryz.data.Work
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Editable Work fields. Order matches the design's two-column grid on
 * expanded layout — single-line fields fill columns, description spans
 * both. Keys match the backend whitelist in `internal/contribution`'s
 * `allowedPatchFields`.
 */
private data class EditField(
    val key: String,
    val label: String,
    val initial: String,
    val multiline: Boolean = false,
    val mono: Boolean = false,
    val numeric: Boolean = false,
)

private fun fieldsFor(work: Work): List<EditField> = listOf(
    EditField("title", "Title", work.title),
    EditField("subtitle", "Subtitle", work.subtitle.orEmpty()),
    EditField("authors", "Authors", work.authors.orEmpty()),
    EditField("description", "Description", work.description.orEmpty(), multiline = true),
    EditField("language", "Language", work.language.orEmpty()),
    EditField("publication_year", "Publication year",
        work.publicationYear?.toString().orEmpty(), numeric = true),
    EditField("isbn", "ISBN", work.isbn.orEmpty(), mono = true),
    EditField("openlibrary_id", "OpenLibrary ID", work.openlibraryId.orEmpty(), mono = true),
)

/**
 * Build the patch to POST. Walks the field list, includes only entries
 * whose current value differs from the initial. Numeric fields coerce to
 * `JsonPrimitive(Int)` or `JsonNull` when cleared; non-parseable numeric
 * input is excluded entirely (the submit button is also disabled in that
 * case via [yearError]).
 */
private fun buildPatch(
    fields: List<EditField>,
    values: Map<String, String>,
): Map<String, JsonElement> {
    val out = mutableMapOf<String, JsonElement>()
    for (f in fields) {
        val now = (values[f.key] ?: f.initial).trim()
        if (now == f.initial.trim()) continue
        val v: JsonElement? = when {
            f.numeric && now.isEmpty() -> JsonNull
            f.numeric -> now.toIntOrNull()?.let { JsonPrimitive(it) }
            else -> JsonPrimitive(now)
        }
        if (v != null) out[f.key] = v
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditWorkSheet(
    work: Work,
    isDesktop: Boolean,
    onDismiss: () -> Unit,
    onSubmit: suspend (Map<String, JsonElement>) -> Unit,
) {
    val body: @Composable () -> Unit = {
        EditWorkBody(work = work, isDesktop = isDesktop, onCancel = onDismiss, onSubmit = onSubmit)
    }

    if (isDesktop) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {},
            text = { body() },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
private fun EditWorkBody(
    work: Work,
    isDesktop: Boolean,
    onCancel: () -> Unit,
    onSubmit: suspend (Map<String, JsonElement>) -> Unit,
) {
    val fields = remember(work.id) { fieldsFor(work) }
    val values = remember(work.id) {
        mutableStateMapOf<String, String>().apply {
            fields.forEach { put(it.key, it.initial) }
        }
    }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // publication_year is the only field where free-text can be invalid —
    // numeric coercion failures here disable the submit button.
    val yearError by remember {
        derivedStateOf {
            val v = (values["publication_year"] ?: "").trim()
            v.isNotEmpty() && v.toIntOrNull() == null
        }
    }

    val patch by remember {
        derivedStateOf { buildPatch(fields, values) }
    }
    val changeCount = patch.size

    Column(
        modifier = Modifier
            .widthIn(min = 320.dp, max = if (isDesktop) 640.dp else 480.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (isDesktop) 16.dp else 14.dp),
    ) {
        // Header row: title + change-count chip
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Suggest edit",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(modifier = Modifier.weight(1f))
            ChangeCountChip(count = changeCount)
        }

        Text(
            text = buildEditsBlurb(work.title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Field grid: two-column on expanded with description spanning both,
        // single-column on compact.
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = if (isDesktop) 520.dp else 460.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isDesktop) {
                // Pair single-line fields into rows; description gets its own row.
                val rows = pairForGrid(fields)
                rows.forEach { row ->
                    if (row.size == 1 && row[0].multiline) {
                        EditTextField(
                            field = row[0],
                            value = values[row[0].key] ?: "",
                            onChange = { values[row[0].key] = it },
                            modifier = Modifier.fillMaxWidth(),
                            isYearError = false,
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { f ->
                                EditTextField(
                                    field = f,
                                    value = values[f.key] ?: "",
                                    onChange = { values[f.key] = it },
                                    modifier = Modifier.weight(1f),
                                    isYearError = f.numeric && yearError,
                                )
                            }
                        }
                    }
                }
            } else {
                fields.forEach { f ->
                    EditTextField(
                        field = f,
                        value = values[f.key] ?: "",
                        onChange = { values[f.key] = it },
                        modifier = Modifier.fillMaxWidth(),
                        isYearError = f.numeric && yearError,
                    )
                }
            }
        }

        if (submitError != null) {
            Text(
                submitError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (submitting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onCancel, enabled = !submitting) { Text("Cancel") }
            Button(
                enabled = changeCount > 0 && !yearError && !submitting,
                onClick = {
                    if (submitting) return@Button
                    submitError = null
                    submitting = true
                    val toSend = patch
                    scope.launch {
                        try {
                            onSubmit(toSend)
                        } catch (e: Throwable) {
                            submitError = e.message ?: "Submit failed"
                        } finally {
                            submitting = false
                        }
                    }
                },
            ) { Text("Submit edit") }
        }
    }
}

@Composable
private fun ChangeCountChip(count: Int) {
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        color = if (count == 0) MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                else primary.copy(alpha = 0.10f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = if (count == 0) "No changes yet"
                   else "$count change${if (count == 1) "" else "s"}",
            style = MaterialTheme.typography.labelMedium,
            color = if (count == 0) onSurfaceVariant else primary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTextField(
    field: EditField,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
    isYearError: Boolean,
) {
    val dirty = value.trim() != field.initial.trim()
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = if (dirty) primary else primary,
        unfocusedBorderColor = if (dirty) primary else outline,
        focusedLabelColor = if (dirty) primary else primary,
        unfocusedLabelColor = if (dirty) primary else onSurfaceVariant,
        focusedContainerColor = if (dirty) primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
        unfocusedContainerColor = if (dirty) primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
    )

    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (dirty) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(primary),
                    )
                }
                Text(if (dirty) "${field.label} · edited" else field.label)
            }
        },
        singleLine = !field.multiline,
        minLines = if (field.multiline) 3 else 1,
        keyboardOptions = if (field.numeric)
            KeyboardOptions(keyboardType = KeyboardType.Number)
        else
            KeyboardOptions.Default,
        textStyle = LocalTextStyle.current.copy(
            fontFamily = if (field.mono) FontFamily.Monospace else FontFamily.Default,
        ),
        isError = isYearError,
        supportingText = if (isYearError) ({
            Text("Must be a whole number", style = MaterialTheme.typography.bodySmall)
        }) else null,
        colors = colors,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier,
    )
}

/**
 * Group fields into rows for the expanded grid. Single-line fields pair up
 * left/right; multiline (description) gets its own row spanning the width.
 */
private fun pairForGrid(fields: List<EditField>): List<List<EditField>> {
    val rows = mutableListOf<MutableList<EditField>>()
    for (f in fields) {
        if (f.multiline) {
            rows.add(mutableListOf(f))
        } else {
            val last = rows.lastOrNull()
            if (last != null && last.size == 1 && !last[0].multiline) {
                last.add(f)
            } else {
                rows.add(mutableListOf(f))
            }
        }
    }
    return rows
}

private fun buildEditsBlurb(title: String): String =
    "Edits to \"$title\" go to a moderator for review."
