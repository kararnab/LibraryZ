package com.libraryz.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.key
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.libraryz.data.BookLayout
import com.libraryz.data.PageLayout
import com.libraryz.data.PagedReader
import com.libraryz.data.Reader
import com.libraryz.data.Spreads
import com.libraryz.data.authorsShort
import com.libraryz.data.pagesLabel
import com.libraryz.theme.LibraryZ
import com.libraryz.theme.LocalReduceMotion
import com.libraryz.theme.Motion
import com.libraryz.theme.movementSpec
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.libraryz.ui.components.LoadErrorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.roundToInt

/* ---------------- Paged (PDF) ---------------- */

@Composable
internal fun PagedReaderLayout(
    reader: PagedReader?,
    loadError: String?,
    // Set when the download failed (worth retrying), not when the file is bad.
    onRetry: (() -> Unit)?,
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
                ph == PagedPhase.Error && onRetry != null -> LoadErrorState(
                    title = "Couldn’t open this book",
                    body = loadError.orEmpty(),
                    onRetry = onRetry,
                )
                ph == PagedPhase.Error -> Text(
                    "Couldn’t open this book: $loadError",
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
