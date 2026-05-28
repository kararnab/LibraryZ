package com.libraryz.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libraryz.data.PagedReader
import com.libraryz.data.Reader
import com.libraryz.data.TextReader
import com.libraryz.data.api.ApiClient
import com.libraryz.data.openReader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    api: ApiClient,
    editionId: String,
    format: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var bytes by remember(editionId) { mutableStateOf<ByteArray?>(null) }
    var loadError by remember(editionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(editionId) {
        try {
            bytes = api.downloadEdition(editionId)
        } catch (e: Throwable) {
            loadError = e.message ?: "Failed to load edition"
        }
    }

    val payload = bytes
    var reader by remember(editionId) { mutableStateOf<Reader?>(null) }
    LaunchedEffect(payload, format) {
        reader?.close()
        reader = if (payload == null) {
            null
        } else {
            try {
                openReader(payload, format)
            } catch (e: Throwable) {
                loadError = e.message ?: "Failed to open edition"
                null
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { reader?.close() }
    }

    var pageIndex by remember(reader) { mutableStateOf(0) }
    val pageCount = (reader as? PagedReader)?.pageCount ?: 0

    val isText = reader is TextReader
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    val title = when (reader) {
                        is PagedReader -> if (pageCount > 0) "p. ${pageIndex + 1} / $pageCount" else ""
                        is TextReader -> "Text"
                        null -> ""
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    if (reader is PagedReader) {
                        IconButton(
                            onClick = { if (pageIndex > 0) pageIndex-- },
                            enabled = pageIndex > 0,
                        ) { Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous page") }
                        IconButton(
                            onClick = { if (pageIndex < pageCount - 1) pageIndex++ },
                            enabled = pageIndex < pageCount - 1,
                        ) { Icon(Icons.Outlined.ChevronRight, contentDescription = "Next page") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        containerColor = if (isText) MaterialTheme.colorScheme.surface else Color(0xFF3F3F46),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            val r = reader
            when {
                loadError != null -> Text(
                    "Couldn't load: $loadError",
                    color = if (isText) MaterialTheme.colorScheme.onSurface else Color.White,
                )
                r == null -> CircularProgressIndicator(color = if (isText) MaterialTheme.colorScheme.primary else Color.White)
                r is PagedReader -> PagedView(reader = r, pageIndex = pageIndex)
                r is TextReader -> TextView(reader = r)
            }
        }
    }
}

@Composable
private fun PagedView(reader: PagedReader, pageIndex: Int) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        val widthPx = with(LocalDensity.current) {
            maxWidth.roundToPx().coerceAtMost(1600)
        }
        var image by remember(reader, pageIndex, widthPx) {
            mutableStateOf<ImageBitmap?>(null)
        }
        LaunchedEffect(reader, pageIndex, widthPx) {
            image = reader.renderPage(pageIndex, widthPx)
        }
        val current = image
        if (current == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Image(
                bitmap = current,
                contentDescription = "Page ${pageIndex + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFAFAF7)),
            )
        }
    }
}

@Composable
private fun TextView(reader: TextReader) {
    val scroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(scroll)
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Text(
            text = reader.text,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = FontFamily.Serif,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
