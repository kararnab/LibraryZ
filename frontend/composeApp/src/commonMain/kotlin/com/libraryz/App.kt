package com.libraryz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.libraryz.data.MockData
import com.libraryz.nav.Navigator
import com.libraryz.nav.Screen
import com.libraryz.nav.rememberNavigator
import com.libraryz.theme.LibraryZTheme
import com.libraryz.ui.components.EmptyState
import com.libraryz.ui.screens.AuthGateScreen
import com.libraryz.ui.screens.BrowseScreen
import com.libraryz.ui.screens.PdfPreviewScreen
import com.libraryz.ui.screens.UploadMode
import com.libraryz.ui.screens.UploadSheet
import com.libraryz.ui.screens.WorkDetailScreen
import kotlinx.coroutines.launch

// Lets any composable show the standard "Not yet implemented…" message
// without threading SnackbarHostState everywhere.
val LocalSnackbar = compositionLocalOf<SnackbarHostState> {
    error("No SnackbarHost in scope")
}

// Width threshold for the M3 "expanded" window size class. Below this we
// behave like a phone (single pane); above, we run a list-detail layout
// with a navigation rail.
private const val EXPANDED_DP = 840

@Composable
fun App() {
    LibraryZTheme {
        val snackbar = remember { SnackbarHostState() }
        CompositionLocalProvider(LocalSnackbar provides snackbar) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                containerColor = MaterialTheme.colorScheme.surface,
            ) { padding ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxSize().padding(padding),
                ) {
                    Root()
                }
            }
        }
    }
}

@Composable
private fun Root() {
    val nav = rememberNavigator(Screen.Auth)
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val expanded = maxWidth.value >= EXPANDED_DP

        when (val s = nav.current) {
            Screen.Auth -> AuthGateScreen(
                onAuthenticated = { nav.replace(Screen.Browse) },
            )

            Screen.Browse -> {
                if (expanded) {
                    ExpandedFrame(nav = nav) {
                        ListDetailLayout(
                            nav = nav,
                            selectedWorkId = null,
                            onSelect = { w -> nav.push(Screen.WorkDetail(w.id)) },
                        )
                    }
                } else {
                    BrowseScreen(
                        works = MockData.works,
                        onWorkClick = { nav.push(Screen.WorkDetail(it.id)) },
                        onUploadClick = { nav.push(Screen.Upload()) },
                        onLogout = { nav.replace(Screen.Auth) },
                        onRefresh = {},
                        showRefresh = false,
                    )
                }
            }

            is Screen.WorkDetail -> {
                val work = MockData.findWork(s.workId)
                if (work == null) {
                    NotFound(onBack = { nav.pop() })
                } else if (expanded) {
                    ExpandedFrame(nav = nav) {
                        ListDetailLayout(
                            nav = nav,
                            selectedWorkId = s.workId,
                            onSelect = { w -> nav.replace(Screen.Browse); nav.push(Screen.WorkDetail(w.id)) },
                        )
                    }
                } else {
                    WorkDetailScreen(
                        work = work,
                        onBack = { nav.pop() },
                        onAddEdition = { nav.push(Screen.Upload(workId = s.workId)) },
                        onPreview = { ed ->
                            if (ed.format.equals("pdf", ignoreCase = true)) {
                                nav.push(Screen.PdfPreview(ed.id))
                            } else {
                                scope.launch { snackbar.showSnackbar("Preview only available for PDF.") }
                            }
                        },
                        onDownload = { _ ->
                            scope.launch { snackbar.showSnackbar("Not yet implemented on this platform.") }
                        },
                    )
                }
            }

            is Screen.Upload -> {
                // Render the screen behind it so the sheet has context.
                if (expanded) {
                    ExpandedFrame(nav = nav) {
                        ListDetailLayout(
                            nav = nav,
                            selectedWorkId = s.workId,
                            onSelect = { w -> nav.replace(Screen.Browse); nav.push(Screen.WorkDetail(w.id)) },
                        )
                    }
                } else {
                    BrowseScreen(
                        works = MockData.works,
                        onWorkClick = {},
                        onUploadClick = {},
                        onLogout = {},
                        onRefresh = {},
                        showRefresh = false,
                    )
                }
                UploadSheet(
                    mode = if (s.workId == null) UploadMode.NewWork else UploadMode.AddEdition,
                    isDesktop = expanded,
                    onDismiss = { nav.pop() },
                    onUpload = {
                        scope.launch { snackbar.showSnackbar("Not yet implemented on this platform.") }
                    },
                    onChooseFile = {
                        scope.launch { snackbar.showSnackbar("File picker not yet implemented on this platform.") }
                    },
                )
            }

            is Screen.PdfPreview -> PdfPreviewScreen(
                editionId = s.editionId,
                onClose = { nav.pop() },
            )
        }
    }
}

@Composable
private fun NotFound(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(title = "Not found", body = "That work no longer exists.")
        // Single tap anywhere goes back is overkill; rely on system back.
        // Kept onBack param for completeness.
        @Suppress("UNUSED_EXPRESSION") onBack
    }
}

/* ---------------- Expanded (Desktop / wide tablet) ---------------- */

@Composable
private fun ExpandedFrame(
    nav: Navigator,
    content: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        NavRail(nav = nav)
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun NavRail(nav: Navigator) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxHeight(),
        header = {
            Box(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Book,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    ) {
        Spacer(Modifier.size(12.dp))
        NavigationRailItem(
            selected = true,
            onClick = { nav.replace(Screen.Browse) },
            icon = { Icon(Icons.Outlined.Book, contentDescription = null) },
            label = { Text("Library") },
        )
        NavigationRailItem(
            selected = false,
            onClick = { nav.push(Screen.Upload()) },
            icon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
            label = { Text("Upload") },
        )
    }
}

@Composable
private fun ListDetailLayout(
    nav: Navigator,
    selectedWorkId: String?,
    onSelect: (com.libraryz.data.Work) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current

    Row(modifier = Modifier.fillMaxSize()) {
        // List pane
        Box(modifier = Modifier.width(420.dp).fillMaxHeight()) {
            BrowseScreen(
                works = MockData.works,
                onWorkClick = onSelect,
                onUploadClick = { nav.push(Screen.Upload()) },
                onLogout = { nav.replace(Screen.Auth) },
                onRefresh = {},
                showRefresh = true,
            )
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // Detail pane
        Box(modifier = Modifier.fillMaxSize()) {
            val work = selectedWorkId?.let { MockData.findWork(it) }
            if (work == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = "Select a work",
                        body = "Pick one from the list to see editions.",
                    )
                }
            } else {
                WorkDetailScreen(
                    work = work,
                    onBack = null,
                    onAddEdition = { nav.push(Screen.Upload(workId = work.id)) },
                    onPreview = { ed ->
                        if (ed.format.equals("pdf", ignoreCase = true)) {
                            nav.push(Screen.PdfPreview(ed.id))
                        } else {
                            scope.launch { snackbar.showSnackbar("Preview only available for PDF.") }
                        }
                    },
                    onDownload = {
                        scope.launch { snackbar.showSnackbar("Not yet implemented on this platform.") }
                    },
                )
            }
        }
    }
}
