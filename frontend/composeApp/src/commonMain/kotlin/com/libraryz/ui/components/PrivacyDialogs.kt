package com.libraryz.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Asked once, on first launch. Both choices start off and the two buttons
 * weigh the same; closing the dialog counts as "No thanks". Settings ›
 * Privacy changes it later.
 */
@Composable
fun TelemetryConsentDialog(onDecide: (crashReports: Boolean, usage: Boolean) -> Unit) {
    var crashReports by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDecide(false, false) },
        title = { Text("Help improve LibraryZ?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "You can send crash reports and usage statistics. Both stay off unless you turn them on. " +
                        "They never include your account, what you read or what you search for. " +
                        "You can change this in Settings › Privacy.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ConsentSwitch("Crash reports", CRASH_REPORTS_SUMMARY, crashReports) { crashReports = it }
                ConsentSwitch("Usage statistics", USAGE_SUMMARY, usage) { usage = it }
            }
        },
        confirmButton = { TextButton(onClick = { onDecide(crashReports, usage) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { onDecide(false, false) }) { Text("No thanks") } },
    )
}

@Composable
private fun ConsentSwitch(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Settings › Privacy › "What's sent": exactly what each choice shares. */
@Composable
fun WhatsSentDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What’s sent") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Crash reports", style = MaterialTheme.typography.titleSmall)
                Text(
                    "When the app crashes, or a book fails to open: the kind of error and where in the app’s code " +
                        "it happened, the app version, and the platform (for example Android 16). Never the error’s " +
                        "message, which could name a book or a file.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Usage statistics", style = MaterialTheme.typography.titleSmall)
                Text(
                    "That the app was opened; whether a search found anything (never the search); the format of a " +
                        "book you open (never which book); how an upload ended; reading status changes; For You " +
                        "suggestions opened, saved or dismissed; losing the connection. Each comes with the app " +
                        "version, the platform, and a random install id that’s replaced when you turn this off.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Neither is linked to your account. Kept for 90 days on the LibraryZ server, not shared " +
                        "with anyone else.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

const val CRASH_REPORTS_SUMMARY = "Where in the app’s code a crash happened. Never what you were reading."
const val USAGE_SUMMARY = "Which features get used, for example that a search found nothing. Never the search."
