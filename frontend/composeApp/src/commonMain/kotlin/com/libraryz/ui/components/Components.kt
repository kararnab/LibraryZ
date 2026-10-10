package com.libraryz.ui.components

import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.libraryz.data.Edition
import com.libraryz.data.UserBook
import com.libraryz.data.Work
import com.libraryz.data.authorsShort
import com.libraryz.data.isPreviewable
import com.libraryz.data.prettySize
import com.libraryz.theme.LibraryZ

/** Human name for an edition format. */
fun formatName(format: String): String = when (format.uppercase()) {
    "TXT" -> "Plain text"
    else -> format.uppercase()
}

/** "PDF · Plain text" for a work's editions, in a stable order. */
fun formatsLabel(editions: List<Edition>): String =
    editions.map { it.format.uppercase() }.distinct().sorted().joinToString(" · ") { formatName(it) }

/** "George Eliot · 1871" or "Aho et al. · 2006", skipping whichever part is missing. */
fun bylineOf(work: Work): String =
    listOfNotNull(authorsShort(work.authors).takeIf { it.isNotEmpty() }, work.publicationYear?.toString()).joinToString(" · ")

/**
 * A catalog row: monogram cover, title, byline and formats, with a
 * bookmark when the book is already in your library. [selected] marks the
 * open row in the desktop list/detail layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkCard(
    work: Work,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: String? = null,
    inLibrary: Boolean = false,
    selected: Boolean = false,
) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(work.title, work.authors, CoverSize.S)
            val muted = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = highlightMatches(work.title, highlight, MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)),
                    style = LibraryZ.tokens.bookTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val byline = bylineOf(work)
                if (byline.isNotEmpty()) {
                    Text(
                        text = highlightMatches(byline, highlight, MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (work.editions.isNotEmpty()) {
                    Text(formatsLabel(work.editions), style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
            if (inLibrary) {
                Icon(
                    Icons.Rounded.Bookmark,
                    contentDescription = "In your library",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Build an AnnotatedString that tints every (case-insensitive) occurrence
 * of [query] inside [text] with [tint] as a background. Returns [text]
 * unchanged when [query] is null/blank — typical no-search-active state.
 */
private fun highlightMatches(text: String, query: String?, tint: Color): AnnotatedString {
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

/** Section heading inside a list ("Catalog", "Reading", …). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun EditionRow(
    edition: Edition,
    onDownload: () -> Unit,
    onRead: () -> Unit,
    modifier: Modifier = Modifier,
    // Moderator-only takedown; hidden when null.
    onRemove: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormatBadge(edition.format)
            Column(Modifier.weight(1f)) {
                Text(formatName(edition.format), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = edition.prettySize,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (edition.isPreviewable) {
                TextButton(onClick = onRead) { Text("Read") }
            }
            IconButton(onClick = onDownload) {
                Icon(
                    Icons.Rounded.Download,
                    contentDescription = "Download ${edition.format.uppercase()} edition",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onRemove != null) {
                TextButton(onClick = onRemove) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** PDF in slate blue, text in brass, anything else neutral. */
@Composable
fun FormatBadge(format: String) {
    val (bg, fg) = when (format.uppercase()) {
        "PDF" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        "TXT" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .height(22.dp)
            .widthIn(min = 40.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(bg)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = format.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

/** Filled brass stars up to [rating]; tappable when [onRate] is set. */
@Composable
fun StarRating(
    rating: Int?,
    onRate: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
    starSize: Dp = 24.dp,
) {
    Row(modifier) {
        for (star in 1..5) {
            val filled = (rating ?: 0) >= star
            val icon = if (filled) Icons.Rounded.Star else Icons.Rounded.StarOutline
            val tint = if (filled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
            if (onRate != null) {
                IconButton(onClick = { onRate(star) }, modifier = Modifier.size(starSize + 16.dp)) {
                    Icon(icon, contentDescription = "$star star${if (star == 1) "" else "s"}", tint = tint, modifier = Modifier.size(starSize))
                }
            } else {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(starSize))
            }
        }
    }
}

/** A placeholder block for loading states. */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(4.dp)) {
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest))
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
    icon: ImageVector = Icons.AutoMirrored.Outlined.LibraryBooks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 40.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 8.dp)
                .size(112.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}

/**
 * Confirmation for a moderator takedown. A reason is required — it's stored
 * for the audit trail — so Remove stays disabled until one is entered.
 */
@Composable
fun RemoveDialog(
    title: String,
    body: String,
    onConfirm: (reason: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = reason,
                    onValueChange = { if (it.length <= 1000) reason = it },
                    label = { Text("Reason") },
                    placeholder = { Text("e.g. DMCA notice, spam, broken file") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(reason.trim()) },
                enabled = reason.isNotBlank(),
            ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * One-tap resume for the book you're in the middle of: cover, title,
 * author and progress on the primary container. Sits atop Browse so the
 * most common reason to open the app is the first thing on screen.
 */
@Composable
fun ContinueReadingCard(entry: UserBook, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val title = entry.work?.title ?: "Your book"
    val authors = authorsShort(entry.work?.authors)
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BookCover(title, entry.work?.authors, CoverSize.M)
            Column(Modifier.weight(1f).fillMaxHeight().heightIn(min = CoverSize.M.height)) {
                Text(
                    "CONTINUE READING",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.alpha(0.8f),
                )
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!authors.isNullOrBlank()) {
                    Text(
                        authors,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alpha(0.8f),
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(
                        progress = { entry.progressPercent / 100f },
                        trackColor = MaterialTheme.colorScheme.surface,
                        drawStopIndicator = {},
                        modifier = Modifier.weight(1f),
                    )
                    Text("${entry.progressPercent}%", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Tones for [Banner], from the design's inline status messages. */
enum class BannerTone { Info, Calm, Error, Plain }

/**
 * An inline status message: icon, optional bold [title], [body], and an
 * optional trailing [action] (usually a TextButton).
 */
@Composable
fun Banner(
    tone: BannerTone,
    icon: ImageVector,
    body: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val (bg, fg) = when (tone) {
        BannerTone.Info -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        BannerTone.Calm -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        BannerTone.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        BannerTone.Plain -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
    }
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.labelLarge)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            if (action != null) Box(Modifier.align(Alignment.CenterVertically)) { action() }
        }
    }
}
