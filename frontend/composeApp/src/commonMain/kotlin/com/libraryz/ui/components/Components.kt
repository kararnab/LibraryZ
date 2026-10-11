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
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.libraryz.data.api.RemovalKind
import com.libraryz.data.api.RemovalRequest
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import com.libraryz.theme.LocalReduceMotion
import com.libraryz.theme.Motion
import com.libraryz.theme.loadingPulse

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
    // Selection fades in and out rather than snapping between rows.
    val selectedColor = MaterialTheme.colorScheme.secondaryContainer
    val container by animateColorAsState(
        if (selected) selectedColor else selectedColor.copy(alpha = 0f),
        Motion.standard(),
        label = "card container",
    )
    val content by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        Motion.standard(),
        label = "card content",
    )
    val muted by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        Motion.standard(),
        label = "card muted",
    )
    Surface(
        onClick = onClick,
        color = container,
        contentColor = content,
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
            // Pops in when the book is saved from its detail pane alongside.
            val reduce = LocalReduceMotion.current
            AnimatedVisibility(
                visible = inLibrary,
                enter = fadeIn(Motion.standard()) + if (reduce) EnterTransition.None else scaleIn(Motion.standard(Motion.MEDIUM), initialScale = 0.6f),
                exit = fadeOut(Motion.standard()) + if (reduce) ExitTransition.None else scaleOut(Motion.standard(), targetScale = 0.6f),
            ) {
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
    // On this device: [saved] opens offline; [pinned] is "Keep offline";
    // [keeping] while it downloads for that. Null hides the toggle.
    // [onRemoveFromDevice] deletes the saved copy.
    saved: Boolean = false,
    pinned: Boolean = false,
    keeping: Boolean = false,
    onKeepOffline: ((Boolean) -> Unit)? = null,
    onRemoveFromDevice: (() -> Unit)? = null,
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
                    text = when {
                        pinned -> "${edition.prettySize} · Kept offline"
                        saved -> "${edition.prettySize} · On this device"
                        else -> edition.prettySize
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (edition.isPreviewable) {
                TextButton(onClick = onRead) { Text("Read") }
            }
            if (onKeepOffline != null) {
                if (keeping) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else if (!pinned) {
                    IconButton(onClick = { onKeepOffline(true) }) {
                        Icon(
                            Icons.Rounded.DownloadForOffline,
                            contentDescription = "Keep offline",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // Undoing it is a choice, not a toggle: one tap shouldn't
                    // quietly undo what the reader set up for a trip.
                    Box {
                        var pinMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { pinMenu = true }) {
                            Icon(
                                Icons.Rounded.OfflinePin,
                                contentDescription = "Kept offline",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        DropdownMenu(expanded = pinMenu, onDismissRequest = { pinMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Stop keeping offline") },
                                leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null) },
                                onClick = { pinMenu = false; onKeepOffline(false) },
                            )
                            if (onRemoveFromDevice != null) {
                                DropdownMenuItem(
                                    text = { Text("Remove from this device") },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
                                    onClick = { pinMenu = false; onRemoveFromDevice() },
                                )
                            }
                        }
                    }
                }
            }
            // Exporting the file (and the moderator's takedown) are rarer
            // than reading or keeping it offline, so they live in a menu.
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "More for the ${edition.format.uppercase()} edition",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Save a copy to Downloads") },
                        leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                        onClick = { menuOpen = false; onDownload() },
                    )
                    if (saved && onRemoveFromDevice != null) {
                        DropdownMenuItem(
                            text = { Text("Remove from this device") },
                            leadingIcon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
                            onClick = { menuOpen = false; onRemoveFromDevice() },
                        )
                    }
                    if (onRemove != null) {
                        DropdownMenuItem(
                            text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onRemove() },
                        )
                    }
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
            val tint by animateColorAsState(
                if (filled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                Motion.standard(),
                label = "star",
            )
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

/** A placeholder block for loading states. It breathes while it waits. */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(4.dp)) {
    Box(modifier.loadingPulse().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest))
}

/** Where a screen's data is: what [PhaseCrossfade] fades between. */
enum class LoadPhase { Loading, Error, Empty, Content }

/**
 * Fades between a screen's loading placeholder, its error, its empty state
 * and its content, instead of swapping them in a single frame.
 */
@Composable
fun PhaseCrossfade(phase: LoadPhase, modifier: Modifier = Modifier, content: @Composable (LoadPhase) -> Unit) {
    Crossfade(phase, modifier, animationSpec = tween(Motion.MEDIUM, easing = Motion.Standard), label = "load phase") {
        content(it)
    }
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
 * A screen whose content couldn't load: the design's error state (offline
 * cloud, a short title, what to do, "Try again"). [body] comes from
 * [com.libraryz.data.api.describeError], never an exception's own text.
 */
@Composable
fun LoadErrorState(
    title: String,
    body: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        title = title,
        body = body,
        icon = Icons.Outlined.CloudOff,
        action = {
            FilledTonalButton(onClick = onRetry) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Try again")
            }
        },
        modifier = modifier,
    )
}

/**
 * Confirmation for a moderator takedown. A reason is required — it's stored
 * for the audit trail — so Remove stays disabled until one is entered. The
 * moderator also says why: the content (the file stays blocked, the
 * default) or a broken file (it may be uploaded again).
 */
@Composable
fun RemoveDialog(
    title: String,
    body: String,
    onConfirm: (RemovalRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(RemovalKind.Content) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.selectableGroup()) {
                    listOf(
                        Triple(RemovalKind.Content, "Its content", "Rights, spam or abuse. The same file can't be uploaded again."),
                        Triple(RemovalKind.Technical, "A broken file", "It won't open or shows wrongly. The same file can be uploaded again."),
                    ).forEach { (k, label, help) ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = kind == k, onClick = { kind = k }, role = Role.RadioButton)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = kind == k, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                                Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { if (it.length <= 1000) reason = it },
                    label = { Text("Reason") },
                    placeholder = { Text(if (kind == RemovalKind.Content) "e.g. DMCA notice, spam" else "e.g. pages missing, won't open") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(RemovalRequest(reason.trim(), kind)) },
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

/**
 * Room for a status message that comes, goes or changes: it fades, and the
 * space for it opens and closes, so what's below slides instead of jumping.
 * [content] draws [message]; while a message leaves it keeps drawing it.
 */
@Composable
fun <T : Any> MessageSlot(message: T?, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val reduce = LocalReduceMotion.current
    val last = remember { LastMessage<T>() }
    if (message != null) last.value = message
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = Motion.reveal(reduce),
        exit = Motion.conceal(reduce),
    ) {
        val shown = last.value ?: return@AnimatedVisibility
        // One message replacing another crossfades, resizing to fit.
        val size = if (reduce) snap<IntSize>() else Motion.standard(Motion.MEDIUM)
        AnimatedContent(
            targetState = shown,
            transitionSpec = { Motion.crossfade() using SizeTransform(clip = false) { _, _ -> size } },
            label = "message",
        ) { content(it) }
    }
}

/** The message a [MessageSlot] last showed; not state, it only matters while drawing. */
private class LastMessage<T : Any> {
    var value: T? = null
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
