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

/**
 * The pages a two-page spread shows for [page], laid out like a printed
 * book: the cover (page 0) stands alone, then 1–2, 3–4, … (1-based, the
 * cover is page 1 and spreads are 2–3, 4–5, …). The last spread may hold a
 * single page.
 */
fun spreadPages(page: Int, pageCount: Int): List<Int> {
    if (pageCount <= 0) return emptyList()
    val p = page.coerceIn(0, pageCount - 1)
    if (p == 0) return listOf(0)
    val left = if (p % 2 == 1) p else p - 1
    return listOfNotNull(left, (left + 1).takeIf { it < pageCount })
}

/**
 * Where turning by [delta] (±1) leads in spread mode: the first page of the
 * next or previous spread, or [pageCount] (the end card) past the last one.
 */
fun turnSpread(page: Int, delta: Int, pageCount: Int): Int {
    if (pageCount <= 0) return 0
    if (page >= pageCount) return if (delta < 0) spreadPages(pageCount - 1, pageCount).first() else pageCount
    val shown = spreadPages(page, pageCount)
    return when {
        delta > 0 -> shown.last() + 1
        shown.first() == 0 -> 0
        else -> spreadPages(shown.first() - 1, pageCount).first()
    }
}

/** "Page 7 of 248" or, for a spread, "Pages 12–13 of 248". */
fun pagesLabel(pages: List<Int>, pageCount: Int): String = when (pages.size) {
    0 -> ""
    1 -> "Page ${pages[0] + 1} of $pageCount"
    else -> "Pages ${pages.first() + 1}–${pages.last() + 1} of $pageCount"
}
