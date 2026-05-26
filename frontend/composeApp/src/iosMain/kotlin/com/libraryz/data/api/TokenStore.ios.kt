package com.libraryz.data.api

import platform.Foundation.NSUserDefaults

private const val KEY = "libraryz_token"

class UserDefaultsTokenStore : TokenStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun load(): String? = defaults.stringForKey(KEY)
    override suspend fun save(token: String) {
        defaults.setObject(token, KEY)
    }
    override suspend fun clear() {
        defaults.removeObjectForKey(KEY)
    }
}

actual fun createTokenStore(): TokenStore = UserDefaultsTokenStore()
