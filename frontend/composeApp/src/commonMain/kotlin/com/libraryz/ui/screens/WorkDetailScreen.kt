package com.libraryz.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.libraryz.data.Edition
import com.libraryz.data.LibraryStatus
import com.libraryz.data.TEXT_POSITIONS
import com.libraryz.data.UserBook
import com.libraryz.data.Work
import com.libraryz.data.api.UpsertLibraryRequest
import com.libraryz.data.authorsFull
import com.libraryz.data.joinAuthors
import com.libraryz.data.pickReadableEdition
import com.libraryz.data.prettySize
import com.libraryz.data.readActionLabel
import com.libraryz.data.splitAuthors
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
    // Opens Browse searching for one author; null leaves the names as plain text.
    onAuthorClick: ((String) -> Unit)? = null,
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
                Header(work, wide, onAuthorClick) {
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

/** Cover beside title, authors and metadata; [extra] goes under the metadata (wide only). */
@Composable
private fun Header(work: Work, wide: Boolean, onAuthorClick: ((String) -> Unit)?, extra: @Composable () -> Unit) {
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
            AuthorLine(splitAuthors(work.authors), wide, onAuthorClick)
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

/**
 * "by A, B, C and D". Wide: each name is a link that searches Browse for
 * it. Narrow: inline links can't be 44dp tall, so the whole line is one
 * button that opens an Authors sheet (one author searches directly).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthorLine(names: List<String>, wide: Boolean, onAuthorClick: ((String) -> Unit)?) {
    if (names.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    var sheetOpen by remember { mutableStateOf(false) }
    // Past six names: the first five, then "and N more" (which opens the sheet).
    val shown = if (names.size <= 6) names else names.take(5)
    val more = names.size - shown.size
    val separator = { i: Int ->
        when {
            more > 0 -> if (i < shown.lastIndex) ", " else ""
            i == shown.lastIndex -> ""
            i == shown.lastIndex - 1 -> " and "
            else -> ", "
        }
    }
    val nameStyle = SpanStyle(color = colors.primary, fontWeight = FontWeight.SemiBold)
    if (onAuthorClick == null) {
        Text("by " + authorsFull(joinAuthors(names)), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        return
    }
    if (wide) {
        val linkStyle = TextLinkStyles(
            style = nameStyle.copy(textDecoration = TextDecoration.Underline),
            hoveredStyle = nameStyle.copy(textDecoration = TextDecoration.Underline, background = colors.primary.copy(alpha = 0.08f)),
        )
        Text(
            buildAnnotatedString {
                append("by ")
                shown.forEachIndexed { i, name ->
                    withLink(LinkAnnotation.Clickable("author:$i", linkStyle) { onAuthorClick(name) }) { append(name) }
                    append(separator(i))
                }
                if (more > 0) {
                    append(" and ")
                    withLink(LinkAnnotation.Clickable("more", linkStyle) { sheetOpen = true }) { append("$more more") }
                }
            },
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
    } else {
        Surface(
            onClick = { if (names.size == 1) onAuthorClick(names[0]) else sheetOpen = true },
            color = Color.Transparent,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics {
                contentDescription = "Authors: ${authorsFull(joinAuthors(names))}. " +
                    if (names.size == 1) "Find their books" else "Show authors"
            },
        ) {
            Row(Modifier.padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    buildAnnotatedString {
                        append("by ")
                        shown.forEachIndexed { i, name ->
                            withStyle(nameStyle) { append(name) }
                            append(separator(i))
                        }
                        if (more > 0) append(" and $more more")
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
            }
        }
    }
    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }, containerColor = colors.surfaceContainerLow) {
            Text("Authors", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                names.forEach { name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(onClickLabel = "Find books by $name") {
                                sheetOpen = false
                                onAuthorClick(name)
                            }
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(colors.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(name.first().uppercase(), style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                            Text("Find their books in Browse", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.onSurfaceVariant)
                    }
                }
            }
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
