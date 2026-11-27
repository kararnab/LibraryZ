package com.libraryz.data

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified

/**
 * The page's alignment in place of the book's. [Auto] is the
 * reader's own: justified in two pages, left in one. Either way a book's
 * left, start and justify follow it; its centred and right-aligned text
 * keeps its alignment.
 */
enum class AlignOverride(val label: String) { Auto("Auto"), Left("Left"), Justify("Justify") }

/** The reader's first-line indent in place of the book's. */
enum class IndentOverride(val label: String) { Book("Book"), None("None"), Indent("2 em") }

/**
 * Where the reader's settings win over an EPUB's own formatting.
 * Applied when the text is drawn ([overridden]),
 * so changing one never re-reads the book.
 */
data class TextOverrides(
    val align: AlignOverride = AlignOverride.Auto,
    val indent: IndentOverride = IndentOverride.Book,
    /** The book's own `line-height`s (see [BookLineHeight]); off = the reader's spacing throughout. */
    val bookLineHeight: Boolean = false,
) {
    /** The page's own alignment: [default] (the reader's for this layout) unless overridden. */
    fun baseAlign(default: TextAlign): TextAlign = when (align) {
        AlignOverride.Auto -> default
        AlignOverride.Left -> TextAlign.Start
        AlignOverride.Justify -> TextAlign.Justify
    }
}

/**
 * A paragraph-style region of the text whose line height is the book's
 * `line-height`; [fallback] is what it is without it (a heading's taller
 * lines, or unspecified = the reader's spacing).
 */
class BookLineHeight(val start: Int, val end: Int, val fallback: TextUnit)

/** As the builder spaces an indented paragraph (a region of its own): untrimmed, at least the line's height. */
private val UNTRIMMED = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None, LineHeightStyle.Mode.Minimum)

/**
 * [text] with [o] applied to its paragraph styles; offsets, spans and
 * annotations unchanged (an indent turns some newlines into spaces, see below).
 * - Alignment is the page's ([TextOverrides.baseAlign]): the builder
 *   leaves a book's left, start and justify to it, so nothing changes here.
 * - Indent: each paragraph's first line is indented by 2 em past the rest,
 *   or not at all; headings (taller lines), centred lines and pictures keep
 *   theirs. A region of several paragraphs is split so every one indents.
 * - Line height: the book's ([lineHeights]) give way to their fallback
 *   unless [TextOverrides.bookLineHeight].
 */
internal fun overridden(text: AnnotatedString, lineHeights: List<BookLineHeight>, o: TextOverrides): AnnotatedString {
    val stripHeights = !o.bookLineHeight && lineHeights.isNotEmpty()
    // Alignment needs nothing here: the builder leaves left, start and justify to the page's (baseAlign).
    if (o.indent == IndentOverride.Book && !stripHeights) return text
    val fallbackAt = lineHeights.associateBy { it.start }
    val src = text.text
    val chars = StringBuilder(src)
    val out = ArrayList<AnnotatedString.Range<ParagraphStyle>>()

    fun emit(style: ParagraphStyle?, start: Int, end: Int) {
        if (end <= start) return
        var s = style
        val book = s?.let { fallbackAt[start]?.takeIf { it.end == end } }
        // A heading or display text: lines taller than the reader's, by the builder's rule.
        val display = (book?.fallback ?: s?.lineHeight)?.isSpecified == true
        if (s != null && book != null && stripHeights) s = s.copy(lineHeight = book.fallback)
        val indentable = o.indent != IndentOverride.Book && !display &&
            s?.textAlign !in setOf(TextAlign.Center, TextAlign.End, TextAlign.Right) &&
            !src.startsWith(IMAGE_CHAR, start)
        val rest = s?.textIndent?.restLine?.takeIf { it.isSpecified } ?: 0.em
        if (!indentable || !rest.isEm) {
            s?.let { out += AnnotatedString.Range(it, start, end) }
            return
        }
        val base = s ?: ParagraphStyle()
        if (o.indent == IndentOverride.None) {
            out += AnnotatedString.Range(base.copy(textIndent = TextIndent(rest, rest)), start, end)
            return
        }
        // One range per paragraph, Skia indenting only a range's first line. A range's edge is a line
        // break of its own, so where the builder's region had n newlines, a split keeps n − 1 and the
        // last becomes a space (drawing nothing, blank to the pager): same lines, same offsets.
        val indented = base.copy(textIndent = TextIndent((rest.value + 2f).em, rest), lineHeightStyle = UNTRIMMED)
        var at = start
        while (at < end) {
            val nl = src.indexOf('\n', at)
            var stop = if (nl < 0 || nl >= end) end else nl + 1
            while (stop < end && src[stop] == '\n') stop++
            if (stop < end) chars[stop - 1] = ' '
            out += AnnotatedString.Range(if (src[at] == '\n') base else indented, at, stop)
            at = stop
        }
    }

    var at = 0
    for (r in text.paragraphStyles.sortedBy { it.start }) {
        emit(null, at, r.start)
        emit(r.item, r.start, r.end)
        at = maxOf(at, r.end)
    }
    emit(null, at, src.length)

    return buildAnnotatedString {
        append(AnnotatedString(chars.toString(), spanStyles = text.spanStyles, paragraphStyles = out))
        for (a in text.getStringAnnotations(0, text.length)) addStringAnnotation(a.tag, a.item, a.start, a.end)
    }
}
