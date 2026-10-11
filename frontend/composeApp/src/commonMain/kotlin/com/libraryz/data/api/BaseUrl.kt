package com.libraryz.data.api

// Where the LibraryZ Go backend listens. Per-platform because the Android
// emulator can't reach the host's localhost — it has to go through 10.0.2.2.
expect val DefaultBaseUrl: String

/**
 * True when [url]'s host is on the local network: a private (10/8,
 * 172.16/12, 192.168/16), link-local, or IPv6 unique-local address, or a
 * `.local` name. Android 17 lets an app reach those only with the
 * ACCESS_LOCAL_NETWORK permission (the emulator's 10.0.2.2, a dev LAN IP,
 * a home server). Loopback and public hosts need nothing.
 */
fun isLocalNetworkUrl(url: String): Boolean {
    val authority = url.substringAfter("://", "").substringBefore('/').substringAfter('@')
    val host = (if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']') else authority.substringBefore(':'))
        .lowercase().trimEnd('.')
    if (host.endsWith(".local")) return true
    if (':' in host) return host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe8") ||
        host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")
    val octets = host.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    val (a, b) = octets
    return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
}

/**
 * [input] as a server base URL, or null when it's blank or not one.
 * Accepts `http(s)://host[:port][/path]`; a bare `host[:port]` gets
 * `http://`. The host is `localhost`, an IPv4 address, a bracketed IPv6
 * address or a DNS name. Scheme and host are lowercased and trailing
 * slashes dropped, so `"$base/works"` keeps one slash.
 */
fun parseServerUrl(input: String): String? {
    val s = input.trim()
    if (s.isEmpty() || s.any { it.isWhitespace() }) return null
    val scheme = s.substringBefore("://", "").lowercase().ifEmpty { "http" }
    if (scheme != "http" && scheme != "https") return null
    val rest = if ("://" in s) s.substringAfter("://") else s
    val authority = rest.substringBefore('/').lowercase()
    val path = rest.substring(rest.substringBefore('/').length).trimEnd('/')
    if (path.any { !(it.isLetterOrDigit() && it.code < 128) && it !in "-._~/%" }) return null
    val hostEnd = if (authority.startsWith("[")) authority.indexOf(']') + 1 else authority.substringBefore(':').length
    if (hostEnd <= 0) return null
    val host = authority.substring(0, hostEnd)
    val port = authority.substring(hostEnd)
    if (port.isNotEmpty() && (port[0] != ':' || port.drop(1).toIntOrNull() !in 1..65535 || port.drop(1).any { !it.isDigit() })) return null
    if (!isValidHost(host)) return null
    return "$scheme://$authority$path"
}

private fun isValidHost(host: String): Boolean {
    if (host.startsWith("[")) {
        val inner = host.removeSurrounding("[", "]")
        return ':' in inner && inner.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' }
    }
    if (host.isEmpty() || host.length > 253) return false
    if (host.all { it.isDigit() || it == '.' }) {
        val octets = host.split('.')
        return octets.size == 4 && octets.all { it.isNotEmpty() && it.length <= 3 && it.toInt() in 0..255 }
    }
    return host.split('.').all { label ->
        label.length in 1..63 && label.first() != '-' && label.last() != '-' &&
            label.all { it in 'a'..'z' || it.isDigit() || it == '-' }
    }
}

/**
 * The server address chosen in Settings, kept on this device. Nothing
 * saved (or something that no longer parses) means [DefaultBaseUrl].
 */
class ServerUrlStore(storeFactory: () -> TokenStore) {
    // Opened on first use; if it can't be, the choice lasts for the session.
    private val store: TokenStore? by lazy { runCatching(storeFactory).getOrNull() }

    suspend fun load(): String? = runCatching { store?.load()?.let(::parseServerUrl) }.getOrNull()

    /** Saves [url], or forgets the choice when it's null (back to the default). */
    suspend fun save(url: String?) {
        runCatching { if (url == null) store?.clear() else store?.save(url) }
    }
}

/**
 * True for a development address: loopback or the local network. Two of
 * these may well be one machine (the emulator's 10.0.2.2 and the host's
 * LAN IP), so switching between them keeps the session; it never carries
 * tokens to or from a public server.
 */
fun isDevServerUrl(url: String): Boolean {
    if (isLocalNetworkUrl(url)) return true
    val authority = parseServerUrl(url)?.substringAfter("://")?.substringBefore('/') ?: return false
    if (authority.startsWith("[")) return authority.substringBefore(']') == "[::1"
    val host = authority.substringBefore(':')
    val octets = host.split('.')
    return host == "localhost" || (octets.size == 4 && octets[0] == "127" && octets.all { o -> o.isNotEmpty() && o.all { it.isDigit() } })
}
