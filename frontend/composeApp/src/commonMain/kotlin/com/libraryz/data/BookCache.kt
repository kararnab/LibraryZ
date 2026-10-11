package com.libraryz.data

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libraryz.data.api.DownloadedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * A flat folder of files on the device, private to the app. The book cache
 * keeps each book under its sha256 and its index as [BookCache.INDEX].
 * [write] replaces a file whole (write aside, then rename), so a crash
 * mid-write never leaves a torn book or index.
 */
interface BookFiles {
    suspend fun read(name: String): ByteArray?
    suspend fun write(name: String, bytes: ByteArray)
    suspend fun delete(name: String)
}

/**
 * Where this platform keeps saved books, or null where it doesn't yet
 * (Wasm: deferred, browsers cap storage; iOS: not run yet). Without it
 * [BookCache] does nothing and every book is downloaded.
 */
expect fun createBookFiles(): BookFiles?

/** A book kept on this device: what's needed to list, open and export it offline. */
@Serializable
data class SavedBook(
    val sha256: String,
    val size: Long,
    val format: String,
    val workId: String,
    val title: String,
    val authors: String? = null,
    /** The server's name for the file, so Download can export it offline. */
    val filename: String? = null,
    /** Epoch ms; the least recently opened unpinned books go first. */
    val lastOpened: Long = 0,
    /** "Keep offline": never dropped to make room. */
    val pinned: Boolean = false,
)

@Serializable
private data class Index(
    val limitBytes: Long = BookCache.DEFAULT_LIMIT,
    val books: Map<String, SavedBook> = emptyMap(),
)

/** What the cache needs to keep a book besides its bytes. */
data class BookInfo(
    val format: String,
    val workId: String,
    val title: String,
    val authors: String? = null,
)

/**
 * Books kept on this device, by edition id. An edition's file never
 * changes (a new file is a new edition, and storage is content-addressed),
 * so a saved copy is never stale: [open] reads it from disk and never asks
 * the server. Books opened in the reader are kept automatically; the
 * least recently opened are dropped past [limitBytes], except pinned ones.
 *
 * A book is kept only if its bytes hash to the sha256 the server sent, and
 * that check runs after the reader already has the bytes, so it never
 * delays opening. Device-scoped, like the reader's layouts: books are
 * public, and they stay across sign-out.
 */
@Stable
class BookCache(
    filesFactory: () -> BookFiles? = { null },
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    // Opened on first use, so building the app doesn't touch platform storage.
    private val files: BookFiles? by lazy { runCatching(filesFactory).getOrNull() }
    private val lock = Mutex()
    private var loaded = false

    /** Whether this platform keeps books at all. */
    val enabled: Boolean get() = files != null

    /** Saved books by edition id; observable for Book detail and Settings. */
    var books: Map<String, SavedBook> by mutableStateOf(emptyMap())
        private set

    var limitBytes: Long by mutableStateOf(DEFAULT_LIMIT)
        private set

    val usedBytes: Long get() = books.values.sumOf { it.size }

    /** Reads the index; later calls are free. Safe to call before every use. */
    suspend fun load() {
        lock.withLock { loadLocked() }
    }

    fun isSaved(editionId: String): Boolean = editionId in books

    fun isPinned(editionId: String): Boolean = books[editionId]?.pinned == true

    /**
     * The saved bytes of [editionId], or null if it isn't saved (or its file
     * went missing, which drops it). Marks it as just opened.
     */
    suspend fun open(editionId: String): ByteArray? {
        val f = files ?: return null
        val book = lock.withLock {
            loadLocked()
            books[editionId]
        } ?: return null
        val bytes = runCatching { f.read(book.sha256) }.getOrNull()
        lock.withLock {
            if (bytes == null) {
                books = books - editionId
            } else {
                books[editionId]?.let { books = books + (editionId to it.copy(lastOpened = now())) }
            }
            saveIndexLocked()
        }
        return bytes
    }

    /**
     * The book's bytes: the saved copy if there is one (no request at all,
     * online or not), else [download]'s, which are then kept in the background.
     */
    suspend fun openOrDownload(editionId: String, info: BookInfo, download: suspend () -> DownloadedFile): ByteArray {
        open(editionId)?.let { return it }
        val file = download()
        keepLater(editionId, file.sha256, file.bytes, info, file.filename)
        return file.bytes
    }

    /**
     * Keeps [bytes] as [editionId] in the background, once they hash to
     * [sha256] (no hash from the server: not kept). Returns at once; the
     * job is for tests.
     */
    fun keepLater(editionId: String, sha256: String?, bytes: ByteArray, info: BookInfo, filename: String?, pinned: Boolean = false): Job? {
        if (files == null || sha256 == null) return null
        return background.launch { runCatching { keep(editionId, sha256, bytes, info, filename, pinned) } }
    }

    /** As [keepLater], but waits; false if the bytes don't match [sha256] or can't be written. */
    suspend fun keep(editionId: String, sha256: String, bytes: ByteArray, info: BookInfo, filename: String?, pinned: Boolean = false): Boolean {
        val f = files ?: return false
        val hash = withContext(Dispatchers.Default) { sha256Hex(bytes) }
        if (!hash.equals(sha256, ignoreCase = true)) return false
        return lock.withLock {
            loadLocked()
            val known = books.values.any { it.sha256 == hash }
            if (!known && runCatching { f.write(hash, bytes) }.isFailure) return@withLock false
            val previous = books[editionId]
            books = books + (
                editionId to SavedBook(
                    sha256 = hash,
                    size = bytes.size.toLong(),
                    format = info.format,
                    workId = info.workId,
                    title = info.title,
                    authors = info.authors,
                    filename = filename ?: previous?.filename,
                    lastOpened = now(),
                    pinned = pinned || previous?.pinned == true,
                )
            )
            evictLocked(keep = editionId)
            saveIndexLocked()
            true
        }
    }

    /** Pins or unpins a saved book. Unpinning may drop it if the cache is over its limit. */
    suspend fun setPinned(editionId: String, pinned: Boolean) {
        lock.withLock {
            loadLocked()
            val book = books[editionId] ?: return@withLock
            books = books + (editionId to book.copy(pinned = pinned))
            evictLocked(keep = null)
            saveIndexLocked()
        }
    }

    suspend fun remove(editionId: String) {
        lock.withLock {
            loadLocked()
            val book = books[editionId] ?: return@withLock
            books = books - editionId
            deleteFileIfUnusedLocked(book.sha256)
            saveIndexLocked()
        }
    }

    /** Removes every saved book, pinned ones too. */
    suspend fun clear() {
        lock.withLock {
            loadLocked()
            val hashes = books.values.map { it.sha256 }.toSet()
            books = emptyMap()
            hashes.forEach { deleteFileIfUnusedLocked(it) }
            saveIndexLocked()
        }
    }

    suspend fun setLimit(bytes: Long) {
        lock.withLock {
            loadLocked()
            limitBytes = bytes
            evictLocked(keep = null)
            saveIndexLocked()
        }
    }

    private suspend fun loadLocked() {
        if (loaded) return
        loaded = true
        val f = files ?: return
        val index = runCatching {
            f.read(INDEX)?.decodeToString()?.let { json.decodeFromString(Index.serializer(), it) }
        }.getOrNull() ?: return
        books = index.books
        limitBytes = index.limitBytes
    }

    private suspend fun saveIndexLocked() {
        val f = files ?: return
        val text = json.encodeToString(Index.serializer(), Index(limitBytes, books))
        runCatching { f.write(INDEX, text.encodeToByteArray()) }
    }

    /** Drops the least recently opened unpinned books (not [keep]) until under the limit. */
    private suspend fun evictLocked(keep: String?) {
        if (limitBytes == NO_LIMIT) return
        val victims = books.entries
            .filter { !it.value.pinned && it.key != keep }
            .sortedBy { it.value.lastOpened }
        var used = usedBytes
        for ((id, book) in victims) {
            if (used <= limitBytes) break
            books = books - id
            deleteFileIfUnusedLocked(book.sha256)
            used -= book.size
        }
    }

    private suspend fun deleteFileIfUnusedLocked(sha256: String) {
        if (books.values.none { it.sha256 == sha256 }) runCatching { files?.delete(sha256) }
    }

    companion object {
        const val INDEX = "index.json"
        const val MB = 1024L * 1024
        const val DEFAULT_LIMIT = 1024 * MB
        const val NO_LIMIT = Long.MAX_VALUE
        /** The sizes Settings offers. */
        val LIMITS = listOf(256 * MB, 1024 * MB, 4096 * MB, NO_LIMIT)
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The works of the books saved on the device, most recently opened first:
 * [known] where the app has the full work (the saved library), else one
 * built from what was saved with each book.
 */
fun savedWorks(books: Map<String, SavedBook>, known: (String) -> Work?): List<Work> =
    books.entries.groupBy { it.value.workId }.map { (workId, saved) ->
        val first = saved.maxBy { it.value.lastOpened }.value
        val work = known(workId) ?: Work(id = workId, title = first.title, authors = first.authors)
        val lastOpened = first.lastOpened
        lastOpened to if (work.editions.isNotEmpty()) work else work.copy(
            editions = saved.map { (id, b) -> Edition(id = id, workId = workId, format = b.format, sizeBytes = b.size, sha256 = b.sha256) },
        )
    }.sortedByDescending { it.first }.map { it.second }
