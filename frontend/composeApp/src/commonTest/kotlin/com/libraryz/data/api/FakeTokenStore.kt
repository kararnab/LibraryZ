package com.libraryz.data.api

class FakeTokenStore(initial: String? = null) : TokenStore {
    var stored: String? = initial
    var saveCount: Int = 0
    var clearCount: Int = 0

    override suspend fun load(): String? = stored
    override suspend fun save(token: String) {
        stored = token
        saveCount++
    }
    override suspend fun clear() {
        stored = null
        clearCount++
    }
}
