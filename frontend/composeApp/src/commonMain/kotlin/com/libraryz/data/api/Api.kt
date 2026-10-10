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
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
data class RemovalRequest(val reason: String)

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

/** [token] is the short-lived access token; [refreshToken] renews it. */
data class Session(val token: String, val refreshToken: String? = null)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class TokenPairResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Int = 0,
)

/**
 * How [ApiClient] keeps a session alive. Implemented by [AuthState]:
 * - [refreshToken] is presented to `/auth/refresh` when an authenticated
 *   call gets a 401;
 * - [onRefreshed] persists the rotated pair;
 * - [onSessionExpired] is called when the session can't be renewed (refresh
 *   token rejected), so the app can clear it and send the user to sign in.
 */
interface SessionHooks {
    val refreshToken: String?
    suspend fun onRefreshed(session: Session)
    suspend fun onSessionExpired()
}

/** [filename] is null when the server didn't name the file. */
class DownloadedFile(val filename: String?, val bytes: ByteArray)

/**
 * Extracts the filename from a `Content-Disposition` header, preferring the
 * RFC 8187 `filename*=UTF-8''…` form (non-ASCII titles) over the ASCII
 * `filename="…"` fallback. Returns null if neither is present.
 */
internal fun parseContentDispositionFilename(header: String): String? {
    Regex("""filename\*\s*=\s*UTF-8''([^;\s]+)""", RegexOption.IGNORE_CASE)
        .find(header)?.groupValues?.get(1)
        ?.let(::percentDecodeUtf8)
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    return Regex("""filename\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
        .find(header)?.groupValues?.get(1)
        ?.takeIf { it.isNotBlank() }
}

private fun percentDecodeUtf8(s: String): String? {
    val out = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%') {
            if (i + 2 >= s.length) return null
            out += s.substring(i + 1, i + 3).toIntOrNull(16)?.toByte() ?: return null
            i += 3
        } else {
            out += c.code.toByte()
            i++
        }
    }
    return out.toByteArray().decodeToString()
}

class ApiException(val status: Int, val body: String, message: String) :
    RuntimeException("$message [HTTP $status]: $body") {
    /**
     * What to show the user. 4xx bodies from the backend are deliberately
     * user-facing validation messages (e.g. "invalid patch: title must not
     * be empty"), so surface them verbatim; 5xx bodies are generic and the
     * full [message] is more useful for a bug report.
     */
    val userMessage: String
        get() = body.trim().takeIf { status in 400..499 && it.isNotEmpty() } ?: (message ?: "Request failed")
}

// 409 body from POST /works/{id}/editions when the file is already stored as
// an edition (on this work or another one).
@Serializable
internal data class DuplicateEditionBody(
    val editionId: String? = null,
    val workId: String? = null,
    val error: String? = null,
)

/**
 * The uploaded file is already in the library as [editionId] on [workId].
 * The message is user-facing — UploadSheet shows it verbatim.
 */
class DuplicateEditionException(
    val editionId: String?,
    val workId: String?,
    message: String = "This file is already in the library.",
) : RuntimeException(message)

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
    private val sessionHooks: SessionHooks? = null,
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

    private enum class RefreshOutcome { Refreshed, Expired, Unavailable }

    // Refresh tokens are single-use and the backend revokes the whole session
    // if one is presented twice, so concurrent 401s must share one refresh.
    private val refreshLock = Mutex()

    /**
     * Runs an authenticated request ([send] must call [maybeAuth], so a retry
     * picks up the new token). On 401 it refreshes the session once and
     * retries. If the session can't be renewed it reports expiry via
     * [SessionHooks.onSessionExpired] and returns the 401 for the caller to
     * surface. A transient refresh failure (offline, 5xx) keeps the session.
     */
    private suspend fun authed(send: suspend () -> HttpResponse): HttpResponse {
        val hooks = sessionHooks
        val tokenUsed = tokenProvider()
        val resp = send()
        if (resp.status != HttpStatusCode.Unauthorized || hooks == null || tokenUsed == null) return resp
        return when (refreshAfter401(hooks, tokenUsed)) {
            RefreshOutcome.Refreshed -> {
                val retry = send()
                // Fresh token still refused: the session was revoked server-side.
                if (retry.status == HttpStatusCode.Unauthorized) hooks.onSessionExpired()
                retry
            }
            RefreshOutcome.Expired -> {
                hooks.onSessionExpired()
                resp
            }
            RefreshOutcome.Unavailable -> resp
        }
    }

    private suspend fun refreshAfter401(hooks: SessionHooks, staleToken: String): RefreshOutcome =
        refreshLock.withLock {
            val current = tokenProvider() ?: return@withLock RefreshOutcome.Expired
            // Another request refreshed while we waited for the lock.
            if (current != staleToken) return@withLock RefreshOutcome.Refreshed
            val refresh = hooks.refreshToken ?: return@withLock RefreshOutcome.Expired
            val resp = try {
                client.post("$baseUrl/auth/refresh") { setBody(RefreshRequest(refresh)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return@withLock RefreshOutcome.Unavailable
            }
            when {
                resp.status.isSuccess() -> {
                    val pair: TokenPairResponse = resp.body()
                    hooks.onRefreshed(Session(pair.accessToken, pair.refreshToken))
                    RefreshOutcome.Refreshed
                }
                resp.status == HttpStatusCode.TooManyRequests || resp.status.value >= 500 ->
                    RefreshOutcome.Unavailable
                else -> RefreshOutcome.Expired
            }
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
        val resp = authed { client.get("$baseUrl/auth/me") { maybeAuth() } }
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
        // Current backends return the token pair as JSON; older ones only set
        // the Authorization header (no refresh token).
        val pair = runCatching { resp.body<TokenPairResponse>() }.getOrNull()
        if (pair != null && pair.accessToken.isNotBlank()) {
            return Session(pair.accessToken, pair.refreshToken.ifBlank { null })
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

    /** Ends the session [refreshToken] belongs to. Best-effort; never throws for HTTP errors. */
    suspend fun logout(refreshToken: String) {
        client.post("$baseUrl/auth/logout") { setBody(RefreshRequest(refreshToken)) }
    }

    /** Authenticated. Ends every session of the current user on every device. */
    suspend fun logoutAll() {
        val resp = authed { client.post("$baseUrl/auth/logout-all") { maybeAuth() } }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "logout everywhere failed")
        }
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
        val resp = authed { client.post("$baseUrl/contributions/$id/approve") { maybeAuth() } }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "approve failed")
        }
        return resp.body()
    }

    /** Moderator-only. Rejects a pending contribution without applying it. */
    suspend fun rejectContribution(id: String): Contribution {
        val resp = authed { client.post("$baseUrl/contributions/$id/reject") { maybeAuth() } }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "reject failed")
        }
        return resp.body()
    }

    /** Authenticated. Submits a proposed edit (flat partial patch) for a work. */
    suspend fun submitContribution(workId: String, patch: Map<String, JsonElement>): Contribution {
        val resp = authed {
            client.post("$baseUrl/works/$workId/contributions") {
                maybeAuth()
                setBody(SubmitContributionRequest(patch))
            }
        }
        if (resp.status != HttpStatusCode.Created) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "submit contribution failed")
        }
        return resp.body()
    }

    /**
     * Removes a work and its editions (soft delete). Moderators can remove any
     * work; anyone else only a work they created that has no editions.
     */
    suspend fun deleteWork(id: String, reason: String) {
        val resp = authed {
            client.delete("$baseUrl/works/$id") {
                maybeAuth()
                setBody(RemovalRequest(reason))
            }
        }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "remove work failed")
        }
    }

    /** Moderator-only. Takes a single edition down (soft delete). */
    suspend fun deleteEdition(id: String, reason: String) {
        val resp = authed {
            client.delete("$baseUrl/editions/$id") {
                maybeAuth()
                setBody(RemovalRequest(reason))
            }
        }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "remove edition failed")
        }
    }

    /** Authenticated. Creates a new Work (no file attached). */
    suspend fun createWork(req: CreateWorkRequest): Work {
        val resp = authed {
            client.post("$baseUrl/works") {
                maybeAuth()
                setBody(req)
            }
        }
        if (resp.status != HttpStatusCode.Created) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "create work failed")
        }
        return resp.body()
    }

    // ----- Personal library (all authenticated) -----

    /** Lists the caller's library entries, optionally filtered. */
    suspend fun listLibrary(status: String? = null, shelf: String? = null): List<UserBook> {
        val resp = authed {
            client.get("$baseUrl/me/library") {
                maybeAuth()
                if (status != null) parameter("status", status)
                if (shelf != null) parameter("shelf", shelf)
            }
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
        val resp = authed { client.get("$baseUrl/me/library/$workId") { maybeAuth() } }
        if (resp.status == HttpStatusCode.NotFound) return null
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "get library entry failed")
        }
        return resp.body()
    }

    /** Creates or updates the caller's entry for [workId] (partial patch). */
    suspend fun upsertLibraryEntry(workId: String, req: UpsertLibraryRequest): UserBook {
        val resp = authed {
            client.put("$baseUrl/me/library/$workId") {
                maybeAuth()
                setBody(req)
            }
        }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "upsert library entry failed")
        }
        return resp.body()
    }

    /** Removes the work from the caller's library. */
    suspend fun removeFromLibrary(workId: String) {
        val resp = authed { client.delete("$baseUrl/me/library/$workId") { maybeAuth() } }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "remove from library failed")
        }
    }

    /** Authenticated. Personalized recommendations (MF model + fallback). */
    suspend fun recommendations(limit: Int = 20): List<Recommendation> {
        val resp = authed {
            client.get("$baseUrl/me/recommendations") {
                maybeAuth()
                parameter("limit", limit)
            }
        }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "recommendations failed")
        }
        return resp.body()
    }

    /** Authenticated. Hides a work from future recommendations. */
    suspend fun dismissRecommendation(workId: String) {
        val resp = authed { client.post("$baseUrl/me/recommendations/$workId/dismiss") { maybeAuth() } }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "dismiss recommendation failed")
        }
    }

    /** Public. Returns the raw edition bytes. */
    suspend fun downloadEdition(id: String): ByteArray = downloadEditionFile(id).bytes

    /**
     * Public. Returns the edition bytes plus the filename the server chose
     * (from `Content-Disposition`; "<Title> - <Authors>.<format>"), so every
     * platform names saved files the same way.
     */
    suspend fun downloadEditionFile(id: String): DownloadedFile {
        val resp = client.get("$baseUrl/editions/$id/download")
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "download edition failed")
        }
        return DownloadedFile(
            filename = resp.headers[HttpHeaders.ContentDisposition]?.let(::parseContentDispositionFilename),
            bytes = resp.body(),
        )
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
        val resp = authed {
            client.submitFormWithBinaryData(
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
        }
        if (resp.status == HttpStatusCode.Conflict) {
            val dup = runCatching {
                json.decodeFromString(DuplicateEditionBody.serializer(), resp.bodyAsText())
            }.getOrNull()
            // No edition to point at means the match was taken down by a
            // moderator; the server's message explains that.
            val removedMessage = dup?.error?.takeIf { dup.editionId == null }
                ?.replaceFirstChar { it.uppercase() }?.let { "$it." }
            if (removedMessage != null) throw DuplicateEditionException(null, null, removedMessage)
            throw DuplicateEditionException(dup?.editionId, dup?.workId)
        }
        if (resp.status != HttpStatusCode.Created) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "upload edition failed")
        }
        return resp.body()
    }

    fun close() { client.close() }
}
