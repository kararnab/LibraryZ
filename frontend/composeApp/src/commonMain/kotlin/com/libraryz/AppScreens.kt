package com.libraryz

import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.flow.filterNotNull
import com.libraryz.ui.components.LoadErrorState
import com.libraryz.ui.components.LoadPhase
import com.libraryz.ui.components.PhaseCrossfade
import com.libraryz.ui.screens.BrowseSkeleton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.libraryz.ui.components.ContinueReadingCard
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.libraryz.data.UserBook
import com.libraryz.data.Work
import com.libraryz.data.api.LibraryState
import com.libraryz.data.api.WorksState
import com.libraryz.ui.components.EmptyState
import com.libraryz.ui.screens.BrowseScreen
import kotlinx.coroutines.launch

@Composable
internal fun DataDrivenBrowse(
    works: WorksState,
    onWorkClick: (com.libraryz.data.Work) -> Unit,
    onUploadClick: () -> Unit,
    compact: Boolean,
    header: (@Composable () -> Unit)? = null,
    libraryWorkIds: Set<String> = emptySet(),
    selectedWorkId: String? = null,
    // Books saved on the device: what Browse lists when the catalog can't load.
    onDevice: List<com.libraryz.data.Work> = emptyList(),
) {
    val scope = rememberCoroutineScope()
    val list = works.items
    val err = works.error

    val offline = err != null && list == null && onDevice.isNotEmpty()
    val phase = when {
        works.loading -> LoadPhase.Loading
        err != null && list == null && !offline -> LoadPhase.Error
        else -> LoadPhase.Content
    }
    PhaseCrossfade(phase) { p ->
    when (p) {
        LoadPhase.Loading -> BrowseSkeleton(compact)
        LoadPhase.Error -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            LoadErrorState(
                title = "Couldn’t load the catalog",
                body = err.orEmpty(),
                onRetry = { scope.launch { works.refresh() } },
            )
        }
        else -> if (offline) BrowseScreen(
            works = onDevice,
            onWorkClick = onWorkClick,
            onUploadClick = onUploadClick,
            // The app's offline strip says why; the list is what's on the device.
            header = header,
            onRefresh = { scope.launch { works.refresh() } },
            compact = compact,
            libraryWorkIds = libraryWorkIds,
            selectedWorkId = selectedWorkId,
            listLabel = "On this device",
        ) else BrowseScreen(
            works = list ?: emptyList(),
            onWorkClick = onWorkClick,
            onUploadClick = onUploadClick,
            header = header,
            onRefresh = { scope.launch { works.refresh() } },
            compact = compact,
            libraryWorkIds = libraryWorkIds,
            selectedWorkId = selectedWorkId,
            onSearch = { q -> works.search(q) },
            activeSearchQuery = works.searchQuery,
            onLoadMore = { scope.launch { works.loadMore() } },
            loadingMore = works.loadingMore,
            endReached = works.endReached,
            loadMoreError = works.loadMoreError,
        )
    }
    }
}

@Composable
internal fun NotFound() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(title = "Not found", body = "That work no longer exists.")
    }
}

/** Stacks optional Browse headers (verify-email banner, Continue reading) with a gap. */
internal fun stackHeaders(vararg headers: (@Composable () -> Unit)?): (@Composable () -> Unit)? {
    val present = headers.filterNotNull()
    if (present.isEmpty()) return null
    return {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { present.forEach { it() } }
    }
}

/** The "Continue reading" banner atop Browse, or null when there's nothing to resume. */
internal fun continueReadingHeader(
    library: LibraryState,
    onRead: (UserBook) -> Unit,
): (@Composable () -> Unit)? {
    val entry = library.continueReading ?: return null
    return { ContinueReadingCard(entry = entry, onClick = { onRead(entry) }) }
}
