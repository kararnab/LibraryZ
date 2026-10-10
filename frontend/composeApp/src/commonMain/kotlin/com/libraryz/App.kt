package com.libraryz

import androidx.compose.foundation.layout.Arrangement
import com.libraryz.ui.screens.VerifyEmailBanner
import com.libraryz.ui.screens.VerifyEmailScreen
import com.libraryz.ui.screens.ResetPasswordScreen
import com.libraryz.ui.screens.ForgotPasswordScreen
import com.libraryz.data.launchDeepLink
import com.libraryz.data.DeepLinkInbox
import com.libraryz.data.appLinkUrl
import com.libraryz.data.openInApp
import kotlinx.coroutines.flow.filterNotNull
import com.libraryz.data.DeepLink
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
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
import com.libraryz.ui.screens.ReaderPrefs
import com.libraryz.ui.screens.BrowseSkeleton
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import com.libraryz.ui.components.ContinueReadingCard
import com.libraryz.ui.screens.SettingsScreen
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import com.libraryz.data.LibraryStatus
import com.libraryz.data.UserBook
import com.libraryz.data.Work
import com.libraryz.data.api.ApiClient
import com.libraryz.data.api.ApiException
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
import com.libraryz.data.pickReadableEdition
import com.libraryz.data.progressUpdate
import com.libraryz.data.safeDownloadName
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
            // auth is also the client's SessionHooks: on a 401 the client
            // refreshes the session through it, or reports it expired.
            ApiClient(DefaultBaseUrl, tokenProvider = { auth.token }, sessionHooks = auth).also { client ->
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

/**
 * Everything below the theme and snackbar host. [startAt] opens a given
 * screen instead of sign-in (the README screenshot generator uses it).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Root(
    api: ApiClient,
    auth: AuthState,
    works: WorksState,
    contributions: ContributionsState,
    library: LibraryState,
    recs: RecommendationsState,
    startAt: Screen? = null,
) {
    // An emailed reset / verification link opens its screen: the page URL
    // on the web, the launch intent / arguments elsewhere.
    val launchLink = remember { launchDeepLink() ?: DeepLinkInbox.take() }
    val nav = rememberNavigator(startAt ?: launchLink?.let(::screenFor) ?: Screen.Auth)
    // …and so does one that arrives while the app is open.
    LaunchedEffect(Unit) {
        DeepLinkInbox.pending.filterNotNull().collect {
            DeepLinkInbox.take()?.let { link -> nav.push(screenFor(link)) }
        }
    }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current

    // Follow the session: a restored one jumps straight to Browse; losing it
    // (logout, or expiry detected by ApiClient anywhere in the app) returns
    // to sign-in.
    // Recovery screens work signed in or out, so the session doesn't move them.
    LaunchedEffect(auth.isAuthenticated) {
        val recovering = nav.current.let {
            it is Screen.ForgotPassword || it is Screen.ResetPassword || it is Screen.VerifyEmail
        }
        if (recovering) return@LaunchedEffect
        if (auth.isAuthenticated) {
            if (nav.current == Screen.Auth) nav.replace(Screen.Browse)
        } else if (nav.current != Screen.Auth) {
            nav.replace(Screen.Auth)
        }
    }
    var passwordChanged by remember { mutableStateOf(false) }

    // "Verify your email" atop Browse until the backend says it's done.
    val resendVerification: () -> Unit = {
        scope.launch {
            try {
                api.requestEmailVerification()
                snackbar.showSnackbar("Sent a new link to ${auth.user?.email ?: "your email"}.")
            } catch (e: Throwable) {
                snackbar.showSnackbar(
                    if ((e as? ApiException)?.status == 429) "Please wait a few minutes before asking for another link."
                    else "Couldn’t send the link. Please try again.",
                )
            }
        }
    }
    val verifyBanner: (@Composable () -> Unit)? = auth.user?.takeIf { !it.emailVerified }?.let { u ->
        {
            VerifyEmailBanner(
                u.email,
                onResend = resendVerification,
                onHaveCode = { code -> nav.push(Screen.VerifyEmail(code)) },
            )
        }
    }
    // An expired session lands on sign-in with a banner explaining why.
    var expiredNotice by remember { mutableStateOf(false) }
    LaunchedEffect(auth.sessionExpired) {
        if (auth.sessionExpired) {
            auth.acknowledgeSessionExpired()
            expiredNotice = true
        }
    }
    // Ends the session server-side too (best effort — offline still logs out
    // locally), then clears it; the effect above navigates to sign-in.
    val logout: () -> Unit = {
        scope.launch {
            auth.refreshToken?.let { rt -> runCatching { api.logout(rt) } }
            auth.clear()
        }
    }

    val logoutEverywhere: () -> Unit = {
        scope.launch {
            try {
                api.logoutAll()
                auth.clear()
            } catch (e: Throwable) {
                snackbar.showSnackbar("Couldn't sign out everywhere: ${(e as? ApiException)?.userMessage ?: e.message ?: "unknown"}")
            }
        }
    }

    // How flowing text is set, shared by the reader and Settings so it
    // carries from one book to the next.
    var readerPrefs by remember { mutableStateOf(ReaderPrefs()) }

    val dismissRec: (String) -> Unit = { workId ->
        scope.launch {
            try {
                val dismissed = recs.dismiss(workId)
                val result = snackbar.showSnackbar(
                    "Removed from For You",
                    actionLabel = if (dismissed != null) "Undo" else null,
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed && dismissed != null) {
                    try {
                        recs.undismiss(dismissed)
                    } catch (e: Throwable) {
                        snackbar.showSnackbar("Couldn't undo: ${e.message ?: "unknown"}")
                    }
                }
            } catch (e: Throwable) {
                snackbar.showSnackbar("Couldn't dismiss: ${e.message ?: "unknown"}")
            }
        }
    }

    // Resolves a work for screens that only hold its id (the review queue).
    val loadWork: suspend (String) -> Work? = { id ->
        works.find(id) ?: runCatching { api.getWork(id) }.getOrNull()
    }

    // Load the library up front: it powers "Continue reading" on Browse.
    LaunchedEffect(auth.isAuthenticated) {
        if (auth.isAuthenticated) library.refresh()
    }

    // System back (Android, predictive back): pop the stack; from another
    // top-level tab, go home to Browse first; only then leave the app.
    BackHandler(enabled = nav.canGoBack || (nav.current != Screen.Browse && nav.current != Screen.Auth)) {
        if (!nav.pop()) nav.replace(Screen.Browse)
    }

    // Hoisted edition-action handlers so compact + expanded layouts share
    // them. They consult the platform support flags first and snackbar the
    // right "Not yet" message otherwise.
    val readEdition: (Work, Edition) -> Unit = { work, ed ->
        if (canPreview(ed.format)) {
            nav.push(Screen.Preview(ed.id, ed.format, work.id, work.title, work.authors))
        } else {
            scope.launch {
                snackbar.showSnackbar("Reading ${ed.format.uppercase()} isn't supported on this platform yet.")
            }
        }
    }
    // One-tap resume from Browse / My Library: the entry embeds its work
    // with editions, so no extra round-trip.
    val readFromLibrary: (UserBook) -> Unit = { ub ->
        val work = ub.work
        val ed = work?.let { pickReadableEdition(it.editions) }
        if (work != null && ed != null) {
            readEdition(work, ed)
        } else {
            nav.push(Screen.WorkDetail(ub.workId))
        }
    }
    val downloadEdition: (Work, Edition) -> Unit = { work, ed ->
        if (!isDownloadSupported) {
            scope.launch { snackbar.showSnackbar("Download not yet implemented on this platform.") }
        } else {
            scope.launch {
                try {
                    snackbar.showSnackbar("Downloading ${work.title}…")
                    val file = api.downloadEditionFile(ed.id)
                    // Server names the file ("<Title> - <Authors>.<format>");
                    // the local name is only a fallback for older backends.
                    val name = file.filename?.let(::safeDownloadName)
                        ?: "${sanitizeFilename(work.title)}.${ed.format.lowercase()}"
                    val path = saveDownload(name, file.bytes)
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

    // Moderator takedowns, shared by compact + expanded WorkDetail. Null for
    // everyone else, which hides the Remove affordances.
    val removeWork: ((Work, String) -> Unit)? = if (auth.isModerator) {
        { work, reason ->
            scope.launch {
                try {
                    works.removeWork(work.id, reason)
                    // Leave the now-404 detail screen.
                    if (nav.current.let { it is Screen.WorkDetail && it.workId == work.id }) {
                        if (!nav.pop()) nav.replace(Screen.Browse)
                    }
                    snackbar.showSnackbar("Removed “${work.title}”.")
                } catch (e: Throwable) {
                    snackbar.showSnackbar("Couldn't remove: ${(e as? ApiException)?.userMessage ?: e.message ?: "unknown"}")
                }
            }
        }
    } else null
    val removeEdition: ((Work, Edition, String) -> Unit)? = if (auth.isModerator) {
        { work, ed, reason ->
            scope.launch {
                try {
                    works.removeEdition(work.id, ed.id, reason)
                    snackbar.showSnackbar("Removed the ${ed.format.uppercase()} edition.")
                } catch (e: Throwable) {
                    snackbar.showSnackbar("Couldn't remove: ${(e as? ApiException)?.userMessage ?: e.message ?: "unknown"}")
                }
            }
        }
    } else null

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val expanded = maxWidth.value >= EXPANDED_DP

        when (val s = nav.current) {
            Screen.Auth -> AuthGateScreen(
                api = api,
                sessionExpired = expiredNotice,
                passwordChanged = passwordChanged,
                onForgotPassword = { email -> nav.push(Screen.ForgotPassword(email)) },
                onAuthenticated = { session ->
                    expiredNotice = false
                    passwordChanged = false
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
                            onRead = readEdition,
                            onReadFromLibrary = readFromLibrary,
                            extraHeader = verifyBanner,
                            onDownload = downloadEdition,
                            onLibraryUpsert = libraryUpsert,
                            onLibraryRemove = libraryRemove,
                            onRemoveWork = removeWork,
                            onRemoveEdition = removeEdition,
                        )
                    }
                } else {
                    CompactFrame(nav = nav, auth = auth, contributions = contributions) {
                        DataDrivenBrowse(
                            works = works,
                            onWorkClick = { nav.push(Screen.WorkDetail(it.id)) },
                            onUploadClick = { nav.push(Screen.Upload()) },
                            compact = true,
                            header = stackHeaders(verifyBanner, continueReadingHeader(library, readFromLibrary)),
                            libraryWorkIds = library.workIds,
                        )
                    }
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
                            onRead = readEdition,
                            onReadFromLibrary = readFromLibrary,
                            extraHeader = verifyBanner,
                            onDownload = downloadEdition,
                            onLibraryUpsert = libraryUpsert,
                            onLibraryRemove = libraryRemove,
                            onRemoveWork = removeWork,
                            onRemoveEdition = removeEdition,
                        )
                    }
                } else {
                    WorkDetailScreen(
                        work = work,
                        onBack = { nav.pop() },
                        onAddEdition = { nav.push(Screen.Upload(workId = s.workId)) },
                        onRead = { ed -> readEdition(work, ed) },
                        onDownload = { ed -> downloadEdition(work, ed) },
                        onSuggestEdit = { nav.push(Screen.EditWork(s.workId)) },
                        libraryEnabled = auth.isAuthenticated,
                        libraryEntry = library.entryFor(s.workId),
                        onLibraryUpsert = { req -> libraryUpsert(s.workId, req) },
                        onLibraryRemove = { libraryRemove(s.workId) },
                        onRemoveWork = removeWork?.let { rm -> { reason -> rm(work, reason) } },
                        onRemoveEdition = removeEdition?.let { rm -> { ed, reason -> rm(work, ed, reason) } },
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
                                onRead = readEdition,
                            onReadFromLibrary = readFromLibrary,
                            extraHeader = verifyBanner,
                                onDownload = downloadEdition,
                                onLibraryUpsert = libraryUpsert,
                                onLibraryRemove = libraryRemove,
                                onRemoveWork = removeWork,
                                onRemoveEdition = removeEdition,
                            )
                        }
                    } else {
                        WorkDetailScreen(
                            work = work,
                            onBack = { nav.pop() },
                            onAddEdition = { nav.push(Screen.Upload(workId = s.workId)) },
                            onRead = { ed -> readEdition(work, ed) },
                            onDownload = { ed -> downloadEdition(work, ed) },
                            onSuggestEdit = null, // already in the edit flow
                        )
                    }
                    EditWorkSheet(
                        work = work,
                        isDesktop = expanded,
                        onDismiss = { nav.pop() },
                        // The sheet shows its own "Sent for review" state.
                        onSubmit = { patch -> api.submitContribution(work.id, patch) },
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
                            onRead = readEdition,
                            onReadFromLibrary = readFromLibrary,
                            extraHeader = verifyBanner,
                            onDownload = downloadEdition,
                            onLibraryUpsert = libraryUpsert,
                            onLibraryRemove = libraryRemove,
                            onRemoveWork = removeWork,
                            onRemoveEdition = removeEdition,
                        )
                    }
                } else {
                    DataDrivenBrowse(
                        works = works,
                        onWorkClick = {},
                        onUploadClick = {},
                        compact = true,
                    )
                }
                UploadSheet(
                    mode = if (s.workId == null) UploadMode.NewWork else UploadMode.AddEdition,
                    isDesktop = expanded,
                    target = s.workId?.let { works.find(it) },
                    onDismiss = { nav.pop() },
                    onViewBook = { workId ->
                        nav.replace(Screen.Browse)
                        nav.push(Screen.WorkDetail(workId))
                    },
                    onUnsupportedFilePicker = {
                        scope.launch {
                            snackbar.showSnackbar("File picker not yet implemented on this platform.")
                        }
                    },
                    loadQuota = { size -> api.uploadQuota(size) },
                    onSubmit = { submission, onProgress ->
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
                                try {
                                    api.uploadEdition(
                                        workId = created.id,
                                        format = inferFormatFromName(submission.file.name),
                                        language = submission.language,
                                        fileName = submission.file.name,
                                        bytes = submission.file.bytes,
                                        onProgress = onProgress,
                                    )
                                } catch (e: Throwable) {
                                    // Don't leave an empty work behind when its
                                    // only upload was rejected (e.g. duplicate
                                    // file) or cancelled. Creators may remove
                                    // their own still-empty work. Best effort,
                                    // and it must run even when cancelled.
                                    withContext(NonCancellable) {
                                        runCatching { api.deleteWork(created.id, "Upload failed; discarding the empty work") }
                                    }
                                    throw e
                                } finally {
                                    withContext(NonCancellable) { works.refresh() }
                                }
                                created.id
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
                                    onProgress = onProgress,
                                )
                                works.refreshOne(wid)
                                wid
                            }
                        }
                    },
                )
            }

            is Screen.Preview -> {
                val entry = library.entryFor(s.workId)
                ReaderScreen(
                    api = api,
                    editionId = s.editionId,
                    format = s.format,
                    title = s.title,
                    authors = s.authors,
                    resumeFrom = entry,
                    finished = entry?.status == LibraryStatus.Read,
                    rating = entry?.rating,
                    prefs = readerPrefs,
                    onPrefsChange = { readerPrefs = it },
                    onRate = { stars -> libraryUpsert(s.workId, UpsertLibraryRequest(rating = stars)) },
                    // Progress saves in the background; a failed save just
                    // means the next one carries it.
                    onProgress = { pos ->
                        scope.launch {
                            runCatching { library.upsert(s.workId, progressUpdate(library.entryFor(s.workId), pos)) }
                        }
                    },
                    onMarkRead = {
                        libraryUpsert(s.workId, UpsertLibraryRequest(status = LibraryStatus.Read))
                        scope.launch { snackbar.showSnackbar("Marked “${s.title}” as read.") }
                    },
                    onClose = { nav.pop() },
                )
            }

            Screen.ContributionQueue -> {
                LaunchedEffect(Unit) { contributions.refresh() }
                if (expanded) {
                    ExpandedFrame(nav = nav, auth = auth, contributions = contributions) {
                        ContributionQueueScreen(
                            state = contributions,
                            isWide = true,
                            onOpenWork = { workId ->
                                nav.replace(Screen.Browse)
                                nav.push(Screen.WorkDetail(workId))
                            },
                            loadWork = loadWork,
                        )
                    }
                } else {
                    CompactFrame(nav = nav, auth = auth, contributions = contributions) {
                        ContributionQueueScreen(
                            state = contributions,
                            isWide = false,
                            onOpenWork = { workId -> nav.push(Screen.WorkDetail(workId)) },
                            loadWork = loadWork,
                        )
                    }
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
                            onRead = readFromLibrary,
                            onBrowse = { nav.replace(Screen.Browse) },
                        )
                    }
                } else {
                    CompactFrame(nav = nav, auth = auth, contributions = contributions) {
                        LibraryScreen(
                            state = library,
                            isWide = false,
                            onBack = null,
                            onOpenWork = { workId -> nav.push(Screen.WorkDetail(workId)) },
                            onRead = readFromLibrary,
                            onBrowse = { nav.replace(Screen.Browse) },
                        )
                    }
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
                            onDismiss = dismissRec,
                            onWantToRead = { workId -> libraryUpsert(workId, UpsertLibraryRequest(status = LibraryStatus.Want)) },
                            isInLibrary = { workId -> library.entryFor(workId) != null },
                            onOpenLibrary = { nav.replace(Screen.Library) },
                        )
                    }
                } else {
                    CompactFrame(nav = nav, auth = auth, contributions = contributions) {
                        ForYouScreen(
                            state = recs,
                            isWide = false,
                            onBack = null,
                            onOpenWork = { workId -> nav.push(Screen.WorkDetail(workId)) },
                            onDismiss = dismissRec,
                            onWantToRead = { workId -> libraryUpsert(workId, UpsertLibraryRequest(status = LibraryStatus.Want)) },
                            isInLibrary = { workId -> library.entryFor(workId) != null },
                            onOpenLibrary = { nav.replace(Screen.Library) },
                        )
                    }
                }
            }

            is Screen.ForgotPassword -> ForgotPasswordScreen(
                api = api,
                initialEmail = s.email,
                onBack = { if (!nav.pop()) nav.replace(Screen.Auth) },
                onHaveCode = { code -> nav.push(Screen.ResetPassword(code)) },
            )

            is Screen.ResetPassword -> ResetPasswordScreen(
                api = api,
                token = s.token,
                onOpenInApp = openInApp?.let { open -> { open(appLinkUrl(DeepLink.ResetPassword(s.token))) } },
                onDone = {
                    // The reset ended every session, this device's included.
                    scope.launch {
                        if (auth.isAuthenticated) auth.clear()
                        passwordChanged = true
                        nav.replace(Screen.Auth)
                    }
                },
                onRequestNewLink = { nav.replace(Screen.Auth); nav.push(Screen.ForgotPassword()) },
            )

            is Screen.VerifyEmail -> VerifyEmailScreen(
                api = api,
                token = s.token,
                onVerified = { if (auth.isAuthenticated) auth.reloadUser() },
                onContinue = { nav.replace(if (auth.isAuthenticated) Screen.Browse else Screen.Auth) },
                onResend = if (auth.isAuthenticated) ({ api.requestEmailVerification() }) else null,
            )

            Screen.Settings -> {
                val settings = @Composable {
                    SettingsScreen(
                        user = auth.user,
                        serverUrl = DefaultBaseUrl,
                        prefs = readerPrefs,
                        onPrefsChange = { readerPrefs = it },
                        onSignOut = logout,
                        onSignOutEverywhere = logoutEverywhere,
                        onBack = null,
                    )
                }
                if (expanded) {
                    ExpandedFrame(nav = nav, auth = auth, contributions = contributions) { settings() }
                } else {
                    CompactFrame(nav = nav, auth = auth, contributions = contributions) { settings() }
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
    compact: Boolean,
    header: (@Composable () -> Unit)? = null,
    libraryWorkIds: Set<String> = emptySet(),
    selectedWorkId: String? = null,
) {
    val scope = rememberCoroutineScope()
    val list = works.items
    val err = works.error

    when {
        works.loading -> BrowseSkeleton(compact)
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
            header = header,
            onRefresh = { scope.launch { works.refresh() } },
            compact = compact,
            libraryWorkIds = libraryWorkIds,
            selectedWorkId = selectedWorkId,
            onSearch = { q -> works.search(q) },
            activeSearchQuery = works.searchQuery,
            onLoadMore = { scope.launch { works.loadMore() } },
            loadingMore = works.loadingMore,
            endReached = works.endReached,
            loadMoreError = works.loadMoreError,
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
private fun CompactFrame(
    nav: Navigator,
    auth: AuthState,
    contributions: ContributionsState,
    content: @Composable () -> Unit,
) {
    val current = nav.current
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

/** Stacks optional Browse headers (verify-email banner, Continue reading) with a gap. */
private fun stackHeaders(vararg headers: (@Composable () -> Unit)?): (@Composable () -> Unit)? {
    val present = headers.filterNotNull()
    if (present.isEmpty()) return null
    return {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { present.forEach { it() } }
    }
}

/** The "Continue reading" banner atop Browse, or null when there's nothing to resume. */
private fun continueReadingHeader(
    library: LibraryState,
    onRead: (UserBook) -> Unit,
): (@Composable () -> Unit)? {
    val entry = library.continueReading ?: return null
    return { ContinueReadingCard(entry = entry, onClick = { onRead(entry) }) }
}

@Composable
private fun ListDetailLayout(
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
    onLibraryUpsert: (String, UpsertLibraryRequest) -> Unit,
    onLibraryRemove: (String) -> Unit,
    onRemoveWork: ((Work, String) -> Unit)? = null,
    onRemoveEdition: ((Work, Edition, String) -> Unit)? = null,
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
                selectedWorkId = selectedWorkId,
            )
        }
        // Detail pane: a rounded card on the low surface.
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxSize().padding(top = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            val work = selectedWorkId?.let { works.find(it) }
            if (work == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = "Pick a book",
                        body = "Choose one from the catalog to see its details and editions.",
                    )
                }
            } else {
                WorkDetailScreen(
                    work = work,
                    onBack = null,
                    onAddEdition = { nav.push(Screen.Upload(workId = work.id)) },
                    onRead = { ed -> onRead(work, ed) },
                    onDownload = { ed -> onDownload(work, ed) },
                    onSuggestEdit = { nav.push(Screen.EditWork(work.id)) },
                    libraryEnabled = auth.isAuthenticated,
                    libraryEntry = library.entryFor(work.id),
                    onLibraryUpsert = { req -> onLibraryUpsert(work.id, req) },
                    onLibraryRemove = { onLibraryRemove(work.id) },
                    onRemoveWork = onRemoveWork?.let { rm -> { reason -> rm(work, reason) } },
                    onRemoveEdition = onRemoveEdition?.let { rm -> { ed, reason -> rm(work, ed, reason) } },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                )
            }
        }
    }
}

private fun screenFor(link: DeepLink): Screen = when (link) {
    is DeepLink.ResetPassword -> Screen.ResetPassword(link.token)
    is DeepLink.VerifyEmail -> Screen.VerifyEmail(link.token)
}
