package com.libraryz.data.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val BASE = "http://test"

private fun json(payload: String, status: HttpStatusCode = HttpStatusCode.OK) =
    Triple(
        status,
        payload,
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

class ApiClientTest {

    @Test
    fun signupSendsPostAndAcceptsCreated() = runTest {
        val engine = MockEngine { req ->
            assertEquals(HttpMethod.Post, req.method)
            assertEquals("/auth/signup", req.url.encodedPath)
            respond("", HttpStatusCode.Created)
        }
        val api = ApiClient(BASE, engine = engine)
        api.signUp(SignUpRequest("a@b.com", "pw", "Ada"))
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun signupBubblesNon201AsApiException() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.BadRequest, "bad email") }
        val api = ApiClient(BASE, engine = engine)
        val ex = assertFailsWith<ApiException> {
            api.signUp(SignUpRequest("nope", "pw", "Ada"))
        }
        assertEquals(400, ex.status)
    }

    @Test
    fun loginExtractsBearerFromAuthorizationHeader() = runTest {
        val engine = MockEngine {
            respond(
                content = "",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.Authorization, "Bearer the.jwt.value"),
            )
        }
        val api = ApiClient(BASE, engine = engine)
        val session = api.login(LoginRequest("a@b.com", "pw"))
        assertEquals("the.jwt.value", session.token)
    }

    @Test
    fun loginWithoutAuthHeaderThrows() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.OK) }
        val api = ApiClient(BASE, engine = engine)
        assertFailsWith<ApiException> { api.login(LoginRequest("a@b.com", "pw")) }
    }

    @Test
    fun loginOn401ThrowsApiException() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized, "nope") }
        val api = ApiClient(BASE, engine = engine)
        val ex = assertFailsWith<ApiException> { api.login(LoginRequest("a@b.com", "pw")) }
        assertEquals(401, ex.status)
    }

    @Test
    fun listWorksDecodesArrayAndForwardsPagingParams() = runTest {
        val engine = MockEngine { req ->
            assertEquals("/works", req.url.encodedPath)
            assertEquals("10", req.url.parameters["limit"])
            assertEquals("20", req.url.parameters["offset"])
            val (s, body, h) = json(
                """[{"id":"w1","title":"Work 1","editions":[]},
                   {"id":"w2","title":"Work 2","authors":"Test","editions":[]}]""",
            )
            respond(body, s, h)
        }
        val api = ApiClient(BASE, engine = engine)
        val works = api.listWorks(limit = 10, offset = 20)
        assertEquals(2, works.size)
        assertEquals("Work 1", works[0].title)
        assertEquals("Test", works[1].authors)
    }

    @Test
    fun getWorkDecodesSingleWorkWithEditions() = runTest {
        val engine = MockEngine {
            val (s, body, h) = json(
                """{"id":"w1","title":"X","publication_year":1988,
                "editions":[{"id":"e1","work_id":"w1","format":"PDF",
                            "size_bytes":42,"sha256":"abc"}]}""",
            )
            respond(body, s, h)
        }
        val api = ApiClient(BASE, engine = engine)
        val w = api.getWork("w1")
        assertEquals("X", w.title)
        assertEquals(1988, w.publicationYear)
        assertEquals(1, w.editions.size)
        assertEquals("PDF", w.editions[0].format)
        assertEquals(42L, w.editions[0].sizeBytes)
    }

    @Test
    fun createWorkAttachesBearerAuth() = runTest {
        var receivedAuth: String? = null
        val engine = MockEngine { req ->
            receivedAuth = req.headers[HttpHeaders.Authorization]
            val (s, body, h) = json(
                """{"id":"w1","title":"New","editions":[]}""",
                HttpStatusCode.Created,
            )
            respond(body, s, h)
        }
        val api = ApiClient(BASE, tokenProvider = { "my.jwt" }, engine = engine)
        val w = api.createWork(CreateWorkRequest(title = "New"))
        assertEquals("New", w.title)
        assertEquals("Bearer my.jwt", receivedAuth)
    }

    @Test
    fun uploadEditionPostsMultipartFormData() = runTest {
        var contentType: String? = null
        val engine = MockEngine { req ->
            assertEquals("/works/w1/editions", req.url.encodedPath)
            contentType = req.body.contentType?.toString()
            val (s, body, h) = json(
                """{"id":"e1","work_id":"w1","format":"PDF",
                    "size_bytes":4,"sha256":"deadbeef"}""",
                HttpStatusCode.Created,
            )
            respond(body, s, h)
        }
        val api = ApiClient(BASE, tokenProvider = { "t" }, engine = engine)
        val ed = api.uploadEdition(
            workId = "w1",
            format = "PDF",
            language = "en",
            fileName = "x.pdf",
            bytes = byteArrayOf(1, 2, 3, 4),
        )
        assertEquals("e1", ed.id)
        assertEquals("PDF", ed.format)
        assertTrue(
            contentType?.startsWith("multipart/form-data") == true,
            "expected multipart/form-data, got $contentType",
        )
    }

    @Test
    fun downloadEditionReturnsExactBytes() = runTest {
        val payload = ByteArray(64) { it.toByte() }
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
            )
        }
        val api = ApiClient(BASE, engine = engine)
        val got = api.downloadEdition("e1")
        assertContentEquals(payload, got)
    }

    @Test
    fun listWorksOn500ThrowsApiException() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError, "boom") }
        val api = ApiClient(BASE, engine = engine)
        val ex = assertFailsWith<ApiException> { api.listWorks() }
        assertEquals(500, ex.status)
    }

    @Test
    fun meAttachesBearerAndDecodesIsModerator() = runTest {
        var receivedAuth: String? = null
        val engine = MockEngine { req ->
            assertEquals("/auth/me", req.url.encodedPath)
            assertEquals(HttpMethod.Get, req.method)
            receivedAuth = req.headers[HttpHeaders.Authorization]
            val (s, body, h) = json(
                """{"id":7,"email":"mod@x.com","name":"Mod","is_moderator":true}""",
            )
            respond(body, s, h)
        }
        val api = ApiClient(BASE, tokenProvider = { "abc.def.ghi" }, engine = engine)
        val u = api.me()
        assertEquals(7L, u.id)
        assertEquals("mod@x.com", u.email)
        assertEquals("Mod", u.name)
        assertEquals(true, u.isModerator)
        assertEquals("Bearer abc.def.ghi", receivedAuth)
    }

    @Test
    fun meOn401ThrowsApiException() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized, "no") }
        val api = ApiClient(BASE, engine = engine)
        val ex = assertFailsWith<ApiException> { api.me() }
        assertEquals(401, ex.status)
    }

    @Test
    fun searchWorksForwardsQueryAndPaging() = runTest {
        var receivedQuery: String? = null
        val engine = MockEngine { req ->
            assertEquals("/works/search", req.url.encodedPath)
            receivedQuery = req.url.parameters["q"]
            assertEquals("5", req.url.parameters["limit"])
            assertEquals("10", req.url.parameters["offset"])
            val (s, body, h) = json(
                """[{"id":"w1","title":"Moby Dick","editions":[]}]""",
            )
            respond(body, s, h)
        }
        val api = ApiClient(BASE, engine = engine)
        val works = api.searchWorks("moby", limit = 5, offset = 10)
        assertEquals("moby", receivedQuery)
        assertEquals(1, works.size)
        assertEquals("Moby Dick", works[0].title)
    }

    @Test
    fun searchWorksOn400ThrowsApiException() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.BadRequest, "q required") }
        val api = ApiClient(BASE, engine = engine)
        val ex = assertFailsWith<ApiException> { api.searchWorks("") }
        assertEquals(400, ex.status)
    }

    @Test
    fun listContributionsForwardsStatusFilter() = runTest {
        val engine = MockEngine { req ->
            assertEquals("/contributions", req.url.encodedPath)
            assertEquals("pending", req.url.parameters["status"])
            val (s, body, h) = json(
                """[{"id":"c1","work_id":"w1","contributor_id":7,
                    "contributor_name":"Mira","status":"pending",
                    "patch":{"title":"x"},
                    "created_at":"2026-05-27T00:00:00Z",
                    "updated_at":"2026-05-27T00:00:00Z"}]""",
            )
            respond(body, s, h)
        }
        val api = ApiClient(BASE, engine = engine)
        val cs = api.listContributions(status = "pending")
        assertEquals(1, cs.size)
        assertEquals("pending", cs[0].status)
        assertEquals("Mira", cs[0].contributorName)
    }

    @Test
    fun approveContributionPostsWithBearer() = runTest {
        var receivedAuth: String? = null
        val engine = MockEngine { req ->
            assertEquals(HttpMethod.Post, req.method)
            assertEquals("/contributions/c1/approve", req.url.encodedPath)
            receivedAuth = req.headers[HttpHeaders.Authorization]
            val (s, body, h) = json(
                """{"id":"c1","work_id":"w1","contributor_id":7,
                    "status":"approved",
                    "created_at":"2026-05-27T00:00:00Z",
                    "updated_at":"2026-05-27T00:00:00Z"}""",
            )
            respond(body, s, h)
        }
        val api = ApiClient(BASE, tokenProvider = { "mod.jwt" }, engine = engine)
        val out = api.approveContribution("c1")
        assertEquals("approved", out.status)
        assertEquals("Bearer mod.jwt", receivedAuth)
    }

    @Test
    fun rejectContributionPostsWithBearer() = runTest {
        val engine = MockEngine { req ->
            assertEquals("/contributions/c1/reject", req.url.encodedPath)
            val (s, body, h) = json(
                """{"id":"c1","work_id":"w1","contributor_id":7,
                    "status":"rejected",
                    "created_at":"2026-05-27T00:00:00Z",
                    "updated_at":"2026-05-27T00:00:00Z"}""",
            )
            respond(body, s, h)
        }
        val api = ApiClient(BASE, tokenProvider = { "mod.jwt" }, engine = engine)
        val out = api.rejectContribution("c1")
        assertEquals("rejected", out.status)
    }

    @Test
    fun approveContributionOn403ThrowsApiException() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Forbidden, "moderator required") }
        val api = ApiClient(BASE, engine = engine)
        val ex = assertFailsWith<ApiException> { api.approveContribution("c1") }
        assertEquals(403, ex.status)
    }

    @Test
    fun submitContributionPostsPatchWithBearerAndDecodesResponse() = runTest {
        var receivedAuth: String? = null
        var requestPath: String? = null
        var bodyText: String? = null
        val engine = MockEngine { req ->
            receivedAuth = req.headers[HttpHeaders.Authorization]
            requestPath = req.url.encodedPath
            bodyText = (req.body as io.ktor.http.content.TextContent).text
            val (s, body, h) = json(
                """{"id":"c1","work_id":"w1","contributor_id":7,
                    "contributor_name":"Mira K","status":"pending",
                    "patch":{"title":"Updated"},
                    "created_at":"2026-05-27T00:00:00Z",
                    "updated_at":"2026-05-27T00:00:00Z"}""",
                HttpStatusCode.Created,
            )
            respond(body, s, h)
        }
        val api = ApiClient(BASE, tokenProvider = { "tok.123" }, engine = engine)
        val patch = mapOf<String, kotlinx.serialization.json.JsonElement>(
            "title" to kotlinx.serialization.json.JsonPrimitive("Updated"),
            "publication_year" to kotlinx.serialization.json.JsonPrimitive(1996),
        )
        val c = api.submitContribution("w1", patch)

        assertEquals("/works/w1/contributions", requestPath)
        assertEquals("Bearer tok.123", receivedAuth)
        assertEquals("c1", c.id)
        assertEquals("pending", c.status)
        assertEquals("Mira K", c.contributorName)
        // Body must wrap the patch under "patch" and preserve value types.
        assertTrue(bodyText!!.contains("\"patch\""), "body missing patch wrapper: $bodyText")
        assertTrue(bodyText!!.contains("\"publication_year\":1996"),
            "expected int-typed year in patch: $bodyText")
    }

    @Test
    fun getLibraryEntryReturnsNullOn404() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.NotFound, "not found") }
        val api = ApiClient(BASE, tokenProvider = { "t" }, engine = engine)
        // A 404 is the "not in library" signal, not an error.
        assertEquals(null, api.getLibraryEntry("w1"))
    }

    @Test
    fun getLibraryEntryDecodesEntryAndEmbeddedWork() = runTest {
        val engine = MockEngine { req ->
            assertEquals("/me/library/w1", req.url.encodedPath)
            val (s, body, h) = json(
                """{"id":"ub1","user_id":3,"work_id":"w1","status":"reading",
                    "progress_percent":40,"rating":5,
                    "work":{"id":"w1","title":"Dune","editions":[]},
                    "created_at":"2026-05-27T00:00:00Z",
                    "updated_at":"2026-05-27T00:00:00Z"}""",
            )
            respond(body, s, h)
        }
        val api = ApiClient(BASE, tokenProvider = { "t" }, engine = engine)
        val ub = api.getLibraryEntry("w1")
        assertEquals("reading", ub?.status)
        assertEquals(40, ub?.progressPercent)
        assertEquals(5, ub?.rating)
        assertEquals("Dune", ub?.work?.title)
    }

    @Test
    fun upsertLibraryEntryPutsPartialPatchWithBearer() = runTest {
        var receivedAuth: String? = null
        var method: HttpMethod? = null
        var bodyText: String? = null
        val engine = MockEngine { req ->
            receivedAuth = req.headers[HttpHeaders.Authorization]
            method = req.method
            bodyText = (req.body as io.ktor.http.content.TextContent).text
            val (s, body, h) = json(
                """{"id":"ub1","user_id":3,"work_id":"w1","status":"reading",
                    "progress_percent":40,
                    "created_at":"2026-05-27T00:00:00Z",
                    "updated_at":"2026-05-27T00:00:00Z"}""",
            )
            respond(body, s, h)
        }
        val api = ApiClient(BASE, tokenProvider = { "tok.9" }, engine = engine)
        val ub = api.upsertLibraryEntry(
            "w1",
            UpsertLibraryRequest(status = "reading", progressPercent = 40),
        )
        assertEquals(HttpMethod.Put, method)
        assertEquals("Bearer tok.9", receivedAuth)
        assertEquals("reading", ub.status)
        // Only the set fields ride the wire — null fields are dropped.
        assertTrue(bodyText!!.contains("\"status\":\"reading\""), "body: $bodyText")
        assertTrue(bodyText!!.contains("\"progress_percent\":40"), "body: $bodyText")
        assertTrue(!bodyText!!.contains("\"rating\""), "null fields must not serialize: $bodyText")
    }

    @Test
    fun listLibraryForwardsStatusFilter() = runTest {
        var receivedStatus: String? = null
        val engine = MockEngine { req ->
            assertEquals("/me/library", req.url.encodedPath)
            receivedStatus = req.url.parameters["status"]
            val (s, body, h) = json(
                """[{"id":"ub1","user_id":3,"work_id":"w1","status":"reading",
                     "created_at":"2026-05-27T00:00:00Z",
                     "updated_at":"2026-05-27T00:00:00Z"}]""",
            )
            respond(body, s, h)
        }
        val api = ApiClient(BASE, tokenProvider = { "t" }, engine = engine)
        val items = api.listLibrary(status = "reading")
        assertEquals("reading", receivedStatus)
        assertEquals(1, items.size)
        assertEquals("w1", items[0].workId)
    }

    @Test
    fun removeFromLibrarySendsDeleteWithBearer() = runTest {
        var method: HttpMethod? = null
        var receivedAuth: String? = null
        val engine = MockEngine { req ->
            method = req.method
            receivedAuth = req.headers[HttpHeaders.Authorization]
            respond("", HttpStatusCode.NoContent)
        }
        val api = ApiClient(BASE, tokenProvider = { "tok.x" }, engine = engine)
        api.removeFromLibrary("w1")
        assertEquals(HttpMethod.Delete, method)
        assertEquals("Bearer tok.x", receivedAuth)
    }

    @Test
    fun recommendationsAttachesBearerAndDecodesWorkAndReason() = runTest {
        var receivedAuth: String? = null
        var limit: String? = null
        val engine = MockEngine { req ->
            assertEquals("/me/recommendations", req.url.encodedPath)
            receivedAuth = req.headers[HttpHeaders.Authorization]
            limit = req.url.parameters["limit"]
            val (s, body, h) = json(
                """[{"work":{"id":"w2","title":"Dune Messiah","editions":[]},
                     "reason":"More from authors you've read","score":5.0}]""",
            )
            respond(body, s, h)
        }
        val api = ApiClient(BASE, tokenProvider = { "tok.r" }, engine = engine)
        val recs = api.recommendations()
        assertEquals("Bearer tok.r", receivedAuth)
        assertEquals("20", limit)
        assertEquals(1, recs.size)
        assertEquals("Dune Messiah", recs[0].work.title)
        assertEquals("More from authors you've read", recs[0].reason)
    }

    @Test
    fun recommendationsOn401ThrowsApiException() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized, "nope") }
        val api = ApiClient(BASE, engine = engine)
        val ex = assertFailsWith<ApiException> { api.recommendations() }
        assertEquals(401, ex.status)
    }

    @Test
    fun dismissRecommendationPostsToDismissPathWithBearer() = runTest {
        var method: HttpMethod? = null
        var path: String? = null
        var receivedAuth: String? = null
        val engine = MockEngine { req ->
            method = req.method
            path = req.url.encodedPath
            receivedAuth = req.headers[HttpHeaders.Authorization]
            respond("", HttpStatusCode.NoContent)
        }
        val api = ApiClient(BASE, tokenProvider = { "tok.d" }, engine = engine)
        api.dismissRecommendation("w1")
        assertEquals(HttpMethod.Post, method)
        assertEquals("/me/recommendations/w1/dismiss", path)
        assertEquals("Bearer tok.d", receivedAuth)
    }
}
