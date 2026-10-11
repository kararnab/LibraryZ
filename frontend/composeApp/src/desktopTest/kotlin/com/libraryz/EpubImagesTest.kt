package com.libraryz

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.libraryz.data.api.ApiClient
import com.libraryz.theme.LibraryZTheme
import com.libraryz.ui.screens.ReaderPrefs
import com.libraryz.ui.screens.ReaderScreen
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * EPUB pictures drawn by the real reader, scrolling and in a two-page
 * spread: a solid red PNG must show up as red pixels, about as large as it
 * was laid out. LIBRARYZ_TEST_SHOTS=dir saves what each case rendered.
 */
class EpubImagesTest {

    private fun png(width: Int, height: Int, rgb: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, rgb)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun epub(body: String, images: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip".encodeToByteArray())
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".encodeToByteArray())
            put(
                "OEBPS/content.opf",
                """<package><manifest><item id="a" href="Text/a.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine></package>""".encodeToByteArray(),
            )
            put("OEBPS/Text/a.xhtml", "<html><body>$body</body></html>".encodeToByteArray())
            images.forEach { (name, bytes) -> put("OEBPS/Images/$name", bytes) }
        }
    }.toByteArray()

    /** Opens [book] in a [width]×[height] reader and returns the settled frame. */
    private fun render(book: ByteArray, width: Int, height: Int, name: String): Image {
        val api = ApiClient("http://test", engine = MockEngine { respond(book, HttpStatusCode.OK) })
        val scene = ImageComposeScene(width, height, Density(1f)) {
            LibraryZTheme {
                ReaderScreen(
                    api = api, editionId = "e1", format = "EPUB", title = "T", authors = null,
                    resumeFrom = null, finished = false, rating = null,
                    prefs = ReaderPrefs(), onPrefsChange = {},
                    onProgress = {}, onMarkRead = {}, onRate = {}, onClose = {},
                )
            }
        }
        try {
            var frame = scene.render(System.nanoTime())
            val until = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < until) {
                Thread.sleep(16)
                frame = scene.render(System.nanoTime())
            }
            System.getenv("LIBRARYZ_TEST_SHOTS")?.let { dir ->
                File(dir, "$name.png").writeBytes(frame.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            return frame
        } finally {
            scene.close()
        }
    }

    /** Pixels that are clearly the test's red. */
    private fun redPixels(frame: Image): Int {
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(frame)
        var n = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val c = bitmap.getColor(x, y)
            if ((c shr 16 and 0xFF) > 200 && (c shr 8 and 0xFF) < 60 && (c and 0xFF) < 60) n++
        }
        return n
    }

    private val red = png(300, 200, 0xE01010)

    @Test
    fun drawsPicturesWhileScrolling() {
        val book = epub("""<h1>One</h1><p>Before.</p><p><img src="../Images/red.png" alt="Red"/></p><p>After.</p>""", mapOf("red.png" to red))
        val n = redPixels(render(book, 600, 800, "scroll"))
        // 300×200 at 1 dp a pixel, density 1.
        assertTrue(n in 56_000..61_000, "red pixels: $n")
    }

    @Test
    fun fitsPicturesToASpreadPage() {
        // Taller than a page: shrunk to the page's height, and the heading picture lays out too.
        val tall = png(400, 2000, 0xE01010)
        val book = epub(
            """<h1><img src="../Images/title.png" alt="Chapter One"/></h1><p>Text.</p><p><img src="../Images/tall.png"/></p><p>After.</p>""",
            mapOf("title.png" to png(200, 60, 0x2040C0), "tall.png" to tall),
        )
        val frame = render(book, 1280, 800, "spread")
        val n = redPixels(frame)
        // Below the 64 dp top bar a page's body is 620 high, so about 124×618, on the next page.
        assertTrue(n in 70_000..80_000, "red pixels: $n")
    }
}
