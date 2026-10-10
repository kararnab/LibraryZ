package com.libraryz.data.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The recovery calls match the server's contract (internal/auth/recovery.go). */
class RecoveryApiTest {

    private data class Seen(val path: String, val body: String, val auth: String?)

    private fun recorder(status: HttpStatusCode, seen: MutableList<Seen>) = MockEngine { req ->
        assertEquals(HttpMethod.Post, req.method)
        seen += Seen(req.url.encodedPath, (req.body as? TextContent)?.text ?: "", req.headers["Authorization"])
        respond("""{"status":"accepted"}""", status)
    }

    @Test
    fun resetRequestAndCompletionAreAnonymousWithSnakeCaseBodies() = runTest {
        val seen = mutableListOf<Seen>()
        val api = ApiClient("http://test", tokenProvider = { "access" }, engine = recorder(HttpStatusCode.Accepted, seen))
        api.requestPasswordReset("a@b.org")
        api.completePasswordReset("tok", "new-passphrase")
        assertEquals("/auth/password-reset", seen[0].path)
        assertEquals("""{"email":"a@b.org"}""", seen[0].body)
        assertEquals("/auth/password-reset/complete", seen[1].path)
        assertEquals("""{"token":"tok","new_password":"new-passphrase"}""", seen[1].body)
        // Credential-free endpoints never carry the bearer token.
        seen.forEach { assertNull(it.auth, "${it.path} sent Authorization") }
    }

    @Test
    fun verificationResendIsAuthenticatedAndCompletionIsNot() = runTest {
        val seen = mutableListOf<Seen>()
        val api = ApiClient("http://test", tokenProvider = { "access" }, engine = recorder(HttpStatusCode.Accepted, seen))
        api.requestEmailVerification()
        api.completeEmailVerification("tok")
        assertEquals("/me/email-verification", seen[0].path)
        assertEquals("Bearer access", seen[0].auth)
        assertEquals("/auth/email-verification/complete", seen[1].path)
        assertEquals("""{"token":"tok"}""", seen[1].body)
        assertNull(seen[1].auth)
    }

    @Test
    fun goneTokensSurfaceAs410() = runTest {
        val api = ApiClient("http://test", engine = MockEngine { respondError(HttpStatusCode.Gone, "this link has expired or was already used") })
        val ex = assertFailsWith<ApiException> { api.completePasswordReset("used", "whatever-long") }
        assertEquals(410, ex.status)
        assertEquals("this link has expired or was already used", ex.userMessage)
    }
}
