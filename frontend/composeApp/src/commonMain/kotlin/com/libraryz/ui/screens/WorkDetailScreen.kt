package com.libraryz.ui.screens

import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.libraryz.data.Edition
import com.libraryz.data.LibraryStatus
import com.libraryz.data.TEXT_POSITIONS
import com.libraryz.data.UserBook
import com.libraryz.data.Work
import com.libraryz.data.api.UpsertLibraryRequest
import com.libraryz.data.pickReadableEdition
import com.libraryz.data.prettySize
import com.libraryz.data.readActionLabel
import com.libraryz.ui.components.BookCover
import com.libraryz.ui.components.CoverSize
import com.libraryz.ui.components.EditionRow
import com.libraryz.ui.components.RemoveDialog
import com.libraryz.ui.components.StarRating

private const val WIDE_DP = 640

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkDetailScreen(
    work: Work,
    onBack: (() -> Unit)?,
    onAddEdition: () -> Unit,
    onRead: (Edition) -> Unit,
    onDownload: (Edition) -> Unit,
    onSuggestEdit: (() -> Unit)? = null,
    // Personal-library controls. Shown only when [libraryEnabled] (signed in).
    // [libraryEntry] is the caller's current entry (null = not in library yet).
    libraryEnabled: Boolean = false,
    libraryEntry: UserBook? = null,
    onLibraryUpsert: (UpsertLibraryRequest) -> Unit = {},
    onLibraryRemove: () -> Unit = {},
    // Moderator takedowns; null hides the affordance (non-moderators).
    onRemoveWork: ((reason: String) -> Unit)? = null,
    onRemoveEdition: ((Edition, reason: String) -> Unit)? = null,
    // In the desktop detail pane the pane supplies the surface.
    containerColor: Color = MaterialTheme.colorScheme.surface,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRemoveWork by remember { mutableStateOf(false) }
    var editionToRemove by remember { mutableStateOf<Edition?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Add edition") },
                            leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onAddEdition()
                            },
                        )
                        if (onSuggestEdit != null) {
                            DropdownMenuItem(
                                text = { Text("Suggest edit") },
                                onClick = {
                                    menuOpen = false
                                    onSuggestEdit()
                                },
                            )
                        }
                        if (libraryEnabled && libraryEntry != null) {
                            DropdownMenuItem(
                                text = { Text("Remove from library") },
                                onClick = {
                                    menuOpen = false
                                    onLibraryRemove()
                                },
                            )
                        }
                        if (onRemoveWork != null) {
                            DropdownMenuItem(
                                text = { Text("Remove work", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuOpen = false
                                    confirmRemoveWork = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = containerColor),
            )
        },
        containerColor = containerColor,
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth.value >= WIDE_DP
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (wide) 40.dp else 16.dp)
                    .padding(top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(if (wide) 28.dp else 20.dp),
            ) {
                // The one thing most visitors came for, so it leads.
                val readable = pickReadableEdition(work.editions)
                Header(work, wide) {
                    if (wide) ReadAction(work, readable, libraryEntry, wide = true, onRead = onRead)
                }
                if (!wide) ReadAction(work, readable, libraryEntry, wide = false, onRead = onRead)

                if (libraryEnabled) {
                    LibraryCard(
                        entry = libraryEntry,
                        wide = wide,
                        onUpsert = onLibraryUpsert,
                    )
                }

                Column {
                    Text(
                        "Editions",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    work.editions.forEach { ed ->
                        EditionRow(
                            edition = ed,
                            onDownload = { onDownload(ed) },
                            onRead = { onRead(ed) },
                            onRemove = onRemoveEdition?.let { { editionToRemove = ed } },
                        )
                    }
                }
            }
        }
    }

    if (confirmRemoveWork && onRemoveWork != null) {
        RemoveDialog(
            title = "Remove this work?",
            body = "“${work.title}” and all ${work.editions.size} of its editions will be hidden from everyone. " +
                "The files are kept for a while in case this needs to be undone.",
            onConfirm = { reason ->
                confirmRemoveWork = false
                onRemoveWork(reason)
            },
            onDismiss = { confirmRemoveWork = false },
        )
    }
    editionToRemove?.let { ed ->
        if (onRemoveEdition != null) {
            RemoveDialog(
                title = "Remove this edition?",
                body = "The ${ed.format.uppercase()} edition (${ed.prettySize}) will be hidden from everyone, " +
                    "and the same file can't be uploaded again.",
                onConfirm = { reason ->
                    editionToRemove = null
                    onRemoveEdition(ed, reason)
                },
                onDismiss = { editionToRemove = null },
            )
        }
    }
}

/** Cover beside title, author and metadata; [extra] goes under the metadata (wide only). */
@Composable
private fun Header(work: Work, wide: Boolean, extra: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(if (wide) 32.dp else 20.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        BookCover(work.title, work.authors, if (wide) CoverSize.XL else CoverSize.L)
        Column(
            modifier = Modifier.weight(1f).padding(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(if (wide) 6.dp else 4.dp),
        ) {
            Text(
                text = work.title,
                style = if (wide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
            )
            if (!work.authors.isNullOrBlank()) {
                Text(work.authors, style = MaterialTheme.typography.bodyLarge)
            }
            val meta = listOfNotNull(work.publicationYear?.toString(), work.language?.takeIf { it.isNotBlank() })
            val metaStyle = if (wide) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall
            if (wide) {
                val line = (meta + listOfNotNull(work.isbn?.let { "ISBN $it" })).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, style = metaStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (meta.isNotEmpty()) {
                    Text(
                        meta.joinToString(" · "),
                        style = metaStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                work.isbn?.takeIf { it.isNotBlank() }?.let {
                    Text("ISBN $it", style = metaStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            extra()
        }
    }
}

/** "Page 370 of 880" for paged books, "42% read" for flowing text. */
private fun positionLabel(entry: UserBook?): String? {
    if (entry == null || entry.status != LibraryStatus.Reading || entry.progressPercent <= 0) return null
    return if (entry.totalPages > 0 && entry.totalPages != TEXT_POSITIONS && entry.currentPage > 0) {
        "Page ${entry.currentPage} of ${entry.totalPages}"
    } else {
        "${entry.progressPercent}% read"
    }
}

@Composable
private fun ReadAction(
    work: Work,
    readable: Edition?,
    entry: UserBook?,
    wide: Boolean,
    onRead: (Edition) -> Unit,
) {
    if (readable == null) {
        if (work.editions.isNotEmpty()) {
            Text(
                text = "No edition here can be read in the app yet — download one below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = if (wide) 14.dp else 0.dp),
            )
        }
        return
    }
    val position = positionLabel(entry)
    val button = @Composable { mod: Modifier ->
        Button(
            onClick = { onRead(readable) },
            contentPadding = PaddingValues(start = 20.dp, end = 28.dp),
            modifier = mod.heightIn(min = 56.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(readActionLabel(entry), style = MaterialTheme.typography.titleMedium)
        }
    }
    if (wide) {
        Row(
            modifier = Modifier.padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            button(Modifier)
            position?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            button(Modifier.fillMaxWidth())
            if (position != null && entry != null) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { entry.progressPercent / 100f },
                        drawStopIndicator = {},
                        modifier = Modifier.weight(1f).height(2.dp),
                    )
                    Text(position, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * The "My Library" card: reading status, rating, shelf and a private note.
 * Status and rating save immediately; shelf and note save explicitly so
 * nothing is written while you're mid-sentence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryCard(
    entry: UserBook?,
    wide: Boolean,
    onUpsert: (UpsertLibraryRequest) -> Unit,
) {
    var editingNote by remember(entry?.id) { mutableStateOf(false) }
    var editingShelf by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("My Library", style = MaterialTheme.typography.titleMedium)

            val statuses = listOf(
                LibraryStatus.Want to "Want to read",
                LibraryStatus.Reading to "Reading",
                LibraryStatus.Read to "Read",
            )
            val statusRow = @Composable { mod: Modifier ->
                SingleChoiceSegmentedButtonRow(mod) {
                    statuses.forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = entry?.status == value,
                            onClick = { onUpsert(UpsertLibraryRequest(status = value)) },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = statuses.size),
                            label = { Text(label, maxLines = 1) },
                        )
                    }
                }
            }
            val stars = @Composable {
                StarRating(rating = entry?.rating, onRate = { onUpsert(UpsertLibraryRequest(rating = it)) })
            }
            val shelfChip = @Composable {
                val shelf = entry?.shelf?.takeIf { it.isNotBlank() }
                AssistChip(
                    onClick = { editingShelf = true },
                    label = { Text(shelf ?: "Add to shelf", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        Icon(
                            if (shelf != null) Icons.AutoMirrored.Rounded.LibraryBooks else Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }

            if (wide) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    statusRow(Modifier.weight(1f))
                    stars()
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Shelf", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    shelfChip()
                }
            } else {
                statusRow(Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    stars()
                    Box(Modifier.padding(start = 8.dp).weight(1f, fill = false)) { shelfChip() }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val note = entry?.notes?.takeIf { it.isNotBlank() }
            when {
                editingNote -> NoteEditor(
                    initial = note.orEmpty(),
                    onCancel = { editingNote = false },
                    onSave = {
                        onUpsert(UpsertLibraryRequest(notes = it))
                        editingNote = false
                    },
                )
                note != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(note, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { editingNote = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Rounded.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Edit note")
                    }
                }
                else -> TextButton(onClick = { editingNote = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Rounded.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add a note")
                }
            }
        }
    }

    if (editingShelf) {
        var shelf by remember { mutableStateOf(entry?.shelf.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editingShelf = false },
            title = { Text("Shelf") },
            text = {
                OutlinedTextField(
                    value = shelf,
                    onValueChange = { shelf = it },
                    label = { Text("Shelf name") },
                    placeholder = { Text("e.g. Victorian novels") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpsert(UpsertLibraryRequest(shelf = shelf.trim()))
                    editingShelf = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingShelf = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NoteEditor(initial: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Note") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Only you can see your notes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { onSave(text.trim()) }) { Text("Save note") }
        }
    }
}
