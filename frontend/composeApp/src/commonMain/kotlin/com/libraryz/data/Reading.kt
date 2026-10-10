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
