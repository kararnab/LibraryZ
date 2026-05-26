package com.libraryz.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.libraryz.data.Contribution
import com.libraryz.data.api.ContributionsState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val EXPANDED_GRID_DP = 1240

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContributionQueueScreen(
    state: ContributionsState,
    isWide: Boolean,
    onBack: (() -> Unit)?,
    onOpenWork: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var pendingApprove by remember { mutableStateOf<Contribution?>(null) }
    var pendingReject by remember { mutableStateOf<Contribution?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Review queue", color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.size(12.dp))
                        PendingCountChip(count = state.items?.size ?: 0)
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { state.refresh() } }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val items = state.items
            val err = state.error
            when {
                state.loading -> LoadingState()
                err != null && items == null -> ErrorState(
                    message = err,
                    onRetry = { scope.launch { state.refresh() } },
                )
                items.isNullOrEmpty() -> EmptyState()
                else -> CardList(
                    contributions = items,
                    isWide = isWide,
                    onOpenWork = onOpenWork,
                    onApproveClicked = { pendingApprove = it },
                    onRejectClicked = { pendingReject = it },
                )
            }

            if (actionError != null) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            actionError!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }

    pendingApprove?.let { c ->
        ConfirmDialog(
            title = "Approve this edit?",
            body = "${changedFieldCount(c)} field${if (changedFieldCount(c) == 1) "" else "s"} on " +
                "\"${c.patch.keys.firstOrNull() ?: "this work"}\" will be applied. This can't be undone.",
            primaryLabel = "Approve",
            onConfirm = {
                pendingApprove = null
                scope.launch {
                    try {
                        state.approve(c.id)
                    } catch (e: Throwable) {
                        actionError = e.message ?: "Approve failed"
                    }
                }
            },
            onDismiss = { pendingApprove = null },
        )
    }
    pendingReject?.let { c ->
        ConfirmDialog(
            title = "Reject this edit?",
            body = "The contribution will be marked rejected and the work won't change.",
            primaryLabel = "Reject",
            destructive = true,
            onConfirm = {
                pendingReject = null
                scope.launch {
                    try {
                        state.reject(c.id)
                    } catch (e: Throwable) {
                        actionError = e.message ?: "Reject failed"
                    }
                }
            },
            onDismiss = { pendingReject = null },
        )
    }
}

@Composable
private fun PendingCountChip(count: Int) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = "$count pending",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun CardList(
    contributions: List<Contribution>,
    isWide: Boolean,
    onOpenWork: (String) -> Unit,
    onApproveClicked: (Contribution) -> Unit,
    onRejectClicked: (Contribution) -> Unit,
) {
    if (isWide) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(contributions, key = { it.id }) { c ->
                ContribCard(c, onOpenWork = onOpenWork,
                    onApprove = { onApproveClicked(c) }, onReject = { onRejectClicked(c) })
            }
        }
    } else {
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(contributions, key = { it.id }) { c ->
                ContribCard(c, onOpenWork = onOpenWork,
                    onApprove = { onApproveClicked(c) }, onReject = { onRejectClicked(c) },
                    dense = true)
            }
        }
    }
}

@Composable
private fun ContribCard(
    c: Contribution,
    onOpenWork: (String) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    dense: Boolean = false,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (dense) 14.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header: work title (tappable) + contributor row
            Column {
                Surface(
                    onClick = { onOpenWork(c.workId) },
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = workIdToTitle(c) ?: "Edit to work ${c.workId.take(8)}…",
                        style = if (dense) MaterialTheme.typography.titleSmall
                                else MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Avatar(name = c.contributorName ?: "?")
                    Text(
                        text = "${c.contributorName ?: "user #${c.contributorId}"} · ${shortenTime(c.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Divider line above the diff
            Surface(color = MaterialTheme.colorScheme.outlineVariant) {
                Box(modifier = Modifier.fillMaxWidth().height(1.dp))
            }

            Column { c.patch.forEach { (field, newValue) -> DiffLine(field, c.current[field], newValue) } }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onReject,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Reject") }
                Button(onClick = onApprove) { Text("Approve") }
            }
        }
    }
}

@Composable
private fun Avatar(name: String) {
    val initials = name.trim().take(2).uppercase()
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials.ifEmpty { "?" },
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiffLine(field: String, oldValue: JsonElement?, newValue: JsonElement) {
    val mono = field == "isbn" || field == "openlibrary_id"
    val oldText = displayOld(field, oldValue)
    val newText = displayNew(newValue)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = field,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        // old (strikethrough) → new (tinted chip). FlowRow so long values
        // wrap rather than overflow the card.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = oldText,
                style = MaterialTheme.typography.bodySmall.copy(
                    textDecoration = TextDecoration.LineThrough,
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "→",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = newText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** Old (current) value for the strikethrough side; "(empty)" when unset. */
private fun displayOld(field: String, v: JsonElement?): String {
    val s = when (v) {
        null, is JsonNull -> ""
        is JsonPrimitive -> v.contentOrNull ?: v.toString()
        else -> v.toString()
    }
    // publication_year comes back as 0 when unset — treat as empty.
    if (field == "publication_year" && (s.isEmpty() || s == "0")) return "(empty)"
    return s.ifBlank { "(empty)" }
}

/** New (proposed) value for the chip; explicit clears render as "(cleared)". */
private fun displayNew(v: JsonElement): String = when (v) {
    is JsonNull -> "(cleared)"
    is JsonPrimitive -> v.contentOrNull ?: v.toString()
    else -> v.toString()
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(3) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.fillMaxWidth().height(140.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "All caught up",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "No pending contributions. New edit suggestions will appear here as readers submit them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("Couldn't load queue",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    primaryLabel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            if (destructive) {
                TextButton(
                    onClick = onConfirm,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(primaryLabel) }
            } else {
                Button(onClick = onConfirm) { Text(primaryLabel) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

private fun changedFieldCount(c: Contribution): Int = c.patch.size

// The backend doesn't yet include the target Work title alongside the
// contribution payload, only work_id. Returning null falls back to a
// "Edit to work <prefix>…" display in the card header. Adding the title
// is a small backend follow-up (join works on work_id) — for now this
// approximation keeps the UI honest about what's available.
private fun workIdToTitle(@Suppress("UNUSED_PARAMETER") c: Contribution): String? = null

// Best-effort relative-ish timestamp: take the date+time portion of the
// ISO string ("2026-05-27T09:30:00Z" → "2026-05-27 09:30"). Real
// "12 min ago" formatting would need kotlinx-datetime; defer to v0.5.
private fun shortenTime(iso: String): String {
    if (iso.length < 16) return iso
    return iso.substring(0, 10) + " " + iso.substring(11, 16)
}
