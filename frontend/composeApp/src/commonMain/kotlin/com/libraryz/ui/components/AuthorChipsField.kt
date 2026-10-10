package com.libraryz.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.libraryz.data.AuthorEntry
import com.libraryz.data.commitAuthor
import com.libraryz.data.splitAuthors
import com.libraryz.data.typeAuthors

/**
 * Authors as input chips over the stored "A; B; C" string. Enter or ";"
 * turns the typed name into a chip, × removes one, Backspace in an empty
 * field selects the last chip and a second Backspace removes it. A name
 * already listed isn't added; its chip is pointed at instead. Pasting
 * "A; B; C" makes three chips.
 *
 * [value] / [onValueChange] carry the stored string, an unfinished name
 * included. Names not in [original] keep a primary outline (Suggest an
 * edit); [highlight] gives the field the primary border of a changed field.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuthorChipsField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Authors",
    original: List<String>? = null,
    highlight: Boolean = false,
    supportingText: String? = "Press Enter or ; after each name",
) {
    val colors = MaterialTheme.colorScheme
    var entry by remember { mutableStateOf(AuthorEntry(splitAuthors(value), "")) }
    // A value we didn't send (a reset, an undo) replaces what's in the field.
    if (value != entry.value) entry = AuthorEntry(splitAuthors(value), "")
    var selected by remember { mutableStateOf<Int?>(null) }
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    fun update(next: AuthorEntry) {
        entry = next
        selected = null
        if (next.value != value) onValueChange(next.value)
    }
    fun remove(i: Int) {
        update(entry.copy(names = entry.names.filterIndexed { j, _ -> j != i }, duplicate = null))
        runCatching { focus.requestFocus() }
    }

    val dup = entry.duplicate
    val border = when {
        dup != null -> BorderStroke(2.dp, colors.error)
        focused -> BorderStroke(2.dp, colors.primary)
        highlight -> BorderStroke(1.dp, colors.primary)
        else -> BorderStroke(1.dp, colors.outline)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            color = Color.Transparent,
            shape = MaterialTheme.shapes.extraSmall,
            border = border,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(interactionSource = null, indication = null) { runCatching { focus.requestFocus() } },
        ) {
            Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (highlight) FontWeight.SemiBold else null,
                    color = when {
                        dup != null -> colors.error
                        focused || highlight -> colors.primary
                        else -> colors.onSurfaceVariant
                    },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    entry.names.forEachIndexed { i, name ->
                        val isNew = original != null && original.none { it.equals(name, ignoreCase = true) }
                        AuthorChip(
                            name = name,
                            selected = selected == i,
                            error = dup == i,
                            isNew = isNew,
                            onSelect = { selected = if (selected == i) null else i },
                            onRemove = { remove(i) },
                        )
                    }
                    BasicTextField(
                        value = entry.draft,
                        onValueChange = { update(typeAuthors(entry, it)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { update(commitAuthor(entry)) }),
                        interactionSource = interaction,
                        decorationBox = { inner ->
                            Box(Modifier.heightIn(min = 32.dp), contentAlignment = Alignment.CenterStart) {
                                if (entry.draft.isEmpty() && entry.names.isEmpty()) {
                                    Text("Add an author", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                                }
                                inner()
                            }
                        },
                        modifier = Modifier
                            .widthIn(min = 120.dp)
                            .focusRequester(focus)
                            .semantics { contentDescription = "Add an author" }
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when {
                                    e.key == Key.Enter || e.key == Key.NumPadEnter -> {
                                        update(commitAuthor(entry))
                                        true
                                    }
                                    e.key == Key.Backspace && entry.draft.isEmpty() && entry.names.isNotEmpty() -> {
                                        val sel = selected
                                        if (sel == null) selected = entry.names.lastIndex else remove(sel)
                                        true
                                    }
                                    else -> false
                                }
                            },
                    )
                }
            }
        }
        val help = when {
            dup != null -> "${entry.names[dup]} is already listed"
            selected != null -> "Press Backspace again to remove ${entry.names[selected!!]}"
            else -> supportingText
        }
        if (help != null) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (dup != null) Icon(Icons.Rounded.Error, contentDescription = null, tint = colors.error, modifier = Modifier.size(16.dp))
                Text(help, style = MaterialTheme.typography.bodySmall, color = if (dup != null) colors.error else colors.onSurfaceVariant)
            }
        }
    }
}

/** One name: tap to select, × (a 44dp target) to remove. */
@Composable
private fun AuthorChip(
    name: String,
    selected: Boolean,
    error: Boolean,
    isNew: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val content = when {
        error -> colors.error
        selected -> colors.onSecondaryContainer
        isNew -> colors.primary
        else -> colors.onSurfaceVariant
    }
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) colors.secondaryContainer else Color.Transparent,
        contentColor = content,
        border = when {
            selected -> null
            error -> BorderStroke(1.dp, colors.error)
            isNew -> BorderStroke(1.dp, colors.primary)
            else -> BorderStroke(1.dp, colors.outlineVariant)
        },
        modifier = Modifier.height(32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp).widthIn(max = 280.dp),
            )
            Box(
                Modifier
                    .size(32.dp)
                    .clickable(onClickLabel = "Remove $name", onClick = onRemove)
                    .semantics { contentDescription = "Remove $name" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}
