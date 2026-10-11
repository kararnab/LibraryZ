package com.libraryz.data

import com.libraryz.data.api.DownloadedFile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookCacheTest {

    private class Disk : BookFiles {
        val files = mutableMapOf<String, ByteArray>()
        override suspend fun read(name: String) = files[name]
        override suspend fun write(name: String, bytes: ByteArray) { files[name] = bytes }
        override suspend fun delete(name: String) { files.remove(name) }
    }

    private val info = BookInfo("TXT", "w1", "Middlemarch", "George Eliot")
    private fun book(n: Int) = ByteArray(n) { (it * 7 + n).toByte() }

    @Test
    fun sha256MatchesKnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".encodeToByteArray()))
        // Two blocks: the length no longer fits in the first.
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
    }

    @Test
    fun savedBookOpensWithoutDownloading() = runTest {
        val disk = Disk()
        val cache = BookCache({ disk }, background = backgroundScope)
        val bytes = book(100)
        var downloads = 0
        val download = suspend { downloads++; DownloadedFile("m.txt", bytes, sha256Hex(bytes)) }

        assertContentEquals(bytes, cache.openOrDownload("e1", info, download))
        cache.keepLater("e1", sha256Hex(bytes), bytes, info, "m.txt")?.join()
        assertTrue(cache.isSaved("e1"))
        assertContentEquals(bytes, cache.openOrDownload("e1", info, download))
        assertEquals(1, downloads)

        // A fresh start reads the index from disk.
        val again = BookCache({ disk })
        assertContentEquals(bytes, again.open("e1"))
        assertEquals("m.txt", again.books["e1"]?.filename)
    }

    @Test
    fun keepsOnlyBytesMatchingTheServersHash() = runTest {
        val disk = Disk()
        val cache = BookCache({ disk })
        assertFalse(cache.keep("e1", sha256Hex(book(5)), book(6), info, null))
        assertFalse(cache.isSaved("e1"))
        assertNull(cache.keepLater("e1", null, book(6), info, null))
        assertTrue(cache.keep("e1", sha256Hex(book(6)).uppercase(), book(6), info, null))
    }

    @Test
    fun missingFileDropsTheEntry() = runTest {
        val disk = Disk()
        val cache = BookCache({ disk })
        cache.keep("e1", sha256Hex(book(10)), book(10), info, null)
        disk.files.remove(sha256Hex(book(10)))
        assertNull(cache.open("e1"))
        assertFalse(cache.isSaved("e1"))
    }

    @Test
    fun dropsLeastRecentlyOpenedButNeverPinned() = runTest {
        val disk = Disk()
        var clock = 0L
        val cache = BookCache({ disk }, now = { ++clock })
        cache.setLimit(250)
        cache.keep("a", sha256Hex(book(100)), book(100), info, null, pinned = true)
        cache.keep("b", sha256Hex(book(101)), book(101), info, null)
        cache.keep("c", sha256Hex(book(102)), book(102), info, null)
        // 303 bytes > 250: "b" is the oldest unpinned.
        assertEquals(setOf("a", "c"), cache.books.keys)
        assertNull(disk.files[sha256Hex(book(101))])

        cache.open("c")
        cache.keep("d", sha256Hex(book(103)), book(103), info, null)
        // The book just kept stays even if it's the newest of the unpinned.
        assertEquals(setOf("a", "d"), cache.books.keys)

        // A pinned book outlasts a limit it's over; unpinned, it goes.
        cache.setLimit(50)
        assertEquals(setOf("a"), cache.books.keys)
        cache.setPinned("a", false)
        assertTrue(cache.books.isEmpty())
    }

    @Test
    fun clearRemovesEverythingAndDisabledCacheDoesNothing() = runTest {
        val disk = Disk()
        val cache = BookCache({ disk })
        cache.keep("a", sha256Hex(book(10)), book(10), info, null, pinned = true)
        cache.clear()
        assertTrue(cache.books.isEmpty())
        assertEquals(setOf(BookCache.INDEX), disk.files.keys)

        val off = BookCache({ null })
        assertFalse(off.enabled)
        assertFalse(off.keep("a", sha256Hex(book(10)), book(10), info, null))
        var downloads = 0
        off.openOrDownload("a", info) { downloads++; DownloadedFile(null, book(10), sha256Hex(book(10))) }
        off.openOrDownload("a", info) { downloads++; DownloadedFile(null, book(10), sha256Hex(book(10))) }
        assertEquals(2, downloads)
    }
}
