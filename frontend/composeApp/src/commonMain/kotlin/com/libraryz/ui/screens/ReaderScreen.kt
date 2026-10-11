package com.libraryz.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.libraryz.data.BookLayout
import com.libraryz.data.BookLayouts
import com.libraryz.data.Chapter
import com.libraryz.data.PageLayout
import com.libraryz.data.PagedReader
import com.libraryz.data.Reader
import com.libraryz.data.ReadingPosition
import com.libraryz.data.Spreads
import com.libraryz.data.TEXT_POSITIONS
import com.libraryz.data.TextReader
import com.libraryz.data.UserBook
import com.libraryz.data.api.ApiClient
import com.libraryz.data.authorsShort
import com.libraryz.data.openReader
import com.libraryz.data.pageBreaks
import com.libraryz.data.pagesLabel
import com.libraryz.data.pagesLeftLabel
import com.libraryz.data.resumePage
import com.libraryz.theme.LibraryZ
import com.libraryz.theme.LocalReduceMotion
import com.libraryz.theme.Motion
import com.libraryz.theme.dissolveFrom
import com.libraryz.theme.movementSpec
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.graphics.TransformOrigin
import com.libraryz.theme.LibraryZTheme
import com.libraryz.theme.ReadingTheme
import com.libraryz.ui.LocalFullscreen
import com.libraryz.ui.components.StarRating
import com.libraryz.ui.components.bookBoxes
import com.libraryz.ui.components.prefetchImages
import com.libraryz.ui.components.rememberSizedText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.roundToInt

private const val SWIPE_THRESHOLD_PX = 80f
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
 */
@Composable
fun ReaderScreen(
    api: ApiClient,
    editionId: String,
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
) {
    var bytes by remember(editionId) { mutableStateOf<ByteArray?>(null) }
    var loadError by remember(editionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(editionId) {
        try {
            bytes = api.downloadEdition(editionId)
        } catch (e: Throwable) {
            loadError = e.message ?: "Failed to load edition"
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
private fun textColumnWidth(width: Dp): Dp = (width - 1.dp) / 2 - TEXT_PAGE_OUTER - TEXT_PAGE_INNER

private val TEXT_PAGE_OUTER = 48.dp
private val TEXT_PAGE_INNER = 40.dp

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

/* ---------------- Paged (PDF) ---------------- */

@Composable
private fun PagedReaderLayout(
    reader: PagedReader?,
    loadError: String?,
    title: String,
    authors: String?,
    wide: Boolean,
    page: Int,
    pageCount: Int,
    spread: Boolean,
    spreads: Spreads,
    bookLayout: BookLayout,
    onLayoutChange: (BookLayout) -> Unit,
    readerLock: Mutex,
    chrome: Boolean,
    onToggleChrome: () -> Unit,
    onSeek: (Int) -> Unit,
    onTurn: (Int) -> Unit,
    onClose: () -> Unit,
    end: @Composable () -> Unit,
) {
    val shownPage = page.coerceAtMost((pageCount - 1).coerceAtLeast(0))
    // Slots on screen; [pageCount] is the end card.
    val slots = when {
        pageCount <= 0 -> emptyList()
        spread -> spreads.at(page)
        else -> listOf(page.coerceIn(0, pageCount))
    }
    val pages = slots.filter { it < pageCount }
    val atEndCard = reader != null && pages.isEmpty() && slots.isNotEmpty()
    val pageText = pagesLabel(slots, pageCount)
    val reduce = LocalReduceMotion.current
    Column(Modifier.fillMaxSize().background(LibraryZ.tokens.readerBackdrop)) {
        // The bars slide away, and the page grows into their room.
        AnimatedVisibility(
            chrome,
            enter = Motion.reveal(reduce) + if (reduce) EnterTransition.None else slideInVertically(movementSpec()) { -it },
            exit = Motion.conceal(reduce) + if (reduce) ExitTransition.None else slideOutVertically(movementSpec()) { -it },
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(start = 4.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close reader")
                    }
                    if (wide) {
                        Row(
                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            val byline = authorsShort(authors)
                            if (byline.isNotEmpty()) {
                                Text(byline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                        Text(pageText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (reader != null) {
                            SpreadToggle(
                                spread = spread,
                                onChange = { two -> onLayoutChange(bookLayout.copy(layout = if (two) PageLayout.Two else PageLayout.Single)) },
                                modifier = Modifier.padding(start = 12.dp),
                            )
                            LayoutMenu(bookLayout, onLayoutChange)
                        }
                    } else {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (pageText.isNotEmpty()) {
                                Text(pageText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    FullscreenButton(MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val phase = when {
                loadError != null -> PagedPhase.Error
                reader == null -> PagedPhase.Loading
                atEndCard -> PagedPhase.End
                else -> PagedPhase.Pages
            }
            Crossfade(phase, animationSpec = tween(Motion.MEDIUM, easing = Motion.Standard), label = "reader") { ph ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                ph == PagedPhase.Error -> Text(
                    "Couldn't load: $loadError",
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(24.dp),
                )
                ph == PagedPhase.Loading || reader == null -> CircularProgressIndicator()
                ph == PagedPhase.End -> Box(Modifier.padding(16.dp).widthIn(max = 440.dp)) { end() }
                else -> PageView(
                    reader = reader,
                    pages = pages,
                    // The end card takes the empty right-hand slot of the last spread.
                    endSlot = pageCount in slots,
                    lock = readerLock,
                    wide = wide,
                    onTurn = onTurn,
                    onToggleChrome = onToggleChrome,
                    end = end,
                )
            }
            }
            }
            PageCounter(
                visible = !chrome && reader != null && !atEndCard,
                text = "${pages.joinToString("–") { "${it + 1}" }} / $pageCount",
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        AnimatedVisibility(
            chrome && pageCount > 0,
            enter = Motion.reveal(reduce) + if (reduce) EnterTransition.None else slideInVertically(movementSpec()) { it },
            exit = Motion.conceal(reduce) + if (reduce) ExitTransition.None else slideOutVertically(movementSpec()) { it },
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(start = if (wide) 24.dp else 8.dp, end = if (wide) 24.dp else 8.dp, top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = { onTurn(-1) }, enabled = page > 0) {
                        Icon(Icons.Rounded.ChevronLeft, contentDescription = if (spread) "Previous two pages" else "Previous page")
                    }
                    // Drag freely; jump when released so we don't rasterize
                    // every page the thumb passes over. In a spread the value
                    // is the left page and a jump lands on whole spreads.
                    var scrub by remember(pageCount) { mutableStateOf<Float?>(null) }
                    val sliderPage = (pages.firstOrNull() ?: shownPage).toFloat()
                    Slider(
                        value = scrub ?: sliderPage,
                        onValueChange = { scrub = it },
                        onValueChangeFinished = {
                            scrub?.let { v ->
                                val target = v.roundToInt()
                                onSeek(if (spread) spreads.at(target).first() else target)
                            }
                            scrub = null
                        },
                        valueRange = 0f..(pageCount - 1).coerceAtLeast(1).toFloat(),
                        enabled = pageCount > 1,
                        modifier = Modifier.weight(1f).semantics {
                            stateDescription = pageText
                        },
                    )
                    scrub?.let {
                        val target = it.roundToInt()
                        val label = if (spread) {
                            spreads.at(target).filter { p -> p < pageCount }.joinToString("–") { p -> "${p + 1}" }
                        } else {
                            "${target + 1}"
                        }
                        Text("p. $label", style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = { onTurn(1) }, enabled = pageCount !in slots) {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = if (spread) "Next two pages" else "Next page")
                    }
                    if (wide) {
                        Text(
                            "F to hide",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(96.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The page number floating over the page while the bars are hidden. */
@Composable
private fun PageCounter(visible: Boolean, text: String, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible,
        enter = fadeIn(Motion.standard(Motion.MEDIUM)),
        exit = fadeOut(Motion.standard()),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.navigationBarsPadding().padding(bottom = 20.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/** Why a two-page text spread changed, which decides how it animates. */
private enum class SpreadChange { Forward, Back, Jump, Repage }

/** What the paged reader's main area shows. */
private enum class PagedPhase { Loading, Error, Pages, End }

/** Single page / two pages, as a two-button icon control. Picking one sets the book's layout. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpreadToggle(spread: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.width(112.dp)) {
        listOf(
            Triple(false, Icons.Rounded.Description, "Single page"),
            Triple(true, Icons.Rounded.AutoStories, "Two pages"),
        ).forEachIndexed { i, (two, icon, label) ->
            SegmentedButton(
                selected = spread == two,
                onClick = { onChange(two) },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
                icon = {},
                label = { Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp)) },
            )
        }
    }
}

/** Reader options: Layout (Auto · Single page · Two pages) and the pairing escape hatch. */
@Composable
private fun LayoutMenu(bookLayout: BookLayout, onChange: (BookLayout) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "Reader options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Layout",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            PageLayout.entries.forEach { l ->
                DropdownMenuItem(
                    text = { Text(l.label) },
                    leadingIcon = { RadioButton(selected = bookLayout.layout == l, onClick = null) },
                    onClick = { onChange(bookLayout.copy(layout = l)); open = false },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Pair from page 1") },
                leadingIcon = { Checkbox(checked = bookLayout.pairFromFirst, onCheckedChange = null) },
                onClick = { onChange(bookLayout.copy(pairFromFirst = !bookLayout.pairFromFirst)); open = false },
            )
        }
    }
}

/** A rendered page, or null [image] when the renderer failed on it. */
private class RenderedPage(val index: Int, val image: ImageBitmap?)

/** What's on screen: the rendered pages, and whether the end card takes the slot after them. */
private class RenderedSpread(val pages: List<RenderedPage>, val endSlot: Boolean) {
    val key: Pair<List<Int>, Boolean> get() = pages.map { it.index } to endSlot
}

/** A US Letter / A4-ish page shape, until a real page says otherwise. */
private const val DEFAULT_PAGE_ASPECT = 1.3f

@Composable
private fun PageView(
    reader: PagedReader,
    pages: List<Int>,
    endSlot: Boolean,
    lock: Mutex,
    wide: Boolean,
    onTurn: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    end: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Outer thirds turn, like an e-reader; the middle shows/hides the bars.
                detectTapGestures { offset ->
                    when {
                        offset.x < size.width / 3f -> onTurn(-1)
                        offset.x > size.width * 2f / 3f -> onTurn(1)
                        else -> onToggleChrome()
                    }
                }
            }
            .pointerInput(Unit) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        if (abs(dragged) > SWIPE_THRESHOLD_PX) onTurn(if (dragged < 0) 1 else -1)
                    },
                    onHorizontalDrag = { _, dx -> dragged += dx },
                )
            }
            .padding(if (wide) 28.dp else 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val slotCount = pages.size + if (endSlot) 1 else 0
        // A spread's pages share the width.
        val slot = maxWidth / slotCount.coerceAtLeast(1)
        val widthPx = with(LocalDensity.current) { slot.roundToPx().coerceAtMost(1600) }
        // Keep showing the previous pages until the next ones are rasterized,
        // so turning pages doesn't flash a spinner.
        var shown by remember(reader, widthPx) { mutableStateOf<RenderedSpread?>(null) }
        LaunchedEffect(reader, pages, endSlot, widthPx) {
            // One at a time: PagedReader isn't thread-safe. A page the
            // renderer chokes on (e.g. a broken font) shows a message instead
            // of escaping to the UI thread and taking the app down.
            shown = RenderedSpread(
                pages.map { i ->
                    val image = try {
                        lock.withLock { reader.renderPage(i, widthPx) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        null
                    }
                    RenderedPage(i, image)
                },
                endSlot,
            )
        }
        val current = shown
        if (current == null) {
            CircularProgressIndicator()
        } else {
            // Turning slides the new pages in from the side they come from;
            // a re-render of the same pages (a resize) just swaps.
            val reduce = LocalReduceMotion.current
            AnimatedContent(
                targetState = current,
                contentKey = { it.key },
                transitionSpec = {
                    val from = initialState.pages.firstOrNull()?.index ?: Int.MAX_VALUE
                    val to = targetState.pages.firstOrNull()?.index ?: Int.MAX_VALUE
                    if (from == to) Motion.crossfade()
                    else Motion.pageTurn(forward = to > from, reduce)
                },
                contentAlignment = Alignment.Center,
                label = "page turn",
            ) { spread ->
            val shown = spread.pages
            val endSlot = spread.endSlot
            // Height over width per slot; the end card takes its neighbour's shape.
            val aspects = shown.map { p -> p.image?.let { it.height.toFloat() / it.width } ?: DEFAULT_PAGE_ASPECT }
            val slots = if (endSlot) aspects + (aspects.lastOrNull() ?: DEFAULT_PAGE_ASPECT) else aspects
            // As tall as fits: the slots' widths at that height fill at most the width.
            val height = minOf(maxHeight, maxWidth / slots.sumOf { 1.0 / it }.toFloat())
            Row(
                modifier = Modifier.shadow(8.dp, RoundedCornerShape(2.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                shown.forEachIndexed { i, p ->
                    val box = Modifier.size(height / slots[i], height)
                    val image = p.image
                    if (image == null) {
                        Box(box.background(Color(0xFFFFFEFA)), contentAlignment = Alignment.Center) {
                            Text(
                                "Couldn't render page ${p.index + 1}",
                                color = Color(0xFF1B1C1A),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    } else {
                        Image(
                            bitmap = image,
                            contentDescription = "Page ${p.index + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = box.background(Color(0xFFFFFEFA)),
                        )
                    }
                }
                if (endSlot) {
                    Box(
                        Modifier.size(height / slots.last(), height)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // Centred while it fits, scrolling on a short window.
                        Box(Modifier.fillMaxWidth().heightIn(min = height).padding(8.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.widthIn(max = 440.dp)) { end() }
                        }
                    }
                }
            }
            }
        }
    }
}

/** Enters and leaves full screen; absent where the platform has none. */
@Composable
private fun FullscreenButton(tint: Color) {
    val fullscreen = LocalFullscreen.current
    if (!fullscreen.isSupported) return
    IconButton(onClick = { fullscreen.set(!fullscreen.isOn) }) {
        Icon(
            if (fullscreen.isOn) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
            contentDescription = if (fullscreen.isOn) "Exit full screen" else "Full screen",
            tint = tint,
        )
    }
}

/* ---------------- Flowing text ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextReaderLayout(
    reader: TextReader,
    title: String,
    authors: String?,
    wide: Boolean,
    prefs: ReaderPrefs,
    onPrefsChange: (ReaderPrefs) -> Unit,
    settingsOpen: Boolean,
    onSettingsOpen: (Boolean) -> Unit,
    scroll: ScrollState,
    startPosition: Int,
    percent: Int,
    onPosition: (Int) -> Unit,
    chrome: Boolean,
    onToggleChrome: () -> Unit,
    onClose: () -> Unit,
    end: @Composable () -> Unit,
    spread: Boolean,
    layout: PageLayout,
    onLayoutChange: (PageLayout) -> Unit,
    pager: TextPager,
) {
    val theme = prefs.theme
    var contentsOpen by remember { mutableStateOf(false) }
    val settings = @Composable {
        ReadingSettings(
            prefs, onPrefsChange,
            showWidth = wide,
            layout = layout.takeIf { wide },
            onLayoutChange = onLayoutChange,
            spread = spread,
            showBookFonts = reader.hasBookFonts,
        )
    }
    val reduce = LocalReduceMotion.current
    Box(Modifier.fillMaxSize().background(theme.background)) {
        Row(Modifier.fillMaxSize()) {
            // A new reading theme dissolves in over the old one.
            Column(Modifier.weight(1f).fillMaxHeight().dissolveFrom(theme.background)) {
                // The bars only fade: sliding them would re-page the book every frame.
                AnimatedVisibility(chrome, enter = fadeIn(Motion.standard(Motion.MEDIUM)), exit = fadeOut(Motion.standard())) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().height(if (wide) 64.dp else 56.dp)
                        .padding(horizontal = if (wide) 12.dp else 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close reader", tint = theme.muted)
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(title, style = MaterialTheme.typography.labelLarge, color = theme.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val byline = authorsShort(authors)
                        if (wide && byline.isNotEmpty()) {
                            Text(byline, style = MaterialTheme.typography.bodySmall, color = theme.muted, maxLines = 1)
                        }
                    }
                    if (reader.chapters.size >= 2) {
                        IconButton(onClick = { contentsOpen = true }) {
                            Icon(Icons.AutoMirrored.Rounded.List, contentDescription = "Contents", tint = theme.muted)
                        }
                    }
                    IconButton(onClick = { onSettingsOpen(!settingsOpen) }) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(if (wide && settingsOpen) theme.rule else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.TextFields, contentDescription = "Reading settings", tint = theme.muted)
                        }
                    }
                    FullscreenButton(theme.muted)
                }
                }
                // Thin full-width progress line.
                Box(Modifier.fillMaxWidth().height(2.dp).background(theme.rule)) {
                    Box(Modifier.fillMaxWidth(percent / 100f).fillMaxHeight().background(theme.muted))
                }
                if (spread) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        TextSpreadView(
                            reader = reader,
                            title = title,
                            prefs = prefs,
                            startPosition = startPosition,
                            percent = percent,
                            onPosition = onPosition,
                            pager = pager,
                            chrome = chrome,
                            onToggleChrome = onToggleChrome,
                            end = end,
                        )
                    }
                } else {
                    // A tap on the text shows or hides the bars (buttons in it, like
                    // the end card's, take their own taps first).
                    Box(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) { detectTapGestures { onToggleChrome() } }) {
                        TextView(
                            reader = reader,
                            pager = pager,
                            scroll = scroll,
                            prefs = prefs,
                            wide = wide,
                            startPosition = startPosition,
                            onPosition = onPosition,
                            end = end,
                        )
                    }
                }
                // Pages carry their own feet in a spread.
                AnimatedVisibility(chrome && !spread, enter = fadeIn(Motion.standard(Motion.MEDIUM)), exit = fadeOut(Motion.standard())) {
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().height(if (wide) 48.dp else 44.dp)
                        .padding(horizontal = if (wide) 32.dp else 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        authorsShort(authors),
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (wide) "← → to turn · $percent%" else "$percent%",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.muted,
                    )
                }
                }
            }
            // In two pages the panel floats, so opening it doesn't re-page the book.
            AnimatedVisibility(
                wide && settingsOpen && !spread,
                enter = fadeIn(Motion.standard(Motion.MEDIUM)) + if (reduce) EnterTransition.None else slideInHorizontally(movementSpec()) { it / 3 },
                exit = fadeOut(Motion.standard()) + if (reduce) ExitTransition.None else slideOutHorizontally(movementSpec()) { it / 3 },
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.width(340.dp).fillMaxHeight(),
                ) {
                    Column(
                        Modifier.statusBarsPadding().verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Reading settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onSettingsOpen(false) }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Close settings")
                            }
                        }
                        settings()
                    }
                }
            }
        }
        // The floating panel grows out of the settings button's corner.
        val fromCorner = TransformOrigin(1f, 0f)
        AnimatedVisibility(
            wide && settingsOpen && spread,
            enter = fadeIn(Motion.standard(Motion.MEDIUM)) +
                if (reduce) EnterTransition.None else scaleIn(movementSpec(), initialScale = 0.9f, transformOrigin = fromCorner),
            exit = fadeOut(Motion.standard()) +
                if (reduce) ExitTransition.None else scaleOut(movementSpec(), targetScale = 0.9f, transformOrigin = fromCorner),
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 64.dp, end = 64.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 6.dp,
                modifier = Modifier.width(360.dp),
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Reading settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onSettingsOpen(false) }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close settings")
                        }
                    }
                    settings()
                }
            }
        }
    }
    if (contentsOpen) {
        val here = pager.offset() ?: (startPosition.toFloat() / (TEXT_POSITIONS - 1) * reader.text.length).roundToInt()
        ContentsSheet(
            chapters = reader.chapters,
            current = reader.chapters.indexOfLast { it.offset <= here },
            onPick = { pager.goTo(it.offset); contentsOpen = false },
            onDismiss = { contentsOpen = false },
        )
    }
    if (!wide && settingsOpen) {
        ModalBottomSheet(
            onDismissRequest = { onSettingsOpen(false) },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                Modifier.padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("Reading settings", style = MaterialTheme.typography.titleMedium)
                settings()
            }
        }
    }
}

/**
 * Layout (wide screens), text size, theme swatches, line spacing, and column
 * width on wide screens, which only applies to one page and is dimmed in two.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingSettings(
    prefs: ReaderPrefs,
    onChange: (ReaderPrefs) -> Unit,
    showWidth: Boolean,
    layout: PageLayout?,
    onLayoutChange: (PageLayout) -> Unit,
    spread: Boolean,
    showBookFonts: Boolean,
) {
    if (layout != null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Layout", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PageLayout.entries.forEachIndexed { i, l ->
                    SegmentedButton(
                        selected = layout == l,
                        onClick = { onLayoutChange(l) },
                        shape = SegmentedButtonDefaults.itemShape(i, PageLayout.entries.size),
                        label = { Text(if (l == PageLayout.Single) "Single" else l.label, maxLines = 1) },
                    )
                }
            }
            if (layout == PageLayout.Auto) {
                Text(
                    "Auto shows two pages when two columns of at least 26 em fit at your text size. " +
                        "Now: ${if (spread) "two pages" else "one page"}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    TextSizeControl(prefs.textSize, onChange = { onChange(prefs.copy(textSize = it)) })
    if (showBookFonts) {
        Row(
            Modifier.fillMaxWidth().clickable { onChange(prefs.copy(bookFonts = !prefs.bookFonts)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Book's fonts", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Where this book uses fonts of its own. Off sets everything in the reader's serif.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = prefs.bookFonts, onCheckedChange = { onChange(prefs.copy(bookFonts = it)) })
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Theme", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReadingTheme.entries.forEach { t ->
                val selected = prefs.theme == t
                Surface(
                    onClick = { onChange(prefs.copy(theme = t)) },
                    color = t.background,
                    contentColor = t.ink,
                    shape = MaterialTheme.shapes.medium,
                    border = if (selected) {
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    },
                    modifier = Modifier.weight(1f).height(64.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("Aa", fontFamily = LibraryZ.tokens.serif, fontSize = 18.sp)
                        Text(t.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Line spacing", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LineSpacing.entries.forEachIndexed { i, s ->
                SegmentedButton(
                    selected = prefs.spacing == s,
                    onClick = { onChange(prefs.copy(spacing = s)) },
                    shape = SegmentedButtonDefaults.itemShape(i, LineSpacing.entries.size),
                    label = { Text(s.label, maxLines = 1) },
                    icon = {},
                )
            }
        }
    }
    if (showWidth) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Column width",
                style = MaterialTheme.typography.labelLarge,
                color = if (spread) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else Color.Unspecified,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ColumnWidth.entries.forEachIndexed { i, w ->
                    SegmentedButton(
                        selected = prefs.width == w,
                        enabled = !spread,
                        onClick = { onChange(prefs.copy(width = w)) },
                        shape = SegmentedButtonDefaults.itemShape(i, ColumnWidth.entries.size),
                        label = { Text(w.label, maxLines = 1) },
                        icon = {},
                    )
                }
            }
            if (spread) {
                Text(
                    "Column width applies to single-page layout. In two pages, each column is half the window.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Text size  18" over a small-A / slider / large-A row. Shared with Settings. */
@Composable
fun TextSizeControl(size: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text("Text size", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("$size", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { onChange((size - 1).coerceIn(ReaderTextSizes)) }, enabled = size > ReaderTextSizes.first) {
                Text("A", fontFamily = LibraryZ.tokens.serif, fontSize = 15.sp)
            }
            Slider(
                value = size.toFloat(),
                onValueChange = { onChange(it.roundToInt().coerceIn(ReaderTextSizes)) },
                valueRange = ReaderTextSizes.first.toFloat()..ReaderTextSizes.last.toFloat(),
                steps = ReaderTextSizes.last - ReaderTextSizes.first - 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onChange((size + 1).coerceIn(ReaderTextSizes)) }, enabled = size < ReaderTextSizes.last) {
                Text("A", fontFamily = LibraryZ.tokens.serif, fontSize = 24.sp)
            }
        }
    }
}

/**
 * Flowing text in a readable measure, followed by the end card. Position
 * is the scroll offset as one of [TEXT_POSITIONS] virtual pages; it's
 * restored on open and re-anchored whenever the layout height changes
 * (size, spacing or width).
 */
@Composable
private fun TextView(
    reader: TextReader,
    pager: TextPager,
    scroll: ScrollState,
    prefs: ReaderPrefs,
    wide: Boolean,
    startPosition: Int,
    onPosition: (Int) -> Unit,
    end: @Composable () -> Unit,
) {
    val last = TEXT_POSITIONS - 1
    val layoutKey = Triple(prefs.textSize, prefs.spacing, prefs.width)
    // A pending (fraction, maxValue-when-requested) scroll restore. It
    // applies once layout produces a different maxValue, i.e. after the
    // text has been measured (open) or re-measured (layout change).
    var anchor by remember(reader) { mutableStateOf<Pair<Float, Int>?>(startPosition.toFloat() / last to -1) }
    var lastLayout by remember(reader) { mutableStateOf(layoutKey) }
    if (layoutKey != lastLayout) {
        val fraction = if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f
        anchor = fraction to scroll.maxValue
        lastLayout = layoutKey
    }
    LaunchedEffect(anchor, scroll.maxValue) {
        val (fraction, requestedAt) = anchor ?: return@LaunchedEffect
        // maxValue is Int.MAX_VALUE until the first measure.
        if (scroll.maxValue == requestedAt || scroll.maxValue == Int.MAX_VALUE) return@LaunchedEffect
        scroll.scrollTo((fraction * scroll.maxValue).roundToInt())
        anchor = null
    }
    // Contents jumps: the text's top in the scroll, plus its line's.
    var textLayout by remember(reader) { mutableStateOf<TextLayoutResult?>(null) }
    val topPadding = if (wide) 56.dp else 28.dp
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    SideEffect {
        pager.goTo = { offset ->
            textLayout?.let { l ->
                val line = l.getLineForOffset(offset.coerceIn(0, reader.text.length))
                // A little space above, so a heading doesn't sit against the bar.
                val y = with(density) { (topPadding - 16.dp).toPx() } + l.getLineTop(line)
                anchor = null
                scope.launch { scroll.scrollTo(y.roundToInt()) }
            }
        }
        pager.offset = {
            textLayout?.let { l ->
                // Just below the top: a jump leaves a margin above its heading.
                val y = scroll.value - with(density) { (topPadding - 24.dp).toPx() }
                l.getLineStart(l.getLineForVerticalPosition(y.coerceAtLeast(0f)))
            }
        }
    }
    LaunchedEffect(reader) {
        snapshotFlow { if (anchor != null) null else scroll.value to scroll.maxValue }
            .collect { state ->
                val (value, max) = state ?: return@collect
                // Text shorter than the screen is read as soon as it's shown.
                onPosition(if (max <= 0) last else (value.toFloat() / max * last).roundToInt())
            }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gutter = if (wide) 40.dp else 28.dp
        val style = LocalTextStyle.current.merge(
            TextStyle(
                fontFamily = LibraryZ.tokens.serif,
                fontSize = prefs.textSize.sp,
                lineHeight = (prefs.textSize * prefs.spacing.factor).sp,
                color = prefs.theme.ink,
            ),
        )
        // Pictures and tables fit the column, pictures most of the screen's height.
        val sized = rememberSizedText(reader, min(maxWidth, prefs.width.max) - gutter * 2, maxHeight * 0.85f, style, prefs.theme, prefs.bookFonts)
        val emPx = with(density) { prefs.textSize.sp.toPx() }
        Box(
            modifier = Modifier.fillMaxSize().verticalScroll(scroll),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    // The whole column even when the text is short, so headings center in it.
                    .fillMaxWidth()
                    .widthIn(max = prefs.width.max)
                    .padding(horizontal = gutter)
                    .padding(top = topPadding, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                Text(
                    text = sized.text,
                    style = style.merge(TextStyle(lineHeightStyle = sized.lineHeightStyle)),
                    inlineContent = sized.inlineContent,
                    onTextLayout = { textLayout = it },
                    modifier = Modifier.bookBoxes(reader, 0, { textLayout }, emPx, prefs.theme),
                )
                end()
            }
        }
    }
}

/**
 * The first line of each [height]-tall page of [layout]. A line's text runs
 * to where the next line starts: Skia ends a line of only a placeholder (a
 * picture, a table row) where it starts, and read as empty those lines
 * looked blank, so they were skipped at the top of a page and drawn,
 * clipped, at the foot of the one before.
 */
internal fun textPageBreaks(layout: TextLayoutResult, height: Float): List<Int> {
    val text = layout.layoutInput.text.text
    return pageBreaks(
        lineCount = layout.lineCount,
        top = layout::getLineTop,
        bottom = layout::getLineBottom,
        height = height,
        blank = { line ->
            val end = if (line + 1 < layout.lineCount) layout.getLineStart(line + 1) else text.length
            text.substring(layout.getLineStart(line), end).isBlank()
        },
    )
}

/**
 * Lets the reader's key handler turn a two-page text spread, which keeps its
 * pages to itself. [TextSpreadView] fills these in while it's shown.
 */
class TextPager {
    var turn: (Int) -> Unit = {}
    /** To the first spread, or ([toEnd]) the last. */
    var jump: (toEnd: Boolean) -> Unit = {}
    /** To a character offset in the text, e.g. a chapter picked from the contents. */
    var goTo: (offset: Int) -> Unit = {}
    /**
     * Where the reader is, as a character offset: the last one shown in a
     * spread (so a chapter starting on the right-hand page counts, as in the
     * running head), else near the top of the screen. Null before layout.
     */
    var offset: () -> Int? = { null }
}

/**
 * Flowing text as a printed book's two-page spread: paginated, never
 * scrolled. Running heads carry the book title (left) and the chapter
 * (right); the feet, book % and the pages left in the chapter. Tap or click
 * the outer fifth of either side, swipe, or use the keys (via [pager]) to
 * turn; the middle shows and hides the bars. Pages are measured for the
 * current size and font, so they aren't stable page numbers; position stays
 * the per-mille of [TEXT_POSITIONS] that scrolling uses.
 */
@Composable
private fun TextSpreadView(
    reader: TextReader,
    title: String,
    prefs: ReaderPrefs,
    startPosition: Int,
    percent: Int,
    onPosition: (Int) -> Unit,
    pager: TextPager,
    chrome: Boolean,
    onToggleChrome: () -> Unit,
    end: @Composable () -> Unit,
) {
    val theme = prefs.theme
    val last = TEXT_POSITIONS - 1
    val chapters = reader.chapters
    // Where the reader is, as a character offset: stays put when the pages
    // are re-measured (text size, spacing, window size). Past the text = the end card.
    var anchor by remember(reader) {
        mutableStateOf((startPosition.toFloat() / last * reader.text.length).roundToInt().coerceIn(0, reader.text.length))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val column = textColumnWidth(maxWidth)
        // Running head (16 + 20) and foot (14 + 16) around the body; page padding 28 / 20.
        val pageHeight = maxHeight
        val bodyHeight = maxHeight - 28.dp - 20.dp - 36.dp - 30.dp
        val baseStyle = TextStyle(
            fontFamily = LibraryZ.tokens.serif,
            fontSize = prefs.textSize.sp,
            lineHeight = (prefs.textSize * prefs.spacing.factor).sp,
            color = theme.ink,
            textAlign = TextAlign.Justify,
        )
        // A picture or table row fits a page, a little under so rounding can't push it over.
        val sized = rememberSizedText(reader, column, bodyHeight - 2.dp, baseStyle, theme, prefs.bookFonts)
        val emPx = with(density) { prefs.textSize.sp.toPx() }
        val text = sized.text
        val style = baseStyle.copy(lineHeightStyle = sized.lineHeightStyle)
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val columnPx = with(density) { column.roundToPx() }.coerceAtLeast(1)
        val bodyPx = with(density) { bodyHeight.toPx() }.coerceAtLeast(1f)
        // Lay the whole text out once per column width and style, then cut it
        // into pages of whole lines. Each page then draws only its own text.
        val layout = remember(text, style, columnPx) {
            measurer.measure(text, style, placeholders = sized.placeholders, constraints = Constraints(maxWidth = columnPx))
        }
        val starts = remember(layout, bodyPx) { textPageBreaks(layout, bodyPx) }
        val pageCount = starts.size
        fun startOf(p: Int): Int = if (p >= pageCount) text.length else layout.getLineStart(starts[p])
        fun endOf(p: Int): Int = if (p + 1 >= pageCount) text.length else startOf(p + 1)
        fun pageAt(offset: Int): Int {
            if (pageCount == 0 || offset >= text.length) return pageCount
            val line = layout.getLineForOffset(offset)
            val i = starts.binarySearch(line)
            return if (i >= 0) i else (-i - 2).coerceAtLeast(0)
        }
        // Two pages a spread, the end card in the slot after the last page.
        val spreads = remember(pageCount) { Spreads(pageCount, coverAlone = false) }
        // Empty text: straight to the end card.
        val slots = spreads.at(pageAt(anchor)).ifEmpty { listOf(pageCount) }
        val atEnd = pageCount in slots
        // How the next change of spread came about: a turn slides that way,
        // a jump fades, and re-paging (size, font, window) just swaps.
        var change by remember { mutableStateOf(SpreadChange.Repage) }
        LaunchedEffect(slots) { change = SpreadChange.Repage }

        SideEffect {
            pager.turn = { delta ->
                change = if (delta > 0) SpreadChange.Forward else SpreadChange.Back
                anchor = startOf(spreads.turn(pageAt(anchor), delta))
            }
            pager.jump = { toEnd ->
                change = SpreadChange.Jump
                anchor = if (toEnd) text.length else 0
            }
            pager.goTo = { offset ->
                change = SpreadChange.Jump
                anchor = offset.coerceIn(0, text.length)
            }
            pager.offset = { (endOf(slots.filter { it < pageCount }.maxOrNull() ?: pageCount) - 1).coerceAtLeast(0) }
        }
        LaunchedEffect(slots, atEnd) {
            onPosition(if (atEnd) last else (startOf(slots.first()).toFloat() / text.length.coerceAtLeast(1) * last).roundToInt())
        }
        // The next spread's pictures, decoded while this one is read.
        LaunchedEffect(slots, layout) {
            val next = spreads.at(spreads.turn(slots.first(), 1)).filter { it < pageCount }
            if (next.isNotEmpty() && next != slots) prefetchImages(reader, startOf(next.min()), endOf(next.max()))
        }

        val reduce = LocalReduceMotion.current
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        when {
                            offset.x < size.width * 0.2f -> pager.turn(-1)
                            offset.x > size.width * 0.8f -> pager.turn(1)
                            else -> onToggleChrome()
                        }
                    }
                }
                .pointerInput(Unit) {
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = { if (abs(dragged) > SWIPE_THRESHOLD_PX) pager.turn(if (dragged < 0) 1 else -1) },
                        onHorizontalDrag = { _, dx -> dragged += dx },
                    )
                },
        ) {
        AnimatedContent(
            targetState = slots,
            transitionSpec = {
                when (change) {
                    SpreadChange.Forward -> Motion.pageTurn(forward = true, reduce)
                    SpreadChange.Back -> Motion.pageTurn(forward = false, reduce)
                    SpreadChange.Jump -> Motion.crossfade(Motion.MEDIUM)
                    SpreadChange.Repage -> Motion.none
                }
            },
            label = "spread turn",
        ) { slots ->
        Row(Modifier.fillMaxSize()) {
            if (slots == listOf(pageCount)) {
                // The book ended on a right-hand page: the card follows alone.
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.widthIn(max = 440.dp)) { end() }
                }
            } else slots.forEachIndexed { i, p ->
                if (i == 1) {
                    // The gutter: a hairline that fades out at the ends.
                    Box(
                        Modifier.width(1.dp).fillMaxHeight().background(
                            Brush.verticalGradient(
                                0f to theme.background,
                                0.12f to theme.rule,
                                0.88f to theme.rule,
                                1f to theme.background,
                            ),
                        ),
                    )
                }
                val left = i == 0
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(
                        start = if (left) TEXT_PAGE_OUTER else TEXT_PAGE_INNER,
                        end = if (left) TEXT_PAGE_INNER else TEXT_PAGE_OUTER,
                        top = 28.dp,
                        bottom = 20.dp,
                    ),
                ) {
                    if (p == pageCount) {
                        // The end card in the empty right-hand page.
                        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            Box(Modifier.fillMaxWidth().heightIn(min = pageHeight - 48.dp), contentAlignment = Alignment.Center) {
                                Box(Modifier.widthIn(max = 440.dp)) { end() }
                            }
                        }
                    } else {
                        RunningLine(
                            start = if (left) title else "",
                            end = if (left) "" else chapters.lastOrNull { it.offset < endOf(p) }?.title.orEmpty(),
                            head = true,
                            color = theme.muted,
                            modifier = Modifier.height(36.dp),
                        )
                        // Clipped above and below only, so a box's edge just past the column still shows.
                        Box(
                            Modifier.weight(1f).fillMaxWidth().drawWithContent {
                                clipRect(left = -size.width, right = size.width * 2) { this@drawWithContent.drawContent() }
                            },
                        ) {
                            var pageLayout by remember(p, text) { mutableStateOf<TextLayoutResult?>(null) }
                            // Same width as the measured column, so lines break where they did there.
                            Text(
                                text.subSequence(startOf(p), trimEnd(text.text, startOf(p), endOf(p))),
                                style = style,
                                inlineContent = sized.inlineContent,
                                onTextLayout = { pageLayout = it },
                                modifier = Modifier.requiredWidth(with(density) { columnPx.toDp() })
                                    .bookBoxes(reader, startOf(p), { pageLayout }, emPx, theme),
                            )
                        }
                        val footEnd = if (left) {
                            ""
                        } else {
                            val next = chapters.firstOrNull { it.offset >= endOf(p) }
                            pagesLeftLabel((next?.let { pageAt(it.offset) } ?: pageCount) - p - 1, inChapter = chapters.isNotEmpty())
                        }
                        RunningLine(
                            start = if (left) "$percent%" else "",
                            end = footEnd,
                            head = false,
                            color = theme.muted,
                            modifier = Modifier.height(30.dp),
                        )
                    }
                }
            }
        }
        }
        }
        if (chrome) {
            listOf(-1 to Alignment.CenterStart, 1 to Alignment.CenterEnd).forEach { (delta, where) ->
                IconButton(
                    onClick = { pager.turn(delta) },
                    enabled = if (delta < 0) slots.first() > 0 else !atEnd,
                    modifier = Modifier.align(where),
                ) {
                    Icon(
                        if (delta < 0) Icons.Rounded.ChevronLeft else Icons.Rounded.ChevronRight,
                        contentDescription = if (delta < 0) "Previous pages" else "Next pages",
                        tint = theme.muted,
                    )
                }
            }
        }
    }
}

/**
 * The book's chapters, nested by depth, the one being read highlighted and
 * scrolled into view. Picking one jumps there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentsSheet(chapters: List<Chapter>, current: Int, onPick: (Chapter) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            "Contents",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 28.dp)) {
            itemsIndexed(chapters) { i, chapter ->
                val here = i == current
                Text(
                    chapter.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (here) FontWeight.SemiBold else null,
                    color = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(chapter) }
                        .padding(start = 24.dp + 16.dp * chapter.depth.coerceAtMost(3), end = 24.dp, top = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

/** [end] moved back past trailing whitespace, not before [start]. */
private fun trimEnd(text: String, start: Int, end: Int): Int {
    var e = end
    while (e > start && text[e - 1].isWhitespace()) e--
    return e
}

/** A running head (small tracked caps) or foot: [start] and [end] at either side. */
@Composable
private fun RunningLine(start: String, end: String, head: Boolean, color: Color, modifier: Modifier = Modifier) {
    val style = if (head) {
        MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.14.em)
    } else {
        MaterialTheme.typography.bodySmall
    }
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = if (head) Alignment.Top else Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (head) start.uppercase() else start,
            style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (head) end.uppercase() else end,
            style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

/* ---------------- End of book ---------------- */

@Composable
private fun EndCard(
    title: String,
    authors: String?,
    finished: Boolean,
    rating: Int?,
    onRate: (Int) -> Unit,
    onMarkRead: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.DoneAll,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text("You’ve reached the end", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                listOfNotNull(title, authorsShort(authors).takeIf { it.isNotEmpty() }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text("How was it?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StarRating(rating = rating, onRate = onRate, starSize = 28.dp)
            if (finished) {
                FilledTonalButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Finished · back to book", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Button(onClick = onMarkRead, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Mark as read", style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = onBack) { Text("Back to book") }
            }
        }
    }
}
