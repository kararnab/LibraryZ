package com.libraryz.data

import androidx.compose.ui.text.font.FontFamily
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A book's embedded fonts, read for real: the app's own Figtree, obfuscated as EPUBs do. */
class EmbeddedFontsTest {

    private val figtree = File("src/commonMain/composeResources/font/figtree.ttf").readBytes()

    private fun epub(font: ByteArray, encryption: String?): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip".encodeToByteArray())
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".encodeToByteArray())
            encryption?.let { put("META-INF/encryption.xml", it.encodeToByteArray()) }
            put(
                "OEBPS/content.opf",
                """<package unique-identifier="id"><metadata><dc:identifier id="id">urn:isbn:9780000000001</dc:identifier></metadata>
                   <manifest><item id="a" href="Text/a.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine></package>""".encodeToByteArray(),
            )
            put(
                "OEBPS/Text/a.xhtml",
                """<html><head><style>@font-face { font-family: "Display"; src: url(../Fonts/display.ttf) }
                   .d { font-family: Display, serif } code { font-family: monospace } .m { font-family: "Missing", serif }</style></head>
                   <body><p class="d">Display</p><p><code>code</code> <span class="m">missing</span> plain</p></body></html>""".encodeToByteArray(),
            )
            put("OEBPS/Fonts/display.ttf", font)
        }
    }.toByteArray()

    private fun familyOf(book: EpubBook, of: String): FontFamily? =
        book.text.spanStyles.lastOrNull { book.text.text.substring(it.start, it.end) == of && it.item.fontFamily != null }?.item?.fontFamily

    @Test
    fun loadsAnObfuscatedFont() {
        val hidden = figtree.copyOf()
        deobfuscate(hidden, FontObfuscation.Idpf, "urn:isbn:9780000000001")
        val encryption = """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
            <enc:EncryptedData><enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/>
            <enc:CipherData><enc:CipherReference URI="OEBPS/Fonts/display.ttf"/></enc:CipherData></enc:EncryptedData></encryption>"""
        val book = parseEpub(epub(hidden, encryption))
        assertNotNull(familyOf(book, "Display"))
        assertEquals(FontFamily.Monospace, familyOf(book, "code"))
        // A family the book doesn't carry, and generic serif, stay the reader's.
        assertEquals(null, familyOf(book, "missing"))
        assertTrue(TextReader(book.text, book.chapters).hasBookFonts)
    }

    @Test
    fun skipsFontsItCannotRead() {
        // Obfuscated but not declared so: the bytes aren't a font.
        val hidden = figtree.copyOf()
        deobfuscate(hidden, FontObfuscation.Idpf, "urn:isbn:9780000000001")
        assertEquals(null, familyOf(parseEpub(epub(hidden, null)), "Display"))
        // A real font under an encryption the reader doesn't know (DRM).
        val drm = """<encryption><EncryptedData><EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/>
            <CipherData><CipherReference URI="OEBPS/Fonts/display.ttf"/></CipherData></EncryptedData></encryption>"""
        val book = parseEpub(epub(figtree, drm))
        assertEquals(null, familyOf(book, "Display"))
        assertFalse(TextReader(book.text, book.chapters).hasBookFonts)
    }
}
