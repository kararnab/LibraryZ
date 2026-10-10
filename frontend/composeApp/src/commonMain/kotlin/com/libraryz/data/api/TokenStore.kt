package com.libraryz.data.api

/**
 * Persistent store for the session: [AuthState] saves the access and
 * refresh tokens together as one opaque value. Implementations choose the
 * platform's obvious option:
 *   - Android: file under [Context.filesDir]
 *   - Desktop: `${user.home}/.libraryz/token`
 *   - Wasm: `window.localStorage`
 *   - iOS: `NSUserDefaults`
 *
 * Interface-first so `commonTest` can substitute a fake without going
 * through the platform's actual file/keystore/localStorage paths.
 *
 * It's deliberately tiny — one key/value, no schema. We'll graduate to
 * EncryptedSharedPreferences / Keychain / DPAPI in a later pass; the
 * refresh token (30-day session) makes that more pressing than it was for
 * a bare access token.
 */
interface TokenStore {
    suspend fun load(): String?
    suspend fun save(token: String)
    suspend fun clear()
}

expect fun createTokenStore(): TokenStore
