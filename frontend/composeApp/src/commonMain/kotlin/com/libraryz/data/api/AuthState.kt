package com.libraryz.data.api

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.libraryz.data.User
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Holds the current session (access + refresh token) in memory, mirrors it
 * to a [TokenStore], and
 * (once a [userFetcher] is attached) keeps a refreshed [User] snapshot
 * for the UI to gate moderator-only affordances on.
 *
 * - [bootstrap] is called once on app launch and reads any persisted token.
 *   `bootstrapped` flips true after — the UI shows a splash until then to
 *   avoid flashing the auth screen.
 * - [signIn] is called after a successful login. Suspends until the token
 *   is written, so the caller (App.kt) can navigate to Browse with the
 *   guarantee that a restart will restore the session.
 * - [clear] is called on logout.
 * - As the [SessionHooks] of [ApiClient] it persists rotated tokens
 *   ([onRefreshed]) and, when the session can't be renewed, clears it and
 *   raises [sessionExpired] ([onSessionExpired]) so the UI can return to
 *   sign-in and say why.
 *
 * Persistence: the store holds `{"access":…,"refresh":…}`.
 *
 * The userFetcher is a `suspend () -> User?` set via [setUserFetcher]
 * after construction because ApiClient itself needs `tokenProvider = {
 * auth.token }`, creating a circular construction with the obvious
 * "AuthState takes ApiClient" wiring. Fetcher failures are swallowed
 * (e.g. offline at startup) — the session stays authenticated, `user`
 * just remains null until a later refresh succeeds.
 */
@Stable
class AuthState(private val store: TokenStore) : SessionHooks {
    var session: Session? by mutableStateOf(null)
        private set

    var bootstrapped: Boolean by mutableStateOf(false)
        private set

    var user: User? by mutableStateOf(null)
        private set

    /** True after the session was ended because it couldn't be renewed. */
    var sessionExpired: Boolean by mutableStateOf(false)
        private set

    private var userFetcher: (suspend () -> User?)? = null

    val isAuthenticated: Boolean get() = session != null
    val token: String? get() = session?.token
    override val refreshToken: String? get() = session?.refreshToken
    val isModerator: Boolean get() = user?.isModerator == true

    /** Attach the fetcher used to populate [user] after bootstrap / signIn. */
    fun setUserFetcher(fetch: suspend () -> User?) {
        userFetcher = fetch
    }

    suspend fun bootstrap() {
        if (bootstrapped) return
        val saved = store.load()?.let(::decodeSession)
        if (saved != null) {
            session = saved
            reloadUser()
        }
        bootstrapped = true
    }

    suspend fun signIn(s: Session) {
        store.save(encodeSession(s))
        session = s
        sessionExpired = false
        reloadUser()
    }

    suspend fun clear() {
        store.clear()
        session = null
        user = null
    }

    override suspend fun onRefreshed(session: Session) {
        store.save(encodeSession(session))
        this.session = session
    }

    override suspend fun onSessionExpired() {
        if (session == null) return // already cleared by a concurrent call
        clear()
        sessionExpired = true
    }

    /** The UI calls this once it has told the user their session expired. */
    fun acknowledgeSessionExpired() {
        sessionExpired = false
    }

    /** Re-reads the signed-in user (e.g. after verifying their email). */
    suspend fun reloadUser() {
        val fetch = userFetcher ?: return
        user = runCatching { fetch.invoke() }.getOrNull()
    }
}

@Serializable
private data class StoredSession(val access: String, val refresh: String? = null)

private val sessionJson = Json { ignoreUnknownKeys = true }

private fun encodeSession(s: Session): String =
    sessionJson.encodeToString(StoredSession.serializer(), StoredSession(s.token, s.refreshToken))

private fun decodeSession(raw: String): Session? =
    runCatching { sessionJson.decodeFromString(StoredSession.serializer(), raw) }
        .getOrNull()
        ?.let { Session(it.access, it.refresh) }
