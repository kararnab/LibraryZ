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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libraryz.data.PagedReader
import com.libraryz.data.Reader
import com.libraryz.data.ReadingPosition
import com.libraryz.data.TEXT_POSITIONS
import com.libraryz.data.TextReader
import com.libraryz.data.UserBook
import com.libraryz.data.api.ApiClient
import com.libraryz.data.openReader
import com.libraryz.data.pagesLabel
import com.libraryz.data.resumePage
import com.libraryz.data.spreadPages
import com.libraryz.data.turnSpread
import com.libraryz.theme.LibraryZ
import com.libraryz.theme.ReadingTheme
import com.libraryz.ui.components.StarRating
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

/** How flowing text is set. Lives above the reader so it carries from book to book. */
data class ReaderPrefs(
    val textSize: Int = 18,
    val theme: ReadingTheme = ReadingTheme.Light,
    val spacing: LineSpacing = LineSpacing.Comfortable,
    val width: ColumnWidth = ColumnWidth.Medium,
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
    LaunchedEffect(reader) { runCatching { focus.requestFocus() } }
    var chrome by remember { mutableStateOf(true) }
    var settingsOpen by remember { mutableStateOf(false) }
    // Null follows the window (see autoSpread); the toggle overrides it for this session.
    var spreadChoice by remember { mutableStateOf<Boolean?>(null) }

    val end = @Composable {
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

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth.value >= WIDE_DP
        val spread = reader is PagedReader && wide &&
            (spreadChoice ?: autoSpread(maxWidth, maxHeight))
        // In a spread, the furthest visible page is where the reader is.
        val shownPage = page.coerceAtMost(pageCount - 1)
        val position = when {
            pageCount <= 0 -> null
            spread -> ReadingPosition(spreadPages(page, pageCount).last(), pageCount)
            else -> ReadingPosition(shownPage, pageCount)
        }

        ProgressReporter(
            key = reader,
            position = position,
            opened = if (pageCount > 0) ReadingPosition(startPage, pageCount) else null,
            onProgress = onProgress,
        )

        fun turn(delta: Int) {
            if (reader !is PagedReader) return
            page = if (spread) turnSpread(page, delta, pageCount) else (page + delta).coerceIn(0, pageCount)
        }

        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Escape -> { onClose(); return@onPreviewKeyEvent true }
                        Key.F -> { chrome = !chrome; return@onPreviewKeyEvent true }
                    }
                    when (reader) {
                        is PagedReader -> when (e.key) {
                            Key.DirectionRight, Key.DirectionDown, Key.PageDown, Key.Spacebar -> { turn(1); true }
                            Key.DirectionLeft, Key.DirectionUp, Key.PageUp -> { turn(-1); true }
                            Key.MoveHome -> { page = 0; true }
                            Key.MoveEnd -> { page = pageCount - 1; true }
                            else -> false
                        }
                        is TextReader -> {
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
                    startPosition = startPage,
                    percent = position?.percent ?: 0,
                    onPosition = { page = it },
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
                    onSpreadChange = { spreadChoice = it },
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
 * Whether a PDF opens as a two-page spread before the reader picks: on an
 * expanded (≥ 840dp), landscape window, where two pages side by side are
 * still readable.
 */
private fun autoSpread(width: Dp, height: Dp): Boolean = width >= 840.dp && width > height

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
    onSpreadChange: (Boolean) -> Unit,
    chrome: Boolean,
    onToggleChrome: () -> Unit,
    onSeek: (Int) -> Unit,
    onTurn: (Int) -> Unit,
    onClose: () -> Unit,
    end: @Composable () -> Unit,
) {
    val atEndCard = reader != null && page >= pageCount
    val shownPage = page.coerceAtMost((pageCount - 1).coerceAtLeast(0))
    val shownPages = if (spread) spreadPages(page, pageCount) else listOf(shownPage).filter { pageCount > 0 }
    Column(Modifier.fillMaxSize().background(LibraryZ.tokens.readerBackdrop)) {
        if (chrome) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(start = 4.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close reader")
                    }
                    val pageText = pagesLabel(shownPages, pageCount)
                    if (wide) {
                        Row(
                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!authors.isNullOrBlank()) {
                                Text(authors, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                        Text(pageText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (reader != null) {
                            IconToggleButton(checked = spread, onCheckedChange = onSpreadChange, modifier = Modifier.padding(start = 8.dp)) {
                                Icon(
                                    Icons.Rounded.AutoStories,
                                    contentDescription = if (spread) "Show one page" else "Show two-page spread",
                                    tint = if (spread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (pageText.isNotEmpty()) {
                                Text(pageText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loadError != null -> Text(
                    "Couldn't load: $loadError",
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(24.dp),
                )
                reader == null -> CircularProgressIndicator()
                atEndCard -> Box(Modifier.padding(16.dp).widthIn(max = 440.dp)) { end() }
                else -> PageView(reader = reader, pages = shownPages, wide = wide, onTurn = onTurn, onToggleChrome = onToggleChrome)
            }
            if (!chrome && reader != null && !atEndCard) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
                ) {
                    Text(
                        "${shownPages.joinToString("–") { "${it + 1}" }} / $pageCount",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }

        if (chrome && pageCount > 0) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(start = if (wide) 24.dp else 8.dp, end = if (wide) 24.dp else 8.dp, top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = { onTurn(-1) }, enabled = page > 0) {
                        Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous page")
                    }
                    // Drag freely; jump when released so we don't rasterize
                    // every page the thumb passes over.
                    var scrub by remember(pageCount) { mutableStateOf<Float?>(null) }
                    Slider(
                        value = scrub ?: shownPage.toFloat(),
                        onValueChange = { scrub = it },
                        onValueChangeFinished = {
                            scrub?.let { onSeek(it.roundToInt()) }
                            scrub = null
                        },
                        valueRange = 0f..(pageCount - 1).coerceAtLeast(1).toFloat(),
                        enabled = pageCount > 1,
                        modifier = Modifier.weight(1f),
                    )
                    scrub?.let {
                        Text("p. ${it.roundToInt() + 1}", style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = { onTurn(1) }, enabled = page < pageCount) {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = "Next page")
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

/** A rendered page, or null [image] when the renderer failed on it. */
private class RenderedPage(val index: Int, val image: ImageBitmap?)

@Composable
private fun PageView(reader: PagedReader, pages: List<Int>, wide: Boolean, onTurn: (Int) -> Unit, onToggleChrome: () -> Unit) {
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
        // A spread's pages share the width.
        val slot = maxWidth / pages.size.coerceAtLeast(1)
        val widthPx = with(LocalDensity.current) { slot.roundToPx().coerceAtMost(1600) }
        // Keep showing the previous pages until the next ones are rasterized,
        // so turning pages doesn't flash a spinner.
        var shown by remember(reader, widthPx) { mutableStateOf<List<RenderedPage>>(emptyList()) }
        LaunchedEffect(reader, pages, widthPx) {
            // One at a time: PagedReader isn't thread-safe. A page the
            // renderer chokes on (e.g. a broken font) shows a message instead
            // of escaping to the UI thread and taking the app down.
            shown = pages.map { i ->
                val image = try {
                    reader.renderPage(i, widthPx)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
                RenderedPage(i, image)
            }
        }
        if (shown.isEmpty()) {
            CircularProgressIndicator()
        } else {
            Row(
                modifier = Modifier.shadow(8.dp, RoundedCornerShape(2.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                shown.forEach { p ->
                    val image = p.image
                    if (image == null) {
                        Text(
                            "Couldn't render page ${p.index + 1}",
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = slot).padding(24.dp),
                        )
                    } else {
                        Image(
                            bitmap = image,
                            contentDescription = "Page ${p.index + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.weight(1f, fill = false).background(Color(0xFFFFFEFA)),
                        )
                    }
                }
            }
        }
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
    onClose: () -> Unit,
    end: @Composable () -> Unit,
) {
    val theme = prefs.theme
    Row(Modifier.fillMaxSize().background(theme.background)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
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
                    if (wide && !authors.isNullOrBlank()) {
                        Text(authors, style = MaterialTheme.typography.bodySmall, color = theme.muted, maxLines = 1)
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
            }
            // Thin full-width progress line.
            Box(Modifier.fillMaxWidth().height(2.dp).background(theme.rule)) {
                Box(Modifier.fillMaxWidth(percent / 100f).fillMaxHeight().background(theme.muted))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                TextView(
                    reader = reader,
                    scroll = scroll,
                    prefs = prefs,
                    wide = wide,
                    startPosition = startPosition,
                    onPosition = onPosition,
                    end = end,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().height(if (wide) 48.dp else 44.dp)
                    .padding(horizontal = if (wide) 32.dp else 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    authors.orEmpty(),
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
        if (wide && settingsOpen) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.width(340.dp).fillMaxHeight(),
            ) {
                Column(
                    Modifier.statusBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Reading settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onSettingsOpen(false) }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close settings")
                        }
                    }
                    ReadingSettings(prefs, onPrefsChange, showWidth = true)
                }
            }
        }
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
                ReadingSettings(prefs, onPrefsChange, showWidth = false)
            }
        }
    }
}

/** Text size slider, theme swatches, line spacing (and column width on wide screens). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingSettings(prefs: ReaderPrefs, onChange: (ReaderPrefs) -> Unit, showWidth: Boolean) {
    TextSizeControl(prefs.textSize, onChange = { onChange(prefs.copy(textSize = it)) })
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
            Text("Column width", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ColumnWidth.entries.forEachIndexed { i, w ->
                    SegmentedButton(
                        selected = prefs.width == w,
                        onClick = { onChange(prefs.copy(width = w)) },
                        shape = SegmentedButtonDefaults.itemShape(i, ColumnWidth.entries.size),
                        label = { Text(w.label, maxLines = 1) },
                        icon = {},
                    )
                }
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
    LaunchedEffect(reader) {
        snapshotFlow { if (anchor != null) null else scroll.value to scroll.maxValue }
            .collect { state ->
                val (value, max) = state ?: return@collect
                // Text shorter than the screen is read as soon as it's shown.
                onPosition(if (max <= 0) last else (value.toFloat() / max * last).roundToInt())
            }
    }
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = prefs.width.max)
                .padding(horizontal = if (wide) 40.dp else 28.dp)
                .padding(top = if (wide) 56.dp else 28.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Text(
                text = reader.text,
                fontFamily = LibraryZ.tokens.serif,
                fontSize = prefs.textSize.sp,
                lineHeight = (prefs.textSize * prefs.spacing.factor).sp,
                color = prefs.theme.ink,
            )
            end()
        }
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
                listOfNotNull(title, authors?.takeIf { it.isNotBlank() }).joinToString(" · "),
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
