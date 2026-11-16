package com.libraryz

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.libraryz.data.api.ApiClient
import com.libraryz.data.api.AuthState
import com.libraryz.data.api.ContributionsState
import com.libraryz.data.api.FakeTokenStore
import com.libraryz.data.api.LibraryState
import com.libraryz.data.api.RecommendationsState
import com.libraryz.data.api.WorksState
import com.libraryz.data.api.Session
import com.libraryz.nav.Screen
import com.libraryz.theme.LibraryZTheme
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Resizing the window across the wide/narrow breakpoint swaps the rail for
 * the bottom bar (or none). The old and new layouts once composed side by
 * side for a frame, both hosting the screen under one saveable key, and
 * Compose threw "Key … was used multiple times". No server needed: requests
 * to the dead address just fail.
 */
class ResizeAcrossBreakpointTest {
    @Test
    fun resizingAcrossTheBreakpointDoesNotCrash() {
        val failures = mutableListOf<String>()
        for (screen in listOf(Screen.Browse, Screen.Settings, Screen.ChangePassword)) {
            val auth = AuthState(FakeTokenStore())
            runBlocking { auth.signIn(Session("a", "r")) }
            val api = ApiClient("http://127.0.0.1:9", tokenProvider = { auth.token }, sessionHooks = auth)
            var width by mutableStateOf(1200)
            val scene = ImageComposeScene(1281, 796, Density(1f)) {
                LibraryZTheme {
                    val snackbar = remember { SnackbarHostState() }
                    CompositionLocalProvider(LocalSnackbar provides snackbar) {
                        Box(Modifier.width(width.dp).fillMaxHeight()) {
                            Root(api, auth, WorksState(api), ContributionsState(api), LibraryState(api), RecommendationsState(api), startAt = screen)
                        }
                    }
                }
            }
            val result = runCatching {
                var t = 0L
                repeat(5) { scene.render(t); t += 16_000_000 }
                width = 400
                repeat(20) { scene.render(t); t += 16_000_000 }
                width = 1200
                repeat(20) { scene.render(t); t += 16_000_000 }
            }
            result.exceptionOrNull()?.let { failures += "$screen: ${it::class.simpleName}: ${it.message?.take(120)}" }
            scene.close()
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
