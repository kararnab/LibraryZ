package com.libraryz.data.api

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libraryz.data.User

/**
 * Holds the current JWT in memory plus mirrors it to a [TokenStore], and
 * (once a [userFetcher] is attached) keeps a refreshed [User] snapshot
 * for the UI to gate moderator-only affordances on.
 *
 * - [bootstrap] is called once on app launch and reads any persisted token.
 *   `bootstrapped` flips true after — the UI shows a splash until then to
 *   avoid flashing the auth screen.
 * - [signIn] is called after a successful login. Suspends until the token
 *   is written, so the caller (App.kt) can navigate to Browse with the
 *   guarantee that a restart will restore the session.
 * - [clear] is called on logout and on 401s.
 *
 * The userFetcher is a `suspend () -> User?` set via [setUserFetcher]
 * after construction because ApiClient itself needs `tokenProvider = {
 * auth.token }`, creating a circular construction with the obvious
 * "AuthState takes ApiClient" wiring. Fetcher failures are swallowed
 * (e.g. offline at startup) — the session stays authenticated, `user`
 * just remains null until a later refresh succeeds.
 */
@Stable
class AuthState(private val store: TokenStore) {
    var session: Session? by mutableStateOf(null)
        private set

    var bootstrapped: Boolean by mutableStateOf(false)
        private set

    var user: User? by mutableStateOf(null)
        private set

    private var userFetcher: (suspend () -> User?)? = null

    val isAuthenticated: Boolean get() = session != null
    val token: String? get() = session?.token
    val isModerator: Boolean get() = user?.isModerator == true

    /** Attach the fetcher used to populate [user] after bootstrap / signIn. */
    fun setUserFetcher(fetch: suspend () -> User?) {
        userFetcher = fetch
    }

    suspend fun bootstrap() {
        if (bootstrapped) return
        val saved = store.load()
        if (saved != null) {
            session = Session(saved)
            refreshUser()
        }
        bootstrapped = true
    }

    suspend fun signIn(s: Session) {
        store.save(s.token)
        session = s
        refreshUser()
    }

    suspend fun clear() {
        store.clear()
        session = null
        user = null
    }

    private suspend fun refreshUser() {
        val fetch = userFetcher ?: return
        user = runCatching { fetch.invoke() }.getOrNull()
    }
}
