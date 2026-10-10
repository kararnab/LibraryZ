package com.libraryz.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Links the backend emails: `…/reset-password?token=…` and
 * `…/verify-email?token=…`. Any scheme and host, so the same parser serves
 * the emailed https links (web; Android App Links) and the app's own
 * `libraryz://reset-password?token=…` form (Android, desktop).
 */
sealed interface DeepLink {
    data class ResetPassword(val token: String) : DeepLink
    data class VerifyEmail(val token: String) : DeepLink
}

fun parseDeepLink(url: String): DeepLink? {
    val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
    val token = url.substringAfter('?', "").substringBefore('#')
        .split('&')
        .firstOrNull { it.startsWith("token=") }
        ?.removePrefix("token=")
        ?.let(::percentDecode)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return when {
        path.endsWith("/reset-password") -> DeepLink.ResetPassword(token)
        path.endsWith("/verify-email") -> DeepLink.VerifyEmail(token)
        else -> null
    }
}

/** The app's own URL scheme, registered by the Android and desktop apps. */
const val AppLinkScheme = "libraryz"

/** [link] as a `libraryz://` URL, which opens the installed app. */
fun appLinkUrl(link: DeepLink): String = when (link) {
    is DeepLink.ResetPassword -> "$AppLinkScheme://reset-password?token=${percentEncode(link.token)}"
    is DeepLink.VerifyEmail -> "$AppLinkScheme://verify-email?token=${percentEncode(link.token)}"
}

/**
 * Links handed to the app by the platform after launch, or before the UI
 * is up: Android intents, a second desktop launch, macOS URL events. The
 * UI takes each one and opens its screen.
 */
object DeepLinkInbox {
    private val _pending = MutableStateFlow<DeepLink?>(null)
    val pending: StateFlow<DeepLink?> = _pending

    /** Queues [url] if it's one of ours; false (and nothing queued) if not. */
    fun deliver(url: String?): Boolean {
        val link = url?.let(::parseDeepLink) ?: return false
        _pending.value = link
        return true
    }

    /** The waiting link, if any, which is then gone. */
    fun take(): DeepLink? = _pending.getAndUpdate { null }
}

/** Encodes everything but RFC 3986 unreserved characters, as UTF-8. */
private fun percentEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c.toChar() in "-._~") {
            append(c.toChar())
        } else {
            append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
        }
    }
}

/** Decodes %XX escapes (as UTF-8) and '+' as space. */
private fun percentDecode(s: String): String {
    val bytes = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val hex = if (c == '%' && i + 2 <= s.lastIndex) s.substring(i + 1, i + 3).toIntOrNull(16) else null
        when {
            hex != null -> { bytes += hex.toByte(); i += 3 }
            c == '+' -> { bytes += ' '.code.toByte(); i++ }
            else -> { c.toString().encodeToByteArray().forEach { bytes += it }; i++ }
        }
    }
    return bytes.toByteArray().decodeToString()
}

/**
 * The link the app was opened with, if it's one of ours. The web build
 * reads the page URL; the other platforms hand theirs to [DeepLinkInbox].
 */
expect fun launchDeepLink(): DeepLink?

/**
 * Opens a `libraryz://` URL in the installed app, where the platform can
 * (web: the browser asks to open the app). Null elsewhere: the app is
 * already here.
 */
expect val openInApp: ((url: String) -> Unit)?
