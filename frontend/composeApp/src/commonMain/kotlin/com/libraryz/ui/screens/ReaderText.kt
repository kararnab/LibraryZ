package com.libraryz.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.key
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.libraryz.data.PageLayout
import com.libraryz.data.Spreads
import com.libraryz.data.TEXT_POSITIONS
import com.libraryz.data.TextReader
import com.libraryz.data.authorsShort
import com.libraryz.data.pageBreaks
import com.libraryz.data.pagesLeftLabel
import com.libraryz.theme.LibraryZ
import com.libraryz.theme.LocalReduceMotion
import com.libraryz.theme.Motion
import com.libraryz.theme.dissolveFrom
import com.libraryz.theme.movementSpec
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.graphics.TransformOrigin
import com.libraryz.ui.components.bookBoxes
import com.libraryz.ui.components.prefetchImages
import com.libraryz.ui.components.rememberSizedText
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Why a two-page text spread changed, which decides how it animates. */
private enum class SpreadChange { Forward, Back, Jump, Repage }

/* ---------------- Flowing text ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextReaderLayout(
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
 * The first line of each [height]-tall page of [layout]. Placeholder lines
 * (pictures, table rows) need two corrections, or they're cut off at the
 * foot of a page:
 * - A line spans what its placeholders draw, which can reach above and
 *   below its box: table rows are centred on fixed-height lines.
 * - A line's text runs to where the next line starts: Skia ends a line of
 *   only a placeholder where it starts, and read as empty those lines looked
 *   blank, so they were skipped at the top of a page.
 */
internal fun textPageBreaks(layout: TextLayoutResult, height: Float): List<Int> {
    val text = layout.layoutInput.text.text
    // The highest and lowest drawn edges of each line's placeholders.
    val drawnTop = HashMap<Int, Float>()
    val drawnBottom = HashMap<Int, Float>()
    layout.layoutInput.placeholders.forEachIndexed { i, range ->
        val rect = layout.placeholderRects.getOrNull(i) ?: return@forEachIndexed
        val line = layout.getLineForOffset(range.start)
        drawnTop[line] = minOf(drawnTop[line] ?: rect.top, rect.top)
        drawnBottom[line] = maxOf(drawnBottom[line] ?: rect.bottom, rect.bottom)
    }
    return pageBreaks(
        lineCount = layout.lineCount,
        top = { line -> minOf(layout.getLineTop(line), drawnTop[line] ?: Float.MAX_VALUE) },
        bottom = { line -> maxOf(layout.getLineBottom(line), drawnBottom[line] ?: 0f) },
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
