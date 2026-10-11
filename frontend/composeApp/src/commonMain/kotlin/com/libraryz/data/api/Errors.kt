package com.libraryz.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

/**
 * Re-throws [CancellationException]. Call it first in a `catch (e: Throwable)`
 * around suspending work: a cancelled coroutine must stop, not carry on as if
 * the request had failed and leave an error message or a dropped change behind.
 */
fun Throwable.rethrowIfCancelled() {
    if (this is CancellationException) throw this
}

/** [runCatching] that lets cancellation through. Use it around suspending calls. */
inline fun <T> catchingNonCancel(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: Throwable) {
        e.rethrowIfCancelled()
        Result.failure(e)
    }

/**
 * True when [e] means no answer came back from the server: it's down, the
 * address is wrong, or the device is offline. On Android, Desktop and iOS
 * the engines throw an [IOException] (timeouts included), possibly wrapped;
 * the browser's fetch only says it failed.
 */
fun isUnreachable(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(8).any {
    it is IOException || it.message?.contains("fetch", ignoreCase = true) == true && it !is ApiException
}

/**
 * [e] from a request to [server] as a sentence for the reader. Never the
 * exception's own text: engine messages carry URLs and timeouts nobody
 * should have to read.
 */
fun describeError(e: Throwable, server: String): String = when {
    // 4xx bodies are the backend's user-facing messages (see ApiException.userMessage).
    e is ApiException && e.status in 400..499 -> e.userMessage
    e is ApiException -> "The library ran into a problem. Please try again in a moment."
    isUnreachable(e) -> "Can’t reach the library at ${server.substringAfter("://")}. " +
        "Check your connection, or the server address in Settings."
    else -> "Something went wrong. Please try again."
}

/** [describeError] for a request this client made. */
fun ApiClient.describe(e: Throwable): String = describeError(e, baseUrl)
