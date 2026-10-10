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
