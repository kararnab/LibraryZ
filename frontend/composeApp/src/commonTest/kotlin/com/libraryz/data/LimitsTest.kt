package com.libraryz.data

import kotlin.test.Test
import kotlin.test.assertEquals

class LimitsTest {
    @Test
    fun cappedKeepsShortTextAndNeverSplitsAPair() {
        assertEquals("abc", "abc".capped(5))
        assertEquals("abc", "abcdef".capped(3))
        // "a😀b": the emoji is two UTF-16 units; cutting between them drops it.
        assertEquals("a", "a😀b".capped(2))
        assertEquals("a😀", "a😀b".capped(3))
    }
}
