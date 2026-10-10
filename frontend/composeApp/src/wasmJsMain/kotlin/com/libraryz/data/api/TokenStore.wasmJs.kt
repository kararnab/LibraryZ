package com.libraryz.data.api

import kotlinx.browser.localStorage
import org.w3c.dom.get

private const val KEY = "libraryz_token"

class LocalStorageTokenStore(private val key: String = KEY) : TokenStore {
    override suspend fun load(): String? = localStorage[key]
    override suspend fun save(token: String) {
        localStorage.setItem(key, token)
    }
    override suspend fun clear() {
        localStorage.removeItem(key)
    }
}

actual fun createTokenStore(): TokenStore = LocalStorageTokenStore()

actual fun createReaderLayoutStore(): TokenStore = LocalStorageTokenStore("libraryz_reader_layouts")
