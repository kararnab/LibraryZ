package com.libraryz.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

sealed interface Screen {
    data object Auth : Screen
    data object Browse : Screen
    data class WorkDetail(val workId: String) : Screen
    data class Upload(val workId: String? = null) : Screen
    // [workId] ties reading progress to the user's library entry; [title]
    // heads the reader so you can see which book you're in.
    data class Preview(
        val editionId: String,
        val format: String,
        val workId: String,
        val title: String,
        val authors: String? = null,
    ) : Screen
    data class EditWork(val workId: String) : Screen
    data object ContributionQueue : Screen
    data object Library : Screen
    data object ForYou : Screen
    data object Settings : Screen
    // Account recovery. The last two are opened from emailed links and
    // work signed in or out.
    data class ForgotPassword(val email: String = "") : Screen
    data class ResetPassword(val token: String) : Screen
    data class VerifyEmail(val token: String) : Screen
}

@Stable
class Navigator(initial: Screen) {
    private val stack = mutableStateListOf(initial)
    val current: Screen by derivedStateOf { stack.last() }
    val canGoBack: Boolean get() = stack.size > 1
    /** The whole stack, bottom first. */
    val entries: List<Screen> get() = stack

    /**
     * Asked before every [push] / [replace]; false keeps the app where it is
     * (the guard says why, e.g. "Uploading needs a connection").
     */
    var guard: (Screen) -> Boolean = { true }

    fun push(s: Screen) { if (guard(s)) stack.add(s) }
    fun pop(): Boolean {
        if (!canGoBack) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
    fun replace(s: Screen) {
        if (!guard(s)) return
        stack.clear()
        stack.add(s)
    }
}

@Composable
fun rememberNavigator(initial: Screen): Navigator = remember { Navigator(initial) }

/**
 * Why [s] can't open offline, or null if it works offline. Those screens
 * only make sense online: they send something, or show the server's live
 * state.
 */
fun needsConnection(s: Screen): String? = when (s) {
    is Screen.Upload -> "Uploading needs a connection to the library."
    is Screen.EditWork -> "Suggesting an edit needs a connection to the library."
    Screen.ContributionQueue -> "The review queue needs a connection to the library."
    Screen.ForYou -> "For You needs a connection to the library."
    else -> null
}
