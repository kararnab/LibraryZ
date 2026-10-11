package com.libraryz.data

/**
 * A read-only zip archive in memory: enough of the format for EPUB (stored
 * and deflated entries, no zip64, no encryption). Pure Kotlin so every
 * target reads it the same way, with no platform zlib and no async browser
 * streams.
 *
 * The server's upload check already caps entry counts and compression
 * ratios; [maxEntrySize] is the client's own cap on what one entry may
 * inflate to.
 */
class ZipArchive(private val bytes: ByteArray, private val maxEntrySize: Int = 64 shl 20) {

    class Entry internal constructor(
        val name: String,
        internal val method: Int,
        internal val flags: Int,
        internal val compressedSize: Int,
        val size: Int,
        internal val localHeader: Int,
    )

    val entries: List<Entry>
    private val byName: Map<String, Entry>
    private val byLowerName: Map<String, Entry>

    init {
        val eocd = findEndOfCentralDirectory()
        val count = u16(eocd + 10)
        var p = u32(eocd + 16)
        val list = ArrayList<Entry>(count)
        repeat(count) {
            // Offsets are unsigned 32-bit; past 2 GiB they read as negative here.
            // Compared as p > size - n so a large p can't overflow past the check.
            if (p < 0 || p > bytes.size - 46 || u32(p) != CENTRAL_SIG) throw ZipException("Bad central directory")
            val nameLen = u16(p + 28)
            val extraLen = u16(p + 30)
            val commentLen = u16(p + 32)
            if (nameLen > bytes.size - 46 - p) throw ZipException("Bad central directory")
            list += Entry(
                name = bytes.decodeToString(p + 46, p + 46 + nameLen),
                method = u16(p + 10),
                flags = u16(p + 8),
                compressedSize = u32(p + 20),
                size = u32(p + 24),
                localHeader = u32(p + 42),
            )
            p += 46 + nameLen + extraLen + commentLen
        }
        entries = list
        byName = list.associateBy { it.name }
        byLowerName = list.associateBy { it.name.lowercase() }
    }

    /** The entry called [name], matched exactly, else ignoring case (some EPUBs disagree with their own manifests). */
    fun find(name: String): Entry? = byName[name] ?: byLowerName[name.lowercase()]

    fun read(entry: Entry): ByteArray {
        if (entry.flags and 1 != 0) throw ZipException("${entry.name} is encrypted")
        val h = entry.localHeader
        if (h < 0 || h > bytes.size - 30 || u32(h) != LOCAL_SIG) throw ZipException("Bad local header for ${entry.name}")
        val start = h + 30 + u16(h + 26) + u16(h + 28)
        if (entry.compressedSize < 0 || entry.compressedSize > bytes.size - start) throw ZipException("${entry.name} runs past the archive")
        val end = start + entry.compressedSize
        return when (entry.method) {
            0 -> {
                if (entry.compressedSize > maxEntrySize) throw ZipException("${entry.name} is too large")
                bytes.copyOfRange(start, end)
            }
            8 -> inflate(bytes, start, end, sizeHint = entry.size, maxSize = maxEntrySize)
            else -> throw ZipException("${entry.name} uses unsupported compression ${entry.method}")
        }
    }

    /** [name]'s contents as UTF-8 text without a byte-order mark, or null if there's no such entry. */
    fun readText(name: String): String? = find(name)?.let { read(it).decodeToString().removePrefix("﻿") }

    private fun findEndOfCentralDirectory(): Int {
        // The record is 22 bytes plus a comment of up to 64 KiB, at the very end.
        val lowest = maxOf(0, bytes.size - 22 - 0xFFFF)
        for (p in bytes.size - 22 downTo lowest) {
            if (u32(p) == EOCD_SIG) return p
        }
        throw ZipException("Not a zip archive")
    }

    private fun u16(p: Int): Int = (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8)
    private fun u32(p: Int): Int = u16(p) or (u16(p + 2) shl 16)

    private companion object {
        const val LOCAL_SIG = 0x04034b50
        const val CENTRAL_SIG = 0x02014b50
        const val EOCD_SIG = 0x06054b50
    }
}

class ZipException(message: String) : RuntimeException(message)

/**
 * Decodes raw DEFLATE (RFC 1951) data in [src] from [start] to [end].
 * Canonical Huffman decoding, after zlib's `puff`: small and easy to check
 * rather than fast, which is plenty for a book's text. Throws [ZipException]
 * on corrupt data or past [maxSize] bytes of output.
 */
fun inflate(src: ByteArray, start: Int = 0, end: Int = src.size, sizeHint: Int = 0, maxSize: Int = Int.MAX_VALUE): ByteArray =
    Inflater(src, start, end, sizeHint, maxSize).run()

private class Huffman(lengths: IntArray, offset: Int, n: Int) {
    val count = IntArray(MAX_BITS + 1)
    val symbol = IntArray(n)

    init {
        for (i in 0 until n) count[lengths[offset + i]]++
        count[0] = 0
        val offs = IntArray(MAX_BITS + 1)
        for (len in 1 until MAX_BITS) offs[len + 1] = offs[len] + count[len]
        for (i in 0 until n) {
            val len = lengths[offset + i]
            if (len != 0) symbol[offs[len]++] = i
        }
    }

    companion object {
        const val MAX_BITS = 15
    }
}

private class Inflater(
    private val src: ByteArray,
    private var pos: Int,
    private val end: Int,
    sizeHint: Int,
    private val maxSize: Int,
) {
    private var out = ByteArray(sizeHint.coerceIn(1024, maxOf(1024, minOf(maxSize, 64 shl 20))))
    private var outLen = 0
    private var bitBuf = 0
    private var bitCount = 0

    fun run(): ByteArray {
        do {
            val last = bits(1)
            when (bits(2)) {
                0 -> stored()
                1 -> codes(FIXED_LITERALS, FIXED_DISTANCES)
                2 -> dynamic()
                else -> throw ZipException("Bad deflate block type")
            }
        } while (last == 0)
        return out.copyOf(outLen)
    }

    private fun bits(need: Int): Int {
        var buf = bitBuf
        while (bitCount < need) {
            if (pos >= end) throw ZipException("Deflate data ends early")
            buf = buf or ((src[pos++].toInt() and 0xFF) shl bitCount)
            bitCount += 8
        }
        bitBuf = buf ushr need
        bitCount -= need
        return buf and ((1 shl need) - 1)
    }

    private fun ensure(more: Int) {
        val need = outLen + more
        if (need > maxSize || need < 0) throw ZipException("Entry inflates past ${maxSize} bytes")
        if (need > out.size) out = out.copyOf(minOf(maxSize, maxOf(need, out.size * 2)))
    }

    private fun stored() {
        bitBuf = 0
        bitCount = 0
        if (pos + 4 > end) throw ZipException("Deflate data ends early")
        val len = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
        val nlen = (src[pos + 2].toInt() and 0xFF) or ((src[pos + 3].toInt() and 0xFF) shl 8)
        if (len != nlen.inv() and 0xFFFF) throw ZipException("Bad stored block length")
        pos += 4
        if (pos + len > end) throw ZipException("Deflate data ends early")
        ensure(len)
        src.copyInto(out, outLen, pos, pos + len)
        outLen += len
        pos += len
    }

    private fun decode(h: Huffman): Int {
        var code = 0
        var first = 0
        var index = 0
        for (len in 1..Huffman.MAX_BITS) {
            code = code or bits(1)
            val count = h.count[len]
            if (code - count < first) return h.symbol[index + (code - first)]
            index += count
            first = (first + count) shl 1
            code = code shl 1
        }
        throw ZipException("Bad Huffman code")
    }

    private fun codes(literals: Huffman, distances: Huffman) {
        while (true) {
            var symbol = decode(literals)
            if (symbol < 256) {
                ensure(1)
                out[outLen++] = symbol.toByte()
                continue
            }
            if (symbol == 256) return
            symbol -= 257
            if (symbol >= 29) throw ZipException("Bad length symbol")
            val len = LENGTH_BASE[symbol] + bits(LENGTH_EXTRA[symbol])
            val d = decode(distances)
            if (d >= 30) throw ZipException("Bad distance symbol")
            val dist = DIST_BASE[d] + bits(DIST_EXTRA[d])
            if (dist > outLen) throw ZipException("Distance too far back")
            ensure(len)
            var from = outLen - dist
            repeat(len) { out[outLen++] = out[from++] }
        }
    }

    private fun dynamic() {
        val nlen = bits(5) + 257
        val ndist = bits(5) + 1
        val ncode = bits(4) + 4
        if (nlen > 286 || ndist > 30) throw ZipException("Bad dynamic block counts")
        val lengths = IntArray(320)
        for (i in 0 until ncode) lengths[CODE_ORDER[i]] = bits(3)
        val lencode = Huffman(lengths, 0, 19)
        lengths.fill(0)
        var index = 0
        while (index < nlen + ndist) {
            val symbol = decode(lencode)
            if (symbol < 16) {
                lengths[index++] = symbol
                continue
            }
            var len = 0
            val repeat = when (symbol) {
                16 -> {
                    if (index == 0) throw ZipException("Repeat with no previous length")
                    len = lengths[index - 1]
                    3 + bits(2)
                }
                17 -> 3 + bits(3)
                else -> 11 + bits(7)
            }
            if (index + repeat > nlen + ndist) throw ZipException("Too many lengths")
            repeat(repeat) { lengths[index++] = len }
        }
        if (lengths[256] == 0) throw ZipException("No end-of-block code")
        codes(Huffman(lengths, 0, nlen), Huffman(lengths, nlen, ndist))
    }

    companion object {
        val LENGTH_BASE = intArrayOf(
            3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
            35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
        )
        val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
        val DIST_BASE = intArrayOf(
            1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
            257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
        )
        val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
        val CODE_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

        val FIXED_LITERALS = Huffman(
            IntArray(288) { when { it < 144 -> 8; it < 256 -> 9; it < 280 -> 7; else -> 8 } }, 0, 288,
        )
        val FIXED_DISTANCES = Huffman(IntArray(30) { 5 }, 0, 30)
    }
}
