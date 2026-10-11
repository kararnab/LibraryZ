package com.libraryz

import com.libraryz.data.BookCache
import com.libraryz.data.savedWorks
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.libraryz.theme.LibraryZ
import com.libraryz.theme.LocalReduceMotion
import com.libraryz.theme.Motion
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.libraryz.data.Edition
import com.libraryz.data.UserBook
import com.libraryz.data.Work
import com.libraryz.data.api.AuthState
import com.libraryz.data.api.ContributionsState
import com.libraryz.data.api.LibraryState
import com.libraryz.data.api.UpsertLibraryRequest
import com.libraryz.data.api.WorksState
import com.libraryz.nav.Navigator
import com.libraryz.nav.Screen
import com.libraryz.ui.components.EmptyState
import com.libraryz.ui.screens.WorkDetailScreen
import com.libraryz.data.api.RemovalRequest

/* ---------------- Expanded (Desktop / wide tablet) ---------------- */

@Composable
internal fun ExpandedFrame(
    current: Screen,
    nav: Navigator,
    auth: AuthState,
    contributions: ContributionsState,
    content: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        NavRail(current, nav = nav, auth = auth, contributions = contributions)
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun NavRail(
    current: Screen,
    nav: Navigator,
    auth: AuthState,
    contributions: ContributionsState,
) {
    val isLibrary = current is Screen.Browse || current is Screen.WorkDetail || current is Screen.EditWork
    val isQueue = current is Screen.ContributionQueue
    val isUpload = current is Screen.Upload
    val isMyLibrary = current is Screen.Library
    val isForYou = current is Screen.ForYou

    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxHeight(),
        header = {
            Box(
                modifier = Modifier
                    .padding(top = 20.dp, bottom = 28.dp)
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Lz",
                    fontFamily = LibraryZ.tokens.serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    ) {
        NavigationRailItem(
            selected = isLibrary,
            onClick = { nav.replace(Screen.Browse) },
            icon = { NavIcon(isLibrary, Icons.Outlined.Explore, Icons.Rounded.Explore) },
            label = { Text("Browse") },
        )
        if (auth.isAuthenticated) {
            NavigationRailItem(
                selected = isMyLibrary,
                onClick = { nav.replace(Screen.Library) },
                icon = { NavIcon(isMyLibrary, Icons.Outlined.AutoStories, Icons.Rounded.AutoStories) },
                label = { Text("Library") },
            )
            NavigationRailItem(
                selected = isForYou,
                onClick = { nav.replace(Screen.ForYou) },
                icon = { NavIcon(isForYou, Icons.Outlined.AutoAwesome, Icons.Rounded.AutoAwesome) },
                label = { Text("For You") },
            )
        }
        if (auth.isModerator) {
            NavigationRailItem(
                selected = isQueue,
                onClick = { nav.replace(Screen.ContributionQueue) },
                icon = {
                    BadgedBox(
                        badge = {
                            val n = contributions.pendingCount
                            if (n > 0) {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ) { Text(if (n > 99) "99+" else n.toString()) }
                            }
                        },
                    ) {
                        NavIcon(current is Screen.ContributionQueue, Icons.AutoMirrored.Outlined.FactCheck, Icons.AutoMirrored.Rounded.FactCheck)
                    }
                },
                label = { Text("Review") },
            )
        }
        NavigationRailItem(
            selected = isUpload,
            onClick = { nav.push(Screen.Upload()) },
            icon = { NavIcon(isUpload, Icons.Outlined.FileUpload, Icons.Rounded.FileUpload) },
            label = { Text("Upload") },
        )
        Spacer(Modifier.weight(1f))
        NavigationRailItem(
            selected = current is Screen.Settings,
            onClick = { nav.replace(Screen.Settings) },
            icon = { NavIcon(current is Screen.Settings, Icons.Outlined.Settings, Icons.Rounded.Settings) },
            label = { Text("Settings") },
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

/**
 * Phone-width chrome for the top-level destinations: a bottom navigation
 * bar, so My Library, For You, Review and Settings are one tap away rather
 * than hidden in an overflow menu. Tabs replace the stack (they're peers);
 * system back from a non-home tab returns to Browse.
 */
@Composable
internal fun CompactFrame(
    current: Screen,
    nav: Navigator,
    auth: AuthState,
    contributions: ContributionsState,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) { content() }
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            NavigationBarItem(
                selected = current is Screen.Browse,
                onClick = { nav.replace(Screen.Browse) },
                icon = { NavIcon(current is Screen.Browse, Icons.Outlined.Explore, Icons.Rounded.Explore) },
                label = { Text("Browse") },
            )
            NavigationBarItem(
                selected = current is Screen.Library,
                onClick = { nav.replace(Screen.Library) },
                icon = { NavIcon(current is Screen.Library, Icons.Outlined.AutoStories, Icons.Rounded.AutoStories) },
                label = { Text("Library") },
            )
            NavigationBarItem(
                selected = current is Screen.ForYou,
                onClick = { nav.replace(Screen.ForYou) },
                icon = { NavIcon(current is Screen.ForYou, Icons.Outlined.AutoAwesome, Icons.Rounded.AutoAwesome) },
                label = { Text("For You") },
            )
            if (auth.isModerator) {
                NavigationBarItem(
                    selected = current is Screen.ContributionQueue,
                    onClick = { nav.replace(Screen.ContributionQueue) },
                    icon = {
                        BadgedBox(
                            badge = {
                                val n = contributions.pendingCount
                                if (n > 0) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError,
                                    ) { Text(if (n > 99) "99+" else n.toString()) }
                                }
                            },
                        ) {
                            NavIcon(current is Screen.ContributionQueue, Icons.AutoMirrored.Outlined.FactCheck, Icons.AutoMirrored.Rounded.FactCheck)
                        }
                    },
                    label = { Text("Review") },
                )
            }
            NavigationBarItem(
                selected = current is Screen.Settings,
                onClick = { nav.replace(Screen.Settings) },
                icon = { NavIcon(current is Screen.Settings, Icons.Outlined.Settings, Icons.Rounded.Settings) },
                label = { Text("Settings") },
            )
        }
    }
}

/** Outlined at rest, filled when its destination is selected (per the design). */
@Composable
private fun NavIcon(selected: Boolean, outlined: ImageVector, filled: ImageVector) {
    Icon(if (selected) filled else outlined, contentDescription = null)
}

@Composable
internal fun ListDetailLayout(
    nav: Navigator,
    auth: AuthState,
    works: WorksState,
    library: LibraryState,
    selectedWorkId: String?,
    onSelect: (Work) -> Unit,
    onRead: (Work, Edition) -> Unit,
    onReadFromLibrary: (UserBook) -> Unit,
    extraHeader: (@Composable () -> Unit)?,
    onDownload: (Work, Edition) -> Unit,
    books: BookCache,
    keeping: Set<String>,
    onKeepOffline: (Work, Edition, Boolean) -> Unit,
    onRemoveFromDevice: (Work, Edition) -> Unit,
    onLibraryUpsert: (String, UpsertLibraryRequest) -> Unit,
    onLibraryRemove: (String) -> Unit,
    onRemoveWork: ((Work, RemovalRequest) -> Unit)? = null,
    onRemoveEdition: ((Work, Edition, RemovalRequest) -> Unit)? = null,
    onAuthorClick: (String) -> Unit,
) {
    LaunchedEffect(selectedWorkId) {
        if (selectedWorkId != null) {
            works.refreshOne(selectedWorkId)
            if (auth.isAuthenticated) library.loadEntry(selectedWorkId)
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // List pane
        Box(modifier = Modifier.width(440.dp).fillMaxHeight()) {
            DataDrivenBrowse(
                works = works,
                onWorkClick = onSelect,
                onUploadClick = { nav.push(Screen.Upload()) },
                compact = false,
                header = stackHeaders(extraHeader, continueReadingHeader(library, onReadFromLibrary)),
                libraryWorkIds = library.workIds,
                onDevice = savedWorks(books.books) { id -> library.entryFor(id)?.work },
                selectedWorkId = selectedWorkId,
            )
        }
        // Detail pane: a rounded card on the low surface.
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxSize().padding(top = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            // Each book fades through to the next.
            val reduce = LocalReduceMotion.current
            AnimatedContent(
                targetState = selectedWorkId,
                transitionSpec = { Motion.fadeThrough(reduce) },
                label = "detail",
            ) { id ->
            val work = id?.let { works.find(it) }
            when {
                id == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = "Pick a book",
                        body = "Choose one from the catalog to see its details and editions.",
                    )
                }
                work == null -> NotFound()
                else -> WorkDetailScreen(
                    work = work,
                    onBack = null,
                    onAddEdition = { nav.push(Screen.Upload(workId = work.id)) },
                    onRead = { ed -> onRead(work, ed) },
                    onDownload = { ed -> onDownload(work, ed) },
                    savedBooks = books.books,
                    keeping = keeping,
                    onKeepOffline = if (books.enabled) { ed, keep -> onKeepOffline(work, ed, keep) } else null,
                    onRemoveFromDevice = if (books.enabled) { ed -> onRemoveFromDevice(work, ed) } else null,
                    onSuggestEdit = { nav.push(Screen.EditWork(work.id)) },
                    onAuthorClick = onAuthorClick,
                    libraryEnabled = auth.isAuthenticated,
                    libraryEntry = library.entryFor(work.id),
                    onLibraryUpsert = { req -> onLibraryUpsert(work.id, req) },
                    onLibraryRemove = { onLibraryRemove(work.id) },
                    onRemoveWork = onRemoveWork?.let { rm -> { removal -> rm(work, removal) } },
                    onRemoveEdition = onRemoveEdition?.let { rm -> { ed, removal -> rm(work, ed, removal) } },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                )
            }
            }
        }
    }
}
