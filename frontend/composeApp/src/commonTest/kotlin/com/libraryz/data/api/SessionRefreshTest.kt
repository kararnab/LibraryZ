package com.libraryz.data.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
private const val ME = """{"id":1,"email":"a@b.com","name":"A","is_moderator":false}"""

private fun pair(access: String, refresh: String) =
    """{"access_token":"$access","refresh_token":"$refresh","token_type":"Bearer","expires_in":900}"""

/** AuthState + ApiClient wired the way App does it. */
private suspend fun signedIn(engine: MockEngine, store: FakeTokenStore = FakeTokenStore()): Pair<AuthState, ApiClient> {
    val auth = AuthState(store)
    val api = ApiClient("http://t", tokenProvider = { auth.token }, engine = engine, sessionHooks = auth)
    auth.signIn(Session("old-access", "refresh-1"))
    return auth to api
}

class SessionRefreshTest {

    @Test
    fun loginReadsTokenPairFromBody() = runTest {
        val engine = MockEngine { respond(pair("acc", "ref"), HttpStatusCode.OK, jsonHeaders) }
        val s = ApiClient("http://t", engine = engine).login(LoginRequest("a@b.com", "pw"))
        assertEquals(Session("acc", "ref"), s)
    }

    @Test
    fun expiredAccessTokenIsRefreshedAndRequestRetried() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/auth/refresh" -> {
                    assertEquals("""{"refresh_token":"refresh-1"}""", (req.body as TextContent).text)
                    respond(pair("new-access", "refresh-2"), HttpStatusCode.OK, jsonHeaders)
                }
                "/auth/me" -> when (req.headers[HttpHeaders.Authorization]) {
                    "Bearer new-access" -> respond(ME, HttpStatusCode.OK, jsonHeaders)
                    else -> respondError(HttpStatusCode.Unauthorized, "invalid or expired token")
                }
                else -> error("unexpected ${req.url}")
            }
        }
        val store = FakeTokenStore()
        val (auth, api) = signedIn(engine, store)

        assertEquals("a@b.com", api.me().email)

        assertEquals("new-access", auth.token)
        assertEquals("refresh-2", auth.refreshToken)
        assertTrue(store.stored!!.contains("refresh-2"), "rotated pair must be persisted")
        assertFalse(auth.sessionExpired)
    }

    @Test
    fun concurrent401sShareOneRefresh() = runTest {
        var refreshes = 0
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/auth/refresh" -> {
                    refreshes++
                    respond(pair("new-access", "refresh-2"), HttpStatusCode.OK, jsonHeaders)
                }
                else -> when (req.headers[HttpHeaders.Authorization]) {
                    "Bearer new-access" -> respond(ME, HttpStatusCode.OK, jsonHeaders)
                    else -> respondError(HttpStatusCode.Unauthorized, "expired")
                }
            }
        }
        val (_, api) = signedIn(engine)

        (1..4).map { async { api.me() } }.awaitAll()

        // Refresh tokens are single-use; a second refresh would look like theft.
        assertEquals(1, refreshes)
    }

    @Test
    fun rejectedRefreshExpiresTheSession() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/auth/refresh" -> respondError(HttpStatusCode.Unauthorized, "invalid refresh token")
                else -> respondError(HttpStatusCode.Unauthorized, "expired")
            }
        }
        val store = FakeTokenStore()
        val (auth, api) = signedIn(engine, store)

        val ex = assertFailsWith<ApiException> { api.listLibrary() }

        assertEquals(401, ex.status)
        assertFalse(auth.isAuthenticated)
        assertTrue(auth.sessionExpired)
        assertNull(store.stored)
        assertEquals(1, store.clearCount)

        auth.acknowledgeSessionExpired()
        assertFalse(auth.sessionExpired)
    }

    @Test
    fun sessionWithoutRefreshTokenExpiresOn401() = runTest {
        // A legacy token-only session can't be renewed: clear it (#6).
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized, "expired") }
        val auth = AuthState(FakeTokenStore(initial = "legacy-token"))
        auth.bootstrap()
        val api = ApiClient("http://t", tokenProvider = { auth.token }, engine = engine, sessionHooks = auth)

        assertFailsWith<ApiException> { api.recommendations() }

        assertFalse(auth.isAuthenticated)
        assertTrue(auth.sessionExpired)
        assertEquals(0, engine.requestHistory.count { it.url.encodedPath == "/auth/refresh" })
    }

    @Test
    fun transientRefreshFailureKeepsTheSession() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/auth/refresh" -> respondError(HttpStatusCode.ServiceUnavailable, "down")
                else -> respondError(HttpStatusCode.Unauthorized, "expired")
            }
        }
        val (auth, api) = signedIn(engine)

        assertFailsWith<ApiException> { api.listLibrary() }

        assertTrue(auth.isAuthenticated, "a 5xx from /auth/refresh must not log the user out")
        assertFalse(auth.sessionExpired)
    }

    @Test
    fun loginWrongPasswordDoesNotTouchSession() = runTest {
        // /auth/login's 401 means "wrong password", not "session expired".
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized, "invalid credentials") }
        val (auth, api) = signedIn(engine)

        assertFailsWith<ApiException> { api.login(LoginRequest("a@b.com", "nope")) }

        assertTrue(auth.isAuthenticated)
        assertEquals(0, engine.requestHistory.count { it.url.encodedPath == "/auth/refresh" })
    }

    @Test
    fun publicCallsNeverTriggerRefresh() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized, "nope") }
        val (auth, api) = signedIn(engine)

        assertFailsWith<ApiException> { api.listWorks() }

        assertTrue(auth.isAuthenticated)
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun sessionRoundTripsThroughTheStore() = runTest {
        val store = FakeTokenStore()
        AuthState(store).signIn(Session("acc", "ref"))

        val restored = AuthState(store)
        restored.bootstrap()

        assertEquals("acc", restored.token)
        assertEquals("ref", restored.refreshToken)
    }

    @Test
    fun logoutPostsRefreshToken() = runTest {
        val engine = MockEngine { req ->
            assertEquals("/auth/logout", req.url.encodedPath)
            assertEquals("""{"refresh_token":"ref"}""", (req.body as TextContent).text)
            respond("", HttpStatusCode.NoContent)
        }
        ApiClient("http://t", engine = engine).logout("ref")
        assertEquals(1, engine.requestHistory.size)
    }
}
