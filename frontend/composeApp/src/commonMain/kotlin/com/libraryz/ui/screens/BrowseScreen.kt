package com.libraryz.ui.screens

import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.libraryz.data.Work
import com.libraryz.theme.LibraryZ
import com.libraryz.ui.components.EmptyState
import com.libraryz.ui.components.SectionLabel
import com.libraryz.ui.components.Skeleton
import com.libraryz.ui.components.WorkCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

// How many rows before the end of the loaded list to start fetching the next page.
private const val LOAD_MORE_THRESHOLD = 5

/**
 * The catalog: an always-visible search pill, an optional [header] (the
 * "Continue reading" hero), then the works. On phones ([compact]) the
 * screen has its own top bar and the hero leads; in the desktop list pane
 * there's no top bar and search leads, per the design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    works: List<Work>,
    onWorkClick: (Work) -> Unit,
    onUploadClick: () -> Unit,
    onRefresh: () -> Unit,
    compact: Boolean,
    onSearch: (suspend (String) -> Unit)? = null,
    activeSearchQuery: String? = null,
    // Infinite scroll: called when the user nears the end of the loaded
    // works, unless [endReached]. [loadMoreError] shows a retry footer.
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    endReached: Boolean = true,
    loadMoreError: String? = null,
    // Shown above the list when not searching (e.g. "Continue reading").
    header: (@Composable () -> Unit)? = null,
    // Works in the user's library get a bookmark; [selectedWorkId] is the
    // row open in the desktop detail pane.
    libraryWorkIds: Set<String> = emptySet(),
    selectedWorkId: String? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Ask for the next page once the last visible row is within a few rows
    // of the end. snapshotFlow + distinctUntilChanged fires once per
    // threshold crossing rather than on every scroll frame; keying on the
    // list size re-arms it after each page lands.
    if (onLoadMore != null && !endReached && loadMoreError == null) {
        LaunchedEffect(listState, works.size) {
            snapshotFlow {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                last >= listState.layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD
            }
                .distinctUntilChanged()
                .filter { it }
                .collect { onLoadMore() }
        }
    }
    var queryText by remember { mutableStateOf(activeSearchQuery ?: "") }

    // 300ms debounce: LaunchedEffect(queryText) restarts on each keystroke,
    // cancelling the prior delay+search. An empty query restores the full
    // list (WorksState.search treats blank as "clear").
    if (onSearch != null) {
        var first by remember { mutableStateOf(true) }
        LaunchedEffect(queryText) {
            if (first) {
                first = false
                return@LaunchedEffect
            }
            delay(300)
            onSearch(queryText)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (compact) {
                TopAppBar(
                    title = { Wordmark() },
                    actions = {
                        IconButton(onClick = onUploadClick) {
                            Icon(Icons.Rounded.FileUpload, contentDescription = "Add a book")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        },
        containerColor = if (compact) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent,
    ) { padding ->
        val searching = activeSearchQuery != null
        val showHero = !searching && header != null
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + if (compact) 0.dp else 16.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            if (showHero && compact) {
                item(key = "hero") { Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { header() } }
            }
            if (onSearch != null) {
                item(key = "search") {
                    SearchPill(
                        query = queryText,
                        onQueryChange = { queryText = it },
                        trailing = if (!compact) {
                            {
                                IconButton(onClick = onRefresh) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                                }
                            }
                        } else null,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            if (showHero && !compact) {
                item(key = "hero") { Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) { header() } }
            }
            item(key = "section") {
                SectionLabel(
                    if (searching) {
                        "${works.size}${if (endReached) "" else "+"} result${if (works.size == 1 && endReached) "" else "s"}"
                    } else {
                        "Catalog"
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            when {
                works.isEmpty() && searching -> item(key = "empty") {
                    EmptyState(
                        title = "No books match “$activeSearchQuery”",
                        body = "Check the spelling, try an author’s surname, or search by ISBN.",
                        icon = Icons.Outlined.SearchOff,
                        action = { FilledTonalButton(onClick = { queryText = "" }) { Text("Clear search") } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                works.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        title = "No books yet",
                        body = "The catalog is empty. Be the first to add a book.",
                        action = { FilledTonalButton(onClick = onUploadClick) { Text("Add a book") } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> items(works, key = { it.id }) { w ->
                    WorkCard(
                        work = w,
                        onClick = { onWorkClick(w) },
                        highlight = activeSearchQuery,
                        inLibrary = w.id in libraryWorkIds,
                        selected = w.id == selectedWorkId,
                        modifier = Modifier.padding(horizontal = if (compact) 0.dp else 8.dp),
                    )
                }
            }
            if (loadingMore || loadMoreError != null) {
                item(key = "load-more") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (loadMoreError != null) {
                            TextButton(onClick = { onLoadMore?.invoke() }) {
                                Text("Couldn't load more — retry")
                            }
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

/** "LibraryZ" in Literata, the Z in library green. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Text(
        text = buildAnnotatedString {
            append("Library")
            withStyle(SpanStyle(color = primary)) { append("Z") }
        },
        fontFamily = LibraryZ.tokens.serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.01).em,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/** The 56dp pill search field. */
@Composable
private fun SearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    trailing: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = CircleShape,
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null)
            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (query.isEmpty()) {
                    Text("Search title, author or ISBN", style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                }
            } else {
                trailing?.invoke()
            }
        }
    }
}

/** Loading placeholder shaped like the catalog (hero, search, rows). */
@Composable
fun BrowseSkeleton(compact: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(top = if (compact) 64.dp else 16.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        ) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Skeleton(Modifier.size(72.dp, 108.dp))
                Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Skeleton(Modifier.fillMaxWidth(0.4f).height(10.dp))
                    Skeleton(Modifier.fillMaxWidth(0.75f).height(18.dp))
                    Skeleton(Modifier.fillMaxWidth(0.5f).height(12.dp))
                }
            }
        }
        Skeleton(Modifier.padding(16.dp).fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp))
        listOf(0.62f to 0.40f, 0.78f to 0.34f, 0.50f to 0.44f, 0.70f to 0.30f, 0.58f to 0.38f).forEach { (a, b) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Skeleton(Modifier.size(40.dp, 60.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Skeleton(Modifier.fillMaxWidth(a).height(14.dp))
                    Skeleton(Modifier.fillMaxWidth(b).height(10.dp))
                }
            }
        }
    }
}
