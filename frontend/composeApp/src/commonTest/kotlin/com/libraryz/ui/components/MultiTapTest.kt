package com.libraryz.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class MultiTapTest {
    private val clock = TestTimeSource()
    private val counter = MultiTapCounter(taps = 3, within = 400.milliseconds)

    // Clicks after the given gaps (ms); the indices of the ones that fire.
    private fun fires(vararg gaps: Int): List<Int> = gaps.withIndex().mapNotNull { (i, gap) ->
        clock += gap.milliseconds
        i.takeIf { counter.tap(clock.markNow()) }
    }

    @Test
    fun firesOnTheThirdQuickTapAndStartsOver() {
        assertEquals(listOf(2, 5), fires(0, 100, 100, 100, 100, 100))
    }

    @Test
    fun aSlowTapRestartsTheCount() {
        assertEquals(listOf(4), fires(0, 100, 500, 100, 100))
    }

    @Test
    fun oneTapFiresEveryTime() {
        val single = MultiTapCounter(taps = 1, within = 400.milliseconds)
        assertEquals(true, single.tap(clock.markNow()))
        assertEquals(true, single.tap(clock.markNow()))
    }
}
