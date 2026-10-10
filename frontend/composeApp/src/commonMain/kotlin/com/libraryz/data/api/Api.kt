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
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.RefreshTokensParams
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.forms.formData
import io.ktor.client.plugins.onUpload
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
import io.ktor.http.encodedPath
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
data class PasswordResetRequest(val email: String)

@Serializable
data class CompletePasswordResetRequest(val token: String, val newPassword: String)

@Serializable
data class TokenRequest(val token: String)

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
 * - [refreshToken] is presented to `/auth/refresh` when a call gets a 401
 *   (Ktor's `Auth` plugin, `bearer { refreshTokens }`);
 * - [onRefreshed] persists the rotated pair;
 * - [onSessionExpired] is called when the session can't be renewed (refresh
 *   token rejected), so the app can clear it and send the user to sign in.
 */
interface SessionHooks {
    val refreshToken: String?
    suspend fun onRefreshed(session: Session)
    suspend fun onSessionExpired()
}

/**
 * Endpoints that take credentials in the body and must never carry a
 * (possibly stale) access token: sending one to `/auth/refresh` would also
 * let the bearer provider try to "refresh" a failed refresh.
 */
private val anonymousAuthPaths = listOf(
    "/auth/login", "/auth/signup", "/auth/refresh", "/auth/logout",
    "/auth/password-reset", "/auth/password-reset/complete", "/auth/email-verification/complete",
)

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
 *
 * Auth is Ktor's `Auth` plugin with a `bearer` provider: [tokenProvider]
 * supplies the access token, attached to every request except the
 * credential endpoints ([anonymousAuthPaths]); on a 401 the plugin calls
 * [renew], which rotates the pair via `/auth/refresh` and retries once.
 * Public endpoints accept the token too (an expired one is simply ignored
 * server-side), so there's no per-call opt-in.
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
        install(Auth) {
            bearer {
                // AuthState is the source of truth (sign-in, sign-out and
                // refreshes all go through it), so read it on every request
                // rather than letting the plugin cache a stale pair.
                cacheTokens = false
                loadTokens { tokenProvider()?.let { BearerTokens(it, sessionHooks?.refreshToken) } }
                sendWithoutRequest { req -> anonymousAuthPaths.none { req.url.encodedPath.endsWith(it) } }
                refreshTokens { renew() }
            }
        }
        defaultRequest {
            contentType(ContentType.Application.Json)
        }
    }

    // Refresh tokens are single-use and the backend revokes the whole session
    // if one is presented twice, so concurrent 401s must share one refresh.
    private val refreshLock = Mutex()

    /**
     * The bearer provider's `refreshTokens`: rotates the pair via
     * `/auth/refresh`. Returns the new tokens (the plugin retries the request
     * with them) or null (the 401 goes back to the caller).
     *
     * - Another request already refreshed while we waited: reuse its tokens.
     * - Refresh rejected (401/400): the session is gone — report it via
     *   [SessionHooks.onSessionExpired] so the app signs the user out.
     * - Transient failure (offline, 429, 5xx): keep the session.
     */
    private suspend fun RefreshTokensParams.renew(): BearerTokens? {
        val hooks = sessionHooks ?: return null
        return refreshLock.withLock {
            val current = tokenProvider() ?: return@withLock null
            if (current != oldTokens?.accessToken) {
                return@withLock BearerTokens(current, hooks.refreshToken)
            }
            val refresh = hooks.refreshToken ?: run {
                hooks.onSessionExpired()
                return@withLock null
            }
            val resp = try {
                client.post("$baseUrl/auth/refresh") {
                    markAsRefreshTokenRequest()
                    setBody(RefreshRequest(refresh))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return@withLock null
            }
            when {
                resp.status.isSuccess() -> {
                    val pair: TokenPairResponse = resp.body()
                    hooks.onRefreshed(Session(pair.accessToken, pair.refreshToken))
                    BearerTokens(pair.accessToken, pair.refreshToken)
                }
                resp.status == HttpStatusCode.TooManyRequests || resp.status.value >= 500 -> null
                else -> {
                    hooks.onSessionExpired()
                    null
                }
            }
        }
    }

    // ----- Auth -----

    /** Creates the account and signs in: the server returns a token pair. */
    suspend fun signUp(req: SignUpRequest): Session {
        val resp = client.post("$baseUrl/auth/signup") { setBody(req) }
        if (resp.status != HttpStatusCode.Created) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "signup failed")
        }
        return resp.toSession("signup")
    }

    /** Authenticated. Reads live DB state for `is_moderator`. */
    suspend fun me(): User {
        val resp = client.get("$baseUrl/auth/me")
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
        return resp.toSession("login")
    }

    private suspend fun HttpResponse.toSession(what: String): Session {
        val pair = runCatching { body<TokenPairResponse>() }.getOrNull()
        if (pair == null || pair.accessToken.isBlank() || pair.refreshToken.isBlank()) {
            throw ApiException(status.value, bodyAsText(), "$what response has no token pair")
        }
        return Session(pair.accessToken, pair.refreshToken)
    }

    /** Ends the session [refreshToken] belongs to. Best-effort; never throws for HTTP errors. */
    suspend fun logout(refreshToken: String) {
        client.post("$baseUrl/auth/logout") { setBody(RefreshRequest(refreshToken)) }
    }

    /** Authenticated. Ends every session of the current user on every device. */
    suspend fun logoutAll() {
        val resp = client.post("$baseUrl/auth/logout-all")
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "logout everywhere failed")
        }
    }

    // ----- Password reset & email verification -----
    //
    // Client side of a contract the backend implements on top of iam's
    // Recovery (StartPasswordReset / CompletePasswordReset /
    // StartEmailVerification / CompleteEmailVerification). iam issues the
    // single-use tokens; the server emails them as links. Status codes:
    //   2xx success · 400 password rejected by policy (body is user-facing)
    //   410 token unknown, used or expired · 429 throttled

    /**
     * Anonymous. Asks for a reset link for [email]. The server answers the
     * same whether or not the account exists, so this can't reveal which
     * emails are registered.
     */
    suspend fun requestPasswordReset(email: String) {
        val resp = client.post("$baseUrl/auth/password-reset") { setBody(PasswordResetRequest(email)) }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "password reset request failed")
        }
    }

    /** Anonymous. Sets a new password with the emailed [token]; signs out every session. */
    suspend fun completePasswordReset(token: String, newPassword: String) {
        val resp = client.post("$baseUrl/auth/password-reset/complete") {
            setBody(CompletePasswordResetRequest(token, newPassword))
        }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "password reset failed")
        }
    }

    /** Authenticated. Emails the current user a verification link. */
    suspend fun requestEmailVerification() {
        val resp = client.post("$baseUrl/me/email-verification")
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "email verification request failed")
        }
    }

    /** Anonymous. Confirms the address the emailed [token] was sent to. */
    suspend fun completeEmailVerification(token: String) {
        val resp = client.post("$baseUrl/auth/email-verification/complete") { setBody(TokenRequest(token)) }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "email verification failed")
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
        val resp = client.post("$baseUrl/contributions/$id/approve")
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "approve failed")
        }
        return resp.body()
    }

    /** Moderator-only. Rejects a pending contribution without applying it. */
    suspend fun rejectContribution(id: String): Contribution {
        val resp = client.post("$baseUrl/contributions/$id/reject")
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "reject failed")
        }
        return resp.body()
    }

    /** Authenticated. Submits a proposed edit (flat partial patch) for a work. */
    suspend fun submitContribution(workId: String, patch: Map<String, JsonElement>): Contribution {
        val resp = client.post("$baseUrl/works/$workId/contributions") {
            setBody(SubmitContributionRequest(patch))
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
        val resp = client.delete("$baseUrl/works/$id") {
            setBody(RemovalRequest(reason))
        }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "remove work failed")
        }
    }

    /** Moderator-only. Takes a single edition down (soft delete). */
    suspend fun deleteEdition(id: String, reason: String) {
        val resp = client.delete("$baseUrl/editions/$id") {
            setBody(RemovalRequest(reason))
        }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "remove edition failed")
        }
    }

    /** Authenticated. Creates a new Work (no file attached). */
    suspend fun createWork(req: CreateWorkRequest): Work {
        val resp = client.post("$baseUrl/works") {
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
        val resp = client.get("$baseUrl/me/library/$workId")
        if (resp.status == HttpStatusCode.NotFound) return null
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "get library entry failed")
        }
        return resp.body()
    }

    /** Creates or updates the caller's entry for [workId] (partial patch). */
    suspend fun upsertLibraryEntry(workId: String, req: UpsertLibraryRequest): UserBook {
        val resp = client.put("$baseUrl/me/library/$workId") {
            setBody(req)
        }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "upsert library entry failed")
        }
        return resp.body()
    }

    /** Removes the work from the caller's library. */
    suspend fun removeFromLibrary(workId: String) {
        val resp = client.delete("$baseUrl/me/library/$workId")
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "remove from library failed")
        }
    }

    /** Authenticated. Personalized recommendations (MF model + fallback). */
    suspend fun recommendations(limit: Int = 20): List<Recommendation> {
        val resp = client.get("$baseUrl/me/recommendations") {
            parameter("limit", limit)
        }
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "recommendations failed")
        }
        return resp.body()
    }

    /** Authenticated. Hides a work from future recommendations. */
    suspend fun dismissRecommendation(workId: String) {
        val resp = client.post("$baseUrl/me/recommendations/$workId/dismiss")
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "dismiss recommendation failed")
        }
    }

    /** Authenticated. Undoes [dismissRecommendation]; a no-op if it wasn't dismissed. */
    suspend fun undismissRecommendation(workId: String) {
        val resp = client.delete("$baseUrl/me/recommendations/$workId/dismiss")
        if (!resp.status.isSuccess()) {
            throw ApiException(resp.status.value, resp.bodyAsText(), "undo dismiss failed")
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
        // Fraction of the request body sent, 0..1. Reaching 1 means the
        // server now has the file and is checking it.
        onProgress: ((Float) -> Unit)? = null,
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
            if (onProgress != null) {
                onUpload { sent, total ->
                    if (total != null && total > 0) onProgress((sent.toFloat() / total).coerceIn(0f, 1f))
                }
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
