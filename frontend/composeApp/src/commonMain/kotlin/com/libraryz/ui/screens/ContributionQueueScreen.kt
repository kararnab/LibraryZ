package com.libraryz.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.libraryz.data.Contribution
import com.libraryz.data.Work
import com.libraryz.data.api.ContributionsState
import com.libraryz.data.authorsFull
import com.libraryz.theme.LibraryZ
import com.libraryz.ui.components.BookCover
import com.libraryz.ui.components.CoverSize
import com.libraryz.ui.components.EmptyState
import com.libraryz.ui.components.Skeleton
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/* ---------------- Diff helpers (pure, tested) ---------------- */

enum class DiffKind { Same, Added, Removed }

data class DiffSegment(val text: String, val kind: DiffKind)

/** Above this many token pairs the LCS table is too big; show a whole replace. */
private const val MAX_DIFF_CELLS = 250_000

/**
 * Word-level diff of [old] → [new]: a longest-common-subsequence over
 * words (each carrying its trailing whitespace, compared without it, so
 * spaces never anchor a match), with adjacent segments of the same kind
 * merged. Very long texts fall back to "all removed, all added".
 */
fun wordDiff(old: String, new: String): List<DiffSegment> {
    val tokenizer = Regex("""^\s+|\S+\s*""")
    val a = tokenizer.findAll(old).map { it.value }.toList()
    val b = tokenizer.findAll(new).map { it.value }.toList()
    val ka = a.map { it.trim() }
    val kb = b.map { it.trim() }
    if (a.size.toLong() * b.size > MAX_DIFF_CELLS) {
        return listOfNotNull(
            old.takeIf { it.isNotEmpty() }?.let { DiffSegment(it, DiffKind.Removed) },
            new.takeIf { it.isNotEmpty() }?.let { DiffSegment(it, DiffKind.Added) },
        )
    }
    // lcs[i][j] = LCS length of a[i..] and b[j..].
    val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
        lcs[i][j] = if (ka[i] == kb[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
    }
    val out = mutableListOf<DiffSegment>()
    fun push(t: String, k: DiffKind) {
        val last = out.lastOrNull()
        if (last != null && last.kind == k) out[out.lastIndex] = last.copy(text = last.text + t) else out += DiffSegment(t, k)
    }
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        when {
            ka[i] == kb[j] -> { push(b[j], DiffKind.Same); i++; j++ }
            lcs[i + 1][j] >= lcs[i][j + 1] -> { push(a[i], DiffKind.Removed); i++ }
            else -> { push(b[j], DiffKind.Added); j++ }
        }
    }
    while (i < a.size) push(a[i++], DiffKind.Removed)
    while (j < b.size) push(b[j++], DiffKind.Added)
    return out
}

private val FieldLabels = mapOf(
    "title" to "Title",
    "subtitle" to "Subtitle",
    "authors" to "Authors",
    "description" to "Description",
    "language" to "Language",
    "publication_year" to "Year",
    "isbn" to "ISBN",
    "openlibrary_id" to "OpenLibrary ID",
)

fun fieldLabel(key: String): String = FieldLabels[key] ?: key.replace('_', ' ').replaceFirstChar { it.uppercase() }

/** A JSON patch/current value as display text; "" for unset (and year 0). */
internal fun displayValue(field: String, v: JsonElement?): String {
    val s = when (v) {
        null, is JsonNull -> ""
        is JsonPrimitive -> v.contentOrNull ?: v.toString()
        else -> v.toString()
    }
    return if (field == "publication_year" && s == "0") "" else s
}

/** "10 Oct, 09:14" from an RFC 3339 timestamp (shown as stored, UTC). */
internal fun submittedAt(iso: String): String {
    val day = dayMonth(iso) ?: return iso
    val time = iso.takeIf { it.length >= 16 }?.substring(11, 16)
    // Non-breaking, so "10 Oct" never splits across lines.
    return (if (time != null) "$day, $time" else day).replace(' ', '\u00A0')
}

private fun changesLabel(n: Int) = if (n == 1) "1 change" else "$n changes"

/* ---------------- Screen ---------------- */

/**
 * The moderators' review queue. Phones: the queue, then one suggestion
 * full-screen with Approve / Reject in a bottom bar. Wide: queue beside
 * the open suggestion. [loadWork] resolves a contribution's book (title,
 * authors) for its cover and heading.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ContributionQueueScreen(
    state: ContributionsState,
    isWide: Boolean,
    onOpenWork: (String) -> Unit,
    loadWork: suspend (String) -> Work?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val items = state.items
    val books = remember { mutableStateMapOf<String, Work>() }
    LaunchedEffect(items) {
        items.orEmpty().map { it.workId }.distinct().filter { it !in books }.forEach { id ->
            loadWork(id)?.let { books[id] = it }
        }
    }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<Contribution, Boolean>?>(null) } // (item, approve?)
    var actionError by remember { mutableStateOf<String?>(null) }
    val selected = items?.firstOrNull { it.id == selectedId }
        ?: if (isWide) items?.firstOrNull() else null

    // On phones the open suggestion is a sub-screen: back returns to the queue.
    BackHandler(enabled = !isWide && selected != null) { selectedId = null }

    val decide: (Contribution, Boolean) -> Unit = { c, approve -> confirm = c to approve }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxSize()) {
        when {
            state.loading -> Column(Modifier.fillMaxSize()) {
                QueueHeader(null)
                QueueSkeleton()
            }
            state.error != null && items == null -> Column(Modifier.fillMaxSize()) {
                QueueHeader(null)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = "Couldn’t load the queue",
                        body = "Check your connection and try again.",
                        icon = Icons.Outlined.CloudOff,
                        action = {
                            FilledTonalButton(onClick = { scope.launch { state.refresh() } }) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Try again")
                            }
                        },
                    )
                }
            }
            items.isNullOrEmpty() -> Column(Modifier.fillMaxSize()) {
                QueueHeader(null)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = "All caught up",
                        body = "No suggestions are waiting for review. New ones will appear here.",
                        icon = Icons.Outlined.TaskAlt,
                    )
                }
            }
            isWide -> Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(380.dp).fillMaxHeight().padding(top = 8.dp)) {
                    QueueHeader(items.size)
                    QueueList(items, books, selected?.id, Modifier.padding(end = 16.dp)) { selectedId = it.id }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxSize().padding(top = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    selected?.let { c ->
                        SuggestionDetail(c, books[c.workId], wide = true, actionError, onOpenWork, decide)
                    }
                }
            }
            selected != null -> Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { selectedId = null }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to queue")
                    }
                    Text("Suggested edit", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    Text(
                        "${items.indexOf(selected) + 1} of ${items.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(Modifier.weight(1f)) {
                    SuggestionDetail(selected, books[selected.workId], wide = false, actionError, onOpenWork, decide)
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(onClick = { decide(selected, false) }, modifier = Modifier.weight(1f).height(48.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Reject")
                        }
                        Button(onClick = { decide(selected, true) }, modifier = Modifier.weight(1f).height(48.dp)) {
                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Approve")
                        }
                    }
                }
            }
            else -> Column(Modifier.fillMaxSize()) {
                QueueHeader(items.size)
                QueueList(items, books, null, Modifier) { selectedId = it.id; actionError = null }
            }
        }
    }

    confirm?.let { (c, approve) ->
        val title = books[c.workId]?.title ?: "This book"
        val fields = c.patch.keys.map { fieldLabel(it).lowercase() }
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = {
                Icon(
                    if (approve) Icons.Rounded.CheckCircle else Icons.Rounded.Block,
                    contentDescription = null,
                    tint = if (approve) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(if (approve) "Approve this edit?" else "Reject this edit?", textAlign = TextAlign.Center) },
            text = {
                Text(
                    if (approve) "$title will show the new ${joinNatural(fields)} to everyone."
                    else "The suggestion leaves the queue and $title stays as it is. This can’t be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    actionError = null
                    scope.launch {
                        try {
                            if (approve) state.approve(c.id) else state.reject(c.id)
                            selectedId = null
                        } catch (e: Throwable) {
                            actionError = e.message ?: (if (approve) "Approve failed" else "Reject failed")
                        }
                    }
                }) {
                    Text(
                        if (approve) "Approve" else "Reject",
                        color = if (approve) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

/** "subtitle and description", "a, b and c". */
internal fun joinNatural(parts: List<String>): String = when (parts.size) {
    0 -> "changes"
    1 -> parts[0]
    else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
}

@Composable
private fun QueueHeader(pending: Int?) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Review", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        if (pending != null) {
            Text("$pending pending", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QueueList(
    items: List<Contribution>,
    books: Map<String, Work>,
    selectedId: String?,
    modifier: Modifier,
    onOpen: (Contribution) -> Unit,
) {
    LazyColumn(modifier.fillMaxSize()) {
        items(items, key = { it.id }) { c ->
            val book = books[c.workId]
            val title = book?.title ?: "Loading…"
            val selected = c.id == selectedId
            Surface(
                onClick = { onOpen(c) },
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                shape = if (selectedId != null) MaterialTheme.shapes.large else RoundedCornerShape(0.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    BookCover(book?.title ?: "?", book?.authors, CoverSize.S)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, style = LibraryZ.tokens.bookTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${c.contributorName?.takeIf { it.isNotBlank() } ?: "User #${c.contributorId}"} · ${submittedAt(c.createdAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            c.patch.keys.forEach { FieldChip(fieldLabel(it)) }
                        }
                    }
                    if (selectedId == null) {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
            }
            if (selectedId == null) HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun FieldChip(label: String) {
    Box(
        Modifier.height(24.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuggestionDetail(
    c: Contribution,
    book: Work?,
    wide: Boolean,
    actionError: String?,
    onOpenWork: (String) -> Unit,
    decide: (Contribution, Boolean) -> Unit,
) {
    var sideBySide by remember { mutableStateOf(false) }
    val who = c.contributorName?.takeIf { it.isNotBlank() } ?: "User #${c.contributorId}"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.padding(horizontal = if (wide) 32.dp else 16.dp).padding(top = if (wide) 24.dp else 4.dp, bottom = if (wide) 20.dp else 18.dp),
            horizontalArrangement = Arrangement.spacedBy(if (wide) 20.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(book?.title ?: "?", book?.authors, CoverSize.M)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = { onOpenWork(c.workId) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(
                        book?.title ?: "Open book",
                        style = if (wide) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                authorsFull(book?.authors).takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    buildAnnotatedString {
                        append("Suggested by ")
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) { append(who) }
                        append(if (wide) " · " else "\n")
                        append("${submittedAt(c.createdAt)} · ${changesLabel(c.patch.size)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (wide) {
                Row(Modifier.align(Alignment.Top), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { decide(c, false) }) {
                        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Reject")
                    }
                    Button(onClick = { decide(c, true) }) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Approve")
                    }
                }
            }
        }
        if (wide) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier.padding(horizontal = if (wide) 32.dp else 16.dp, vertical = if (wide) 24.dp else 0.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (wide) 24.dp else 18.dp),
        ) {
            if (actionError != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(actionError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            c.patch.forEach { (key, newValue) ->
                val old = displayValue(key, c.current[key])
                val new = displayValue(key, newValue)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // `current` is read now, so an identical edit approved
                    // earlier makes this one a no-op; say so plainly.
                    if (old == new) {
                        DiffLabel(fieldLabel(key))
                        Text(
                            "Already matches the current book, so approving changes nothing here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        return@Column
                    }
                    if (key == "description") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            DiffLabel(fieldLabel(key), Modifier.weight(1f))
                            Legend()
                            if (wide) {
                                SingleChoiceSegmentedButtonRow(Modifier.width(260.dp)) {
                                    SegmentedButton(
                                        selected = !sideBySide, onClick = { sideBySide = false },
                                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                                        label = { Text("Inline", maxLines = 1) },
                                    )
                                    SegmentedButton(
                                        selected = sideBySide, onClick = { sideBySide = true },
                                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                                        label = { Text("Side by side", maxLines = 1) },
                                    )
                                }
                            }
                        }
                        if (wide && sideBySide) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OldNew(old, removed = true, Modifier.weight(1f))
                                OldNew(new, removed = false, Modifier.weight(1f))
                            }
                        } else {
                            InlineDiff(old, new, wide)
                        }
                    } else {
                        DiffLabel(fieldLabel(key))
                        if (wide) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OldNew(old, removed = true, Modifier.weight(1f))
                                OldNew(new, removed = false, Modifier.weight(1f))
                            }
                        } else {
                            OldNew(old, removed = true, Modifier.fillMaxWidth())
                            OldNew(new, removed = false, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            if (wide) {
                Text("Unchanged fields aren’t shown.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DiffLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.06.em),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(MaterialTheme.colorScheme.errorContainer to "removed", MaterialTheme.colorScheme.primaryContainer to "added").forEach { (c, l) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(10.dp).background(c, RoundedCornerShape(2.dp)))
                Text(l, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One side of a before/after pair: "−" struck through on red, or "+" on green. */
@Composable
private fun OldNew(value: String, removed: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (removed) scheme.errorContainer.copy(alpha = 0.55f) else scheme.primaryContainer.copy(alpha = 0.6f)
    Row(
        modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (removed) "−" else "+",
            fontWeight = FontWeight.Bold,
            color = if (removed) scheme.error else scheme.primary,
            modifier = Modifier.width(12.dp),
        )
        if (value.isEmpty()) {
            Text(
                if (removed) "empty" else "cleared",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = scheme.onSurfaceVariant,
            )
        } else {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDecoration = if (removed) TextDecoration.LineThrough else null,
                ),
                color = scheme.onSurface,
            )
        }
    }
}

/** The description's word diff in reading type, deletions struck on red, insertions on green. */
@Composable
private fun InlineDiff(old: String, new: String, wide: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val segments = wordDiff(old, new)
    val text: AnnotatedString = buildAnnotatedString {
        segments.forEachIndexed { i, seg ->
            if (seg.kind == DiffKind.Same) {
                append(seg.text)
                return@forEachIndexed
            }
            // Highlight the words only; the space after them stays plain, and
            // a removal running straight into an insertion gets one.
            val words = seg.text.trimEnd()
            val gap = seg.text.substring(words.length).ifEmpty {
                if (segments.getOrNull(i + 1)?.kind == DiffKind.Added) " " else ""
            }
            val style = if (seg.kind == DiffKind.Removed) {
                SpanStyle(background = scheme.errorContainer, color = scheme.onErrorContainer, textDecoration = TextDecoration.LineThrough)
            } else {
                SpanStyle(background = scheme.primaryContainer, color = scheme.onPrimaryContainer, fontWeight = FontWeight.Medium)
            }
            withStyle(style) { append(words) }
            append(gap)
        }
    }
    Surface(
        color = scheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            fontFamily = LibraryZ.tokens.serif,
            fontSize = if (wide) 16.sp else 15.sp,
            lineHeight = if (wide) 28.sp else 26.sp,
            color = scheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp).then(if (wide) Modifier.widthIn(max = 680.dp) else Modifier),
        )
    }
}

@Composable
private fun QueueSkeleton() {
    Column {
        listOf(0.60f to 0.42f, 0.48f to 0.36f, 0.72f to 0.40f, 0.55f to 0.30f).forEach { (a, b) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Skeleton(Modifier.size(40.dp, 60.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Skeleton(Modifier.fillMaxWidth(a).height(14.dp))
                    Skeleton(Modifier.fillMaxWidth(b).height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Skeleton(Modifier.size(64.dp, 20.dp), shape = RoundedCornerShape(6.dp))
                        Skeleton(Modifier.size(80.dp, 20.dp), shape = RoundedCornerShape(6.dp))
                    }
                }
            }
        }
    }
}
