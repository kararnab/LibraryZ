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

    fun push(s: Screen) { stack.add(s) }
    fun pop(): Boolean {
        if (!canGoBack) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
    fun replace(s: Screen) {
        stack.clear()
        stack.add(s)
    }
}

@Composable
fun rememberNavigator(initial: Screen): Navigator = remember { Navigator(initial) }
