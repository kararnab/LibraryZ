package com.libraryz.data

import com.libraryz.data.api.LibraryStamps
import com.libraryz.data.api.UpsertLibraryRequest
import kotlinx.serialization.Serializable

// How library changes merge (PLAN.md 8.3): fields come in groups — status,
// progress (current/total pages + percent), rating, shelf, notes — each
// stamped with when it changed ([Hlc]). A group is written only if its stamp
// is newer, here as on the server, so changes made offline merge the same
// way wherever they're applied, in any order and any number of times.

private val UpsertLibraryRequest.hasProgress: Boolean
    get() = currentPage != null || totalPages != null || progressPercent != null

/**
 * [req] with a fresh stamp for each group it changes. A notes edit also
 * records the stamp of the text it started from ([entry]'s), so the server
 * can tell a blind overwrite from an informed one.
 */
fun stampRequest(req: UpsertLibraryRequest, entry: UserBook?, clock: Hlc): UpsertLibraryRequest {
    fun at(present: Boolean) = if (present) clock.next() else null
    return req.copy(
        stamps = LibraryStamps(
            status = at(req.status != null),
            progress = at(req.hasProgress),
            rating = at(req.rating != null),
            shelf = at(req.shelf != null),
            notes = at(req.notes != null),
        ),
        notesBase = if (req.notes != null) entry?.notesStamp.orEmpty() else null,
    )
}

private fun newer(stamp: String?, than: String?): Boolean = stamp != null && stamp > than.orEmpty()

/**
 * [entry] (null: not in the library yet) after [req], as the server will
 * apply it. [now] (RFC 3339) dates status transitions and the update.
 */
fun applyLocally(entry: UserBook?, workId: String, req: UpsertLibraryRequest, now: String, work: Work?, userId: Long): UserBook {
    var ub = entry ?: UserBook(
        id = "local-$workId", userId = userId, workId = workId, status = LibraryStatus.Want,
        createdAt = now, work = work,
    )
    val s = req.stamps ?: LibraryStamps()
    if (req.hasProgress && newer(s.progress, ub.progressStamp)) {
        ub = ub.copy(
            currentPage = req.currentPage ?: ub.currentPage,
            totalPages = req.totalPages ?: ub.totalPages,
            progressPercent = req.progressPercent ?: ub.progressPercent,
            progressStamp = s.progress,
        )
    }
    if (req.status != null && newer(s.status, ub.statusStamp)) {
        ub = ub.copy(
            status = req.status,
            statusStamp = s.status,
            startedAt = ub.startedAt ?: now.takeIf { req.status != LibraryStatus.Want },
            finishedAt = if (req.status == LibraryStatus.Read) now else ub.finishedAt,
        )
        if (req.status == LibraryStatus.Read && req.progressPercent == null && newer(s.status, ub.progressStamp)) {
            ub = ub.copy(progressPercent = 100, progressStamp = s.status)
        }
    }
    if (req.rating != null && newer(s.rating, ub.ratingStamp)) ub = ub.copy(rating = req.rating, ratingStamp = s.rating)
    if (req.shelf != null && newer(s.shelf, ub.shelfStamp)) ub = ub.copy(shelf = req.shelf, shelfStamp = s.shelf)
    if (req.notes != null) {
        // Edited without seeing the stored text: whichever loses is kept.
        val blind = req.notesBase != null && req.notesBase != ub.notesStamp.orEmpty() && !ub.notesStamp.isNullOrEmpty()
        if (newer(s.notes, ub.notesStamp)) {
            if (blind && !ub.notes.isNullOrEmpty() && ub.notes != req.notes) ub = ub.copy(notesOther = ub.notes)
            ub = ub.copy(notes = req.notes, notesStamp = s.notes)
        } else if (blind && req.notes.isNotEmpty() && req.notes != ub.notes) {
            ub = ub.copy(notesOther = req.notes)
        }
    }
    if (req.resolveNotes == true) ub = ub.copy(notesOther = null)
    return ub.copy(updatedAt = now)
}

/** Two queued changes to one work as one: [b]'s groups replace [a]'s; the first notes base stays. */
fun coalesce(a: UpsertLibraryRequest, b: UpsertLibraryRequest): UpsertLibraryRequest {
    val sa = a.stamps ?: LibraryStamps()
    val sb = b.stamps ?: LibraryStamps()
    val progress = if (b.hasProgress) b else a
    return UpsertLibraryRequest(
        status = b.status ?: a.status,
        shelf = b.shelf ?: a.shelf,
        currentPage = progress.currentPage,
        totalPages = progress.totalPages,
        progressPercent = progress.progressPercent,
        rating = b.rating ?: a.rating,
        notes = b.notes ?: a.notes,
        stamps = LibraryStamps(
            status = if (b.status != null) sb.status else sa.status,
            progress = (progress.stamps ?: LibraryStamps()).progress,
            rating = if (b.rating != null) sb.rating else sa.rating,
            shelf = if (b.shelf != null) sb.shelf else sa.shelf,
            notes = if (b.notes != null) sb.notes else sa.notes,
        ),
        notesBase = if (a.notes != null) a.notesBase else b.notesBase,
        resolveNotes = if (a.resolveNotes == true || b.resolveNotes == true) true else null,
    )
}

/** A library change waiting to be sent: an [upsert], or a removal at [removedAt]. */
@Serializable
data class PendingWrite(
    val workId: String,
    val upsert: UpsertLibraryRequest? = null,
    val removedAt: String? = null,
)

/**
 * [queue] plus [w]: a change to a work whose last queued change is also a
 * change merges into it; a removal drops the work's earlier changes (they
 * predate it, so the server would ignore them anyway).
 */
fun enqueue(queue: List<PendingWrite>, w: PendingWrite): List<PendingWrite> {
    if (w.removedAt != null) return queue.filterNot { it.workId == w.workId && it.upsert != null } + w
    val i = queue.indexOfLast { it.workId == w.workId }
    val last = queue.getOrNull(i)
    if (last?.upsert != null && w.upsert != null) {
        return queue.toMutableList().also { it[i] = last.copy(upsert = coalesce(last.upsert, w.upsert)) }
    }
    return queue + w
}
