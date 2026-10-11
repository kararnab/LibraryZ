package com.libraryz.data

import java.util.zip.Deflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** Checks the pure-Kotlin [inflate] against the JDK's zlib on many shapes of input. */
class InflateJvmTest {

    private fun deflate(data: ByteArray, level: Int, strategy: Int): ByteArray {
        val d = Deflater(level, true)
        d.setStrategy(strategy)
        d.setInput(data)
        d.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    @Test
    fun matchesZlibAcrossLevelsAndStrategies() {
        val random = Random(42)
        val words = listOf("the ", "and ", "whale ", "Ishmael ", "sea, ", "\n\n", "<p>", "</p>", "—", "ç")
        val inputs = listOf(
            ByteArray(0),
            "a".encodeToByteArray(),
            ByteArray(100_000) { 7 }, // long runs: maximum-length matches
            ByteArray(70_000) { random.nextInt().toByte() }, // incompressible: stored blocks
            buildString { repeat(40_000) { append(words[random.nextInt(words.size)]) } }.encodeToByteArray(),
        )
        for (data in inputs) {
            for (level in listOf(0, 1, 6, 9)) {
                for (strategy in listOf(Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY)) {
                    val packed = deflate(data, level, strategy)
                    assertContentEquals(data, inflate(packed, sizeHint = data.size), "level $level strategy $strategy size ${data.size}")
                }
            }
        }
    }
}
