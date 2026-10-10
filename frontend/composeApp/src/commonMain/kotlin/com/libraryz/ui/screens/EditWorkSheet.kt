package com.libraryz.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.libraryz.data.Work
import com.libraryz.data.api.ApiException
import com.libraryz.ui.components.Banner
import com.libraryz.ui.components.BannerTone
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


/** "2 changes", "No changes yet", or what still needs fixing. */
internal fun changeSummary(changed: Int, invalid: Int): String = when {
    invalid > 0 -> "${if (changed == 1) "1 change" else "$changed changes"} · fix $invalid field${if (invalid == 1) "" else "s"} to submit"
    changed == 0 -> "No changes yet"
    changed == 1 -> "1 change"
    else -> "$changed changes"
}

/**
 * Suggest an edit to [work]. Every field is pre-filled; changed ones are
 * marked with what they were and can be undone one at a time. Submitting
 * sends only the changes, for a moderator to review. Full-screen on
 * phones, a two-column dialog on desktop.
 */
@Composable
fun EditWorkSheet(
    work: Work,
    isDesktop: Boolean,
    onDismiss: () -> Unit,
    onSubmit: suspend (Map<String, JsonElement>) -> Unit,
) {
    val fields = remember(work.id) { fieldsFor(work) }
    val values = remember(work.id) { mutableStateMapOf<String, String>() }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    var sentCount by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    fun valueOf(f: EditField) = values[f.key] ?: f.initial
    fun isChanged(f: EditField) = valueOf(f).trim() != f.initial.trim()
    fun isInvalid(f: EditField) = f.numeric && valueOf(f).isNotBlank() && valueOf(f).trim().toIntOrNull() == null
    val changed = fields.count(::isChanged)
    val invalid = fields.count(::isInvalid)
    val canSubmit = changed > 0 && invalid == 0 && !submitting

    fun submit() {
        if (!canSubmit) return
        val patch = buildPatch(fields, values)
        submitting = true
        submitError = null
        scope.launch {
            try {
                onSubmit(patch)
                sentCount = patch.size
            } catch (e: Throwable) {
                submitError = (e as? ApiException)?.userMessage ?: e.message ?: "Couldn’t send your suggestion."
            } finally {
                submitting = false
            }
        }
    }

    val summary = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tint = when {
                invalid > 0 -> MaterialTheme.colorScheme.error
                changed > 0 -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(
                when { invalid > 0 -> Icons.Rounded.Error; changed > 0 -> Icons.Rounded.EditNote; else -> Icons.Rounded.Edit },
                contentDescription = null, tint = tint, modifier = Modifier.size(18.dp),
            )
            Text(changeSummary(changed, invalid), style = MaterialTheme.typography.labelLarge, color = tint)
        }
    }

    val fieldsView = @Composable {
        val byKey = fields.associateBy { it.key }
        val field = @Composable { key: String, mod: Modifier ->
            val f = byKey.getValue(key)
            FieldEditor(
                field = f,
                value = valueOf(f),
                changed = isChanged(f),
                invalid = isInvalid(f),
                onChange = { values[f.key] = it },
                onUndo = { values.remove(f.key) },
                modifier = mod,
            )
        }
        if (isDesktop) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    field("title", Modifier.weight(1f)); field("subtitle", Modifier.weight(1f))
                }
                field("authors", Modifier.fillMaxWidth())
                field("description", Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    field("language", Modifier.weight(1f)); field("publication_year", Modifier.width(160.dp))
                    field("isbn", Modifier.weight(1f))
                }
                field("openlibrary_id", Modifier.fillMaxWidth(0.5f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                field("title", Modifier.fillMaxWidth())
                field("subtitle", Modifier.fillMaxWidth())
                field("authors", Modifier.fillMaxWidth())
                field("description", Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    field("language", Modifier.weight(1f)); field("publication_year", Modifier.width(128.dp))
                }
                field("isbn", Modifier.fillMaxWidth())
                field("openlibrary_id", Modifier.fillMaxWidth())
            }
        }
    }

    val banner = @Composable {
        Banner(BannerTone.Info, Icons.Rounded.VerifiedUser, "A moderator reviews every suggestion before it shows on the book.")
        submitError?.let {
            Spacer(Modifier.height(12.dp))
            Banner(BannerTone.Error, Icons.Rounded.Error, it)
        }
    }

    Dialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val sent = sentCount
        if (isDesktop) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.width(if (sent != null) 440.dp else 760.dp).heightIn(max = 820.dp).padding(vertical = 32.dp),
            ) {
                if (sent != null) {
                    SentView(work.title, sent, onDismiss, Modifier.padding(24.dp))
                } else {
                    Column {
                        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Suggest an edit", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                            }
                            Column { banner() }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(24.dp)) { fieldsView() }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.weight(1f)) { summary() }
                            TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") }
                            Button(onClick = ::submit, enabled = canSubmit) {
                                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (submitting) "Sending…" else "Submit for review")
                            }
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
                        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                        Text(
                            if (sent == null) "Suggest an edit" else "",
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium),
                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                        )
                        if (sent == null) {
                            TextButton(onClick = ::submit, enabled = canSubmit) { Text(if (submitting) "Sending…" else "Submit") }
                        }
                    }
                    if (sent != null) {
                        SentView(work.title, sent, onDismiss, Modifier.fillMaxSize().padding(horizontal = 32.dp))
                    } else {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column { banner() }
                            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { summary() }
                                Text(
                                    work.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.End,
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
                            fieldsView()
                        }
                    }
                }
            }
        }
    }
}

/** One field: an outlined input, then (when changed) "EDITED · Was … · Undo". */
@Composable
private fun FieldEditor(
    field: EditField,
    value: String,
    changed: Boolean,
    invalid: Boolean,
    onChange: (String) -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = {
                Text(
                    if (field.numeric) "Year" else field.label,
                    fontWeight = if (changed) FontWeight.SemiBold else null,
                    color = if (changed && !invalid) primary else androidx.compose.ui.graphics.Color.Unspecified,
                )
            },
            placeholder = if (field.key == "subtitle") ({ Text("None") }) else null,
            singleLine = !field.multiline,
            minLines = if (field.multiline) 4 else 1,
            isError = invalid,
            textStyle = if (field.mono) {
                MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            } else MaterialTheme.typography.bodyLarge,
            keyboardOptions = if (field.numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            supportingText = when {
                invalid -> ({ Text("Use a whole number, like 1871") })
                field.key == "authors" -> ({ Text("Separate several authors with commas") })
                field.key == "openlibrary_id" && !changed -> ({ Text("Looks like OL12345W") })
                else -> null
            },
            colors = if (changed && !invalid) {
                OutlinedTextFieldDefaults.colors(unfocusedBorderColor = primary)
            } else OutlinedTextFieldDefaults.colors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (changed) {
            Row(
                Modifier.padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier.height(20.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("EDITED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Text(
                    if (field.initial.isBlank()) "Was empty" else "Was “${field.initial}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                TextButton(onClick = onUndo) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Undo")
                }
            }
        }
    }
}

@Composable
private fun SentView(title: String, count: Int, onBack: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.padding(bottom = 8.dp).size(112.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.RateReview, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        }
        Text("Sent for review", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Thanks. A moderator will look at your ${if (count == 1) "change" else "$count changes"} to $title. " +
                "${if (count == 1) "It’ll" else "They’ll"} appear on the book page once approved.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 12.dp)) {
            Text("Back to book", style = MaterialTheme.typography.titleMedium)
        }
    }
}
