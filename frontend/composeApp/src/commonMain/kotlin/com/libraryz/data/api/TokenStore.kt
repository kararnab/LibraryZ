package com.libraryz.data.api

/**
 * Persistent store for the JWT. Implementations choose the platform's
 * obvious option:
 *   - Android: file under [Context.filesDir]
 *   - Desktop: `${user.home}/.libraryz/token`
 *   - Wasm: `window.localStorage`
 *   - iOS: `NSUserDefaults`
 *
 * Interface-first so `commonTest` can substitute a fake without going
 * through the platform's actual file/keystore/localStorage paths.
 *
 * It's deliberately tiny — one key/value, no schema. We'll graduate to
 * EncryptedSharedPreferences / Keychain / DPAPI in a later pass once we
 * decide whether the JWT is sensitive enough to warrant it.
 */
interface TokenStore {
    suspend fun load(): String?
    suspend fun save(token: String)
    suspend fun clear()
}

expect fun createTokenStore(): TokenStore
