package com.libraryz.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.libraryz.data.Recommendation
import com.libraryz.data.api.RecommendationsState
import com.libraryz.ui.components.EmptyState
import com.libraryz.ui.components.WorkCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForYouScreen(
    state: RecommendationsState,
    isWide: Boolean,
    onBack: (() -> Unit)?,
    onOpenWork: (String) -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("For You", color = MaterialTheme.colorScheme.onSurface) },
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
        val items = state.items
        val err = state.error
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                err != null && items == null -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        title = "Couldn't load recommendations",
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
                        title = "Nothing to recommend yet",
                        body = "Add and rate a few books in your library, and suggestions will show up here.",
                        icon = Icons.Outlined.AutoAwesome,
                    )
                }
                else -> RecList(items, isWide, onOpenWork, onDismiss)
            }
        }
    }
}

@Composable
private fun RecList(
    items: List<Recommendation>,
    isWide: Boolean,
    onOpenWork: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    if (isWide) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.work.id }) { rec -> RecItem(rec, onOpenWork, onDismiss) }
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items, key = { it.work.id }) { rec -> RecItem(rec, onOpenWork, onDismiss) }
        }
    }
}

@Composable
private fun RecItem(rec: Recommendation, onOpenWork: (String) -> Unit, onDismiss: (String) -> Unit) {
    Column {
        WorkCard(work = rec.work, onClick = { onOpenWork(rec.work.id) })
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = rec.reason ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onDismiss(rec.work.id) }) {
                Text("Not interested", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
