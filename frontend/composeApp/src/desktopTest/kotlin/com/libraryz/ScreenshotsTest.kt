package com.libraryz

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.libraryz.data.Edition
import com.libraryz.data.Work
import com.libraryz.data.api.ApiClient
import com.libraryz.data.api.AuthState
import com.libraryz.data.api.ContributionsState
import com.libraryz.data.api.FakeTokenStore
import com.libraryz.data.api.LibraryState
import com.libraryz.data.api.LoginRequest
import com.libraryz.data.api.RecommendationsState
import com.libraryz.data.api.WorksState
import com.libraryz.nav.Screen
import com.libraryz.theme.LibraryZTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test

/**
 * Regenerates the README screenshots (docs/screenshots) from the real UI
 * against a running, seeded backend:
 *
 *   docker compose up -d && ./scripts/seed.sh
 *   LIBRARYZ_SCREENSHOTS_DIR=$PWD/../docs/screenshots \
 *     ./gradlew :composeApp:desktopTest --tests '*ScreenshotsTest*' --rerun
 *
 * Skipped unless LIBRARYZ_SCREENSHOTS_DIR is set, so CI never needs a server.
 * LIBRARYZ_BASE_URL overrides http://localhost:8080.
 */
class ScreenshotsTest {
    private val outDir = System.getenv("LIBRARYZ_SCREENSHOTS_DIR")?.let(::File)
    private val baseUrl = System.getenv("LIBRARYZ_BASE_URL") ?: "http://localhost:8080"

    private class Session(
        val api: ApiClient,
        val auth: AuthState,
        val works: WorksState,
        val contributions: ContributionsState,
        val library: LibraryState,
        val recs: RecommendationsState,
    )

    /** Signs in as a seeded demo user (or stays signed out when [email] is null). */
    private fun session(email: String?): Session = runBlocking {
        val auth = AuthState(FakeTokenStore())
        val api = ApiClient(baseUrl, tokenProvider = { auth.token }, sessionHooks = auth)
        auth.setUserFetcher { runCatching { api.me() }.getOrNull() }
        auth.bootstrap()
        val s = Session(api, auth, WorksState(api), ContributionsState(api), LibraryState(api), RecommendationsState(api))
        if (email != null) {
            auth.signIn(api.login(LoginRequest(email, "libraryz-demo")))
            s.works.refresh()
            s.library.refresh()
            s.recs.refresh()
            if (auth.isModerator) s.contributions.refresh()
        }
        s
    }

    /** Renders [screen] through the app's real root at 1281×796 and saves [name].png. */
    private fun shot(name: String, s: Session, screen: Screen, dark: Boolean = false) {
        val dir = outDir ?: return
        val scene = ImageComposeScene(1281, 796, Density(1f)) {
            LibraryZTheme(darkTheme = dark) {
                val snackbar = remember { SnackbarHostState() }
                CompositionLocalProvider(LocalSnackbar provides snackbar) {
                    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                        Root(s.api, s.auth, s.works, s.contributions, s.library, s.recs, startAt = screen)
                    }
                }
            }
        }
        // Let network loads, PDF rasterization and font loading settle.
        repeat(30) { i ->
            scene.render(i * 100_000_000L)
            runBlocking { delay(150) }
        }
        val img = scene.render(4_000_000_000L)
        File(dir, "$name.png").writeBytes(Image.makeFromBitmap(Bitmap.makeFromImage(img)).encodeToData()!!.bytes)
        scene.close()
        println("wrote $name.png")
        // Kong allows 60 requests a minute per IP and every shot loads a few
        // screens' worth; spacing them out keeps the run under it.
        Thread.sleep(5_000)
    }

    private fun Session.work(title: String): Work =
        works.items.orEmpty().first { it.title.startsWith(title) }.let { w ->
            runBlocking { works.refreshOne(w.id) } ?: w
        }

    private fun Work.edition(format: String): Edition = editions.first { it.format.equals(format, ignoreCase = true) }

    @Test
    fun regenerateReadmeScreenshots() {
        if (outDir == null) {
            println("LIBRARYZ_SCREENSHOTS_DIR not set; skipping screenshot generation")
            return
        }
        outDir.mkdirs()

        val out = session(null)
        shot("login", out, Screen.Auth)

        val reader = session("reader@libraryz.local")
        val ddia = reader.work("Designing Data-Intensive")
        shot("browse", reader, Screen.WorkDetail(ddia.id))
        shot("my_library", reader, Screen.Library)
        shot("for_you", reader, Screen.ForYou)
        shot("settings", reader, Screen.Settings)
        shot("pdf_preview", reader, Screen.Preview(ddia.edition("PDF").id, "PDF", ddia.id, ddia.title, ddia.authors))
        val refactoring = reader.work("Refactoring")
        shot(
            "text_preview", reader,
            Screen.Preview(refactoring.edition("TXT").id, "TXT", refactoring.id, refactoring.title, refactoring.authors),
        )
        shot("upload_screen", reader, Screen.Upload())
        val compilers = reader.work("Compilers")
        shot("browse_dark", reader, Screen.WorkDetail(compilers.id), dark = true)

        // Last for the reader: the search stays in their WorksState.
        val sicp = reader.work("Structure and Interpretation")
        runBlocking { reader.works.search("comp") }
        shot("search", reader, Screen.WorkDetail(sicp.id))

        val mod = session("mod@libraryz.local")
        shot("review", mod, Screen.ContributionQueue)
    }
}
