package com.libraryz.data.api

import kotlinx.browser.localStorage
import org.w3c.dom.get

private const val KEY = "libraryz_token"

class LocalStorageTokenStore : TokenStore {
    override suspend fun load(): String? = localStorage[KEY]
    override suspend fun save(token: String) {
        localStorage.setItem(KEY, token)
    }
    override suspend fun clear() {
        localStorage.removeItem(KEY)
    }
}

actual fun createTokenStore(): TokenStore = LocalStorageTokenStore()
