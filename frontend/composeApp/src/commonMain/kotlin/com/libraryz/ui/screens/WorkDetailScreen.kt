package com.libraryz.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.libraryz.data.Edition
import com.libraryz.data.LibraryStatus
import com.libraryz.data.UserBook
import com.libraryz.data.Work
import com.libraryz.data.api.UpsertLibraryRequest
import com.libraryz.ui.components.EditionRow
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkDetailScreen(
    work: Work,
    onBack: (() -> Unit)?,
    onAddEdition: () -> Unit,
    onPreview: (Edition) -> Unit,
    onDownload: (Edition) -> Unit,
    onSuggestEdit: (() -> Unit)? = null,
    // Personal-library controls. Shown only when [libraryEnabled] (signed in).
    // [libraryEntry] is the caller's current entry (null = not in library yet).
    libraryEnabled: Boolean = false,
    libraryEntry: UserBook? = null,
    onLibraryUpsert: (UpsertLibraryRequest) -> Unit = {},
    onLibraryRemove: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (onSuggestEdit != null) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Suggest edit") },
                                onClick = {
                                    menuOpen = false
                                    onSuggestEdit()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddEdition,
                text = { Text("Add edition") },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = work.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!work.authors.isNullOrBlank()) {
                Text(
                    text = work.authors,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            MetaRow("Year", work.publicationYear?.toString())
            MetaRow("ISBN", work.isbn, monospace = true)
            MetaRow("Language", work.language)

            if (libraryEnabled) {
                Spacer(Modifier.height(20.dp))
                LibrarySection(
                    entry = libraryEntry,
                    onUpsert = onLibraryUpsert,
                    onRemove = onLibraryRemove,
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "EDITIONS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            work.editions.forEach { ed ->
                EditionRow(
                    edition = ed,
                    onDownload = { onDownload(ed) },
                    onPreview = { onPreview(ed) },
                )
            }

            Spacer(Modifier.height(96.dp))
        }
    }
}

/**
 * The "My Library" controls: status chips, rating, reading progress, shelf,
 * and notes. Status/rating/progress changes upsert immediately; the free-text
 * shelf + notes fields debounce (600ms after the last keystroke) so we don't
 * PUT on every character. Local field state re-seeds whenever the entry's
 * identity changes (e.g. first load, or remove-then-readd).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySection(
    entry: UserBook?,
    onUpsert: (UpsertLibraryRequest) -> Unit,
    onRemove: () -> Unit,
) {
    Text(
        text = "MY LIBRARY",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // --- Status chips ---
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("Want to read", LibraryStatus.Want, entry?.status) {
                    onUpsert(UpsertLibraryRequest(status = it))
                }
                StatusChip("Reading", LibraryStatus.Reading, entry?.status) {
                    onUpsert(UpsertLibraryRequest(status = it))
                }
                StatusChip("Read", LibraryStatus.Read, entry?.status) {
                    onUpsert(UpsertLibraryRequest(status = it))
                }
            }

            if (entry != null) {
                Spacer(Modifier.height(12.dp))

                // --- Rating ---
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Rating",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(88.dp),
                    )
                    StarRating(rating = entry.rating) { stars ->
                        onUpsert(UpsertLibraryRequest(rating = stars))
                    }
                }

                // --- Reading progress (only while reading) ---
                if (entry.status == LibraryStatus.Reading) {
                    Spacer(Modifier.height(4.dp))
                    var sliderValue by remember(entry.id) {
                        mutableStateOf(entry.progressPercent.toFloat())
                    }
                    Text(
                        text = "Progress · ${sliderValue.toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            onUpsert(UpsertLibraryRequest(progressPercent = sliderValue.toInt()))
                        },
                        valueRange = 0f..100f,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // --- Shelf (debounced) ---
                DebouncedField(
                    label = "Shelf",
                    initial = entry.shelf.orEmpty(),
                    seedKey = entry.id,
                    singleLine = true,
                ) { value ->
                    onUpsert(UpsertLibraryRequest(shelf = value))
                }

                Spacer(Modifier.height(8.dp))

                // --- Notes (debounced) ---
                DebouncedField(
                    label = "Notes",
                    initial = entry.notes.orEmpty(),
                    seedKey = entry.id,
                    singleLine = false,
                ) { value ->
                    onUpsert(UpsertLibraryRequest(notes = value))
                }

                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onRemove) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Remove from library", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusChip(
    label: String,
    value: String,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    FilterChip(
        selected = selected == value,
        onClick = { onSelect(value) },
        label = { Text(label) },
    )
}

@Composable
private fun StarRating(rating: Int?, onRate: (Int) -> Unit) {
    Row {
        for (star in 1..5) {
            val filled = (rating ?: 0) >= star
            IconButton(onClick = { onRate(star) }) {
                Icon(
                    imageVector = if (filled) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = "Rate $star",
                    tint = if (filled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * A text field that calls [onCommit] 600ms after the user stops typing (and
 * only when the value actually changed from [initial]). Re-seeds when
 * [seedKey] changes so it tracks the entry it's bound to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebouncedField(
    label: String,
    initial: String,
    seedKey: Any?,
    singleLine: Boolean,
    onCommit: (String) -> Unit,
) {
    var text by remember(seedKey) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
    )
    LaunchedEffect(text, seedKey) {
        if (text == initial) return@LaunchedEffect
        delay(600)
        onCommit(text)
    }
}

@Composable
private fun MetaRow(label: String, value: String?, monospace: Boolean = false) {
    if (value.isNullOrBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}
