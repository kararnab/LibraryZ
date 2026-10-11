package com.libraryz.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import com.libraryz.data.BookCell
import com.libraryz.data.BookImage
import com.libraryz.data.BookTable
import com.libraryz.data.IMAGE_TAG
import com.libraryz.data.TABLE_TAG
import com.libraryz.data.TextReader
import com.libraryz.theme.ReadingTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A [TextReader]'s text ready to lay out with its pictures and tables: pass
 * [text] and [inlineContent] to `Text`, or [text] and [placeholders] to a
 * `TextMeasurer`, with [lineHeightStyle] in the style either way.
 */
class SizedText(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
    val placeholders: List<AnnotatedString.Range<Placeholder>>,
    /**
     * Lets a line grow past the style's line height to fit its picture or
     * table row. Android's default ([LineHeightStyle.Mode.Fixed]) would crop
     * it to one line of text; Skia grows the line either way. Null without
     * either, so plain text lays out as it always has.
     */
    val lineHeightStyle: LineHeightStyle?,
)

/**
 * [reader]'s text for a column [maxWidth] wide, its colours made readable
 * on [theme] and its pictures and table rows sized:
 *
 * - A block picture's placeholder spans the column, so the picture is drawn
 *   centered in it, as wide as it is (one image pixel = 1 dp) up to that,
 *   then shrunk to fit [maxHeight]. An inline one is an em high.
 * - A table's columns share the width by their content, as a browser's
 *   automatic table layout does; each row is as tall as its tallest cell
 *   (at most [maxHeight]) and the table is centered.
 *
 * [style] is the reading text's: cells are measured in it.
 */
@Composable
fun rememberSizedText(reader: TextReader, maxWidth: Dp, maxHeight: Dp, style: TextStyle, theme: ReadingTheme): SizedText {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 0)
    return remember(reader, maxWidth, maxHeight, density, style, theme) {
        prepare(reader, maxWidth, maxHeight, style, theme, density, measurer)
    }
}

internal fun prepare(
    reader: TextReader,
    maxWidth: Dp,
    maxHeight: Dp,
    style: TextStyle,
    theme: ReadingTheme,
    density: Density,
    measurer: TextMeasurer,
): SizedText {
    val text = themed(reader.text, theme)
    if (reader.images.isEmpty() && reader.tables.isEmpty()) return SizedText(text, emptyMap(), emptyList(), null)
    val content = HashMap<String, InlineTextContent>()
    reader.images.forEach { (key, image) ->
        content[key] = InlineTextContent(imagePlaceholder(image, maxWidth, maxHeight, density, fill = true)) { BookImageView(image) }
    }
    // Cells start-aligned whatever the page does (a spread justifies), unless the book says otherwise.
    val cellStyle = style.copy(textAlign = TextAlign.Start, lineHeightStyle = MINIMUM)
    reader.tables.forEachIndexed { t, table ->
        val layout = layoutTable(table, reader, maxWidth, maxHeight, cellStyle, theme, density, measurer)
        layout.rows.forEachIndexed { r, row ->
            val placeholder = with(density) { Placeholder(maxWidth.toSp(), row.height.toSp(), PlaceholderVerticalAlign.Center) }
            content["$t:$r"] = InlineTextContent(placeholder) {
                TableRowView(row, header = table.rows[r].all { it.header }, bordered = table.bordered, style = cellStyle, theme = theme)
            }
        }
    }
    return SizedText(
        text = text,
        inlineContent = content,
        placeholders = placeholdersOf(text, content),
        lineHeightStyle = MINIMUM,
    )
}

private val MINIMUM = LineHeightStyle.Default.copy(mode = LineHeightStyle.Mode.Minimum)

private fun placeholdersOf(text: AnnotatedString, content: Map<String, InlineTextContent>) =
    (text.getStringAnnotations(IMAGE_TAG, 0, text.length) + text.getStringAnnotations(TABLE_TAG, 0, text.length))
        .mapNotNull { r -> content[r.item]?.let { AnnotatedString.Range(it.placeholder, r.start, r.end) } }
        .sortedBy { it.start }

/** A picture's placeholder: a block one [fill]s the width (to center in it) or is just its own width. */
private fun imagePlaceholder(image: BookImage, maxWidth: Dp, maxHeight: Dp, density: Density, fill: Boolean): Placeholder {
    if (!image.block) {
        return Placeholder(
            width = (image.width.toFloat() / image.height).em,
            height = 1.em,
            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
        )
    }
    return with(density) {
        var w = min(maxWidth, image.width.dp)
        var h = w * image.height.toFloat() / image.width.toFloat()
        if (h > maxHeight) {
            w *= maxHeight / h
            h = maxHeight
        }
        Placeholder(if (fill) maxWidth.toSp() else w.toSp(), h.toSp(), PlaceholderVerticalAlign.Center)
    }
}

internal class CellLayout(
    val cell: BookCell,
    val width: Dp,
    val fill: Color?,
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
)
internal class RowLayout(val height: Dp, val cells: List<CellLayout>)
internal class TableLayout(val columns: List<Dp>, val rows: List<RowLayout>)

private val CELL_PAD_H = 8.dp
private val CELL_PAD_V = 4.dp

/**
 * Column widths from each column's narrowest (longest word) and widest (one
 * line) cell: all at their widest if that fits, else everything above the
 * narrowest shared in proportion; past that, the narrowest scaled down.
 * Cells spanning columns don't size them.
 */
internal fun layoutTable(
    table: BookTable,
    reader: TextReader,
    maxWidth: Dp,
    maxHeight: Dp,
    style: TextStyle,
    theme: ReadingTheme,
    density: Density,
    measurer: TextMeasurer,
): TableLayout = with(density) {
    val columns = table.rows.maxOf { row -> row.sumOf { it.span } }
    val available = maxWidth.toPx()
    val pad = CELL_PAD_H.toPx() * 2
    // Cell text made readable on its own cell's colour.
    val fills = table.rows.map { row -> row.map { c -> c.background?.let { highlight(it, theme) } } }
    val texts = table.rows.mapIndexed { r, row -> row.mapIndexed { i, c -> themed(c.text, theme, fills[r][i] ?: theme.background) } }

    fun cellContent(text: AnnotatedString, width: Dp): Map<String, InlineTextContent> =
        text.getStringAnnotations(IMAGE_TAG, 0, text.length).associate { r ->
            val image = reader.images.getValue(r.item)
            r.item to InlineTextContent(imagePlaceholder(image, width, maxHeight, density, fill = false)) { BookImageView(image) }
        }

    val minW = FloatArray(columns)
    val maxW = FloatArray(columns)
    table.rows.forEachIndexed { r, row ->
        var c = 0
        row.forEachIndexed { i, cell ->
            if (cell.span == 1 && c < columns) {
                val content = cellContent(texts[r][i], maxWidth)
                val m = measurer.measure(
                    texts[r][i], style,
                    constraints = Constraints(maxWidth = available.roundToInt().coerceAtLeast(1)),
                    placeholders = placeholdersOf(texts[r][i], content),
                ).multiParagraph.intrinsics
                minW[c] = max(minW[c], m.minIntrinsicWidth + pad)
                maxW[c] = max(maxW[c], m.maxIntrinsicWidth + pad)
            }
            c += cell.span
        }
    }
    // Columns with no single-column cell at all.
    for (c in 0 until columns) if (maxW[c] == 0f) {
        minW[c] = pad * 2
        maxW[c] = pad * 2
    }
    val sumMin = minW.sum()
    val sumMax = maxW.sum()
    val widths = when {
        sumMax <= available -> maxW.toList()
        sumMin >= available -> minW.map { it * available / sumMin }
        else -> List(columns) { minW[it] + (maxW[it] - minW[it]) * (available - sumMin) / (sumMax - sumMin) }
    }.map { it.toDp() }

    val rows = table.rows.mapIndexed { r, row ->
        var c = 0
        val cells = row.mapIndexed { i, cell ->
            val width = widths.subList(c.coerceAtMost(columns), (c + cell.span).coerceAtMost(columns)).fold(0.dp) { a, b -> a + b }
            c += cell.span
            val inner = (width - CELL_PAD_H * 2).coerceAtLeast(1.dp)
            CellLayout(cell, width, fills[r][i], texts[r][i], cellContent(texts[r][i], inner))
        }
        val height = cells.maxOf { cl ->
            measurer.measure(
                cl.text, style,
                constraints = Constraints(maxWidth = (cl.width - CELL_PAD_H * 2).roundToPx().coerceAtLeast(1)),
                placeholders = placeholdersOf(cl.text, cl.inlineContent),
            ).size.height.toDp() + CELL_PAD_V * 2
        }
        RowLayout(min(height, maxHeight), cells)
    }
    TableLayout(widths, rows)
}

/** One table row in its placeholder: cells side by side, the table centered in the column. */
@Composable
private fun TableRowView(row: RowLayout, header: Boolean, bordered: Boolean, style: TextStyle, theme: ReadingTheme) {
    val rule = theme.rule
    Row(
        Modifier.fillMaxSize().then(
            // Unbordered tables still set their header off with a rule.
            if (header && !bordered) {
                Modifier.drawBehind { drawLine(rule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            } else {
                Modifier
            },
        ),
        horizontalArrangement = Arrangement.Center,
    ) {
        row.cells.forEach { c ->
            Box(
                Modifier.width(c.width).fillMaxHeight()
                    .then(c.fill?.let { Modifier.background(it) } ?: Modifier)
                    .then(if (bordered) Modifier.border(0.5.dp, rule) else Modifier)
                    .padding(horizontal = CELL_PAD_H, vertical = CELL_PAD_V),
            ) {
                Text(c.text, style = style, inlineContent = c.inlineContent)
            }
        }
    }
}

/**
 * [text] with the book's colours made readable on [theme]: greys and black
 * (body-text colours) give way to the theme's ink, other text colours keep
 * their hue at a lightness that reads on the background, and highlights are
 * toned to the background so the ink stays readable on them.
 */
internal fun themed(text: AnnotatedString, theme: ReadingTheme, background: Color = theme.background): AnnotatedString {
    if (text.spanStyles.none { it.item.color.isSpecified || it.item.background.isSpecified }) return text
    return buildAnnotatedString {
        append(AnnotatedString(text.text, paragraphStyles = text.paragraphStyles))
        for (r in text.spanStyles) {
            val s = r.item
            val color = if (s.color.isSpecified) readableInk(s.color, background) else Color.Unspecified
            val highlight = if (s.background.isSpecified) highlight(s.background, theme) ?: Color.Unspecified else Color.Unspecified
            addStyle(s.copy(color = color, background = highlight), r.start, r.end)
        }
        for (a in text.getStringAnnotations(0, text.length)) addStringAnnotation(a.tag, a.item, a.start, a.end)
    }
}

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** A text colour with its hue kept and lightness moved until it reads on [background] (4:1); greys → the theme's ink. */
internal fun readableInk(color: Color, background: Color): Color {
    val c = color.compositeOver(background)
    val (h, s, l0) = hsl(c)
    if (s < 0.15f) return Color.Unspecified
    val dark = background.luminance() < 0.4f
    var l = l0
    var out = c
    while (contrast(out, background) < 4f && l in 0f..1f) {
        l += if (dark) 0.04f else -0.04f
        out = fromHsl(h, s, l.coerceIn(0f, 1f))
    }
    return out
}

/**
 * A highlight the theme's ink reads on (4.5:1): on light themes the book's
 * colour, lightened if it's too deep; on dark ones a deep tint of the same
 * hue. Null for white, black or grey boxes, which only frame text.
 */
internal fun highlight(color: Color, theme: ReadingTheme): Color? {
    val c = color.compositeOver(theme.background)
    val (h, s, l0) = hsl(c)
    if (s < 0.1f) return null
    val dark = theme.background.luminance() < 0.4f
    var l = if (dark) 0.24f else l0
    val sat = if (dark) s.coerceIn(0.3f, 0.6f) else s
    var out = if (dark) fromHsl(h, sat, l) else c
    while (contrast(theme.ink, out) < 4.5f && l in 0.02f..0.98f) {
        l += if (dark) -0.03f else 0.04f
        out = fromHsl(h, sat, l.coerceIn(0f, 1f))
    }
    return out
}

private fun hsl(c: Color): Triple<Float, Float, Float> {
    val mx = maxOf(c.red, c.green, c.blue)
    val mn = minOf(c.red, c.green, c.blue)
    val l = (mx + mn) / 2f
    if (mx == mn) return Triple(0f, 0f, l)
    val d = mx - mn
    val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
    val h = when (mx) {
        c.red -> ((c.green - c.blue) / d + (if (c.green < c.blue) 6f else 0f))
        c.green -> (c.blue - c.red) / d + 2f
        else -> (c.red - c.green) / d + 4f
    } * 60f
    return Triple(h, s, l)
}

private fun fromHsl(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
}

/**
 * Decodes [image] while any of it is on screen and lets it go once it
 * scrolls away, so a long illustrated book holds only a few bitmaps. A file
 * the platform can't decode leaves its space empty.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun BookImageView(image: BookImage) {
    var visible by remember(image) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, image, visible) {
        value = if (visible) {
            withContext(Dispatchers.Default) { runCatching { image.bytes().decodeToImageBitmap() }.getOrNull() }
        } else {
            null
        }
    }
    Box(
        Modifier.fillMaxSize().onGloballyPositioned {
            val bounds = it.boundsInWindow()
            visible = bounds.width > 0f && bounds.height > 0f
        },
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = image.alt.ifEmpty { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
