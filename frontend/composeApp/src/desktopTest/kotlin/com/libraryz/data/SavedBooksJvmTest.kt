package com.libraryz.data

import com.libraryz.data.api.ApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The reader's path end to end on a real folder: download once, then open with the server gone. */
class SavedBooksJvmTest {

    @Test
    fun opensFromDiskWithoutTheServer() = runTest {
        val book = ByteArray(300_000) { (it * 31 % 251).toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(book).joinToString("") { "%02x".format(it) }
        assertEquals(hash, sha256Hex(book))

        var requests = 0
        var online = true
        val engine = MockEngine { req ->
            requests++
            if (!online) throw IOException("connection refused")
            assertEquals("/editions/e1/download", req.url.encodedPath)
            respond(
                ByteReadChannel(book), HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentDisposition to listOf("attachment; filename=\"Middlemarch.txt\""),
                    "X-Content-SHA256" to listOf(hash),
                ),
            )
        }
        val api = ApiClient("http://libraryz.test", engine = engine)
        val dir = Files.createTempDirectory("books").toFile()
        val info = BookInfo("TXT", "w1", "Middlemarch")

        val cache = BookCache({ DirBookFiles(dir) }, background = backgroundScope)
        val first = cache.openOrDownload("e1", info) { api.downloadEditionFile("e1") }
        assertContentEquals(book, first)
        // What the reader's background keep does with the same response.
        cache.keepLater("e1", hash, first, info, "Middlemarch.txt")?.join()
        assertTrue(dir.resolve(hash).isFile)

        // A later start, offline: no request is made.
        online = false
        val later = BookCache({ DirBookFiles(dir) })
        assertContentEquals(book, later.openOrDownload("e1", info) { api.downloadEditionFile("e1") })
        assertEquals(1, requests)
        assertEquals("Middlemarch.txt", later.books["e1"]?.filename)
        dir.deleteRecursively()
    }
}
