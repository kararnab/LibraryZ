package com.libraryz.data

/**
 * Links the backend emails: `…/reset-password?token=…` and
 * `…/verify-email?token=…`. Any scheme and host, so the same parser serves
 * web URLs and app links once the backend settles on their form.
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
 * reads the page URL; other platforms return null until app links are
 * registered for the backend's link format.
 */
expect fun launchDeepLink(): DeepLink?
