package com.libraryz.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Serialized with kotlinx-serialization's SnakeCase naming strategy
// (see ApiClient's Json config) — Kotlin camelCase properties map to the
// snake_case JSON keys the Go backend emits per `internal/catalog/repository.go`.
@Serializable
data class Work(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val authors: String? = null,
    val description: String? = null,
    val language: String? = null,
    val publicationYear: Int? = null,
    val isbn: String? = null,
    val openlibraryId: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val editions: List<Edition> = emptyList(),
    val tags: List<Tag> = emptyList(),
)

@Serializable
data class Edition(
    val id: String,
    val workId: String,
    val format: String,
    val language: String? = null,
    val sizeBytes: Long,
    val sha256: String,
    val uploadedBy: Int? = null,
    val createdAt: String? = null,
)

@Serializable
data class Tag(
    val id: String,
    val name: String,
)

@Serializable
data class User(
    val id: Long,
    val email: String,
    val name: String,
    val isModerator: Boolean,
)

// Contribution mirrors the backend `internal/contribution.Contribution`.
// `patch` is a flat partial object — values are mixed-type (mostly strings,
// but publication_year is an integer), hence `JsonElement` rather than
// `String?`. `contributorName` is populated in slice 2.5 via a users-table
// join; the field is nullable so older / unauthenticated reads still decode.
@Serializable
data class Contribution(
    val id: String,
    val workId: String,
    val contributorId: Long,
    val contributorName: String? = null,
    val status: String,
    val patch: Map<String, JsonElement> = emptyMap(),
    // `current` holds the target Work's present value for each patched
    // field — the "old" side of the queue diff. Keys mirror `patch`.
    val current: Map<String, JsonElement> = emptyMap(),
    val reviewerId: Long? = null,
    val decidedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

// UserBook mirrors the backend `internal/library.UserBook` — one user's
// personal-library entry for a work. `work` is embedded on read so the My
// Library list renders without a second round-trip; it's nullable because the
// upsert/get responses always carry it but defensive decoding shouldn't fail
// if the backend ever omits it. `rating` is nullable to distinguish "unrated".
@Serializable
data class UserBook(
    val id: String,
    val userId: Long,
    val workId: String,
    val status: String,
    val shelf: String? = null,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val progressPercent: Int = 0,
    val rating: Int? = null,
    val notes: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val work: Work? = null,
)

// The three reading-status values the backend's enum accepts.
object LibraryStatus {
    const val Want = "want"
    const val Reading = "reading"
    const val Read = "read"
}

// Recommendation mirrors the backend `internal/recommendation.Recommendation`:
// a suggested work plus a human-readable `reason` for why it surfaced. `score`
// is the backend's internal ranking value (omitted/0 for popularity fallbacks).
@Serializable
data class Recommendation(
    val work: Work,
    val reason: String? = null,
    val score: Double? = null,
)

val Edition.prettySize: String get() = formatBytes(sizeBytes)

val Edition.isPdf: Boolean
    get() = format.equals("pdf", ignoreCase = true)

/** True when this build can preview the edition's format. */
val Edition.isPreviewable: Boolean
    get() = canPreview(format)

/**
 * Human-readable byte size. Picks B / KB / MB so a 2 KB PDF doesn't render
 * as "0.0 MB" (the original MB-only formatter was the bug).
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1_048_576) {
        val kb = bytes / 1024.0
        return "${(kb * 10).toLong() / 10.0} KB"
    }
    val mb = bytes / 1_048_576.0
    return "${(mb * 10).toLong() / 10.0} MB"
}
