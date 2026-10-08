package com.libraryz.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadTest {
    @Test
    fun safeDownloadNameStripsPathsAndKeepsExtension() {
        assertEquals("passwd.pdf", safeDownloadName("../../etc/passwd.pdf"))
        assertEquals("evil.txt", safeDownloadName("C:\\Windows\\evil.txt"))
        assertEquals("hidden.epub", safeDownloadName("..hidden.epub"))
        assertEquals("a_b.pdf", safeDownloadName("a:b.pdf"))
        val long = "x".repeat(150) + ".pdf"
        assertEquals(long, safeDownloadName(long))
        assertNull(safeDownloadName("../"))
        assertNull(safeDownloadName("   "))
    }
}
