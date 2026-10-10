package com.libraryz.data.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.client.engine.mock.toByteArray
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
        // Nothing to renew with: clear the session instead of retrying (#6).
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized, "expired") }
        val auth = AuthState(FakeTokenStore())
        auth.signIn(Session("access-only"))
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
    fun loginStoresBothTokens() = runTest {
        // The App flow: login → AuthState.signIn → persisted pair.
        val engine = MockEngine { respond(pair("acc", "ref"), HttpStatusCode.OK, jsonHeaders) }
        val store = FakeTokenStore()
        val auth = AuthState(store)
        auth.signIn(ApiClient("http://t", engine = engine).login(LoginRequest("a@b.com", "pw")))

        assertEquals("acc", auth.token)
        assertEquals("ref", auth.refreshToken)
        assertTrue(store.stored!!.contains("acc") && store.stored!!.contains("ref"))
    }

    @Test
    fun credentialEndpointsNeverCarryTheAccessToken() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/auth/login", "/auth/signup" -> respond(pair("acc", "ref"), HttpStatusCode.OK, jsonHeaders)
                "/auth/logout" -> respond("", HttpStatusCode.NoContent)
                else -> respond(ME, HttpStatusCode.OK, jsonHeaders)
            }
        }
        val (_, api) = signedIn(engine)

        api.login(LoginRequest("a@b.com", "pw"))
        api.logout("refresh-1")
        api.me()

        val byPath = engine.requestHistory.associateBy { it.url.encodedPath }
        assertNull(byPath.getValue("/auth/login").headers[HttpHeaders.Authorization])
        assertNull(byPath.getValue("/auth/logout").headers[HttpHeaders.Authorization])
        assertEquals("Bearer old-access", byPath.getValue("/auth/me").headers[HttpHeaders.Authorization])
    }

    @Test
    fun uploadIsResentAfterRefresh() = runTest {
        // Multipart bodies must survive the plugin's retry.
        val engine = MockEngine { req ->
            when {
                req.url.encodedPath == "/auth/refresh" ->
                    respond(pair("new-access", "refresh-2"), HttpStatusCode.OK, jsonHeaders)
                req.headers[HttpHeaders.Authorization] != "Bearer new-access" ->
                    respondError(HttpStatusCode.Unauthorized, "expired")
                else -> {
                    val bytes = req.body.toByteArray()
                    assertTrue(bytes.decodeToString().contains("file-bytes"), "retry lost the body")
                    respond(
                        """{"id":"e1","work_id":"w1","format":"txt","sha256":"x","size_bytes":10}""",
                        HttpStatusCode.Created, jsonHeaders,
                    )
                }
            }
        }
        val (_, api) = signedIn(engine)

        api.uploadEdition("w1", "txt", null, "a.txt", "file-bytes".encodeToByteArray())
        assertEquals(1, engine.requestHistory.count { it.url.encodedPath == "/auth/refresh" })
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
