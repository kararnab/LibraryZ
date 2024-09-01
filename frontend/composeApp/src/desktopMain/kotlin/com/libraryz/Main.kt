package com.libraryz

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "LibraryZ",
        state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        App()
    }
}
