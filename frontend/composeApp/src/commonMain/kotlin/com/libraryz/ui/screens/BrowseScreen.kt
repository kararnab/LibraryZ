package com.libraryz.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.libraryz.data.Work
import com.libraryz.ui.components.EmptyState
import com.libraryz.ui.components.WorkCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    works: List<Work>,
    onWorkClick: (Work) -> Unit,
    onUploadClick: () -> Unit,
    onLogout: () -> Unit,
    onRefresh: () -> Unit,
    showRefresh: Boolean,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("LibraryZ") },
                actions = {
                    if (showRefresh) {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                        }
                    }
                    IconButton(onClick = onUploadClick) {
                        Icon(Icons.Outlined.FileUpload, contentDescription = "Upload")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Log out") },
                            onClick = { menuOpen = false; onLogout() },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            if (works.isEmpty()) {
                FloatingActionButton(
                    onClick = onUploadClick,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add work")
                }
            } else {
                ExtendedFloatingActionButton(
                    onClick = onUploadClick,
                    text = { Text("Add work") },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        if (works.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(title = "No works yet", body = "Tap + to add one")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 88.dp,
                ),
            ) {
                items(works, key = { it.id }) { w ->
                    WorkCard(work = w, onClick = { onWorkClick(w) })
                }
            }
        }
    }
}
