package com.libraryz

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import com.libraryz.data.ReadingPosition
import com.libraryz.data.api.ApiClient
import com.libraryz.theme.LibraryZTheme
import com.libraryz.ui.Fullscreen
import com.libraryz.ui.LocalFullscreen
import com.libraryz.ui.screens.ReaderPrefs
import com.libraryz.ui.screens.ReaderScreen
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reader keys: arrows turn PDF pages (single and spread), F11 and Esc drive full screen. */
class ReaderKeyboardTest {

    private fun pdf(pages: Int): ByteArray = PDDocument().use { doc ->
        repeat(pages) { doc.addPage(PDPage()) }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    // Compose has no stable way to build a desktop key event for a scene.
    @OptIn(InternalComposeUiApi::class)
    private fun keyDown(key: Key) = KeyEvent(key = key, type = KeyEventType.KeyDown)

    /** Snapshot state, like the real ones, so the reader reacts to changes. */
    private class FakeFullscreen : Fullscreen {
        override val isSupported = true
        override var isOn by mutableStateOf(false)
        override fun set(on: Boolean) { isOn = on }
    }

    /** What a reading session did: the last reported position, and whether the reader asked to close. */
    private class Session(val reported: ReadingPosition?, val closed: Boolean)

    /**
     * Opens a 10-page PDF in a [width]×[height] window, sends [keys] once it
     * has loaded, then closes the reader (disposing it).
     */
    private fun read(
        width: Int,
        height: Int,
        keys: List<Key>,
        fullscreen: Fullscreen = FakeFullscreen(),
    ): Session {
        val body = pdf(10)
        var downloaded = false
        val api = ApiClient("http://test", engine = MockEngine { downloaded = true; respond(body, HttpStatusCode.OK) })
        var reported: ReadingPosition? = null
        var closed = false
        val scene = ImageComposeScene(width, height, Density(1f)) {
            CompositionLocalProvider(LocalFullscreen provides fullscreen) {
                LibraryZTheme {
                    ReaderScreen(
                        api = api, editionId = "e1", format = "PDF", title = "T", authors = null,
                        resumeFrom = null, finished = false, rating = null,
                        prefs = ReaderPrefs(), onPrefsChange = {},
                        onProgress = { reported = it },
                        onMarkRead = {}, onRate = {}, onClose = { closed = true },
                    )
                }
            }
        }
        try {
            fun frames(ms: Long) {
                val until = System.currentTimeMillis() + ms
                while (System.currentTimeMillis() < until) {
                    scene.render(System.nanoTime())
                    Thread.sleep(16)
                }
            }
            // Download, open and first render, then the focus request.
            val loadBy = System.currentTimeMillis() + 10_000
            while (!downloaded && System.currentTimeMillis() < loadBy) frames(50)
            frames(1_000)
            keys.forEach {
                scene.sendKeyEvent(keyDown(it))
                frames(100)
            }
            frames(1_500) // progress is reported a second after it settles
        } finally {
            scene.close()
        }
        return Session(reported, closed)
    }

    @Test
    fun rightArrowTurnsOnePageAtATime() {
        // 600 wide: below the spread threshold.
        val right = Key.DirectionRight
        assertEquals(ReadingPosition(2, 10), read(600, 800, listOf(right, right)).reported)
    }

    @Test
    fun rightArrowTurnsWholeSpreads() {
        // 1280×800 landscape: spread by default. Cover, 2–3, then 4–5
        // (0-based 3–4); progress is the furthest visible page.
        val right = Key.DirectionRight
        assertEquals(ReadingPosition(4, 10), read(1280, 800, listOf(right, right)).reported)
    }

    @Test
    fun f11TogglesFullScreen() {
        val fullscreen = FakeFullscreen()
        read(1280, 800, listOf(Key.F11), fullscreen)
        // On after F11; closing the reader then left it.
        assertFalse(fullscreen.isOn)
        val twice = FakeFullscreen()
        read(1280, 800, listOf(Key.F11, Key.F11), twice)
        assertFalse(twice.isOn)
    }

    @Test
    fun escLeavesFullScreenBeforeClosingTheBook() {
        val oneEsc = read(1280, 800, listOf(Key.F11, Key.Escape))
        assertFalse(oneEsc.closed)
        val twoEsc = read(1280, 800, listOf(Key.F11, Key.Escape, Key.Escape))
        assertTrue(twoEsc.closed)
    }

    @Test
    fun closingTheReaderLeavesFullScreen() {
        val fullscreen = FakeFullscreen().apply { isOn = true }
        read(1280, 800, emptyList(), fullscreen)
        assertFalse(fullscreen.isOn)
    }
}
