package com.libraryz

import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import com.libraryz.nav.Navigator
import com.libraryz.nav.Screen

/** A screen as the window shows it: where it sits in the stack, and at which width. */
internal data class NavEntry(val screen: Screen, val depth: Int, val expanded: Boolean)

/** What surrounds a screen: the phone's navigation bar, the wide window's rail, or nothing. */
internal enum class Chrome { Bar, Rail, None }

internal fun chromeOf(s: Screen, expanded: Boolean): Chrome = when (s) {
    Screen.Browse, Screen.Library, Screen.ForYou, Screen.ContributionQueue, Screen.Settings ->
        if (expanded) Chrome.Rail else Chrome.Bar
    // Wide, a book opens in the catalog's detail pane.
    is Screen.WorkDetail -> if (expanded) Chrome.Rail else Chrome.None
    else -> Chrome.None
}

/** Screens with one key share the window: moving between them animates only what differs. */
internal fun windowKey(s: Screen, expanded: Boolean): Any =
    chromeOf(s, expanded).takeIf { it != Chrome.None } ?: stateKey(s)

/** Screens with one key share one composition inside the bar or rail. */
internal fun paneKey(s: Screen, expanded: Boolean): String =
    if (expanded && s is Screen.WorkDetail) stateKey(Screen.Browse) else stateKey(s)

/** Names a screen's saved state (scroll positions and the like). */
internal fun stateKey(s: Screen): String = when (s) {
    is Screen.WorkDetail -> "work:${s.workId}"
    is Screen.Preview -> "read:${s.editionId}"
    is Screen.EditWork -> "edit:${s.workId}"
    is Screen.Upload -> "upload:${s.workId}"
    is Screen.ForgotPassword -> "forgot"
    is Screen.ResetPassword -> "reset"
    is Screen.VerifyEmail -> "verify"
    else -> s.toString()
}

/** Top-level destinations keep their state when you leave them, like tabs. */
internal val TopLevelKeys = listOf(
    Screen.Auth, Screen.Browse, Screen.Library, Screen.ForYou, Screen.ContributionQueue, Screen.Settings,
).map(::stateKey).toSet()

/** Opens a book from another destination: wide, in the catalog's detail pane. */
internal fun openWork(nav: Navigator, workId: String, expanded: Boolean) {
    if (expanded) nav.replace(Screen.Browse)
    nav.push(Screen.WorkDetail(workId))
}
