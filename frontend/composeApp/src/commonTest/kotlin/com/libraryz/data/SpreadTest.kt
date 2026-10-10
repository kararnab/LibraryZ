package com.libraryz.data

import kotlin.test.Test
import kotlin.test.assertEquals

class SpreadTest {

    @Test
    fun coverStandsAloneThenPagesPairLikeABook() {
        assertEquals(listOf(0), spreadPages(0, 10))
        assertEquals(listOf(1, 2), spreadPages(1, 10))
        assertEquals(listOf(1, 2), spreadPages(2, 10))
        assertEquals(listOf(11, 12), spreadPages(12, 248)) // "Pages 12–13"
    }

    @Test
    fun lastSpreadCanHoldOnePage() {
        assertEquals(listOf(9), spreadPages(9, 10))
        assertEquals(listOf(7, 8), spreadPages(8, 9))
    }

    @Test
    fun outOfRangePagesClamp() {
        assertEquals(listOf(9), spreadPages(10, 10)) // the end card's index
        assertEquals(listOf(0), spreadPages(-1, 10))
        assertEquals(emptyList(), spreadPages(0, 0))
    }

    @Test
    fun turningForwardWalksSpreadsThenTheEndCard() {
        assertEquals(1, turnSpread(0, 1, 10))
        assertEquals(3, turnSpread(1, 1, 10))
        assertEquals(3, turnSpread(2, 1, 10)) // from the right-hand page too
        assertEquals(9, turnSpread(7, 1, 10))
        assertEquals(10, turnSpread(9, 1, 10)) // past the last spread
        assertEquals(10, turnSpread(10, 1, 10)) // stays on the end card
        assertEquals(1, turnSpread(0, 1, 1)) // one-page book: cover, then end
    }

    @Test
    fun turningBackWalksSpreadsToTheCover() {
        assertEquals(9, turnSpread(10, -1, 10)) // end card back to the last spread
        assertEquals(7, turnSpread(10, -1, 9))
        assertEquals(1, turnSpread(3, -1, 10))
        assertEquals(1, turnSpread(4, -1, 10))
        assertEquals(0, turnSpread(1, -1, 10))
        assertEquals(0, turnSpread(0, -1, 10))
    }

    @Test
    fun labels() {
        assertEquals("Page 1 of 248", pagesLabel(listOf(0), 248))
        assertEquals("Pages 12–13 of 248", pagesLabel(listOf(11, 12), 248))
        assertEquals("", pagesLabel(emptyList(), 0))
    }
}
