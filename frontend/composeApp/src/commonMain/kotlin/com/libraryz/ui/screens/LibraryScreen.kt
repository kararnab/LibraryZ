package com.libraryz.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.libraryz.data.LibraryStatus
import com.libraryz.data.UserBook
import com.libraryz.data.api.LibraryState
import com.libraryz.ui.components.EmptyState
import kotlinx.coroutines.launch

// Status filter tabs: null = All, then the three reading states.
private data class StatusTab(val label: String, val value: String?)

private val statusTabs = listOf(
    StatusTab("All", null),
    StatusTab("Want", LibraryStatus.Want),
    StatusTab("Reading", LibraryStatus.Reading),
    StatusTab("Read", LibraryStatus.Read),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryState,
    isWide: Boolean,
    onBack: (() -> Unit)?,
    onOpenWork: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("My Library", color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { state.refresh() } }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Status filter row
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                statusTabs.forEach { tab ->
                    FilterChip(
                        selected = state.statusFilter == tab.value,
                        onClick = { scope.launch { state.refresh(tab.value) } },
                        label = { Text(tab.label) },
                    )
                }
            }

            val items = state.items
            val err = state.error
            Box(Modifier.fillMaxSize()) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    err != null && items == null -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            title = "Couldn't load your library",
                            body = err,
                            action = {
                                Button(onClick = { scope.launch { state.refresh() } }) { Text("Retry") }
                            },
                        )
                    }
                    items.isNullOrEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            title = "Your library is empty",
                            body = "Add a book from its detail page — mark it want to read, reading, or read.",
                            icon = Icons.Outlined.Bookmarks,
                        )
                    }
                    else -> CardList(items, isWide, onOpenWork)
                }
            }
        }
    }
}

@Composable
private fun CardList(items: List<UserBook>, isWide: Boolean, onOpenWork: (String) -> Unit) {
    if (isWide) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.id }) { ub -> LibraryCard(ub, onOpenWork) }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.id }) { ub -> LibraryCard(ub, onOpenWork) }
        }
    }
}

@Composable
private fun LibraryCard(ub: UserBook, onOpenWork: (String) -> Unit) {
    Surface(
        onClick = { onOpenWork(ub.workId) },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = ub.work?.title ?: "Work ${ub.workId.take(8)}…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val authors = ub.work?.authors
                    if (!authors.isNullOrBlank()) {
                        Text(
                            text = authors,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                StatusBadge(ub.status)
            }

            if (ub.rating != null) {
                Row {
                    repeat(5) { i ->
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = if (i < ub.rating) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            modifier = Modifier.height(16.dp),
                        )
                    }
                }
            }

            if (ub.status == LibraryStatus.Reading && ub.progressPercent > 0) {
                Spacer(Modifier.height(2.dp))
                LinearProgressIndicator(
                    progress = { ub.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${ub.progressPercent}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val label = when (status) {
        LibraryStatus.Want -> "Want to read"
        LibraryStatus.Reading -> "Reading"
        LibraryStatus.Read -> "Read"
        else -> status
    }
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}
