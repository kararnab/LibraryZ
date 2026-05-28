package com.libraryz.data.api

import com.libraryz.data.Contribution
import com.libraryz.data.Edition
import com.libraryz.data.Recommendation
import com.libraryz.data.User
import com.libraryz.data.UserBook
import com.libraryz.data.Work
import kotlinx.serialization.json.JsonElement
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

@Serializable
data class SignUpRequest(val email: String, val password: String, val name: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class CreateWorkRequest(
    val title: String,
    val authors: String? = null,
    val description: String? = null,
    val language: String? = null,
    val publicationYear: Int? = null,
    val isbn: String? = null,
    val openlibraryId: String? = null,
)

@Serializable
data class SubmitContributionRequest(val patch: Map<String, JsonElement>)

// Partial patch for PUT /me/library/{work_id}. Null fields are dropped on the
// wire (Json.encodeDefaults stays false), so each call sends only what it
// means to change — set status without clobbering an existing rating, etc.
@Serializable
data class UpsertLibraryRequest(
    val status: String? = null,
    val shelf: String? = null,
    val currentPage: Int? = null,
    val totalPages: Int? = null,
    val progressPercent: Int? = null,
    val rating: Int? = null,
    val notes: String? = null,
)

data class Session(val token: String)

class ApiException(val status: Int, val body: String, message: String) :
    RuntimeException("$message [HTTP $status]: $body")

/**
 * Tiny HTTP client wrapping the LibraryZ backend. Engine is auto-selected
 * from whichever ktor-client-<engine> dep is on the source set's classpath.
 * `tokenProvider` supplies the JWT for authenticated endpoints — callers
 * that need auth opt in via [maybeAuth]; public endpoints don't.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class ApiClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String? = { null },
    engine: HttpClientEngine? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    private val client: HttpClient = if (engine != null) {
        HttpClient(engine) { configure() }
    } else {
        HttpClient { configure() }
    }

    private fun HttpClientConfig<*>.configure() {
        expectSuccess = false
        install(ContentNegotiation) { json(this@ApiClient.json) }
        install(Logging) { level = LogLevel.INFO }
        defaultRequest {
            contentType(ContentType.Application.Json)
        }
    }

    private fun HttpRequestBuilder.maybeAuth() {
        tokenProvider()?.let { bearerAuth(it) }
    }

    // ----- Auth -----

    suspend fun signUp(req: SignUpRequest) {
        val resp = client.post("$baseUrl/auth/signup") { setBody(req) }
        if (resp.status != HttpStatusCode.Created) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "signup failed")
        }
    }

    /** Authenticated. Reads live DB state for `is_moderator`. */
    suspend fun me(): User {
        val resp = client.get("$baseUrl/auth/me") { maybeAuth() }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "me failed")
        }
        return resp.body()
    }

    suspend fun login(req: LoginRequest): Session {
        val resp = client.post("$baseUrl/auth/login") { setBody(req) }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "login failed")
        }
        val auth = resp.headers["Authorization"]
            ?: throw ApiException(resp.status.value, resp.bodyAsText(),
                "login response missing Authorization header")
        val token = auth.removePrefix("Bearer ").trim()
        if (token.isEmpty()) {
            throw ApiException(resp.status.value, auth, "empty bearer token")
        }
        return Session(token)
    }

    // ----- Catalog -----

    suspend fun listWorks(limit: Int = 50, offset: Int = 0): List<Work> {
        val resp = client.get("$baseUrl/works") {
            parameter("limit", limit)
            parameter("offset", offset)
        }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "list works failed")
        }
        return resp.body()
    }

    /** Public. Free-text search over title / authors / description. */
    suspend fun searchWorks(q: String, limit: Int = 50, offset: Int = 0): List<Work> {
        val resp = client.get("$baseUrl/works/search") {
            parameter("q", q)
            parameter("limit", limit)
            parameter("offset", offset)
        }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "search works failed")
        }
        return resp.body()
    }

    suspend fun getWork(id: String): Work {
        val resp = client.get("$baseUrl/works/$id")
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "get work failed")
        }
        return resp.body()
    }

    /** Public. Lists contributions, optionally filtered by status. */
    suspend fun listContributions(
        status: String? = "pending",
        limit: Int = 50,
        offset: Int = 0,
    ): List<Contribution> {
        val resp = client.get("$baseUrl/contributions") {
            if (status != null) parameter("status", status)
            parameter("limit", limit)
            parameter("offset", offset)
        }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "list contributions failed")
        }
        return resp.body()
    }

    /** Moderator-only. Approves a pending contribution and applies its patch. */
    suspend fun approveContribution(id: String): Contribution {
        val resp = client.post("$baseUrl/contributions/$id/approve") { maybeAuth() }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "approve failed")
        }
        return resp.body()
    }

    /** Moderator-only. Rejects a pending contribution without applying it. */
    suspend fun rejectContribution(id: String): Contribution {
        val resp = client.post("$baseUrl/contributions/$id/reject") { maybeAuth() }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "reject failed")
        }
        return resp.body()
    }

    /** Authenticated. Submits a proposed edit (flat partial patch) for a work. */
    suspend fun submitContribution(workId: String, patch: Map<String, JsonElement>): Contribution {
        val resp = client.post("$baseUrl/works/$workId/contributions") {
            maybeAuth()
            setBody(SubmitContributionRequest(patch))
        }
        if (resp.status != HttpStatusCode.Created) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "submit contribution failed")
        }
        return resp.body()
    }

    /** Authenticated. Creates a new Work (no file attached). */
    suspend fun createWork(req: CreateWorkRequest): Work {
        val resp = client.post("$baseUrl/works") {
            maybeAuth()
            setBody(req)
        }
        if (resp.status != HttpStatusCode.Created) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "create work failed")
        }
        return resp.body()
    }

    // ----- Personal library (all authenticated) -----

    /** Lists the caller's library entries, optionally filtered. */
    suspend fun listLibrary(status: String? = null, shelf: String? = null): List<UserBook> {
        val resp = client.get("$baseUrl/me/library") {
            maybeAuth()
            if (status != null) parameter("status", status)
            if (shelf != null) parameter("shelf", shelf)
        }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "list library failed")
        }
        return resp.body()
    }

    /**
     * Returns the caller's entry for [workId], or null if they haven't added
     * the work yet. A 404 is the expected "not in library" signal, not an
     * error, so it maps to null rather than throwing.
     */
    suspend fun getLibraryEntry(workId: String): UserBook? {
        val resp = client.get("$baseUrl/me/library/$workId") { maybeAuth() }
        if (resp.status == HttpStatusCode.NotFound) return null
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "get library entry failed")
        }
        return resp.body()
    }

    /** Creates or updates the caller's entry for [workId] (partial patch). */
    suspend fun upsertLibraryEntry(workId: String, req: UpsertLibraryRequest): UserBook {
        val resp = client.put("$baseUrl/me/library/$workId") {
            maybeAuth()
            setBody(req)
        }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "upsert library entry failed")
        }
        return resp.body()
    }

    /** Removes the work from the caller's library. */
    suspend fun removeFromLibrary(workId: String) {
        val resp = client.delete("$baseUrl/me/library/$workId") { maybeAuth() }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "remove from library failed")
        }
    }

    /** Authenticated. Personalized recommendations (MF model + fallback). */
    suspend fun recommendations(limit: Int = 20): List<Recommendation> {
        val resp = client.get("$baseUrl/me/recommendations") {
            maybeAuth()
            parameter("limit", limit)
        }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "recommendations failed")
        }
        return resp.body()
    }

    /** Authenticated. Hides a work from future recommendations. */
    suspend fun dismissRecommendation(workId: String) {
        val resp = client.post("$baseUrl/me/recommendations/$workId/dismiss") { maybeAuth() }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "dismiss recommendation failed")
        }
    }

    /** Public. Returns the raw edition bytes. */
    suspend fun downloadEdition(id: String): ByteArray {
        val resp = client.get("$baseUrl/editions/$id/download")
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "download edition failed")
        }
        return resp.body()
    }

    /**
     * Authenticated. Uploads the bytes as a new Edition of [workId].
     * Multipart parts: `format`, optional `language`, `file` (with filename).
     * Backend dedupes on sha256 — re-uploading identical bytes returns the
     * existing Edition.
     */
    suspend fun uploadEdition(
        workId: String,
        format: String,
        language: String?,
        fileName: String,
        bytes: ByteArray,
    ): Edition {
        val resp = client.submitFormWithBinaryData(
            url = "$baseUrl/works/$workId/editions",
            formData = formData {
                append("format", format)
                if (!language.isNullOrBlank()) append("language", language)
                append(
                    key = "file",
                    value = bytes,
                    headers = Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    },
                )
            },
        ) {
            maybeAuth()
        }
        if (resp.status != HttpStatusCode.Created) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "upload edition failed")
        }
        return resp.body()
    }

    fun close() { client.close() }
}
