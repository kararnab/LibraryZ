package com.libraryz.ui.screens

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.libraryz.data.UploadQuota
import com.libraryz.data.api.ApiException
import com.libraryz.data.api.DuplicateEditionException
import com.libraryz.data.formatBytes
import kotlin.time.Duration.Companion.seconds

/** The server's upload cap (the edition handler and Kong both enforce it). */
const val MAX_UPLOAD_BYTES = 500L * 1024 * 1024

/** Formats the server accepts (sanitize.Validate's allowlist). */
val UploadFormats = listOf("PDF", "EPUB", "TXT")

internal const val FORMATS_HINT = "PDF, EPUB or TXT · up to 500 MB"

/** Something wrong with the chosen file, caught before uploading. */
sealed interface FileProblem {
    data class TooLarge(val size: Long) : FileProblem
    data class Unsupported(val format: String) : FileProblem
}

fun checkUploadFile(name: String, size: Long): FileProblem? {
    val format = inferFormatFromName(name)
    return when {
        format !in UploadFormats -> FileProblem.Unsupported(format)
        size > MAX_UPLOAD_BYTES -> FileProblem.TooLarge(size)
        else -> null
    }
}

fun FileProblem.message(): String = when (this) {
    is FileProblem.TooLarge -> "This file is ${formatBytes(size)}. The limit is 500 MB."
    is FileProblem.Unsupported ->
        if (format.isEmpty()) "That file type isn’t supported. Use PDF, EPUB or TXT."
        else "$format files aren’t supported. Use PDF, EPUB or TXT."
}

/** Why an upload didn't make it, mapped from the server's response. */
sealed interface UploadFailure {
    /** The same bytes are already an edition; [workId] is where (null if it was taken down). */
    data class Duplicate(val workId: String?, val message: String) : UploadFailure
    /**
     * Over the upload allowance. [retryAfterSeconds] is when it frees up,
     * if known; [quota] is the allowance, when the app (not the gateway) said no.
     */
    data class RateLimited(val retryAfterSeconds: Long?, val quota: UploadQuota? = null) : UploadFailure
    data object TooLarge : UploadFailure
    data object Unsupported : UploadFailure
    /** The server rejected the file's contents (PDF/EPUB safety check, bad UTF-8, …). */
    data class FailedCheck(val message: String) : UploadFailure
    data class Other(val message: String) : UploadFailure
}

/**
 * Maps an upload error to what the sheet shows. [afterSend] is true once
 * the whole file reached the server, so a 400 then means its contents were
 * rejected rather than the request being malformed.
 */
fun classifyUploadError(e: Throwable, afterSend: Boolean): UploadFailure = when {
    e is DuplicateEditionException -> UploadFailure.Duplicate(e.workId, e.message ?: "This file is already in the library.")
    e is ApiException && e.status == 429 -> UploadFailure.RateLimited(e.retryAfterSeconds)
    e is ApiException && e.status == 413 -> UploadFailure.TooLarge
    e is ApiException && e.status == 415 -> UploadFailure.Unsupported
    e is ApiException && e.status == 400 && afterSend -> UploadFailure.FailedCheck(e.userMessage)
    e is ApiException -> UploadFailure.Other(e.userMessage)
    else -> UploadFailure.Other("Can’t reach the library. Check your connection.")
}

/** "3 h 12 min", "12 min", "under a minute". */
fun formatWait(seconds: Long): String {
    val minutes = (seconds + 59) / 60
    return when {
        seconds < 60 -> "under a minute"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

/** "per day", "per hour", "every 12 hours". */
internal fun perWindow(seconds: Long): String = when {
    seconds == 86_400L -> "per day"
    seconds == 3_600L -> "per hour"
    seconds % 86_400L == 0L -> "every ${seconds / 86_400L} days"
    seconds % 3_600L == 0L -> "every ${seconds / 3_600L} hours"
    else -> "every ${formatWait(seconds)}"
}

/** The line under the file picker; null when there's no limit to show. */
fun quotaHint(q: UploadQuota): String? {
    if (q.unlimited) return null
    val files = if (q.filesLeft == 1) "1 upload" else "${q.filesLeft} uploads"
    val line = "$files and ${formatBytes(q.bytesLeft)} left of ${q.filesLimit} files · ${formatBytes(q.bytesLimit)} ${perWindow(q.windowSeconds)}."
    return if (q.isNewAccount) "$line New accounts get less; it goes up once your email is verified and the account is a week old." else line
}

/**
 * The upload-limit banner's text. [fileSize] is the chosen file's, if any;
 * [secondsLeft] counts down to when it fits (null if unknown).
 */
fun rateLimitedMessage(f: UploadFailure.RateLimited, fileSize: Long?, secondsLeft: Long?): String {
    val q = f.quota
    val per = q?.let { perWindow(it.windowSeconds) }
    val why = when {
        q == null -> "There have been too many uploads from your network."
        q.filesLeft == 0 -> "You can upload ${q.filesLimit} files $per, and you’ve used them all."
        fileSize != null && fileSize > q.bytesLimit ->
            return "This file is larger than your whole allowance of ${formatBytes(q.bytesLimit)} $per. Choose a smaller file."
        fileSize != null -> "This file needs ${formatBytes(fileSize)}, and ${formatBytes(q.bytesLeft)} of your ${formatBytes(q.bytesLimit)} $per is left."
        else -> "You’ve used your upload allowance."
    }
    val wait = if (secondsLeft != null) "Try again in ${formatWait(secondsLeft)}." else "Try again later."
    return "$why $wait Your details are kept here."
}
