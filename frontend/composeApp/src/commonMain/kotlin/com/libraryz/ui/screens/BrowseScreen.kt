package com.libraryz.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.libraryz.data.Work
import com.libraryz.ui.components.EmptyState
import com.libraryz.ui.components.WorkCard
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    works: List<Work>,
    onWorkClick: (Work) -> Unit,
    onUploadClick: () -> Unit,
    onLogout: () -> Unit,
    onRefresh: () -> Unit,
    showRefresh: Boolean,
    onReviewClick: (() -> Unit)? = null,
    pendingReviewCount: Int = 0,
    onLibraryClick: (() -> Unit)? = null,
    onForYouClick: (() -> Unit)? = null,
    onSearch: (suspend (String) -> Unit)? = null,
    activeSearchQuery: String? = null,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(activeSearchQuery != null) }
    var queryText by remember { mutableStateOf(activeSearchQuery ?: "") }

    // 300ms debounce per the design. LaunchedEffect(queryText) restarts on
    // each keystroke, cancelling the prior delay+search, which is the
    // natural debounce idiom in Compose.
    if (onSearch != null) {
        LaunchedEffect(queryText, searchActive) {
            if (!searchActive) return@LaunchedEffect
            delay(300)
            onSearch(queryText)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (searchActive && onSearch != null) {
                SearchAppBar(
                    query = queryText,
                    onQueryChange = { queryText = it },
                    onClose = {
                        searchActive = false
                        queryText = ""
                        // Trigger one final empty-search call so WorksState
                        // re-loads the full list. Skip if onSearch is null
                        // (won't happen here since the guard above blocks
                        // searchActive when onSearch is null).
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("LibraryZ") },
                    actions = {
                        if (onSearch != null) {
                            IconButton(onClick = { searchActive = true }) {
                                Icon(Icons.Outlined.Search, contentDescription = "Search")
                            }
                        }
                        if (showRefresh) {
                            IconButton(onClick = onRefresh) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                            }
                        }
                        IconButton(onClick = onUploadClick) {
                            Icon(Icons.Outlined.FileUpload, contentDescription = "Upload")
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (onReviewClick != null) {
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(Icons.Outlined.RateReview, contentDescription = null)
                                    },
                                    text = { Text("Review") },
                                    trailingIcon = {
                                        if (pendingReviewCount > 0) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.error,
                                                contentColor = MaterialTheme.colorScheme.onError,
                                            ) {
                                                Text(badgeLabel(pendingReviewCount))
                                            }
                                        }
                                    },
                                    onClick = { menuOpen = false; onReviewClick() },
                                )
                            }
                            if (onLibraryClick != null) {
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Bookmarks, contentDescription = null)
                                    },
                                    text = { Text("My Library") },
                                    onClick = { menuOpen = false; onLibraryClick() },
                                )
                            }
                            if (onForYouClick != null) {
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                                    },
                                    text = { Text("For You") },
                                    onClick = { menuOpen = false; onForYouClick() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Log out") },
                                onClick = { menuOpen = false; onLogout() },
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        },
        floatingActionButton = {
            if (works.isEmpty()) {
                FloatingActionButton(
                    onClick = onUploadClick,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add work")
                }
            } else {
                ExtendedFloatingActionButton(
                    onClick = onUploadClick,
                    text = { Text("Add work") },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        val searching = activeSearchQuery != null
        when {
            works.isEmpty() && searching -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    title = "No matches for \"$activeSearchQuery\"",
                    body = "Try a different title, author, or ISBN.",
                    icon = Icons.Outlined.Search,
                )
            }
            works.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(title = "No works yet", body = "Tap + to add one")
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 88.dp,
                ),
            ) {
                if (searching) {
                    item(key = "result-count") {
                        Text(
                            text = "${works.size} result${if (works.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(works, key = { it.id }) { w ->
                    WorkCard(
                        work = w,
                        onClick = { onWorkClick(w) },
                        highlight = activeSearchQuery,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchAppBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Close search")
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(modifier = Modifier.padding(horizontal = 8.dp).weight(1f)) {
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                        if (query.isEmpty()) {
                            Text(
                                "Search title, author, ISBN…",
                                style = LocalTextStyle.current,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear")
                        }
                    }
                }
            }
        }
    }
}

/** Format a pending count for a badge. ≤99 verbatim, >99 → "99+". */
private fun badgeLabel(count: Int): String = if (count > 99) "99+" else count.toString()
