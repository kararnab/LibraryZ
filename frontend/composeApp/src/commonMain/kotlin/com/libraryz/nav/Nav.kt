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
    data class Preview(val editionId: String, val format: String) : Screen
    data class EditWork(val workId: String) : Screen
    data object ContributionQueue : Screen
    data object Library : Screen
    data object ForYou : Screen
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
