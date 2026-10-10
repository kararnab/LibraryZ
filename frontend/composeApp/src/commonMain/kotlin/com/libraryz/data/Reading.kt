package com.libraryz.data

import com.libraryz.data.api.UpsertLibraryRequest

/**
 * Where the reader is in an edition. Paged formats use real pages;
 * flowing text has no pages, so it uses [TEXT_POSITIONS] virtual ones
 * (a per-mille scroll offset), which is finer than [percent] and lets a
 * long text resume to roughly the right paragraph.
 *
 * Stored in the library entry's `current_page` / `total_pages`, so the
 * position follows the user across devices without a backend change.
 */
data class ReadingPosition(val page: Int, val pageCount: Int) {
    /** 1..100 once anything has been read; 0 for an empty edition. */
    val percent: Int
        get() = if (pageCount <= 0) 0 else ((page + 1) * 100 / pageCount).coerceIn(0, 100)

    val isAtEnd: Boolean get() = pageCount > 0 && page >= pageCount - 1
}

/** Virtual page count for flowing (text) formats. */
const val TEXT_POSITIONS = 1000

/**
 * The edition "Read" opens: the first one this build can render, PDF
 * before TXT (PDF keeps the book's own layout). Null when nothing is
 * readable here, e.g. EPUB-only works.
 */
fun pickReadableEdition(editions: List<Edition>): Edition? {
    val readable = editions.filter { it.isPreviewable }
    return readable.firstOrNull { it.isPdf } ?: readable.firstOrNull()
}

/**
 * The 0-based page to open at. Uses the saved page when it was saved
 * against the same page count (same edition, or same text scale), else
 * falls back to the saved percent, else the start.
 */
fun resumePage(entry: UserBook?, pageCount: Int): Int {
    if (entry == null || pageCount <= 0) return 0
    if (entry.totalPages == pageCount && entry.currentPage in 1..pageCount) {
        return entry.currentPage - 1
    }
    if (entry.progressPercent in 1..99) {
        return (entry.progressPercent * pageCount / 100).coerceIn(0, pageCount - 1)
    }
    return 0
}

/**
 * The library update that records [pos]. Opening a book you haven't
 * started moves it to "Reading"; a finished book stays "Read" so
 * re-reading a chapter doesn't un-finish it.
 */
fun progressUpdate(entry: UserBook?, pos: ReadingPosition): UpsertLibraryRequest =
    UpsertLibraryRequest(
        status = if (entry == null || entry.status == LibraryStatus.Want) LibraryStatus.Reading else null,
        currentPage = pos.page + 1,
        totalPages = pos.pageCount,
        progressPercent = pos.percent,
    )

/** "Continue reading · 42%" vs "Start reading", for the Read buttons. */
fun readActionLabel(entry: UserBook?): String =
    if (entry?.status == LibraryStatus.Reading && entry.progressPercent > 0) {
        "Continue reading · ${entry.progressPercent}%"
    } else {
        "Start reading"
    }

/** How a book's pages are laid out. Chosen per book, on this device. */
enum class PageLayout(val label: String) {
    Auto("Auto"),
    Single("Single page"),
    Two("Two pages"),
}

/**
 * A book's pages grouped into two-page spreads. Slot [pageCount] is the end
 * card, which pairs like a page: it fills the empty right-hand slot when the
 * book ends on a left-hand page, and stands alone otherwise.
 *
 * [coverAlone]: like a printed book, page 1 sits alone on the right, then
 * 2–3, 4–5 (even pages on the left); false pairs 1–2, 3–4 for offset scans.
 * [alone] pages (a landscape map in a portrait PDF) are shown by themselves,
 * and pairing starts again after each.
 */
class Spreads(
    val pageCount: Int,
    coverAlone: Boolean = true,
    alone: Set<Int> = emptySet(),
) {
    /** Each spread's slots, in order. Empty only for an empty book. */
    val list: List<List<Int>>
    private val spreadOf: IntArray

    init {
        val out = ArrayList<List<Int>>()
        if (pageCount > 0) {
            val end = pageCount
            var i = 0
            if (coverAlone) {
                out += listOf(0)
                i = 1
            }
            while (i <= end) {
                val pair = i + 1 <= end && i !in alone && (i + 1) !in alone
                out += if (pair) listOf(i, i + 1) else listOf(i)
                i += if (pair) 2 else 1
            }
        }
        list = out
        spreadOf = IntArray(pageCount + 1)
        out.forEachIndexed { s, slots -> slots.forEach { spreadOf[it] = s } }
    }

    /** The spread holding [page] (clamped to the book, the end card included). */
    fun at(page: Int): List<Int> =
        if (list.isEmpty()) emptyList() else list[spreadOf[page.coerceIn(0, pageCount)]]

    /** The first slot of the spread [delta] (±1) away from [page]'s, stopping at either end. */
    fun turn(page: Int, delta: Int): Int {
        if (list.isEmpty()) return 0
        val s = (spreadOf[page.coerceIn(0, pageCount)] + delta).coerceIn(0, list.lastIndex)
        return list[s].first()
    }
}

/**
 * "Page 7 of 248", "Pages 12–13 of 248" for a spread, or "End of book" once
 * the end card is showing. [pages] may hold the end card's slot ([pageCount]).
 */
fun pagesLabel(pages: List<Int>, pageCount: Int): String {
    if (pageCount <= 0 || pages.isEmpty()) return ""
    if (pageCount in pages) return "End of book"
    return when (pages.size) {
        1 -> "Page ${pages[0] + 1} of $pageCount"
        else -> "Pages ${pages.first() + 1}–${pages.last() + 1} of $pageCount"
    }
}

/** A chapter heading found in plain text: where its line starts, and the heading. */
data class Chapter(val offset: Int, val title: String)

private val chapterLine = Regex(
    "^[ \\t]*((?:chapter|book|part|letter|canto|act)[ \\t]+" +
        "(?:\\d+|[ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|" +
        "fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)" +
        "\\.?(?:[ \\t]*[.:—–-][^\\n]*)?)[ \\t]*\\r?$",
    setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
)

/**
 * Chapter headings in plain text ("CHAPTER I.", "Chapter 12: The Storm",
 * "LETTER 4"), for the text reader's running heads. Only short lines that
 * start a paragraph count, so a sentence opening with "Part one of…" in
 * hard-wrapped prose doesn't.
 */
fun chaptersOf(text: String): List<Chapter> = chapterLine.findAll(text).mapNotNull { m ->
    val heading = m.groups[1] ?: return@mapNotNull null
    val start = m.range.first
    val afterBlank = start == 0 || text.lastIndexOf('\n', start - 1).let { prev ->
        prev < 0 || text.substring(maxOf(0, text.lastIndexOf('\n', prev - 1) + 1), prev).isBlank()
    }
    if (!afterBlank || heading.value.length > 60) return@mapNotNull null
    Chapter(start, heading.value.trim().trimEnd('.').replace(Regex("\\s+"), " "))
}.toList()

/** "6 pages left in chapter", "1 page left in book", "Last page of chapter". */
fun pagesLeftLabel(left: Int, inChapter: Boolean): String {
    val where = if (inChapter) "chapter" else "book"
    return when {
        left <= 0 -> "Last page of $where"
        left == 1 -> "1 page left in $where"
        else -> "$left pages left in $where"
    }
}

/**
 * Splits laid-out lines into pages [height] tall: the first line of each
 * page. Blank lines aren't left at the top of a page. A line taller than a
 * page still gets one to itself.
 */
fun pageBreaks(
    lineCount: Int,
    top: (Int) -> Float,
    bottom: (Int) -> Float,
    height: Float,
    blank: (Int) -> Boolean = { false },
): List<Int> {
    val starts = ArrayList<Int>()
    var line = 0
    while (line < lineCount) {
        while (starts.isNotEmpty() && line < lineCount && blank(line)) line++
        if (line >= lineCount) break
        starts += line
        val pageTop = top(line)
        var next = line + 1
        while (next < lineCount && bottom(next) - pageTop <= height) next++
        line = next
    }
    if (starts.isEmpty() && lineCount > 0) starts += 0
    return starts
}
