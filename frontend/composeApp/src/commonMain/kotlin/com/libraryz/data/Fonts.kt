package com.libraryz.data

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * A font file an EPUB embeds, as a Compose [Font], or null if the platform
 * can't load it. [identity] names it (a hash of its bytes), so the same file
 * is loaded once. Desktop, web and iOS load from memory; Android from a file
 * in the app's cache.
 */
internal expect fun loadFont(identity: String, bytes: ByteArray, weight: FontWeight, style: FontStyle): Font?

/** TrueType / OpenType (including collections): what every platform's font loader reads. WOFF isn't. */
internal fun isSfnt(b: ByteArray): Boolean {
    if (b.size < 12) return false
    val tag = (b[0].toInt() and 0xFF shl 24) or (b[1].toInt() and 0xFF shl 16) or (b[2].toInt() and 0xFF shl 8) or (b[3].toInt() and 0xFF)
    return tag == 0x00010000 || tag == 0x4F54544F /* OTTO */ || tag == 0x74727565 /* true */ || tag == 0x74746366 /* ttcf */
}

/** EPUB font obfuscation (`META-INF/encryption.xml`): which scheme hides a font. */
internal enum class FontObfuscation(val algorithm: String) {
    Idpf("http://www.idpf.org/2008/embedding"),
    Adobe("http://ns.adobe.com/pdf/enc#RC"),
}

/**
 * Undoes a font's obfuscation in place, keyed by the book's unique
 * identifier: IDPF XORs the first 1040 bytes with the SHA-1 of the
 * identifier (whitespace removed); Adobe XORs the first 1024 with the 16
 * bytes of its `urn:uuid:`. False if the key can't be made.
 */
internal fun deobfuscate(font: ByteArray, scheme: FontObfuscation, identifier: String): Boolean {
    val key: ByteArray
    val length: Int
    when (scheme) {
        FontObfuscation.Idpf -> {
            key = sha1(identifier.filterNot { it == ' ' || it == '\t' || it == '\r' || it == '\n' }.encodeToByteArray())
            length = 1040
        }
        FontObfuscation.Adobe -> {
            val hex = identifier.trim().removePrefix("urn:uuid:").replace("-", "")
            if (hex.length != 32) return false
            key = ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toIntOrNull(16)?.toByte() ?: return false }
            length = 1024
        }
    }
    for (i in 0 until minOf(length, font.size)) font[i] = (font[i].toInt() xor key[i % key.size].toInt()).toByte()
    return true
}

/** SHA-1 of [data]: only for the IDPF font key, not for anything security-sensitive. */
internal fun sha1(data: ByteArray): ByteArray {
    var h0 = 0x67452301
    var h1 = 0xEFCDAB89.toInt()
    var h2 = 0x98BADCFE.toInt()
    var h3 = 0x10325476
    var h4 = 0xC3D2E1F0.toInt()
    val bitLength = data.size.toLong() * 8
    val padded = ByteArray(((data.size + 9 + 63) / 64) * 64)
    data.copyInto(padded)
    padded[data.size] = 0x80.toByte()
    for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
    val w = IntArray(80)
    for (chunk in padded.indices step 64) {
        for (i in 0 until 16) {
            val p = chunk + i * 4
            w[i] = (padded[p].toInt() and 0xFF shl 24) or (padded[p + 1].toInt() and 0xFF shl 16) or
                (padded[p + 2].toInt() and 0xFF shl 8) or (padded[p + 3].toInt() and 0xFF)
        }
        for (i in 16 until 80) w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        for (i in 0 until 80) {
            val (f, k) = when (i) {
                in 0..19 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                in 20..39 -> (b xor c xor d) to 0x6ED9EBA1
                in 40..59 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDC.toInt()
                else -> (b xor c xor d) to 0xCA62C1D6.toInt()
            }
            val t = a.rotateLeft(5) + f + e + k + w[i]
            e = d
            d = c
            c = b.rotateLeft(30)
            b = a
            a = t
        }
        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
    }
    return ByteArray(20) { i -> (intArrayOf(h0, h1, h2, h3, h4)[i / 4] ushr (24 - 8 * (i % 4))).toByte() }
}

internal fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
