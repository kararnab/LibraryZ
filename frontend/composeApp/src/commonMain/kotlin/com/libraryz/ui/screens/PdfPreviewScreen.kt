package com.libraryz.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPreviewScreen(
    editionId: String,
    page: Int = 3,
    totalPages: Int = 247,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "p. $page / $totalPages",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    IconButton(onClick = {}) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        containerColor = Color(0xFF3F3F46),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            FakePdfPage()
        }
    }
}

@Composable
private fun FakePdfPage() {
    // Placeholder page that matches the design wireframe. Real rendering plugs
    // into per-platform engines later (Android PdfRenderer, Desktop PDFBox).
    Box(
        modifier = Modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .fillMaxHeight(0.95f)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFFFAFAF7))
            .padding(horizontal = 28.dp, vertical = 32.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "1.1 The Elements of Programming",
                color = Color(0xFF1A1A1A),
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            // 14 placeholder lines with varying widths matching wireframe.
            val widths = listOf(1.0f, 0.96f, 0.98f, 0.92f, 1.0f, 0.88f, 0.95f,
                1.0f, 0.90f, 0.0f, 1.0f, 0.94f, 0.97f, 0.70f)
            widths.forEach { w ->
                if (w == 0f) {
                    Spacer(Modifier.height(6.dp))
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(w)
                            .height(6.dp)
                            .background(Color(0xFF1A1A1A).copy(alpha = 0.85f)),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    "3",
                    color = Color(0xFF1A1A1A).copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
