package com.libraryz.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.libraryz.data.Edition
import com.libraryz.data.Work
import com.libraryz.data.isPreviewable
import com.libraryz.data.prettySize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkCard(
    work: Work,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: String? = null,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 56.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Book,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    val highlightTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                    Text(
                        text = highlightMatches(work.title, highlight, highlightTint),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!work.authors.isNullOrBlank()) {
                        Text(
                            text = work.authors,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val count = work.editions.size
                    EditionCountChip(count)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/**
 * Build an AnnotatedString that tints every (case-insensitive) occurrence
 * of [query] inside [text] with [tint] as a background. Returns [text]
 * unchanged when [query] is null/blank — typical no-search-active state.
 */
private fun highlightMatches(text: String, query: String?, tint: androidx.compose.ui.graphics.Color): AnnotatedString {
    if (query.isNullOrBlank()) return AnnotatedString(text)
    val q = query.trim()
    return buildAnnotatedString {
        val lower = text.lowercase()
        val needle = q.lowercase()
        var idx = 0
        while (idx < text.length) {
            val match = lower.indexOf(needle, idx)
            if (match < 0) {
                append(text.substring(idx))
                break
            }
            append(text.substring(idx, match))
            withStyle(SpanStyle(background = tint)) {
                append(text.substring(match, match + needle.length))
            }
            idx = match + needle.length
        }
    }
}

@Composable
fun EditionCountChip(count: Int) {
    val label = if (count == 1) "1 EDITION" else "$count EDITIONS"
    Box(
        modifier = Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
fun EditionRow(
    edition: Edition,
    onDownload: () -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormatBadge(edition.format)
            Text(
                text = edition.prettySize,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (edition.isPreviewable) {
                TextButton(onClick = onPreview) { Text("Preview") }
            }
            TextButton(onClick = onDownload) { Text("Download") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun FormatBadge(format: String) {
    Box(
        modifier = Modifier
            .height(28.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = format.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
    icon: ImageVector = Icons.Outlined.Book,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}

