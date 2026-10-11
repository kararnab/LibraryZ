package com.libraryz.ui.screens

import androidx.compose.material.icons.outlined.AutoAwesome
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.libraryz.data.Recommendation
import com.libraryz.data.api.RecommendationsState
import com.libraryz.theme.LibraryZ
import com.libraryz.ui.components.LoadErrorState
import com.libraryz.ui.components.BookCover
import com.libraryz.ui.components.CoverSize
import com.libraryz.ui.components.EmptyState
import com.libraryz.ui.components.LoadPhase
import com.libraryz.ui.components.PhaseCrossfade
import com.libraryz.theme.itemMotion
import com.libraryz.ui.components.Skeleton
import com.libraryz.ui.components.bylineOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForYouScreen(
    state: RecommendationsState,
    isWide: Boolean,
    onBack: (() -> Unit)?,
    onOpenWork: (String) -> Unit,
    onDismiss: (String) -> Unit,
    // Adds the work to the library as "Want to read".
    onWantToRead: (String) -> Unit,
    // True when the work is already in the library (the button then shows "Saved").
    isInLibrary: (String) -> Boolean,
    onUpload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("For You", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { state.refresh() } }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh recommendations")
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!items.isNullOrEmpty() || state.loading) {
                Text(
                    "Picked from what you’ve read and rated.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
            val phase = when {
                state.loading -> LoadPhase.Loading
                err != null && items == null -> LoadPhase.Error
                items.isNullOrEmpty() -> LoadPhase.Empty
                else -> LoadPhase.Content
            }
            PhaseCrossfade(phase, Modifier.fillMaxSize()) { p ->
                when (p) {
                    LoadPhase.Loading -> ForYouSkeleton()
                    LoadPhase.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadErrorState(
                            title = "Couldn’t load recommendations",
                            body = err.orEmpty(),
                            onRetry = { scope.launch { state.refresh() } },
                        )
                    }
                    // The server fills short lists with popular books, even for a
                    // reader with no ratings: empty means every book in the
                    // catalogue is in the library or dismissed.
                    LoadPhase.Empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            title = "You’re all caught up",
                            body = "Every book in the catalogue is already in your library or dismissed. " +
                                "New books will show up here as they’re added.",
                            icon = Icons.Outlined.AutoAwesome,
                            action = {
                                FilledTonalButton(onClick = onUpload) {
                                    Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Upload a book")
                                }
                            },
                        )
                    }
                    LoadPhase.Content -> LazyVerticalGrid(
                        columns = if (isWide) GridCells.Adaptive(380.dp) else GridCells.Fixed(1),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // A dismissed book fades out and the rest close the gap.
                        items(items.orEmpty(), key = { it.work.id }) { rec ->
                            Box(itemMotion()) { RecCard(rec, onOpenWork, onDismiss, onWantToRead, isInLibrary(rec.work.id)) }
                        }
                    }
                }
            }
        }
    }
}

/** A glyph for the kind of reason the backend gave. */
private fun reasonIcon(reason: String): ImageVector = when {
    "rated" in reason.lowercase() -> Icons.Rounded.Star
    reason.lowercase().startsWith("readers") -> Icons.Rounded.AutoStories
    else -> Icons.Rounded.AutoAwesome
}

@Composable
private fun RecCard(
    rec: Recommendation,
    onOpenWork: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onWantToRead: (String) -> Unit,
    saved: Boolean,
) {
    val work = rec.work
    Surface(
        onClick = { onOpenWork(work.id) },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BookCover(work.title, work.authors, CoverSize.M)
                Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(work.title, style = LibraryZ.tokens.bookTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val byline = bylineOf(work)
                    if (byline.isNotEmpty()) {
                        Text(byline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val reason = rec.reason?.takeIf { it.isNotBlank() }
                    if (reason != null) {
                        Row(
                            modifier = Modifier.padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                reasonIcon(reason),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                IconButton(onClick = { onDismiss(work.id) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Not interested in ${work.title}")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onWantToRead(work.id) }, enabled = !saved) {
                    Icon(
                        if (saved) Icons.Rounded.BookmarkAdded else Icons.Rounded.BookmarkAdd,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (saved) "In your library" else "Want to read")
                }
                TextButton(onClick = { onOpenWork(work.id) }) { Text("Details") }
            }
        }
    }
}

@Composable
private fun ForYouSkeleton() {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(0.60f to 0.40f, 0.72f to 0.34f, 0.52f to 0.44f).forEach { (a, b) ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Skeleton(Modifier.size(72.dp, 108.dp))
                        Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Skeleton(Modifier.fillMaxWidth(a).height(16.dp))
                            Skeleton(Modifier.fillMaxWidth(b).height(12.dp))
                            Skeleton(Modifier.padding(top = 10.dp).fillMaxWidth(0.9f).height(10.dp))
                        }
                    }
                    Skeleton(Modifier.size(150.dp, 40.dp), shape = RoundedCornerShape(20.dp))
                }
            }
        }
    }
}
