package com.libraryz.ui.screens

import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.libraryz.data.LibraryStatus
import com.libraryz.data.UserBook
import com.libraryz.data.api.LibraryState
import com.libraryz.data.pickReadableEdition
import com.libraryz.theme.LibraryZ
import com.libraryz.ui.components.BookCover
import com.libraryz.ui.components.CoverSize
import com.libraryz.ui.components.EmptyState
import com.libraryz.ui.components.Skeleton
import com.libraryz.ui.components.StarRating
import kotlinx.coroutines.launch

// Status filter tabs: null = All, then the three reading states.
private data class StatusTab(val label: String, val value: String?)

private val statusTabs = listOf(
    StatusTab("All", null),
    StatusTab("Want to read", LibraryStatus.Want),
    StatusTab("Reading", LibraryStatus.Reading),
    StatusTab("Read", LibraryStatus.Read),
)

private val Months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "3 Oct" from an RFC 3339 timestamp; null if it doesn't parse. */
internal fun dayMonth(iso: String?): String? {
    val (_, m, d) = isoDate(iso) ?: return null
    return "$d ${Months[m - 1]}"
}

/** "Aug 2026" from an RFC 3339 timestamp; null if it doesn't parse. */
internal fun monthYear(iso: String?): String? {
    val (y, m, _) = isoDate(iso) ?: return null
    return "${Months[m - 1]} $y"
}

private fun isoDate(iso: String?): Triple<Int, Int, Int>? {
    val parts = iso?.take(10)?.split('-') ?: return null
    if (parts.size != 3) return null
    val y = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val d = parts[2].toIntOrNull() ?: return null
    return Triple(y, m, d)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryState,
    isWide: Boolean,
    onBack: (() -> Unit)?,
    onOpenWork: (String) -> Unit,
    onRead: (UserBook) -> Unit,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("My Library", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { state.refresh() } }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
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
            PrimaryTabRow(
                selectedTabIndex = statusTabs.indexOfFirst { it.value == state.statusFilter }.coerceAtLeast(0),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                statusTabs.forEach { tab ->
                    Tab(
                        selected = state.statusFilter == tab.value,
                        onClick = { scope.launch { state.refresh(tab.value) } },
                        text = { Text(tab.label, maxLines = 1) },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val items = state.items
            val err = state.error
            Box(Modifier.fillMaxSize()) {
                when {
                    state.loading -> LibrarySkeleton(isWide)
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
                        val tab = statusTabs.firstOrNull { it.value == state.statusFilter && it.value != null }
                        EmptyState(
                            title = if (tab == null) "Your shelves are empty" else "Nothing in “${tab.label}”",
                            body = "Books you save, start or rate land here, sorted into Want to read, Reading and Read.",
                            icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                            action = {
                                Button(onClick = onBrowse) {
                                    Icon(Icons.Rounded.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Browse the catalog")
                                }
                            },
                        )
                    }
                    else -> LazyVerticalGrid(
                        columns = if (isWide) GridCells.Adaptive(360.dp) else GridCells.Fixed(1),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items, key = { it.id }) { ub -> LibraryCard(ub, onOpenWork, onRead) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryCard(ub: UserBook, onOpenWork: (String) -> Unit, onRead: (UserBook) -> Unit) {
    val title = ub.work?.title ?: "Work ${ub.workId.take(8)}…"
    val readable = ub.work?.let { pickReadableEdition(it.editions) } != null
    Surface(
        onClick = { onOpenWork(ub.workId) },
        color = Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // At least cover-height, taller when a long title needs it, so the
        // action button is never squeezed.
        Row(
            modifier = Modifier.padding(12.dp).height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BookCover(title, ub.work?.authors, CoverSize.M)
            Column(
                Modifier.weight(1f).fillMaxHeight().heightIn(min = CoverSize.M.height),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = LibraryZ.tokens.bookTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val authors = ub.work?.authors
                if (!authors.isNullOrBlank()) {
                    Text(
                        authors,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (ub.status) {
                    LibraryStatus.Reading -> {
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            LinearProgressIndicator(
                                progress = { ub.progressPercent / 100f },
                                drawStopIndicator = {},
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${ub.progressPercent}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (readable) {
                            FilledTonalButton(onClick = { onRead(ub) }, contentPadding = PaddingValues(start = 16.dp, end = 24.dp)) {
                                Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Continue reading")
                            }
                        }
                    }
                    LibraryStatus.Want -> {
                        Text(
                            listOfNotNull("Want to read", dayMonth(ub.createdAt)?.let { "added $it" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        if (readable) {
                            OutlinedButton(onClick = { onRead(ub) }) { Text("Start reading") }
                        }
                    }
                    else -> {
                        if (ub.rating != null) {
                            StarRating(rating = ub.rating, onRate = null, starSize = 16.dp, modifier = Modifier.padding(top = 6.dp))
                        }
                        Text(
                            listOfNotNull("Read", monthYear(ub.finishedAt)?.let { "finished $it" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibrarySkeleton(isWide: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(0.60f to 0.40f, 0.72f to 0.34f, 0.52f to 0.44f, 0.66f to 0.30f).forEach { (a, b) ->
            Surface(
                color = Color.Transparent,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = if (isWide) Modifier.width(420.dp) else Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Skeleton(Modifier.size(72.dp, 108.dp))
                    Column(Modifier.weight(1f).height(108.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Skeleton(Modifier.fillMaxWidth(a).height(16.dp))
                        Skeleton(Modifier.fillMaxWidth(b).height(12.dp))
                        Skeleton(Modifier.fillMaxWidth().height(4.dp).padding(top = 0.dp))
                        Spacer(Modifier.weight(1f))
                        Skeleton(Modifier.size(150.dp, 40.dp), shape = RoundedCornerShape(20.dp))
                    }
                }
            }
        }
    }
}
