package com.libraryz.data

import com.libraryz.data.api.TokenStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A book's reader layout: Auto / Single / Two pages, and how pages pair. */
@Serializable
data class BookLayout(
    val layout: PageLayout = PageLayout.Auto,
    /** Pair 1–2, 3–4 instead of the printed book's 2–3, 4–5 (offset scans). */
    val pairFromFirst: Boolean = false,
)

/**
 * The layout chosen for each book, kept on this device (it depends on the
 * book and the screen, not the account). Keyed by edition, since a scan's
 * pairing belongs to that file. Only non-default choices are stored, and
 * the oldest are dropped past [MAX_BOOKS].
 */
class BookLayouts(storeFactory: () -> TokenStore = { MemoryStore() }) {
    // Opened on first use, so building the app doesn't touch platform storage;
    // if it can't be opened, choices last for the session.
    private val store: TokenStore by lazy { runCatching(storeFactory).getOrElse { MemoryStore() } }
    private val lock = Mutex()
    private var cache: LinkedHashMap<String, BookLayout>? = null

    private suspend fun all(): LinkedHashMap<String, BookLayout> {
        cache?.let { return it }
        val loaded = runCatching {
            store.load()?.let { json.decodeFromString(mapSerializer, it) }
        }.getOrNull().orEmpty()
        return LinkedHashMap(loaded).also { cache = it }
    }

    suspend fun get(editionId: String): BookLayout = lock.withLock { all()[editionId] ?: BookLayout() }

    suspend fun set(editionId: String, layout: BookLayout) {
        lock.withLock {
            val map = all()
            map.remove(editionId)
            if (layout != BookLayout()) map[editionId] = layout
            while (map.size > MAX_BOOKS) map.remove(map.keys.first())
            runCatching { store.save(json.encodeToString(mapSerializer, map)) }
        }
    }

    private class MemoryStore : TokenStore {
        private var value: String? = null
        override suspend fun load(): String? = value
        override suspend fun save(token: String) { value = token }
        override suspend fun clear() { value = null }
    }

    companion object {
        const val MAX_BOOKS = 500
        private val json = Json { ignoreUnknownKeys = true }
        private val mapSerializer = MapSerializer(String.serializer(), BookLayout.serializer())
    }
}
