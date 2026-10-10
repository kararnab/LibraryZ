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
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libraryz.data.User
import com.libraryz.theme.LibraryZ
import com.libraryz.ui.components.SectionLabel

/**
 * Account + app settings: who you're signed in as, reader text size,
 * signing out (this device or everywhere), and which server the app talks
 * to. The one home for account actions, so they aren't scattered across
 * per-screen overflow menus.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    user: User?,
    serverUrl: String,
    prefs: ReaderPrefs,
    onPrefsChange: (ReaderPrefs) -> Unit,
    onSignOut: () -> Unit,
    onSignOutEverywhere: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var confirmEverywhere by remember { mutableStateOf(false) }
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

                HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SectionLabel("About", Modifier.padding(top = 0.dp))
                SettingsRow(icon = Icons.Rounded.Dns, title = "Server", subtitle = serverUrl, onClick = null)
            }
        }
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
