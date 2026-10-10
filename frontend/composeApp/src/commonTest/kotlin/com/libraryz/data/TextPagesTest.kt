package com.libraryz.data

import kotlin.test.Test
import kotlin.test.assertEquals

class TextPagesTest {

    @Test
    fun findsChapterHeadingsThatStartAParagraph() {
        val text = "PRELUDE\n\nWho that cares much to know...\n\nCHAPTER I.\n\nMiss Brooke had that kind of beauty\n" +
            "\nChapter 2: The Storm\nIt rained.\nPart one of the plan was simple.\n\nLETTER 4\n"
        val found = chaptersOf(text)
        assertEquals(listOf("CHAPTER I", "Chapter 2: The Storm", "LETTER 4"), found.map { it.title })
        assertEquals(text.indexOf("CHAPTER I."), found[0].offset)
    }

    @Test
    fun ignoresHeadingWordsInsideProse() {
        // Not after a blank line, and not just a number after "Book".
        assertEquals(emptyList(), chaptersOf("He opened the\nBook of Kells and read.\nPart of the day was gone."))
    }

    @Test
    fun pagesLeftLabels() {
        assertEquals("6 pages left in chapter", pagesLeftLabel(6, inChapter = true))
        assertEquals("1 page left in book", pagesLeftLabel(1, inChapter = false))
        assertEquals("Last page of chapter", pagesLeftLabel(0, inChapter = true))
    }

    @Test
    fun pageBreaksFillPagesWithWholeLines() {
        // 10 lines, 30 px each; a 100 px page holds 3.
        val starts = pageBreaks(10, top = { it * 30f }, bottom = { (it + 1) * 30f }, height = 100f)
        assertEquals(listOf(0, 3, 6, 9), starts)
    }

    @Test
    fun pagesDontStartOnABlankLine() {
        val blank = setOf(3, 4)
        val starts = pageBreaks(8, top = { it * 30f }, bottom = { (it + 1) * 30f }, height = 90f, blank = { it in blank })
        assertEquals(listOf(0, 5), starts)
    }

    @Test
    fun aTallLineStillGetsAPage() {
        assertEquals(listOf(0, 1), pageBreaks(2, top = { it * 200f }, bottom = { (it + 1) * 200f }, height = 100f))
        assertEquals(emptyList(), pageBreaks(0, top = { 0f }, bottom = { 0f }, height = 100f))
    }
}
