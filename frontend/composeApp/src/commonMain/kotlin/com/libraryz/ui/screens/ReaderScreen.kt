package com.libraryz.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.key
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.libraryz.data.BookCache
import com.libraryz.data.BookInfo
import com.libraryz.data.BookLayout
import com.libraryz.data.BookLayouts
import com.libraryz.data.PageLayout
import com.libraryz.data.PagedReader
import com.libraryz.data.Reader
import com.libraryz.data.ReadingPosition
import com.libraryz.data.Spreads
import com.libraryz.data.TEXT_POSITIONS
import com.libraryz.data.TextReader
import com.libraryz.data.UserBook
import com.libraryz.data.api.describe
import com.libraryz.data.api.ApiClient
import com.libraryz.data.api.rethrowIfCancelled
import com.libraryz.data.openReader
import com.libraryz.data.resumePage
import com.libraryz.theme.LibraryZ
import com.libraryz.theme.LibraryZTheme
import com.libraryz.theme.ReadingTheme
import com.libraryz.ui.LocalFullscreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val SWIPE_THRESHOLD_PX = 80f
private const val WIDE_DP = 640

/** Text sizes (sp) the reader offers for flowing text. */
val ReaderTextSizes = 15..24

enum class LineSpacing(val label: String, val factor: Float) {
    Compact("Compact", 1.5f),
    Comfortable("Comfortable", 1.7f),
    Airy("Airy", 1.9f),
}

enum class ColumnWidth(val label: String, val max: Dp) {
    Narrow("Narrow", 520.dp),
    Medium("Medium", 640.dp),
    Wide("Wide", 760.dp),
}

/**
 * How flowing text is set. Lives above the reader so it carries from book to
 * book, and is saved on the device ([ReaderPrefsStore]).
 */
data class ReaderPrefs(
    val textSize: Int = 18,
    val theme: ReadingTheme = ReadingTheme.Light,
    val spacing: LineSpacing = LineSpacing.Comfortable,
    val width: ColumnWidth = ColumnWidth.Medium,
    /** Use the fonts an EPUB embeds (its @font-face rules) where it asks for them; off = the reader's serif throughout. */
    val bookFonts: Boolean = true,
)

/**
 * Full-screen reader. Opens at [resumeFrom]'s saved position and reports
 * where the reader is via [onProgress] (debounced, plus once on close), so
 * the caller can keep the library entry current without a manual slider.
 * Nothing is reported until the reader actually moves, so peeking at a
 * book doesn't add it to the library.
 *
 * Paged: ←/→, PgUp/PgDn, Space, Home/End, tap the left/right third of the
 * page (the middle shows/hides the bars, as does F), swipe, or drag the
 * scrubber; turning past the last page shows the end card. Text: scroll,
 * PgUp/PgDn/Space/←/→, Home/End; the Aa panel sets size, theme, spacing
 * and width. Esc closes.
 *
 * Both readers can show two pages side by side ([PageLayout], saved per
 * book in [layouts]); see [autoSpread] for when Auto does.
 *
 * A book saved in [books] opens from the device without asking the server;
 * otherwise it's downloaded and then kept there in the background.
 *
 * When another device has read further ([elsewhere]), the book opens where
 * this device left off and offers "Go there" rather than moving silently.
 */
@Composable
fun ReaderScreen(
    api: ApiClient,
    editionId: String,
    workId: String,
    format: String,
    title: String,
    authors: String?,
    resumeFrom: UserBook?,
    finished: Boolean,
    rating: Int?,
    prefs: ReaderPrefs,
    onPrefsChange: (ReaderPrefs) -> Unit,
    onProgress: (ReadingPosition) -> Unit,
    onMarkRead: () -> Unit,
    onRate: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    layouts: BookLayouts = remember { BookLayouts() },
    books: BookCache = remember { BookCache() },
    // Newer progress from another device: [resumeFrom] is this device's own
    // spot, and the reader offers to go here instead of jumping.
    elsewhere: UserBook? = null,
) {
    var bytes by remember(editionId) { mutableStateOf<ByteArray?>(null) }
    var loadError by remember(editionId) { mutableStateOf<String?>(null) }
    // A failed download can be retried; a file that won't open can't.
    var downloadFailed by remember(editionId) { mutableStateOf(false) }
    var attempt by remember(editionId) { mutableStateOf(0) }

    LaunchedEffect(editionId, attempt) {
        try {
            bytes = books.openOrDownload(editionId, BookInfo(format, workId, title, authors)) {
                api.downloadEditionFile(editionId)
            }
        } catch (e: Throwable) {
            e.rethrowIfCancelled()
            loadError = api.describe(e)
            downloadFailed = true
        }
    }

    val payload = bytes
    var reader by remember(editionId) { mutableStateOf<Reader?>(null) }
    LaunchedEffect(payload, format) {
        reader?.close()
        reader = if (payload == null) {
            null
        } else {
            try {
                openReader(payload, format)
            } catch (e: Throwable) {
                e.rethrowIfCancelled()
                loadError = e.message ?: "Failed to open edition"
                null
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { reader?.close() }
    }

    // Snapshot the saved entry once: our own progress reports update it
    // while we read, and re-resuming from those would fight the reader.
    val resumeEntry = remember(editionId) { resumeFrom }
    val pageCount = when (val r = reader) {
        is PagedReader -> r.pageCount
        is TextReader -> TEXT_POSITIONS
        null -> 0
    }
    val startPage = remember(reader) { resumePage(resumeEntry, pageCount) }
    // Paged: 0..pageCount, where pageCount is the end card after the last page.
    var page by remember(reader) { mutableStateOf(startPage) }
    // "Go there" re-opens the view at the new spot (the text view only reads
    // its start position when it opens).
    var jump by remember(reader) { mutableStateOf(0) }
    var offerElsewhere by remember(editionId) { mutableStateOf(elsewhere != null) }
    val textScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var chrome by remember { mutableStateOf(true) }
    var settingsOpen by remember { mutableStateOf(false) }
    // Keys go to the reader. Hiding the bars or closing the settings panel
    // can remove the control that had focus (e.g. the fullscreen button just
    // clicked), which would leave the arrows with nowhere to go; take focus
    // back each time. Not while settings are open: they may be a sheet.
    LaunchedEffect(reader, chrome, settingsOpen) {
        if (!settingsOpen) runCatching { focus.requestFocus() }
    }
    // Auto / Single / Two pages and the pairing, remembered per book.
    var bookLayout by remember(editionId) { mutableStateOf(BookLayout()) }
    LaunchedEffect(editionId) { bookLayout = layouts.get(editionId) }
    val setLayout: (BookLayout) -> Unit = { l ->
        bookLayout = l
        scope.launch { layouts.set(editionId, l) }
    }
    // PagedReader isn't thread-safe: rendering and the page-shape scan share this.
    val readerLock = remember(reader) { Mutex() }
    // Pages wider than tall stand alone in a spread. Scanned the first time a
    // spread is shown; until then, pages pair as if all were portrait.
    var landscape by remember(reader) { mutableStateOf<Set<Int>?>(null) }
    val textPager = remember { TextPager() }
    val fullscreen = LocalFullscreen.current
    // Full screen is for reading: leaving the book leaves it.
    DisposableEffect(fullscreen) {
        onDispose { if (fullscreen.isOn) fullscreen.set(false) }
    }
    // Immersive: full screen hides the reader's bars (tap or F brings them
    // back), and leaving it shows them again.
    LaunchedEffect(fullscreen.isOn) { chrome = !fullscreen.isOn }

    val end = @Composable {
        // On a text book the card sits on the reading theme's page, not the app's, so it follows that.
        val dark = if (reader is TextReader) prefs.theme == ReadingTheme.Dark else LibraryZ.tokens.dark
        LibraryZTheme(darkTheme = dark) {
            EndCard(
                title = title,
                authors = authors,
                finished = finished,
                rating = rating,
                onRate = onRate,
                onMarkRead = onMarkRead,
                onBack = onClose,
            )
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth.value >= WIDE_DP
        // Two pages need at least a medium window; below that the saved
        // choice is kept but one page shows.
        val spread = reader != null && wide && when (bookLayout.layout) {
            PageLayout.Single -> false
            PageLayout.Two -> true
            PageLayout.Auto -> autoSpread(maxWidth, maxHeight) &&
                (reader !is TextReader || twoColumnsFit(maxWidth, prefs.textSize))
        }
        val spreads = remember(pageCount, bookLayout.pairFromFirst, landscape) {
            Spreads(pageCount, coverAlone = !bookLayout.pairFromFirst, alone = landscape.orEmpty())
        }
        val r0 = reader
        if (spread && r0 is PagedReader) {
            LaunchedEffect(r0) {
                if (landscape == null) {
                    landscape = readerLock.withLock { runCatching { r0.landscapePages() }.getOrDefault(emptySet()) }
                }
            }
        }
        // In a spread, the furthest visible page is where the reader is.
        val shownPage = page.coerceAtMost(pageCount - 1)
        val position = when {
            pageCount <= 0 -> null
            spread && reader is PagedReader ->
                ReadingPosition(spreads.at(page).filter { it < pageCount }.maxOrNull() ?: (pageCount - 1), pageCount)
            else -> ReadingPosition(shownPage, pageCount)
        }
        val textSpread = spread && reader is TextReader

        ProgressReporter(
            key = reader,
            position = position,
            opened = if (pageCount > 0) ReadingPosition(startPage, pageCount) else null,
            onProgress = onProgress,
        )

        fun turn(delta: Int) {
            if (reader !is PagedReader) return
            page = if (spread) spreads.turn(page, delta) else (page + delta).coerceIn(0, pageCount)
        }

        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        // Esc steps out of full screen before it closes the book.
                        Key.Escape -> {
                            if (fullscreen.isOn) fullscreen.set(false) else onClose()
                            return@onPreviewKeyEvent true
                        }
                        Key.F -> { chrome = !chrome; return@onPreviewKeyEvent true }
                        Key.F11 -> {
                            if (fullscreen.isSupported) fullscreen.set(!fullscreen.isOn)
                            return@onPreviewKeyEvent true
                        }
                    }
                    when (reader) {
                        is PagedReader -> when (e.key) {
                            Key.DirectionRight, Key.DirectionDown, Key.PageDown, Key.Spacebar -> { turn(1); true }
                            Key.DirectionLeft, Key.DirectionUp, Key.PageUp -> { turn(-1); true }
                            Key.MoveHome -> { page = 0; true }
                            Key.MoveEnd -> { page = pageCount - 1; true }
                            else -> false
                        }
                        is TextReader -> if (textSpread) {
                            when (e.key) {
                                Key.PageDown, Key.Spacebar, Key.DirectionRight, Key.DirectionDown -> { textPager.turn(1); true }
                                Key.PageUp, Key.DirectionLeft, Key.DirectionUp -> { textPager.turn(-1); true }
                                Key.MoveHome -> { textPager.jump(false); true }
                                Key.MoveEnd -> { textPager.jump(true); true }
                                else -> false
                            }
                        } else {
                            val screen = textScroll.viewportSize * 0.9f
                            when (e.key) {
                                Key.PageDown, Key.Spacebar, Key.DirectionRight -> { scope.launch { textScroll.animateScrollBy(screen) }; true }
                                Key.PageUp, Key.DirectionLeft -> { scope.launch { textScroll.animateScrollBy(-screen) }; true }
                                Key.MoveHome -> { scope.launch { textScroll.animateScrollTo(0) }; true }
                                Key.MoveEnd -> { scope.launch { textScroll.animateScrollTo(textScroll.maxValue) }; true }
                                else -> false
                            }
                        }
                        null -> false
                    }
                },
        ) {
            key(jump) {
            when (val r = reader) {
                is TextReader -> TextReaderLayout(
                    reader = r,
                    title = title,
                    authors = authors,
                    wide = wide,
                    prefs = prefs,
                    onPrefsChange = onPrefsChange,
                    settingsOpen = settingsOpen,
                    onSettingsOpen = { settingsOpen = it },
                    scroll = textScroll,
                    // Where to open: the saved spot at first, and the current
                    // one when switching between one and two pages.
                    startPosition = page,
                    percent = position?.percent ?: 0,
                    onPosition = { page = it },
                    spread = textSpread,
                    layout = bookLayout.layout,
                    onLayoutChange = { setLayout(bookLayout.copy(layout = it)) },
                    pager = textPager,
                    chrome = chrome,
                    onToggleChrome = { chrome = !chrome },
                    onClose = onClose,
                    end = end,
                )
                else -> PagedReaderLayout(
                    reader = r as PagedReader?,
                    loadError = loadError,
                    onRetry = if (downloadFailed) ({ loadError = null; downloadFailed = false; attempt++ }) else null,
                    title = title,
                    authors = authors,
                    wide = wide,
                    page = page,
                    pageCount = pageCount,
                    spread = spread,
                    spreads = spreads,
                    bookLayout = bookLayout,
                    onLayoutChange = setLayout,
                    readerLock = readerLock,
                    chrome = chrome,
                    onToggleChrome = { chrome = !chrome },
                    onSeek = { page = it },
                    onTurn = ::turn,
                    onClose = onClose,
                    end = end,
                )
            }
            }
            val there = elsewhere
            if (offerElsewhere && there != null && reader != null && pageCount > 0) {
                ElsewhereOffer(
                    label = elsewhereLabel(there, reader is PagedReader, pageCount),
                    onGo = {
                        page = resumePage(there, pageCount)
                        jump++
                        offerElsewhere = false
                    },
                    onDismiss = { offerElsewhere = false },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 96.dp),
                )
            }
        }
    }
}

/** "p. 212" for a paged book saved against the same page count, else "68%". */
internal fun elsewhereLabel(entry: UserBook, paged: Boolean, pageCount: Int): String =
    if (paged && entry.totalPages == pageCount && entry.currentPage > 0) "on p. ${entry.currentPage}"
    else "at ${entry.progressPercent}%"

/** "You're on p. 212 on another device — Go there", for a while after opening. */
@Composable
private fun ElsewhereOffer(label: String, onGo: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) {
        delay(12_000)
        onDismiss()
    }
    Surface(
        modifier = modifier.widthIn(max = 480.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("You’re $label on another device", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            TextButton(onClick = onGo) { Text("Go there", color = MaterialTheme.colorScheme.inversePrimary) }
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Stay here") }
        }
    }
}

/**
 * Whether Auto shows two pages: on an expanded (≥ 840dp) window that is
 * landscape, at least 1.2× wider than tall. Below 840dp each page of a
 * spread would be smaller than one page alone.
 */
internal fun autoSpread(width: Dp, height: Dp): Boolean = width >= 840.dp && width >= height * 1.2f

/**
 * The text reader's extra test for Auto: two columns of at least 26 em (about
 * 60 characters a line) fit at [textSize], so large text falls back to one
 * column by itself.
 */
internal fun twoColumnsFit(width: Dp, textSize: Int): Boolean = textColumnWidth(width) >= (26 * textSize).dp

/** One column of a two-page text spread in a [width]-wide window: half, less the page margins. */
internal fun textColumnWidth(width: Dp): Dp = (width - 1.dp) / 2 - TEXT_PAGE_OUTER - TEXT_PAGE_INNER

internal val TEXT_PAGE_OUTER = 48.dp
internal val TEXT_PAGE_INNER = 40.dp

/**
 * Reports [position] to [onProgress] a second after it settles, and once
 * more on dispose if the last move wasn't reported yet. Positions equal to
 * [opened] are never reported (opening a book isn't progress).
 */
@Composable
private fun ProgressReporter(
    key: Any?,
    position: ReadingPosition?,
    opened: ReadingPosition?,
    onProgress: (ReadingPosition) -> Unit,
) {
    val report by rememberUpdatedState(onProgress)
    val latest by rememberUpdatedState(position)
    var reported by remember(key) { mutableStateOf(opened) }
    LaunchedEffect(position) {
        if (position == null || position == reported) return@LaunchedEffect
        delay(1000)
        report(position)
        reported = position
    }
    DisposableEffect(key) {
        onDispose {
            // Only a loaded book's last move counts. The loading phase
            // (key == null) is disposed when the book arrives, and reporting
            // then would record merely opening it.
            if (key == null) return@onDispose
            val last = latest
            if (last != null && last != reported) report(last)
        }
    }
}
