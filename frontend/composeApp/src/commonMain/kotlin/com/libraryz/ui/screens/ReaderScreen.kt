package com.libraryz.ui.screens

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libraryz.data.PagedReader
import com.libraryz.data.ReadingPosition
import com.libraryz.data.Reader
import com.libraryz.data.TEXT_POSITIONS
import com.libraryz.data.TextReader
import com.libraryz.data.UserBook
import com.libraryz.data.api.ApiClient
import com.libraryz.data.openReader
import com.libraryz.data.resumePage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val PageBackdrop = Color(0xFF3F3F46)
private const val SWIPE_THRESHOLD_PX = 80f

/** Text sizes (sp) the reader offers for flowing text. */
val ReaderTextSizes = 12..28

/**
 * Full-screen reader. Opens at [resumeFrom]'s saved position and reports
 * where the reader is via [onProgress] (debounced, plus once on close), so
 * the caller can keep the library entry current without a manual slider.
 * Nothing is reported until the reader actually moves, so peeking at a
 * book doesn't add it to the library.
 *
 * Paged: ←/→, PgUp/PgDn, Space, Home/End, tap the left/right third of the
 * page, swipe, or drag the scrubber. Text: scroll, PgUp/PgDn/Space,
 * Home/End, and A−/A+ for size. Esc closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    api: ApiClient,
    editionId: String,
    format: String,
    title: String,
    resumeFrom: UserBook?,
    finished: Boolean,
    textSize: Int,
    onTextSizeChange: (Int) -> Unit,
    onProgress: (ReadingPosition) -> Unit,
    onMarkRead: () -> Unit,
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
    val isText = reader is TextReader
    val pageCount = when (val r = reader) {
        is PagedReader -> r.pageCount
        is TextReader -> TEXT_POSITIONS
        null -> 0
    }
    val startPage = remember(reader) { resumePage(resumeEntry, pageCount) }
    var page by remember(reader) { mutableStateOf(startPage) }
    val position = if (pageCount > 0) ReadingPosition(page, pageCount) else null

    ProgressReporter(
        key = reader,
        position = position,
        opened = if (pageCount > 0) ReadingPosition(startPage, pageCount) else null,
        onProgress = onProgress,
    )

    val textScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(reader) { runCatching { focus.requestFocus() } }

    fun turn(delta: Int) {
        if (reader is PagedReader) page = (page + delta).coerceIn(0, pageCount - 1)
    }

    Scaffold(
        modifier = modifier
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (e.key == Key.Escape) {
                    onClose()
                    return@onPreviewKeyEvent true
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
                            Key.PageDown, Key.Spacebar -> { scope.launch { textScroll.animateScrollBy(screen) }; true }
                            Key.PageUp -> { scope.launch { textScroll.animateScrollBy(-screen) }; true }
                            Key.MoveHome -> { scope.launch { textScroll.animateScrollTo(0) }; true }
                            Key.MoveEnd -> { scope.launch { textScroll.animateScrollTo(textScroll.maxValue) }; true }
                            else -> false
                        }
                    }
                    null -> false
                }
            },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (position != null) {
                            Text(
                                text = if (isText) "${position.percent}%" else "Page ${page + 1} of $pageCount",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    if (isText) {
                        IconButton(
                            onClick = { onTextSizeChange(textSize - 2) },
                            enabled = textSize - 2 >= ReaderTextSizes.first,
                        ) { Icon(Icons.Outlined.TextDecrease, contentDescription = "Smaller text") }
                        IconButton(
                            onClick = { onTextSizeChange(textSize + 2) },
                            enabled = textSize + 2 <= ReaderTextSizes.last,
                        ) { Icon(Icons.Outlined.TextIncrease, contentDescription = "Larger text") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = {
            if (position != null) {
                ReaderBottomBar(
                    position = position,
                    paged = reader is PagedReader,
                    finished = finished,
                    onSeek = { page = it },
                    onTurn = ::turn,
                    onMarkRead = onMarkRead,
                )
            }
        },
        containerColor = if (isText) MaterialTheme.colorScheme.surface else PageBackdrop,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            val r = reader
            when {
                loadError != null -> Text(
                    "Couldn't load: $loadError",
                    color = if (isText) MaterialTheme.colorScheme.onSurface else Color.White,
                )
                r == null -> CircularProgressIndicator(color = if (isText) MaterialTheme.colorScheme.primary else Color.White)
                r is PagedReader -> PagedView(reader = r, pageIndex = page, onTurn = ::turn)
                r is TextReader -> TextView(
                    reader = r,
                    scroll = textScroll,
                    textSize = textSize,
                    startPosition = startPage,
                    onPosition = { page = it },
                )
            }
        }
    }
}

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
            val last = latest
            if (last != null && last != reported) report(last)
        }
    }
}

@Composable
private fun ReaderBottomBar(
    position: ReadingPosition,
    paged: Boolean,
    finished: Boolean,
    onSeek: (Int) -> Unit,
    onTurn: (Int) -> Unit,
    onMarkRead: () -> Unit,
) {
    BottomAppBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (paged) {
                IconButton(onClick = { onTurn(-1) }, enabled = position.page > 0) {
                    Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous page")
                }
                // Drag freely; jump when released so we don't rasterize
                // every page the thumb passes over.
                var scrub by remember(position.pageCount) { mutableStateOf<Float?>(null) }
                Slider(
                    value = scrub ?: position.page.toFloat(),
                    onValueChange = { scrub = it },
                    onValueChangeFinished = {
                        scrub?.let { onSeek(it.roundToInt()) }
                        scrub = null
                    },
                    valueRange = 0f..(position.pageCount - 1).coerceAtLeast(1).toFloat(),
                    enabled = position.pageCount > 1,
                    modifier = Modifier.weight(1f),
                )
                scrub?.let {
                    Text(
                        "p. ${it.roundToInt() + 1}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                IconButton(onClick = { onTurn(1) }, enabled = !position.isAtEnd) {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = "Next page")
                }
            } else {
                LinearProgressIndicator(
                    progress = { position.percent / 100f },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
            }
            if (position.isAtEnd) {
                if (finished) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text("Finished", style = MaterialTheme.typography.labelLarge)
                } else {
                    Button(onClick = onMarkRead) { Text("Mark as read") }
                }
            }
        }
    }
}

@Composable
private fun PagedView(reader: PagedReader, pageIndex: Int, onTurn: (Int) -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Tap the outer thirds to turn, like an e-reader.
                detectTapGestures { offset ->
                    when {
                        offset.x < size.width / 3f -> onTurn(-1)
                        offset.x > size.width * 2f / 3f -> onTurn(1)
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
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        val widthPx = with(LocalDensity.current) {
            maxWidth.roundToPx().coerceAtMost(1600)
        }
        // Keep showing the previous page until the next one is rasterized,
        // so turning pages doesn't flash a spinner.
        var image by remember(reader, widthPx) {
            mutableStateOf<ImageBitmap?>(null)
        }
        LaunchedEffect(reader, pageIndex, widthPx) {
            image = reader.renderPage(pageIndex, widthPx)
        }
        val current = image
        if (current == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Image(
                bitmap = current,
                contentDescription = "Page ${pageIndex + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFAFAF7)),
            )
        }
    }
}

/**
 * Flowing text in a readable measure (≤ 680dp wide). Position is the
 * scroll offset as one of [TEXT_POSITIONS] virtual pages; it's restored
 * on open and re-anchored when the text size changes the layout height.
 */
@Composable
private fun TextView(
    reader: TextReader,
    scroll: ScrollState,
    textSize: Int,
    startPosition: Int,
    onPosition: (Int) -> Unit,
) {
    val last = TEXT_POSITIONS - 1
    // A pending (fraction, maxValue-when-requested) scroll restore. It
    // applies once layout produces a different maxValue, i.e. after the
    // text has been measured (open) or re-measured (size change).
    var anchor by remember(reader) { mutableStateOf<Pair<Float, Int>?>(startPosition.toFloat() / last to -1) }
    var lastSize by remember(reader) { mutableStateOf(textSize) }
    if (textSize != lastSize) {
        val fraction = if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f
        anchor = fraction to scroll.maxValue
        lastSize = textSize
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
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(scroll),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = reader.text,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = FontFamily.Serif,
                fontSize = textSize.sp,
                lineHeight = (textSize * 1.6f).sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .widthIn(max = 680.dp)
                .padding(horizontal = 24.dp, vertical = 32.dp),
        )
    }
}
