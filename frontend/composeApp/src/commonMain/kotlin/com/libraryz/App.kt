package com.libraryz

import com.libraryz.data.BookCache
import com.libraryz.data.BookInfo
import com.libraryz.data.BookLayouts
import com.libraryz.data.createBookFiles
import com.libraryz.data.savedWorks
import com.libraryz.data.api.describe
import com.libraryz.data.api.createReaderLayoutStore
import com.libraryz.data.api.createReaderPrefsStore
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
import androidx.compose.material.icons.rounded.Settings
import com.libraryz.theme.LocalReduceMotion
import com.libraryz.theme.Motion
import com.libraryz.ui.components.OfflineStrip
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalDensity
import com.libraryz.ui.screens.ReaderPrefs
import com.libraryz.ui.screens.ReaderPrefsStore
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import com.libraryz.ui.screens.SettingsScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import com.libraryz.data.telemetry.BookOpenedFormat
import com.libraryz.data.telemetry.BookOpenedSource
import com.libraryz.data.telemetry.Events
import com.libraryz.data.telemetry.LibraryStatusSetStatus
import com.libraryz.data.telemetry.Telemetry
import com.libraryz.data.telemetry.UploadFinishedKind
import com.libraryz.data.telemetry.UploadFinishedResult
import com.libraryz.data.telemetry.createCrashStore
import com.libraryz.data.telemetry.createEventStore
import androidx.compose.runtime.DisposableEffect
import com.libraryz.data.telemetry.createTelemetryStore
import com.libraryz.data.telemetry.installCrashHandler
import com.libraryz.data.api.DuplicateEditionException
import com.libraryz.ui.components.TelemetryConsentDialog
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.libraryz.data.api.DownloadedFile
import com.libraryz.data.api.ServerUrlStore
import com.libraryz.data.api.createServerUrlStore
import com.libraryz.data.api.isDevServerUrl
import com.libraryz.data.api.parseServerUrl
import com.libraryz.data.api.LibraryState
import com.libraryz.data.api.RecommendationsState
import com.libraryz.data.api.UpsertLibraryRequest
import com.libraryz.data.api.WorksState
import com.libraryz.data.api.createLibraryStore
import com.libraryz.data.api.createTokenStore
import com.libraryz.data.canPreview
import com.libraryz.data.isDownloadSupported
import com.libraryz.data.pickReadableEdition
import com.libraryz.data.progressUpdate
import com.libraryz.data.safeDownloadName
import com.libraryz.data.sanitizeFilename
import com.libraryz.data.saveDownload
import com.libraryz.nav.Screen
import com.libraryz.nav.needsConnection
import com.libraryz.nav.rememberNavigator
import com.libraryz.theme.LibraryZTheme
import com.libraryz.ui.screens.AuthGateScreen
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.libraryz.data.api.RemovalRequest
import com.libraryz.data.api.catchingNonCancel
import com.libraryz.data.api.rethrowIfCancelled

val LocalSnackbar = compositionLocalOf<SnackbarHostState> {
    error("No SnackbarHost in scope")
}

/**
 * Consented crash reports and usage events. The default sends nothing (no
 * stores, never loaded), which is what previews and screenshots get.
 */
val LocalTelemetry = staticCompositionLocalOf { Telemetry() }

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
                auth.setUserFetcher { catchingNonCancel { client.me() }.getOrNull() }
            }
        }
        // The server chosen in Settings (or on sign-in), loaded before the
        // session is restored so that's checked against the right server.
        val serverStore = remember { ServerUrlStore(::createServerUrlStore) }
        var serverUrl by remember { mutableStateOf(DefaultBaseUrl) }
        // Another server is another catalogue: start these afresh.
        val works = remember(serverUrl) { WorksState(api) }
        val contributions = remember(serverUrl) { ContributionsState(api) }
        val library = remember(serverUrl) { LibraryState(api, ::createLibraryStore, serverUrl) }
        val recs = remember(serverUrl) { RecommendationsState(api) }
        // Crash reports and usage statistics, each only if the reader agreed
        // (asked once, in Root). The handler is installed at once so a crash
        // during start-up is caught too, once the choice is loaded.
        val telemetry = remember { Telemetry(::createTelemetryStore, ::createCrashStore, ::createEventStore) }

        LaunchedEffect(Unit) {
            serverStore.load()?.let { api.baseUrl = it; serverUrl = it }
            auth.bootstrap()
        }
        LaunchedEffect(telemetry) {
            installCrashHandler { type, frames -> telemetry.recordCrash(type, frames, fatal = true) }
            telemetry.load()
            telemetry.track(Events.appOpened())
        }
        val scope = rememberCoroutineScope()
        // Blank or invalid input means the default. Between dev addresses
        // (often one machine by two names) the session carries over: the new
        // server either accepts it or the refresh fails and we land on
        // sign-in. Otherwise its tokens would go to a server that didn't
        // issue them, so it ends on the old one (best effort, briefly) first.
        val changeServer: (String) -> Unit = { input ->
            scope.launch {
                val parsed = parseServerUrl(input)
                val url = parsed ?: DefaultBaseUrl
                if (url != serverUrl) {
                    val keepSession = isDevServerUrl(serverUrl) && isDevServerUrl(url)
                    if (auth.isAuthenticated && !keepSession) {
                        auth.refreshToken?.let { rt -> withTimeoutOrNull(3_000) { catchingNonCancel { api.logout(rt) } } }
                        auth.clear()
                    }
                    api.baseUrl = url
                    serverUrl = url
                    serverStore.save(url.takeIf { it != DefaultBaseUrl })
                    if (auth.isAuthenticated) auth.reloadUser()
                }
                snackbar.showSnackbar(
                    when {
                        parsed == null && input.isNotBlank() -> "That isn’t a valid server address. Using the default, $url."
                        else -> "Server: $url"
                    },
                )
            }
        }
        // Keyed on the states too: a server switch that keeps the session
        // loads the new server's catalogue.
        LaunchedEffect(auth.isAuthenticated, works) {
            if (auth.isAuthenticated && works.items == null) works.refresh()
        }
        // Pre-load the moderator queue once we know the user is a moderator —
        // this also drives the badge count on the nav rail / overflow item.
        LaunchedEffect(auth.isModerator, contributions) {
            if (auth.isModerator && contributions.items == null) contributions.refresh()
        }

        CompositionLocalProvider(LocalSnackbar provides snackbar, LocalTelemetry provides telemetry) {
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
                            serverUrl = serverUrl,
                            onServerUrlChange = changeServer,
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
    serverUrl: String = api.baseUrl,
    onServerUrlChange: (String) -> Unit = {},
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
    val telemetry = LocalTelemetry.current

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
                e.rethrowIfCancelled()
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
            auth.refreshToken?.let { rt -> catchingNonCancel { api.logout(rt) } }
            library.forget()
            auth.clear()
        }
    }

    val logoutEverywhere: () -> Unit = {
        scope.launch {
            try {
                api.logoutAll()
                library.forget()
                auth.clear()
            } catch (e: Throwable) {
                e.rethrowIfCancelled()
                snackbar.showSnackbar("Couldn’t sign out everywhere. ${api.describe(e)}")
            }
        }
    }

    // How flowing text is set, shared by the reader and Settings so it
    // carries from one book to the next.
    var readerPrefs by remember { mutableStateOf(ReaderPrefs()) }
    val readerPrefsStore = remember { ReaderPrefsStore(::createReaderPrefsStore) }
    LaunchedEffect(Unit) { readerPrefsStore.load()?.let { readerPrefs = it } }
    val setReaderPrefs: (ReaderPrefs) -> Unit = { p ->
        readerPrefs = p
        scope.launch { readerPrefsStore.save(p) }
    }
    val readerLayouts = remember { BookLayouts(::createReaderLayoutStore) }
    // Books kept on this device: the reader opens these without the server.
    val books = remember { BookCache(::createBookFiles) }
    LaunchedEffect(books) { books.load() }
    // Offline, Browse lists the books on this device, and Book detail finds
    // them (and the saved library's works) without the server.
    val onDevice = savedWorks(books.books) { id -> library.entryFor(id)?.work }
    remember(works, library, books) {
        works.onSearched = { found -> telemetry.track(Events.search(hadResults = found)) }
        library.workLookup = { id -> works.find(id) }
        works.localWorks = {
            savedWorks(books.books) { id -> library.entryFor(id)?.work } + library.items.orEmpty().mapNotNull { it.work }
        }
    }
    val removeFromDevice: (Work, Edition) -> Unit = { work, ed ->
        scope.launch {
            books.remove(ed.id)
            snackbar.showSnackbar("Removed “${work.title}” from this device. It downloads again when you open it.")
        }
    }
    // Editions being fetched for "Keep offline", so their rows show progress.
    var keeping by remember { mutableStateOf(emptySet<String>()) }
    val keepOffline: (Work, Edition, Boolean) -> Unit = { work, ed, keep ->
        scope.launch {
            when {
                !keep -> books.setPinned(ed.id, false)
                books.isSaved(ed.id) -> books.setPinned(ed.id, true)
                ed.id !in keeping -> {
                    keeping = keeping + ed.id
                    try {
                        val file = api.downloadEditionFile(ed.id)
                        val kept = file.sha256 != null && books.keep(
                            ed.id, file.sha256, file.bytes,
                            BookInfo(ed.format, work.id, work.title, work.authors), file.filename, pinned = true,
                        )
                        snackbar.showSnackbar(
                            if (kept) "“${work.title}” is kept on this device." else "Couldn’t keep “${work.title}” on this device.",
                        )
                    } catch (e: Throwable) {
                        e.rethrowIfCancelled()
                        snackbar.showSnackbar("Couldn’t download “${work.title}”. ${api.describe(e)}")
                    } finally {
                        keeping = keeping - ed.id
                    }
                }
            }
        }
    }

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
                        e.rethrowIfCancelled()
                        snackbar.showSnackbar("Couldn’t undo. ${api.describe(e)}")
                    }
                }
            } catch (e: Throwable) {
                e.rethrowIfCancelled()
                snackbar.showSnackbar("Couldn’t dismiss. ${api.describe(e)}")
            }
        }
    }

    // Resolves a work for screens that only hold its id (the review queue).
    val loadWork: suspend (String) -> Work? = { id ->
        works.find(id) ?: catchingNonCancel { api.getWork(id) }.getOrNull()
    }

    // Load the library up front: it powers "Continue reading" on Browse.
    LaunchedEffect(auth.isAuthenticated, library) {
        if (auth.isAuthenticated) library.refresh()
    }
    // Offline: ask the server every so often whether it's back. Changes
    // wait in the library's queue meanwhile (and are retried when a send
    // failed for a passing reason).
    LaunchedEffect(auth.isAuthenticated, library) {
        while (auth.isAuthenticated) {
            delay(if (api.reachable) 30_000 else 15_000)
            when {
                !api.reachable -> api.ping()
                library.pending.isNotEmpty() -> catchingNonCancel { library.sync() }
            }
        }
    }
    // Back online (any answer from the server): send what's waiting and
    // swap saved copies for the server's.
    var wasOffline by remember { mutableStateOf(false) }
    LaunchedEffect(api.reachable, auth.isAuthenticated) {
        if (!api.reachable) wasOffline = true
        if (api.reachable && auth.isAuthenticated) {
            // The user never loaded (a launch without the server), or may
            // have changed (roles, verification) while we were away.
            if (auth.user == null || wasOffline) auth.reloadUser()
            wasOffline = false
            if (library.offline || library.pending.isNotEmpty()) library.refresh()
            if (works.items == null && works.error != null) works.refresh()
        }
    }
    // Screens that only work online explain themselves instead of failing.
    remember(nav, api) {
        nav.guard = { s ->
            val why = needsConnection(s)
            if (why != null && !api.reachable) {
                scope.launch { snackbar.showSnackbar(why) }
                false
            } else {
                true
            }
        }
    }
    // Crash reports and usage statistics: asked once, then sent about once a
    // minute (a crash from the last run first) while the server answers.
    if (telemetry.loaded && !telemetry.consent.asked) {
        TelemetryConsentDialog(
            onDecide = { crashes, usage ->
                scope.launch {
                    telemetry.setConsent(crashes, usage)
                    // The launch was before the choice; count it now.
                    telemetry.track(Events.appOpened())
                }
            },
        )
    }
    LaunchedEffect(telemetry) {
        snapshotFlow { telemetry.loaded }.first { it }
        while (true) {
            if (api.reachable) telemetry.flush(api)
            delay(60_000)
        }
    }
    LaunchedEffect(api.reachable) {
        if (!api.reachable) telemetry.track(Events.wentOffline())
    }
    // Leaving the screen or closing sends what's waiting now (AppExit).
    DisposableEffect(telemetry, api) {
        AppExit.send = { telemetry.flush(api) }
        onDispose { AppExit.send = null }
    }

    // The queue is sent only for the user who made it.
    val userId = auth.user?.id
    LaunchedEffect(userId, library) { userId?.let { library.signedInAs(it) } }

    // System back (Android, predictive back): pop the stack; from another
    // top-level tab, go home to Browse first; only then leave the app.
    BackHandler(enabled = nav.canGoBack || (nav.current != Screen.Browse && nav.current != Screen.Auth)) {
        if (!nav.pop()) nav.replace(Screen.Browse)
    }

    // Hoisted edition-action handlers so compact + expanded layouts share
    // them. They consult the platform support flags first and snackbar the
    // right "Not yet" message otherwise.
    // An author link on Book detail: Browse, searching for that name. It
    // runs in Root's scope: the detail layout that was clicked leaves the
    // composition with the nav change, and its scope with it.
    val searchAuthor: (String) -> Unit = { name ->
        nav.replace(Screen.Browse)
        scope.launch { works.search(name) }
    }
    val readEdition: (Work, Edition) -> Unit = { work, ed ->
        if (canPreview(ed.format)) {
            telemetry.track(
                Events.bookOpened(
                    format = BookOpenedFormat.entries.firstOrNull { it.wire == ed.format.lowercase() } ?: BookOpenedFormat.Other,
                    source = if (books.isSaved(ed.id)) BookOpenedSource.Device else BookOpenedSource.Server,
                ),
            )
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
                snackbar.showSnackbar("Downloading ${work.title}…")
                val file = try {
                    // A saved copy exports without the server.
                    books.open(ed.id)?.let { bytes -> DownloadedFile(books.books[ed.id]?.filename, bytes) }
                        ?: api.downloadEditionFile(ed.id).also { f ->
                            books.keepLater(ed.id, f.sha256, f.bytes, BookInfo(ed.format, work.id, work.title, work.authors), f.filename)
                        }
                } catch (e: Throwable) {
                    e.rethrowIfCancelled()
                    snackbar.showSnackbar("Download failed. ${api.describe(e)}")
                    return@launch
                }
                try {
                    // Server names the file ("<Title> - <Authors>.<format>");
                    // the local name is only a fallback for older backends.
                    val name = file.filename?.let(::safeDownloadName)
                        ?: "${sanitizeFilename(work.title)}.${ed.format.lowercase()}"
                    val path = saveDownload(name, file.bytes)
                    snackbar.showSnackbar("Saved to $path")
                } catch (e: Throwable) {
                    e.rethrowIfCancelled()
                    // A local problem (disk full, no permission): its own message says which.
                    snackbar.showSnackbar("Couldn’t save the file: ${e.message ?: "unknown error"}")
                }
            }
        }
    }

    // Personal-library handlers, shared by compact + expanded WorkDetail.
    val libraryUpsert: (String, UpsertLibraryRequest) -> Unit = { workId, req ->
        LibraryStatusSetStatus.entries.firstOrNull { it.wire == req.status }?.let { telemetry.track(Events.libraryStatusSet(it)) }
        scope.launch {
            try {
                library.upsert(workId, req)
            } catch (e: Throwable) {
                e.rethrowIfCancelled()
                snackbar.showSnackbar("Couldn’t update your library. ${api.describe(e)}")
            }
        }
    }
    val libraryRemove: (String) -> Unit = { workId ->
        scope.launch {
            try {
                library.remove(workId)
                snackbar.showSnackbar("Removed from your library.")
            } catch (e: Throwable) {
                e.rethrowIfCancelled()
                snackbar.showSnackbar("Couldn’t remove. ${api.describe(e)}")
            }
        }
    }

    // Moderator takedowns, shared by compact + expanded WorkDetail. Null for
    // everyone else, which hides the Remove affordances.
    val removeWork: ((Work, RemovalRequest) -> Unit)? = if (auth.isModerator) {
        { work, removal ->
            scope.launch {
                try {
                    works.removeWork(work.id, removal)
                    // Leave the now-404 detail screen.
                    if (nav.current.let { it is Screen.WorkDetail && it.workId == work.id }) {
                        if (!nav.pop()) nav.replace(Screen.Browse)
                    }
                    snackbar.showSnackbar("Removed “${work.title}”.")
                } catch (e: Throwable) {
                    e.rethrowIfCancelled()
                    snackbar.showSnackbar("Couldn’t remove. ${api.describe(e)}")
                }
            }
        }
    } else null
    val removeEdition: ((Work, Edition, RemovalRequest) -> Unit)? = if (auth.isModerator) {
        { work, ed, removal ->
            scope.launch {
                try {
                    works.removeEdition(work.id, ed.id, removal)
                    snackbar.showSnackbar("Removed the ${ed.format.uppercase()} edition.")
                } catch (e: Throwable) {
                    e.rethrowIfCancelled()
                    snackbar.showSnackbar("Couldn’t remove. ${api.describe(e)}")
                }
            }
        }
    } else null

    // Sheets (Upload, Suggest edit) open over the screen they were opened
    // from, which stays where it was underneath.
    val top = nav.current
    val sheet = top is Screen.Upload || top is Screen.EditWork
    val depth = nav.entries.size - if (sheet) 1 else 0
    val base = if (sheet) nav.entries.getOrNull(nav.entries.size - 2) ?: Screen.Browse else top

    // Screens keep their scroll positions while they're in the stack, so
    // going back lands where you left; top-level destinations keep theirs
    // for good, like tabs. Popped screens' states are dropped.
    val saved = rememberSaveableStateHolder()
    val savedKeys = remember { mutableSetOf<String>() }
    val stack = nav.entries.toList()
    LaunchedEffect(stack) {
        val live = stack.mapTo(HashSet()) { stateKey(it) }
        savedKeys.filter { it !in live && it !in TopLevelKeys }.forEach {
            saved.removeState(it)
            savedKeys.remove(it)
        }
        savedKeys += live
    }

    // One screen's content, without the navigation bar or rail around it.
    val screen: @Composable (Screen, Boolean) -> Unit = { s, expanded ->
        when {
            // Wide: the catalog and the open book share one list/detail
            // layout, so picking another book keeps the list where it is.
            expanded && (s == Screen.Browse || s is Screen.WorkDetail) -> ListDetailLayout(
                onAuthorClick = searchAuthor,
                nav = nav,
                auth = auth,
                works = works,
                library = library,
                selectedWorkId = (s as? Screen.WorkDetail)?.workId,
                onSelect = { w ->
                    if (nav.current != Screen.Browse) nav.replace(Screen.Browse)
                    nav.push(Screen.WorkDetail(w.id))
                },
                onRead = readEdition,
                onReadFromLibrary = readFromLibrary,
                extraHeader = verifyBanner,
                onDownload = downloadEdition,
                books = books,
                keeping = keeping,
                onKeepOffline = keepOffline,
                onRemoveFromDevice = removeFromDevice,
                onLibraryUpsert = libraryUpsert,
                onLibraryRemove = libraryRemove,
                onRemoveWork = removeWork,
                onRemoveEdition = removeEdition,
            )

            s == Screen.Browse -> DataDrivenBrowse(
                works = works,
                onWorkClick = { nav.push(Screen.WorkDetail(it.id)) },
                onUploadClick = { nav.push(Screen.Upload()) },
                compact = true,
                header = stackHeaders(verifyBanner, continueReadingHeader(library, readFromLibrary)),
                libraryWorkIds = library.workIds,
                onDevice = onDevice,
            )

            s is Screen.WorkDetail -> {
                // Ensure we have an editions-populated copy of this work, plus
                // the caller's library entry for the controls.
                LaunchedEffect(s.workId) {
                    works.refreshOne(s.workId)
                    if (auth.isAuthenticated) library.loadEntry(s.workId)
                }
                val work = works.find(s.workId)
                if (work == null) {
                    NotFound()
                } else {
                    WorkDetailScreen(
                        work = work,
                        onBack = { nav.pop() },
                        onAddEdition = { nav.push(Screen.Upload(workId = s.workId)) },
                        onRead = { ed -> readEdition(work, ed) },
                        onDownload = { ed -> downloadEdition(work, ed) },
                        savedBooks = books.books,
                        keeping = keeping,
                        onKeepOffline = if (books.enabled) { ed, keep -> keepOffline(work, ed, keep) } else null,
                        onRemoveFromDevice = if (books.enabled) { ed -> removeFromDevice(work, ed) } else null,
                        onSuggestEdit = { nav.push(Screen.EditWork(s.workId)) },
                        onAuthorClick = searchAuthor,
                        libraryEnabled = auth.isAuthenticated,
                        libraryEntry = library.entryFor(s.workId),
                        onLibraryUpsert = { req -> libraryUpsert(s.workId, req) },
                        onLibraryRemove = { libraryRemove(s.workId) },
                        onRemoveWork = removeWork?.let { rm -> { removal -> rm(work, removal) } },
                        onRemoveEdition = removeEdition?.let { rm -> { ed, removal -> rm(work, ed, removal) } },
                    )
                }
            }

            s == Screen.Auth -> AuthGateScreen(
                api = api,
                sessionExpired = expiredNotice,
                passwordChanged = passwordChanged,
                onForgotPassword = { email -> nav.push(Screen.ForgotPassword(email)) },
                serverUrl = serverUrl,
                onServerUrlChange = onServerUrlChange,
                onAuthenticated = { session ->
                    expiredNotice = false
                    passwordChanged = false
                    library.signingIn()         // hold queued changes until we know who this is
                    auth.signIn(session)        // suspends until token persisted
                    works.refresh()             // pull initial list
                    nav.replace(Screen.Browse)
                },
            )

            s is Screen.Preview -> {
                val entry = library.entryFor(s.workId)
                // Opened once per book: later progress (ours) mustn't re-offer.
                val (resumeAt, elsewhere) = remember(s.editionId) { library.resumePoint(s.workId) }
                ReaderScreen(
                    api = api,
                    editionId = s.editionId,
                    workId = s.workId,
                    format = s.format,
                    title = s.title,
                    authors = s.authors,
                    resumeFrom = resumeAt,
                    elsewhere = elsewhere,
                    finished = entry?.status == LibraryStatus.Read,
                    rating = entry?.rating,
                    prefs = readerPrefs,
                    onPrefsChange = setReaderPrefs,
                    onRate = { stars -> libraryUpsert(s.workId, UpsertLibraryRequest(rating = stars)) },
                    // Progress saves in the background; a failed save just
                    // means the next one carries it.
                    onProgress = { pos ->
                        scope.launch {
                            catchingNonCancel { library.upsert(s.workId, progressUpdate(library.entryFor(s.workId), pos)) }
                        }
                    },
                    onMarkRead = {
                        libraryUpsert(s.workId, UpsertLibraryRequest(status = LibraryStatus.Read))
                        scope.launch { snackbar.showSnackbar("Marked “${s.title}” as read.") }
                    },
                    onClose = { nav.pop() },
                    layouts = readerLayouts,
                    books = books,
                )
            }

            s == Screen.ContributionQueue -> {
                LaunchedEffect(Unit) { contributions.refresh() }
                ContributionQueueScreen(
                    state = contributions,
                    isWide = expanded,
                    onOpenWork = { workId -> openWork(nav, workId, expanded) },
                    loadWork = loadWork,
                )
            }

            s == Screen.Library -> {
                LaunchedEffect(Unit) { library.refresh() }
                LibraryScreen(
                    state = library,
                    isWide = expanded,
                    onBack = null,
                    onOpenWork = { workId -> openWork(nav, workId, expanded) },
                    onRead = readFromLibrary,
                    onBrowse = { nav.replace(Screen.Browse) },
                )
            }

            s == Screen.ForYou -> {
                LaunchedEffect(Unit) { recs.refresh() }
                ForYouScreen(
                    state = recs,
                    isWide = expanded,
                    onBack = null,
                    onOpenWork = { workId ->
                        telemetry.track(Events.recommendationOpened())
                        openWork(nav, workId, expanded)
                    },
                    onDismiss = { workId ->
                        telemetry.track(Events.recommendationDismissed())
                        dismissRec(workId)
                    },
                    onWantToRead = { workId ->
                        telemetry.track(Events.recommendationSaved())
                        libraryUpsert(workId, UpsertLibraryRequest(status = LibraryStatus.Want))
                    },
                    isInLibrary = { workId -> library.entryFor(workId) != null },
                    onUpload = { nav.push(Screen.Upload()) },
                )
            }

            s is Screen.ForgotPassword -> ForgotPasswordScreen(
                api = api,
                initialEmail = s.email,
                onBack = { if (!nav.pop()) nav.replace(Screen.Auth) },
                onHaveCode = { code -> nav.push(Screen.ResetPassword(code)) },
            )

            s is Screen.ResetPassword -> ResetPasswordScreen(
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

            s is Screen.VerifyEmail -> VerifyEmailScreen(
                api = api,
                token = s.token,
                onVerified = { if (auth.isAuthenticated) auth.reloadUser() },
                onContinue = { nav.replace(if (auth.isAuthenticated) Screen.Browse else Screen.Auth) },
                onResend = if (auth.isAuthenticated) ({ api.requestEmailVerification() }) else null,
            )

            s == Screen.Settings -> SettingsScreen(
                user = auth.user,
                serverUrl = serverUrl,
                onServerUrlChange = onServerUrlChange,
                prefs = readerPrefs,
                onPrefsChange = setReaderPrefs,
                onSignOut = logout,
                onSignOutEverywhere = logoutEverywhere,
                books = books,
                telemetry = telemetry.consent,
                onTelemetryChange = { crashes, usage -> scope.launch { telemetry.setConsent(crashes, usage) } },
                onBack = null,
            )

            // Sheets never become the base screen.
            else -> Unit
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val expanded = maxWidth.value >= EXPANDED_DP
        val reduce = LocalReduceMotion.current
        val shift = with(LocalDensity.current) { Motion.SHIFT_DP.dp.roundToPx() }

        // Deeper into the stack slides forward, back out slides back, and
        // peers (tabs, signing in) fade through. Screens that share the
        // navigation bar or rail keep it still: only their content changes.
        AnimatedContent(
            targetState = NavEntry(base, depth, expanded),
            contentKey = { windowKey(it.screen, it.expanded) },
            transitionSpec = {
                when {
                    // A resize across the breakpoint re-lays the window out; nothing moved.
                    initialState.expanded != targetState.expanded -> Motion.none
                    targetState.depth > initialState.depth -> Motion.sharedAxisX(forward = true, shift, reduce)
                    targetState.depth < initialState.depth -> Motion.sharedAxisX(forward = false, shift, reduce)
                    else -> Motion.fadeThrough(reduce)
                }
            },
            label = "screen",
        ) { entry ->
            val s = entry.screen
            val pane = @Composable {
                Column(Modifier.fillMaxSize()) {
                OfflineStrip(
                    visible = !api.reachable && auth.isAuthenticated,
                    waiting = library.pending.size,
                    onRetry = { scope.launch { api.ping() } },
                )
                Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = s,
                    contentKey = { paneKey(it, entry.expanded) },
                    transitionSpec = { Motion.fadeThrough(reduce) },
                    label = "pane",
                ) { p ->
                    saved.SaveableStateProvider(paneKey(p, entry.expanded)) { screen(p, entry.expanded) }
                }
                }
                }
            }
            // Upload is a destination of its own on the rail.
            val selected = if (nav.current is Screen.Upload) nav.current else s
            when (chromeOf(s, entry.expanded)) {
                Chrome.Rail -> ExpandedFrame(selected, nav = nav, auth = auth, contributions = contributions, content = pane)
                Chrome.Bar -> CompactFrame(selected, nav = nav, auth = auth, contributions = contributions, content = pane)
                Chrome.None -> saved.SaveableStateProvider(paneKey(s, entry.expanded)) { screen(s, entry.expanded) }
            }
        }

        when (top) {
            is Screen.EditWork -> {
                LaunchedEffect(top.workId) { works.refreshOne(top.workId) }
                works.find(top.workId)?.let { work ->
                    EditWorkSheet(
                        work = work,
                        isDesktop = expanded,
                        onDismiss = { nav.pop() },
                        // The sheet shows its own "Sent for review" state.
                        onSubmit = { patch -> api.submitContribution(work.id, patch) },
                        describeError = api::describe,
                    )
                }
            }

            is Screen.Upload -> UploadSheet(
                mode = if (top.workId == null) UploadMode.NewWork else UploadMode.AddEdition,
                isDesktop = expanded,
                target = top.workId?.let { works.find(it) },
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
                    val kind = if (submission is UploadSubmission.NewWork) UploadFinishedKind.NewWork else UploadFinishedKind.AddEdition
                    try {
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
                                        runCatching { api.deleteWork(created.id, RemovalRequest("Upload failed; discarding the empty work")) }
                                    }
                                    throw e
                                } finally {
                                    withContext(NonCancellable) { works.refresh() }
                                }
                                created.id
                            }
                            is UploadSubmission.AddEdition -> {
                                val wid = top.workId
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
                        }.also { telemetry.track(Events.uploadFinished(kind, UploadFinishedResult.Accepted)) }
                    } catch (e: Throwable) {
                        e.rethrowIfCancelled()
                        telemetry.track(Events.uploadFinished(kind, uploadResult(e)))
                        throw e
                    }
                },
            )

            else -> Unit
        }
    }
}

/** How a failed upload ended, for usage statistics. */
private fun uploadResult(e: Throwable): UploadFinishedResult = when {
    e is DuplicateEditionException -> UploadFinishedResult.Duplicate
    e is ApiException && e.status == 429 -> UploadFinishedResult.Quota
    e is ApiException && e.status in 400..499 -> UploadFinishedResult.Rejected
    else -> UploadFinishedResult.Failed
}

private fun screenFor(link: DeepLink): Screen = when (link) {
    is DeepLink.ResetPassword -> Screen.ResetPassword(link.token)
    is DeepLink.VerifyEmail -> Screen.VerifyEmail(link.token)
}
