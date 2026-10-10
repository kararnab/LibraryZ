package com.libraryz.data

import kotlin.test.Test
import kotlin.test.assertEquals

class SpreadTest {

    @Test
    fun coverStandsAloneThenPagesPairLikeABook() {
        val s = Spreads(10)
        assertEquals(listOf(0), s.at(0))
        assertEquals(listOf(1, 2), s.at(1))
        assertEquals(listOf(1, 2), s.at(2))
        assertEquals(listOf(11, 12), Spreads(248).at(12)) // "Pages 12–13"
    }

    @Test
    fun pairFromPageOneForOffsetScans() {
        val s = Spreads(10, coverAlone = false)
        assertEquals(listOf(0, 1), s.at(0))
        assertEquals(listOf(2, 3), s.at(3))
    }

    @Test
    fun theEndCardFillsAnEmptyRightHandSlot() {
        // 10 pages end on page 10, a left-hand page: the card sits beside it.
        assertEquals(listOf(9, 10), Spreads(10).at(9))
        assertEquals(listOf(9, 10), Spreads(10).at(10))
        // 9 pages end on a right-hand page: the card follows alone.
        assertEquals(listOf(7, 8), Spreads(9).at(8))
        assertEquals(listOf(9), Spreads(9).at(9))
    }

    @Test
    fun aLandscapePageStandsAloneAndPairingRestarts() {
        // 1-based 38–39, then 40 alone, then 41–42.
        val s = Spreads(248, alone = setOf(39))
        assertEquals(listOf(37, 38), s.at(38))
        assertEquals(listOf(39), s.at(39))
        assertEquals(listOf(40, 41), s.at(40))
    }

    @Test
    fun outOfRangePagesClamp() {
        assertEquals(listOf(0), Spreads(10).at(-1))
        assertEquals(listOf(9, 10), Spreads(10).at(99))
        assertEquals(emptyList(), Spreads(0).at(0))
    }

    @Test
    fun turningWalksSpreadsAndStopsAtEitherEnd() {
        val s = Spreads(10)
        assertEquals(1, s.turn(0, 1))
        assertEquals(3, s.turn(1, 1))
        assertEquals(3, s.turn(2, 1)) // from the right-hand page too
        assertEquals(9, s.turn(7, 1))
        assertEquals(9, s.turn(9, 1)) // the last spread already holds the end card
        assertEquals(7, s.turn(9, -1))
        assertEquals(1, s.turn(4, -1))
        assertEquals(0, s.turn(1, -1))
        assertEquals(0, s.turn(0, -1))
        val odd = Spreads(9)
        assertEquals(9, odd.turn(7, 1)) // to the lone end card
        assertEquals(7, odd.turn(9, -1))
        assertEquals(1, Spreads(1).turn(0, 1)) // one-page book: cover, then end
    }

    @Test
    fun labels() {
        assertEquals("Page 1 of 248", pagesLabel(listOf(0), 248))
        assertEquals("Pages 12–13 of 248", pagesLabel(listOf(11, 12), 248))
        assertEquals("End of book", pagesLabel(listOf(247, 248), 248))
        assertEquals("End of book", pagesLabel(listOf(248), 248))
        assertEquals("", pagesLabel(emptyList(), 0))
    }
}
