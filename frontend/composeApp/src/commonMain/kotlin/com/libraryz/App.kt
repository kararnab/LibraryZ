package com.libraryz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.libraryz.data.Edition
import com.libraryz.data.Work
import com.libraryz.data.api.ApiClient
import com.libraryz.data.api.AuthState
import com.libraryz.data.api.ContributionsState
import com.libraryz.data.api.CreateWorkRequest
import com.libraryz.data.api.DefaultBaseUrl
import com.libraryz.data.api.LibraryState
import com.libraryz.data.api.RecommendationsState
import com.libraryz.data.api.UpsertLibraryRequest
import com.libraryz.data.api.WorksState
import com.libraryz.data.api.createTokenStore
import com.libraryz.data.canPreview
import com.libraryz.data.isDownloadSupported
import com.libraryz.data.sanitizeFilename
import com.libraryz.data.saveDownload
import com.libraryz.nav.Navigator
import com.libraryz.nav.Screen
import com.libraryz.nav.rememberNavigator
import com.libraryz.theme.LibraryZTheme
import com.libraryz.ui.components.EmptyState
import com.libraryz.ui.screens.AuthGateScreen
import com.libraryz.ui.screens.BrowseScreen
import com.libraryz.ui.screens.ContributionQueueScreen
import com.libraryz.ui.screens.EditWorkSheet
import com.libraryz.ui.screens.ForYouScreen
import com.libraryz.ui.screens.LibraryScreen
import com.libraryz.ui.screens.ReaderScreen
import com.libraryz.ui.screens.UploadMode
import com.libraryz.ui.screens.UploadSheet
import com.libraryz.ui.screens.UploadSubmission
import com.libraryz.ui.screens.WorkDetailScreen
import com.libraryz.ui.screens.inferFormatFromName
import kotlinx.coroutines.launch

val LocalSnackbar = compositionLocalOf<SnackbarHostState> {
    error("No SnackbarHost in scope")
}

// M3 "expanded" window-size threshold. Below = phone single-pane; above =
// navigation rail + list-detail layout.
private const val EXPANDED_DP = 840

@Composable
fun App() {
    LibraryZTheme {
        val snackbar = remember { SnackbarHostState() }
        val tokenStore = remember { createTokenStore() }
        val auth = remember { AuthState(tokenStore) }
        val api = remember {
            // ApiClient needs auth.token; AuthState wants to call api.me().
            // Break the circular construction by attaching the fetcher
            // post-hoc — me() failures are swallowed inside AuthState so
            // offline starts don't drop the session.
            ApiClient(DefaultBaseUrl, tokenProvider = { auth.token }).also { client ->
                auth.setUserFetcher { runCatching { client.me() }.getOrNull() }
            }
        }
        val works = remember { WorksState(api) }
        val contributions = remember { ContributionsState(api) }
        val library = remember { LibraryState(api) }
        val recs = remember { RecommendationsState(api) }

        LaunchedEffect(Unit) { auth.bootstrap() }
        LaunchedEffect(auth.isAuthenticated) {
            if (auth.isAuthenticated && works.items == null) works.refresh()
        }
        // Pre-load the moderator queue once we know the user is a moderator —
        // this also drives the badge count on the nav rail / overflow item.
        LaunchedEffect(auth.isModerator) {
            if (auth.isModerator && contributions.items == null) contributions.refresh()
        }

        CompositionLocalProvider(LocalSnackbar provides snackbar) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                containerColor = MaterialTheme.colorScheme.surface,
            ) { padding ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxSize().padding(padding),
                ) {
                    if (!auth.bootstrapped) {
                        Splash()
                    } else {
                        Root(
                            api = api,
                            auth = auth,
                            works = works,
                            contributions = contributions,
                            library = library,
                            recs = recs,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Splash() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun Root(
    api: ApiClient,
    auth: AuthState,
    works: WorksState,
    contributions: ContributionsState,
    library: LibraryState,
    recs: RecommendationsState,
) {
    val nav = rememberNavigator(Screen.Auth)
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current

    // If bootstrap restored a session, jump straight to Browse.
    LaunchedEffect(auth.isAuthenticated) {
        if (auth.isAuthenticated && nav.current == Screen.Auth) {
            nav.replace(Screen.Browse)
        }
    }

    // Hoisted edition-action handlers so compact + expanded layouts share
    // them. They consult the platform support flags first and snackbar the
    // right "Not yet" message otherwise.
    val previewEdition: (Edition) -> Unit = { ed ->
        if (canPreview(ed.format)) {
            nav.push(Screen.Preview(ed.id, ed.format))
        } else {
            scope.launch {
                snackbar.showSnackbar("Preview not available for ${ed.format.uppercase()} on this platform.")
            }
        }
    }
    val downloadEdition: (Work, Edition) -> Unit = { work, ed ->
        if (!isDownloadSupported) {
            scope.launch { snackbar.showSnackbar("Download not yet implemented on this platform.") }
        } else {
            scope.launch {
                try {
                    val name = "${sanitizeFilename(work.title)}.${ed.format.lowercase()}"
                    snackbar.showSnackbar("Downloading $name…")
                    val bytes = api.downloadEdition(ed.id)
                    val path = saveDownload(name, bytes)
                    snackbar.showSnackbar("Saved to $path")
                } catch (e: Throwable) {
                    snackbar.showSnackbar("Download failed: ${e.message ?: "unknown"}")
                }
            }
        }
    }

    // Personal-library handlers, shared by compact + expanded WorkDetail.
    val libraryUpsert: (String, UpsertLibraryRequest) -> Unit = { workId, req ->
        scope.launch {
            try {
                library.upsert(workId, req)
            } catch (e: Throwable) {
                snackbar.showSnackbar("Couldn't update library: ${e.message ?: "unknown"}")
            }
        }
    }
    val libraryRemove: (String) -> Unit = { workId ->
        scope.launch {
            try {
                library.remove(workId)
                snackbar.showSnackbar("Removed from your library.")
            } catch (e: Throwable) {
                snackbar.showSnackbar("Couldn't remove: ${e.message ?: "unknown"}")
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val expanded = maxWidth.value >= EXPANDED_DP

        when (val s = nav.current) {
            Screen.Auth -> AuthGateScreen(
                api = api,
                onAuthenticated = { session ->
                    auth.signIn(session)        // suspends until token persisted
                    works.refresh()             // pull initial list
                    nav.replace(Screen.Browse)
                },
            )

            Screen.Browse -> {
                if (expanded) {
                    ExpandedFrame(nav = nav, auth = auth, contributions = contributions) {
                        ListDetailLayout(
                            nav = nav,
                            auth = auth,
                            works = works,
                            library = library,
                            selectedWorkId = null,
                            onSelect = { w -> nav.push(Screen.WorkDetail(w.id)) },
                            onPreview = previewEdition,
                            onDownload = downloadEdition,
                            onLibraryUpsert = libraryUpsert,
                            onLibraryRemove = libraryRemove,
                        )
                    }
                } else {
                    DataDrivenBrowse(
                        works = works,
                        onWorkClick = { nav.push(Screen.WorkDetail(it.id)) },
                        onUploadClick = { nav.push(Screen.Upload()) },
                        onLogout = { scope.launch { auth.clear(); nav.replace(Screen.Auth) } },
                        showRefresh = false,
                        onReviewClick = if (auth.isModerator) {
                            { nav.push(Screen.ContributionQueue) }
                        } else null,
                        pendingReviewCount = if (auth.isModerator) contributions.pendingCount else 0,
                        onLibraryClick = if (auth.isAuthenticated) {
                            { nav.push(Screen.Library) }
                        } else null,
                        onForYouClick = if (auth.isAuthenticated) {
                            { nav.push(Screen.ForYou) }
                        } else null,
                    )
                }
            }

            is Screen.WorkDetail -> {
                // Ensure we have an editions-populated copy of this work, plus
                // the caller's library entry for the controls.
                LaunchedEffect(s.workId) {
                    works.refreshOne(s.workId)
                    if (auth.isAuthenticated) library.loadEntry(s.workId)
                }
                val work = works.find(s.workId)
                if (work == null) {
                    NotFound()
                } else if (expanded) {
                    ExpandedFrame(nav = nav, auth = auth, contributions = contributions) {
                        ListDetailLayout(
                            nav = nav,
                            auth = auth,
                            works = works,
                            library = library,
                            selectedWorkId = s.workId,
                            onSelect = { w ->
                                nav.replace(Screen.Browse)
                                nav.push(Screen.WorkDetail(w.id))
                            },
                            onPreview = previewEdition,
                            onDownload = downloadEdition,
                            onLibraryUpsert = libraryUpsert,
                            onLibraryRemove = libraryRemove,
                        )
                    }
                } else {
                    WorkDetailScreen(
                        work = work,
                        onBack = { nav.pop() },
                        onAddEdition = { nav.push(Screen.Upload(workId = s.workId)) },
                        onPreview = previewEdition,
                        onDownload = { ed -> downloadEdition(work, ed) },
                        onSuggestEdit = { nav.push(Screen.EditWork(s.workId)) },
                        libraryEnabled = auth.isAuthenticated,
                        libraryEntry = library.entryFor(s.workId),
                        onLibraryUpsert = { req -> libraryUpsert(s.workId, req) },
                        onLibraryRemove = { libraryRemove(s.workId) },
                    )
                }
            }

            is Screen.EditWork -> {
                // Render WorkDetail behind so the sheet/dialog has visible context.
                LaunchedEffect(s.workId) {
                    works.refreshOne(s.workId)
                    if (auth.isAuthenticated) library.loadEntry(s.workId)
                }
                val work = works.find(s.workId)
                if (work == null) {
                    NotFound()
                } else {
                    if (expanded) {
                        ExpandedFrame(nav = nav, auth = auth, contributions = contributions) {
                            ListDetailLayout(
                                nav = nav,
                                auth = auth,
                                works = works,
                                library = library,
                                selectedWorkId = s.workId,
                                onSelect = { w ->
                                    nav.replace(Screen.Browse)
                                    nav.push(Screen.WorkDetail(w.id))
                                },
                                onPreview = previewEdition,
                                onDownload = downloadEdition,
                                onLibraryUpsert = libraryUpsert,
                                onLibraryRemove = libraryRemove,
                            )
                        }
                    } else {
                        WorkDetailScreen(
                            work = work,
                            onBack = { nav.pop() },
                            onAddEdition = { nav.push(Screen.Upload(workId = s.workId)) },
                            onPreview = previewEdition,
                            onDownload = { ed -> downloadEdition(work, ed) },
                            onSuggestEdit = null, // already in the edit flow
                        )
                    }
                    EditWorkSheet(
                        work = work,
                        isDesktop = expanded,
                        onDismiss = { nav.pop() },
                        onSubmit = { patch ->
                            api.submitContribution(work.id, patch)
                            snackbar.showSnackbar("Edit submitted for review.")
                            nav.pop()
                        },
                    )
                }
            }

            is Screen.Upload -> {
                // Render the screen behind the modal so it has context.
                if (expanded) {
                    ExpandedFrame(nav = nav, auth = auth, contributions = contributions) {
                        ListDetailLayout(
                            nav = nav,
                            auth = auth,
                            works = works,
                            library = library,
                            selectedWorkId = s.workId,
                            onSelect = { w ->
                                nav.replace(Screen.Browse)
                                nav.push(Screen.WorkDetail(w.id))
                            },
                            onPreview = previewEdition,
                            onDownload = downloadEdition,
                            onLibraryUpsert = libraryUpsert,
                            onLibraryRemove = libraryRemove,
                        )
                    }
                } else {
                    DataDrivenBrowse(
                        works = works,
                        onWorkClick = {},
                        onUploadClick = {},
                        onLogout = {},
                        showRefresh = false,
                    )
                }
                UploadSheet(
                    mode = if (s.workId == null) UploadMode.NewWork else UploadMode.AddEdition,
                    isDesktop = expanded,
                    onDismiss = { nav.pop() },
                    onUnsupportedFilePicker = {
                        scope.launch {
                            snackbar.showSnackbar("File picker not yet implemented on this platform.")
                        }
                    },
                    onSubmit = { submission ->
                        when (submission) {
                            is UploadSubmission.NewWork -> {
                                val created = api.createWork(
                                    CreateWorkRequest(
                                        title = submission.title,
                                        authors = submission.authors,
                                        language = submission.language,
                                        publicationYear = submission.publicationYear,
                                    )
                                )
                                api.uploadEdition(
                                    workId = created.id,
                                    format = inferFormatFromName(submission.file.name),
                                    language = submission.language,
                                    fileName = submission.file.name,
                                    bytes = submission.file.bytes,
                                )
                                works.refresh()
                            }
                            is UploadSubmission.AddEdition -> {
                                val wid = s.workId
                                    ?: error("AddEdition submission without a workId")
                                api.uploadEdition(
                                    workId = wid,
                                    format = submission.format,
                                    language = submission.language,
                                    fileName = submission.file.name,
                                    bytes = submission.file.bytes,
                                )
                                works.refreshOne(wid)
                            }
                        }
                        nav.pop()
                    },
                )
            }

            is Screen.Preview -> ReaderScreen(
                api = api,
                editionId = s.editionId,
                format = s.format,
                onClose = { nav.pop() },
            )

            Screen.ContributionQueue -> {
                LaunchedEffect(Unit) { contributions.refresh() }
                if (expanded) {
                    ExpandedFrame(nav = nav, auth = auth, contributions = contributions) {
                        ContributionQueueScreen(
                            state = contributions,
                            isWide = true,
                            onBack = null,
                            onOpenWork = { workId ->
                                nav.replace(Screen.Browse)
                                nav.push(Screen.WorkDetail(workId))
                            },
                        )
                    }
                } else {
                    ContributionQueueScreen(
                        state = contributions,
                        isWide = false,
                        onBack = { nav.pop() },
                        onOpenWork = { workId -> nav.push(Screen.WorkDetail(workId)) },
                    )
                }
            }

            Screen.Library -> {
                LaunchedEffect(Unit) { library.refresh() }
                if (expanded) {
                    ExpandedFrame(nav = nav, auth = auth, contributions = contributions) {
                        LibraryScreen(
                            state = library,
                            isWide = true,
                            onBack = null,
                            onOpenWork = { workId ->
                                nav.replace(Screen.Browse)
                                nav.push(Screen.WorkDetail(workId))
                            },
                        )
                    }
                } else {
                    LibraryScreen(
                        state = library,
                        isWide = false,
                        onBack = { nav.pop() },
                        onOpenWork = { workId -> nav.push(Screen.WorkDetail(workId)) },
                    )
                }
            }

            Screen.ForYou -> {
                LaunchedEffect(Unit) { recs.refresh() }
                if (expanded) {
                    ExpandedFrame(nav = nav, auth = auth, contributions = contributions) {
                        ForYouScreen(
                            state = recs,
                            isWide = true,
                            onBack = null,
                            onOpenWork = { workId ->
                                nav.replace(Screen.Browse)
                                nav.push(Screen.WorkDetail(workId))
                            },
                            onDismiss = { workId -> scope.launch { recs.dismiss(workId) } },
                        )
                    }
                } else {
                    ForYouScreen(
                        state = recs,
                        isWide = false,
                        onBack = { nav.pop() },
                        onOpenWork = { workId -> nav.push(Screen.WorkDetail(workId)) },
                        onDismiss = { workId -> scope.launch { recs.dismiss(workId) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun DataDrivenBrowse(
    works: WorksState,
    onWorkClick: (com.libraryz.data.Work) -> Unit,
    onUploadClick: () -> Unit,
    onLogout: () -> Unit,
    showRefresh: Boolean,
    onReviewClick: (() -> Unit)? = null,
    pendingReviewCount: Int = 0,
    onLibraryClick: (() -> Unit)? = null,
    onForYouClick: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val list = works.items
    val err = works.error

    when {
        works.loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        err != null && list == null -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                title = "Couldn't load works",
                body = err,
                action = {
                    Button(onClick = { scope.launch { works.refresh() } }) { Text("Retry") }
                },
            )
        }
        else -> BrowseScreen(
            works = list ?: emptyList(),
            onWorkClick = onWorkClick,
            onUploadClick = onUploadClick,
            onLogout = onLogout,
            onRefresh = { scope.launch { works.refresh() } },
            showRefresh = showRefresh,
            onReviewClick = onReviewClick,
            pendingReviewCount = pendingReviewCount,
            onLibraryClick = onLibraryClick,
            onForYouClick = onForYouClick,
            onSearch = { q -> works.search(q) },
            activeSearchQuery = works.searchQuery,
        )
    }
}

@Composable
private fun NotFound() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(title = "Not found", body = "That work no longer exists.")
    }
}

/* ---------------- Expanded (Desktop / wide tablet) ---------------- */

@Composable
private fun ExpandedFrame(
    nav: Navigator,
    auth: AuthState,
    contributions: ContributionsState,
    content: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        NavRail(nav = nav, auth = auth, contributions = contributions)
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun NavRail(
    nav: Navigator,
    auth: AuthState,
    contributions: ContributionsState,
) {
    val current = nav.current
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
                    .padding(top = 16.dp)
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Book,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    ) {
        Spacer(Modifier.size(12.dp))
        NavigationRailItem(
            selected = isLibrary,
            onClick = { nav.replace(Screen.Browse) },
            icon = { Icon(Icons.Outlined.Book, contentDescription = null) },
            label = { Text("Browse") },
        )
        if (auth.isAuthenticated) {
            NavigationRailItem(
                selected = isMyLibrary,
                onClick = { nav.push(Screen.Library) },
                icon = { Icon(Icons.Outlined.Bookmarks, contentDescription = null) },
                label = { Text("My Library") },
            )
            NavigationRailItem(
                selected = isForYou,
                onClick = { nav.push(Screen.ForYou) },
                icon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                label = { Text("For You") },
            )
        }
        if (auth.isModerator) {
            NavigationRailItem(
                selected = isQueue,
                onClick = { nav.push(Screen.ContributionQueue) },
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
                        Icon(Icons.Outlined.RateReview, contentDescription = null)
                    }
                },
                label = { Text("Review") },
            )
        }
        NavigationRailItem(
            selected = isUpload,
            onClick = { nav.push(Screen.Upload()) },
            icon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
            label = { Text("Upload") },
        )
    }
}

@Composable
private fun ListDetailLayout(
    nav: Navigator,
    auth: AuthState,
    works: WorksState,
    library: LibraryState,
    selectedWorkId: String?,
    onSelect: (Work) -> Unit,
    onPreview: (Edition) -> Unit,
    onDownload: (Work, Edition) -> Unit,
    onLibraryUpsert: (String, UpsertLibraryRequest) -> Unit,
    onLibraryRemove: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedWorkId) {
        if (selectedWorkId != null) {
            works.refreshOne(selectedWorkId)
            if (auth.isAuthenticated) library.loadEntry(selectedWorkId)
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // List pane
        Box(modifier = Modifier.width(420.dp).fillMaxHeight()) {
            DataDrivenBrowse(
                works = works,
                onWorkClick = onSelect,
                onUploadClick = { nav.push(Screen.Upload()) },
                onLogout = { scope.launch { auth.clear(); nav.replace(Screen.Auth) } },
                showRefresh = true,
            )
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // Detail pane
        Box(modifier = Modifier.fillMaxSize()) {
            val work = selectedWorkId?.let { works.find(it) }
            if (work == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = "Select a work",
                        body = "Pick one from the list to see editions.",
                    )
                }
            } else {
                WorkDetailScreen(
                    work = work,
                    onBack = null,
                    onAddEdition = { nav.push(Screen.Upload(workId = work.id)) },
                    onPreview = onPreview,
                    onDownload = { ed -> onDownload(work, ed) },
                    onSuggestEdit = { nav.push(Screen.EditWork(work.id)) },
                    libraryEnabled = auth.isAuthenticated,
                    libraryEntry = library.entryFor(work.id),
                    onLibraryUpsert = { req -> onLibraryUpsert(work.id, req) },
                    onLibraryRemove = { onLibraryRemove(work.id) },
                )
            }
        }
    }
}
