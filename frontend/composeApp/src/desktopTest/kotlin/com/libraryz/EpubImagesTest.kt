package com.libraryz

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.libraryz.data.api.ApiClient
import com.libraryz.theme.LibraryZTheme
import com.libraryz.theme.ReadingTheme
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
import kotlin.math.abs
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

    private fun epub(body: String, images: Map<String, ByteArray>, css: String = ""): ByteArray = ByteArrayOutputStream().also { out ->
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
            put("OEBPS/Text/a.xhtml", "<html><head><style>$css</style></head><body>$body</body></html>".encodeToByteArray())
            images.forEach { (name, bytes) -> put("OEBPS/Images/$name", bytes) }
        }
    }.toByteArray()

    /** Opens [book] in a [width]×[height] reader and returns the settled frame. */
    private fun render(book: ByteArray, width: Int, height: Int, name: String, prefs: ReaderPrefs = ReaderPrefs()): Image {
        val api = ApiClient("http://test", engine = MockEngine { respond(book, HttpStatusCode.OK) })
        val scene = ImageComposeScene(width, height, Density(1f)) {
            LibraryZTheme {
                ReaderScreen(
                    api = api, editionId = "e1", format = "EPUB", title = "T", authors = null,
                    resumeFrom = null, finished = false, rating = null,
                    prefs = prefs, onPrefsChange = {},
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
    private fun redPixels(frame: Image): Int =
        pixels(frame) { r, g, b -> r > 200 && g < 60 && b < 60 }

    private fun pixels(frame: Image, test: (r: Int, g: Int, b: Int) -> Boolean): Int {
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(frame)
        var n = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val c = bitmap.getColor(x, y)
            if (test(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF)) n++
        }
        return n
    }

    /** A page of the book's own styling: alignment, a poem, colours, a highlight and a table. */
    private val styled = epub(
        """<h2>Styles</h2>
           <p class="center">A centered line.</p>
           <p>Plain text with a <span class="key">key term</span> and a <span class="hl">highlighted phrase</span>.</p>
           <div class="poem"><span class="l">The first line of verse</span><span class="l2">an indented second</span></div>
           <table class="grid"><tr><th>Word</th><th>Meaning</th></tr>
             <tr><td class="cue">ephemeral</td><td>lasting a very short time, gone almost as soon as it came</td></tr>
             <tr><td class="cue">laconic</td><td>using very few words</td></tr></table>
           <p>After the table.</p>
           <div class="note"><p>A boxed note, as the book's stylesheet draws it.</p></div>
           <p class="big">Large type</p>
           <p><img src="../Images/mark.svg" alt="mark"/></p>""",
        mapOf("mark.svg" to """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 40 20" width="200" height="100"><rect width="40" height="20" fill="#e01010"/></svg>""".encodeToByteArray()),
        css = """.center { text-align: center } .key { color: #1565c0; font-weight: bold } .hl { background-color: #fff176 }
                 .poem { margin-left: 2em } span.l, span.l2 { display: block } span.l2 { margin-left: 2em }
                 table.grid td, table.grid th { border: 1px solid #999 } td.cue { background: #c8e6c9; color: #2e7d32 }
                 .note { border: 1px solid #1565c0; background: #e3f2fd } .big { font-size: 1.6em }""",
    )

    @Test
    fun drawsTheBooksStylesInEachTheme() {
        val yellow = { r: Int, g: Int, b: Int -> r > 235 && g > 220 && b in 90..140 }
        val green = { r: Int, g: Int, b: Int -> abs(r - 0xC8) < 8 && abs(g - 0xE6) < 8 && abs(b - 0xC9) < 8 }
        val blue = { r: Int, g: Int, b: Int -> b > 150 && r < 80 && g in 60..140 }
        val light = render(styled, 600, 1400, "styles-light")
        assertTrue(pixels(light, yellow) > 1_000, "highlight")
        assertTrue(pixels(light, green) > 5_000, "cell background")
        assertTrue(pixels(light, blue) > 50, "key term")
        assertTrue(pixels(light) { r, g, b -> abs(r - 0xE3) < 6 && abs(g - 0xF2) < 6 && abs(b - 0xFD) < 6 } > 5_000, "note box")
        // The SVG picture, rasterized: 200 × 100 of red.
        assertTrue(redPixels(light) in 18_000..21_000, "svg: ${redPixels(light)}")
        val dark = render(styled, 600, 1400, "styles-dark", ReaderPrefs(theme = ReadingTheme.Dark))
        // On dark the highlight and cell keep their hue but are toned down, so the light ink still reads.
        assertTrue(pixels(dark, yellow) < 50 && pixels(dark, green) < 50, "untoned colours on dark")
        assertTrue(pixels(dark) { r, g, b -> g > r + 20 && g > b + 20 && g in 40..110 } > 5_000, "toned cell background")
        render(styled, 1280, 800, "styles-spread")
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
