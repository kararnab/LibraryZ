package com.libraryz.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.libraryz.data.Chapter
import com.libraryz.data.PageLayout
import com.libraryz.data.authorsShort
import com.libraryz.theme.LibraryZ
import com.libraryz.theme.ReadingTheme
import com.libraryz.ui.LocalFullscreen
import com.libraryz.ui.components.StarRating
import kotlin.math.roundToInt

/** Enters and leaves full screen; absent where the platform has none. */
@Composable
internal fun FullscreenButton(tint: Color) {
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

/**
 * Layout (wide screens), text size, theme swatches, line spacing, and column
 * width on wide screens, which only applies to one page and is dimmed in two.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingSettings(
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
 * The book's chapters, nested by depth, the one being read highlighted and
 * scrolled into view. Picking one jumps there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContentsSheet(chapters: List<Chapter>, current: Int, onPick: (Chapter) -> Unit, onDismiss: () -> Unit) {
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

/* ---------------- End of book ---------------- */

@Composable
internal fun EndCard(
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
