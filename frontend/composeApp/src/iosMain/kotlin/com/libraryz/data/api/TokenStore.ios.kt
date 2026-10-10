package com.libraryz.data.api

import platform.Foundation.NSUserDefaults

private const val KEY = "libraryz_token"

class UserDefaultsTokenStore(private val key: String = KEY) : TokenStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun load(): String? = defaults.stringForKey(key)
    override suspend fun save(token: String) {
        defaults.setObject(token, key)
    }
    override suspend fun clear() {
        defaults.removeObjectForKey(key)
    }
}

actual fun createTokenStore(): TokenStore = UserDefaultsTokenStore()

actual fun createReaderLayoutStore(): TokenStore = UserDefaultsTokenStore("libraryz_reader_layouts")
