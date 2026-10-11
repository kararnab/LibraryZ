package com.libraryz.data

/**
 * An encoded image's pixel size, read from its header without decoding it:
 * PNG, JPEG, GIF, WebP and BMP, the raster formats every platform's decoder
 * reads. Null for anything else (SVG included) or a header too short to say.
 */
fun imageSize(b: ByteArray): Pair<Int, Int>? {
    fun u8(p: Int) = b[p].toInt() and 0xFF
    fun be16(p: Int) = (u8(p) shl 8) or u8(p + 1)
    fun le16(p: Int) = u8(p) or (u8(p + 1) shl 8)
    fun le24(p: Int) = le16(p) or (u8(p + 2) shl 16)
    fun be32(p: Int) = (be16(p) shl 16) or be16(p + 2)
    fun le32(p: Int) = le16(p) or (le16(p + 2) shl 16)
    fun ascii(p: Int, s: String) = p + s.length <= b.size && s.indices.all { b[p + it] == s[it].code.toByte() }

    val size = when {
        b.size >= 24 && ascii(1, "PNG") && ascii(12, "IHDR") -> be32(16) to be32(20)
        b.size >= 10 && ascii(0, "GIF8") -> le16(6) to le16(8)
        b.size >= 26 && ascii(0, "BM") -> le32(18) to kotlin.math.abs(le32(22))
        b.size >= 30 && ascii(0, "RIFF") && ascii(8, "WEBP") -> when {
            ascii(12, "VP8 ") -> (le16(26) and 0x3FFF) to (le16(28) and 0x3FFF)
            ascii(12, "VP8L") -> {
                val bits = le32(21)
                1 + (bits and 0x3FFF) to 1 + ((bits ushr 14) and 0x3FFF)
            }
            ascii(12, "VP8X") -> 1 + le24(24) to 1 + le24(27)
            else -> null
        }
        b.size >= 4 && u8(0) == 0xFF && u8(1) == 0xD8 -> jpegSize(b, ::u8, ::be16)
        else -> null
    } ?: return null
    return size.takeIf { (w, h) -> w > 0 && h > 0 }
}

/** The frame size from a JPEG's first start-of-frame segment. */
private fun jpegSize(b: ByteArray, u8: (Int) -> Int, be16: (Int) -> Int): Pair<Int, Int>? {
    var p = 2
    while (p + 9 <= b.size) {
        if (u8(p) != 0xFF) return null
        val marker = u8(p + 1)
        when {
            marker == 0xFF -> p++ // fill byte
            marker in 0xD0..0xD9 || marker == 0x01 -> p += 2 // no length
            marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC ->
                return be16(p + 7) to be16(p + 5)
            else -> p += 2 + be16(p + 2)
        }
    }
    return null
}
