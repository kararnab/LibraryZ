package com.libraryz.data

import com.libraryz.data.api.LibraryStamps
import com.libraryz.data.api.UpsertLibraryRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryMergeTest {

    @Test
    fun clockOrdersByStringAndNeverGoesBack() {
        var wall = 1_000L
        val c = Hlc("dev") { wall }
        val a = c.next()
        val b = c.next()           // same millisecond: counter
        wall = 500                 // clock stepped back
        val d = c.next()
        assertTrue(a < b && b < d, "$a $b $d")
        assertEquals("0000000001000.0000.dev", a)
        // A stamp from a device ahead of us: our next one sorts after it.
        c.observe("0000000009000.0003.other")
        assertTrue(c.next() > "0000000009000.0003.other")
        assertEquals("other", Hlc.deviceOf("0000000009000.0003.other"))
        assertEquals(12, Hlc.newDevice().length)
    }

    @Test
    fun localApplyFollowsTheServersRules() {
        val c = Hlc("dev") { 1_000L }
        val read = stampRequest(UpsertLibraryRequest(status = LibraryStatus.Read), null, c)
        val ub = applyLocally(null, "w1", read, "2026-10-11T10:00:00Z", null, 7)
        assertEquals(LibraryStatus.Read, ub.status)
        assertEquals(100, ub.progressPercent)
        assertEquals("2026-10-11T10:00:00Z", ub.finishedAt)
        assertEquals(7, ub.userId)
        // An older change to a group loses.
        val old = UpsertLibraryRequest(currentPage = 3, stamps = LibraryStamps(progress = "0000000000001.0000.x"))
        assertEquals(0, applyLocally(ub, "w1", old, "t", null, 7).currentPage)
        // A notes edit records the text it started from.
        val notes = stampRequest(UpsertLibraryRequest(notes = "hi"), ub.copy(notesStamp = "n1"), c)
        assertEquals("n1", notes.notesBase)
        assertNull(stampRequest(UpsertLibraryRequest(rating = 3), ub, c).notesBase)
    }

    @Test
    fun queueMergesChangesPerWorkAndRemovalsDropOlderOnes() {
        val c = Hlc("dev") { 1_000L }
        fun up(r: UpsertLibraryRequest) = PendingWrite("w1", upsert = stampRequest(r, null, c))
        var q = enqueue(emptyList(), up(UpsertLibraryRequest(currentPage = 10, rating = 2)))
        q = enqueue(q, up(UpsertLibraryRequest(currentPage = 20)))
        q = enqueue(q, PendingWrite("w2", upsert = stampRequest(UpsertLibraryRequest(rating = 5), null, c)))
        assertEquals(2, q.size)
        val merged = q[0].upsert!!
        assertEquals(20, merged.currentPage)
        assertEquals(2, merged.rating)
        assertTrue(merged.stamps!!.progress!! > merged.stamps!!.rating!!)

        q = enqueue(q, PendingWrite("w1", removedAt = c.next()))
        assertEquals(listOf("w2", "w1"), q.map { it.workId })
        // Added back after the removal: a separate change, sent after it.
        q = enqueue(q, up(UpsertLibraryRequest(status = LibraryStatus.Want)))
        assertEquals(3, q.size)
    }
}
