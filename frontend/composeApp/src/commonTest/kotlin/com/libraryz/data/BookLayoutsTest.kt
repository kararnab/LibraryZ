package com.libraryz.data

import com.libraryz.data.api.FakeTokenStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BookLayoutsTest {

    @Test
    fun choicesAreRememberedPerBookAcrossInstances() = runTest {
        val store = FakeTokenStore()
        BookLayouts { store }.set("e1", BookLayout(PageLayout.Two, pairFromFirst = true))
        val reopened = BookLayouts { store }
        assertEquals(BookLayout(PageLayout.Two, pairFromFirst = true), reopened.get("e1"))
        assertEquals(BookLayout(), reopened.get("e2")) // new books start on Auto
    }

    @Test
    fun goingBackToTheDefaultForgetsTheBook() = runTest {
        val store = FakeTokenStore()
        val layouts = BookLayouts { store }
        layouts.set("e1", BookLayout(PageLayout.Single))
        layouts.set("e1", BookLayout())
        assertEquals("{}", store.stored)
    }

    @Test
    fun unreadableStorageStartsEmpty() = runTest {
        assertEquals(BookLayout(), BookLayouts { FakeTokenStore("not json") }.get("e1"))
        assertEquals(BookLayout(), BookLayouts { error("no storage") }.get("e1"))
    }
}
