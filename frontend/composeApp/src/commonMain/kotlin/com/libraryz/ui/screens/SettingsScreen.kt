package com.libraryz.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libraryz.AppVersion
import com.libraryz.data.BookCache
import com.libraryz.data.User
import com.libraryz.data.formatBytes
import com.libraryz.data.telemetry.TelemetryConsent
import com.libraryz.ui.components.CRASH_REPORTS_SUMMARY
import com.libraryz.ui.components.USAGE_SUMMARY
import com.libraryz.ui.components.WhatsSentDialog
import kotlinx.coroutines.launch
import com.libraryz.theme.LibraryZ
import com.libraryz.ui.components.SectionLabel
import com.libraryz.ui.components.ServerAddressDialog
import com.libraryz.ui.components.rememberMultiTap

/**
 * Account + app settings: who you're signed in as, reader text size,
 * signing out (this device or everywhere), and which server the app talks
 * to. The one home for account actions, so they aren't scattered across
 * per-screen overflow menus. Where the platform keeps books, also how much
 * room [books] may take. Privacy holds the crash-report and usage-statistics
 * choices first asked at launch ([telemetry]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    user: User?,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    prefs: ReaderPrefs,
    onPrefsChange: (ReaderPrefs) -> Unit,
    onSignOut: () -> Unit,
    onSignOutEverywhere: () -> Unit,
    books: BookCache,
    telemetry: TelemetryConsent,
    onTelemetryChange: (crashReports: Boolean, usage: Boolean) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var confirmEverywhere by remember { mutableStateOf(false) }
    var showWhatsSent by remember { mutableStateOf(false) }
    var editServer by remember { mutableStateOf(false) }
    var pickLimit by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(top = 4.dp, bottom = 24.dp)) {
                AccountCard(user, Modifier.padding(horizontal = 16.dp))

                SectionLabel("Reading", Modifier.padding(top = 4.dp))
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextSizeControl(prefs.textSize, onChange = { onPrefsChange(prefs.copy(textSize = it)) })
                    Text(
                        "Miss Brooke had that kind of beauty which seems to be thrown into relief by poor dress.",
                        fontFamily = LibraryZ.tokens.serif,
                        fontSize = prefs.textSize.sp,
                        lineHeight = (prefs.textSize * prefs.spacing.factor).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                }

                if (books.enabled) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SectionLabel("Books on this device")
                    val count = books.books.size
                    SettingsRow(
                        icon = Icons.Rounded.OfflinePin,
                        title = "Space for books",
                        subtitle = "${if (count == 1) "1 book" else "$count books"} · ${formatBytes(books.usedBytes)} of ${limitLabel(books.limitBytes)}. " +
                            "Books you open are kept here; the least recently read go first, except those you keep offline.",
                        onClick = { pickLimit = true },
                    )
                    if (count > 0) {
                        SettingsRow(
                            icon = Icons.Rounded.DeleteSweep,
                            title = "Remove books from this device",
                            subtitle = "They download again when you open them",
                            onClick = { confirmClear = true },
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SectionLabel("Sessions")
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.Logout,
                    title = "Sign out",
                    subtitle = "On this device",
                    onClick = onSignOut,
                )
                SettingsRow(
                    icon = Icons.Rounded.DevicesOther,
                    title = "Sign out everywhere",
                    subtitle = "Ends every session, on all devices",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = { confirmEverywhere = true },
                )

                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SectionLabel("Privacy")
                SettingsSwitchRow(
                    icon = Icons.Rounded.BugReport,
                    title = "Send crash reports",
                    subtitle = CRASH_REPORTS_SUMMARY,
                    checked = telemetry.crashReports,
                    onCheckedChange = { onTelemetryChange(it, telemetry.usage) },
                )
                SettingsSwitchRow(
                    icon = Icons.Rounded.Insights,
                    title = "Share usage statistics",
                    subtitle = USAGE_SUMMARY,
                    checked = telemetry.usage,
                    onCheckedChange = { onTelemetryChange(telemetry.crashReports, it) },
                )
                SettingsRow(
                    icon = Icons.Rounded.PrivacyTip,
                    title = "What’s sent",
                    subtitle = "Exactly what each one shares, and for how long it’s kept",
                    onClick = { showWhatsSent = true },
                )

                HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SectionLabel("About", Modifier.padding(top = 0.dp))
                SettingsRow(icon = Icons.Rounded.Dns, title = "Server", subtitle = serverUrl, onClick = rememberMultiTap(taps = 3) { editServer = true })
                SettingsRow(icon = Icons.Rounded.Info, title = "Version", subtitle = AppVersion, onClick = null)
            }
        }
    }

    if (showWhatsSent) {
        WhatsSentDialog(onDismiss = { showWhatsSent = false })
    }

    if (editServer) {
        ServerAddressDialog(
            current = serverUrl,
            signedIn = user != null,
            onSave = { editServer = false; onServerUrlChange(it) },
            onDismiss = { editServer = false },
        )
    }

    if (pickLimit) {
        AlertDialog(
            onDismissRequest = { pickLimit = false },
            title = { Text("Space for books") },
            text = {
                Column {
                    BookCache.LIMITS.forEach { limit ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                pickLimit = false
                                scope.launch { books.setLimit(limit) }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = books.limitBytes == limit, onClick = null)
                            Text(limitLabel(limit), modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickLimit = false }) { Text("Done") } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Remove books from this device?") },
            text = { Text("Books you keep offline are removed too. Your library and reading progress are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { books.clear() }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmEverywhere) {
        AlertDialog(
            onDismissRequest = { confirmEverywhere = false },
            title = { Text("Sign out everywhere?") },
            text = { Text("You'll need to sign in again on every device. Your library and reading progress are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEverywhere = false
                    onSignOutEverywhere()
                }) { Text("Sign out everywhere", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEverywhere = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
    tint: Color = Color.Unspecified,
) {
    val content = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    val muted = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint.copy(alpha = 0.85f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = muted)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = content)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun AccountCard(user: User?, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = user?.name?.trim()?.firstOrNull()?.uppercase() ?: "?",
                    fontFamily = LibraryZ.tokens.serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = user?.name?.ifBlank { null } ?: "Signed in",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (user != null) {
                    Text(
                        text = user.email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (user?.isModerator == true) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = CircleShape,
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Rounded.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Moderator", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun limitLabel(bytes: Long): String = when {
    bytes == BookCache.NO_LIMIT -> "No limit"
    bytes % (1024 * BookCache.MB) == 0L -> "${bytes / (1024 * BookCache.MB)} GB"
    bytes % BookCache.MB == 0L -> "${bytes / BookCache.MB} MB"
    else -> formatBytes(bytes)
}
