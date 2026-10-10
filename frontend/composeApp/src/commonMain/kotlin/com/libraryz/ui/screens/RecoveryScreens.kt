package com.libraryz.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.libraryz.data.api.ApiClient
import com.libraryz.data.api.ApiException
import com.libraryz.ui.components.Banner
import com.libraryz.ui.components.BannerTone
import kotlinx.coroutines.launch

/** How a recovery request went wrong, from the shared status-code contract. */
internal enum class RecoveryProblem { Throttled, InvalidLink, Network, Other }

internal fun classifyRecoveryError(e: Throwable): RecoveryProblem = when {
    e is ApiException && e.status == 429 -> RecoveryProblem.Throttled
    e is ApiException && (e.status == 410 || e.status == 404) -> RecoveryProblem.InvalidLink
    e is ApiException -> RecoveryProblem.Other
    else -> RecoveryProblem.Network
}

/** Shared frame: a back arrow (optional) over a centered, readable column. */
@Composable
private fun RecoveryFrame(onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to log in")
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    content()
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** The centered "art + headline + body + actions" layout for outcomes. */
@Composable
private fun Outcome(
    icon: ImageVector,
    title: String,
    body: String,
    actions: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.padding(bottom = 8.dp).size(112.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        actions()
    }
}

@Composable
private fun ProblemBanner(problem: RecoveryProblem?, onRetry: () -> Unit) {
    when (problem) {
        RecoveryProblem.Throttled -> Banner(
            BannerTone.Calm,
            Icons.Rounded.HourglassTop,
            title = "Please wait a moment",
            body = "We’ve sent several links recently. Try again in a few minutes.",
        )
        RecoveryProblem.Network -> Banner(
            BannerTone.Plain,
            Icons.Rounded.CloudOff,
            "Can’t reach the library. Check your connection.",
            action = { TextButton(onClick = onRetry) { Text("Retry") } },
        )
        RecoveryProblem.Other, RecoveryProblem.InvalidLink -> Banner(BannerTone.Error, Icons.Rounded.Error, "Something went wrong. Please try again.")
        null -> Unit
    }
}

/**
 * "Reset your password" → "Check your email". The confirmation reads the
 * same whether or not the account exists (the server won't say).
 */
@Composable
fun ForgotPasswordScreen(
    api: ApiClient,
    initialEmail: String,
    onBack: () -> Unit,
    // The code from the email, typed or pasted: how the native apps finish
    // a reset when the emailed link opens the web instead.
    onHaveCode: (String) -> Unit,
) {
    var enteringCode by remember { mutableStateOf(false) }
    if (enteringCode) {
        CodeDialog(
            title = "Enter your reset code",
            onDismiss = { enteringCode = false },
            onSubmit = { enteringCode = false; onHaveCode(it) },
        )
    }
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(initialEmail) }
    var sending by remember { mutableStateOf(false) }
    var sentTo by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<RecoveryProblem?>(null) }

    fun send() {
        val e = email.trim()
        if (e.isEmpty() || sending) return
        sending = true
        problem = null
        scope.launch {
            try {
                api.requestPasswordReset(e)
                sentTo = e
            } catch (t: Throwable) {
                problem = classifyRecoveryError(t)
            } finally {
                sending = false
            }
        }
    }

    RecoveryFrame(onBack = onBack) {
        val to = sentTo
        if (to == null) {
            Text("Reset your password", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Enter the email you signed up with. We’ll send you a link to choose a new password.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProblemBanner(problem, ::send)
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                enabled = !sending,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(onClick = ::send, enabled = email.isNotBlank() && !sending, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                if (sending) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (sending) "Sending…" else "Send reset link", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Outcome(
                icon = Icons.Outlined.MarkEmailRead,
                title = "Check your email",
                body = "If an account uses $to, a reset link is on its way. It works once and only for a limited time.",
            ) {
                ProblemBanner(problem, ::send)
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Back to log in", style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = { enteringCode = true }) { Text("Have a code? Enter it") }
                TextButton(onClick = ::send, enabled = !sending) { Text(if (sending) "Sending…" else "Didn’t get it? Send again") }
            }
        }
    }
}

/**
 * Opened from the emailed reset link. Setting the password signs the
 * account out everywhere, so success leads back to log in.
 */
@Composable
fun ResetPasswordScreen(
    api: ApiClient,
    token: String,
    onDone: () -> Unit,
    onRequestNewLink: () -> Unit,
    onOpenInApp: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<RecoveryProblem?>(null) }
    var policyMessage by remember { mutableStateOf<String?>(null) }
    val longEnough = password.length >= 8

    fun save() {
        if (!longEnough || saving) return
        saving = true
        problem = null
        policyMessage = null
        scope.launch {
            try {
                api.completePasswordReset(token, password)
                onDone()
            } catch (t: Throwable) {
                // A 400 is the password policy talking; the link still works.
                if (t is ApiException && t.status == 400) policyMessage = t.userMessage
                else problem = classifyRecoveryError(t)
            } finally {
                saving = false
            }
        }
    }

    RecoveryFrame(onBack = null) {
        Wordmark()
        if (problem == RecoveryProblem.InvalidLink) {
            Outcome(
                icon = Icons.Outlined.LinkOff,
                title = "This link has expired",
                body = "Reset links work once and only for a limited time. Ask for a new one and use the latest email.",
            ) {
                Button(onClick = onRequestNewLink, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Send a new link", style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = onDone) { Text("Back to log in") }
            }
            return@RecoveryFrame
        }
        Text("Choose a new password", style = MaterialTheme.typography.headlineMedium)
        Text(
            "You’ll be signed out everywhere, then you can log in with the new one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProblemBanner(problem, ::save)
        policyMessage?.let { Banner(BannerTone.Error, Icons.Rounded.Error, it) }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; policyMessage = null },
            label = { Text("New password") },
            singleLine = true,
            enabled = !saving,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { show = !show }) {
                    Icon(
                        if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (show) "Hide password" else "Show password",
                    )
                }
            },
            supportingText = { PasswordHint(password.length) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Button(onClick = ::save, enabled = longEnough && !saving, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            if (saving) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(if (saving) "Saving…" else "Save new password", style = MaterialTheme.typography.titleMedium)
        }
        // Web only: hand the link to the desktop or Android app instead.
        if (onOpenInApp != null) {
            TextButton(onClick = onOpenInApp, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                Text("Open in the LibraryZ app")
            }
        }
    }
}

/**
 * Opened from the emailed verification link: confirms it straight away,
 * then offers the way on. [onResend] (signed in only) asks for a fresh link.
 */
@Composable
fun VerifyEmailScreen(
    api: ApiClient,
    token: String,
    onVerified: suspend () -> Unit,
    onContinue: () -> Unit,
    onResend: (suspend () -> Unit)?,
) {
    var state by remember { mutableStateOf<RecoveryProblem?>(null) }
    var done by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var resent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(token, attempt) {
        state = null
        try {
            api.completeEmailVerification(token)
            onVerified()
            done = true
        } catch (t: Throwable) {
            state = classifyRecoveryError(t)
        }
    }
    RecoveryFrame(onBack = null) {
        Wordmark()
        when {
            done -> Outcome(
                icon = Icons.Outlined.Verified,
                title = "Email verified",
                body = "Thanks. You can always get back into your account with this address.",
            ) {
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Continue", style = MaterialTheme.typography.titleMedium)
                }
            }
            state == RecoveryProblem.InvalidLink -> Outcome(
                icon = Icons.Outlined.LinkOff,
                title = "This link has expired",
                body = if (resent) "A new link is on its way. Use the latest email."
                else "Verification links work once and only for a limited time.",
            ) {
                if (onResend != null && !resent) {
                    Button(
                        onClick = { scope.launch { runCatching { onResend() }.onSuccess { resent = true } } },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text("Send a new link", style = MaterialTheme.typography.titleMedium) }
                }
                TextButton(onClick = onContinue) { Text("Continue") }
            }
            state != null -> Outcome(
                icon = Icons.Rounded.CloudOff,
                title = "Couldn’t verify just now",
                body = "Check your connection and try again.",
            ) {
                Button(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Try again", style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = onContinue) { Text("Continue") }
            }
            else -> Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

/**
 * Atop Browse until the address is confirmed. "Send again" emails a fresh
 * link; [onResend] reports how that went.
 */
@Composable
fun VerifyEmailBanner(
    email: String,
    onResend: () -> Unit,
    onHaveCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var enteringCode by remember { mutableStateOf(false) }
    if (enteringCode) {
        CodeDialog(
            title = "Enter your verification code",
            onDismiss = { enteringCode = false },
            onSubmit = { enteringCode = false; onHaveCode(it) },
        )
    }
    Column(modifier) {
        Banner(
            BannerTone.Info,
            Icons.Rounded.MarkEmailUnread,
            title = "Verify your email",
            body = "We sent a link to $email. Confirm it so you can always get back into your account.",
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { enteringCode = true }) { Text("Have a code?") }
            TextButton(onClick = onResend) { Text("Send again") }
        }
    }
}

/**
 * The code from a recovery email: the token the link carries, printed in
 * the email for apps the link can't open. Pasting a whole link works too.
 */
@Composable
private fun CodeDialog(title: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    val token = codeFromInput(code)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "It’s in the email, under the link. You can also paste the whole link.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Code") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(token) }, enabled = token.isNotEmpty()) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A pasted code, or the token inside a pasted link; trimmed of spaces. */
internal fun codeFromInput(input: String): String {
    val trimmed = input.trim()
    return when (val link = com.libraryz.data.parseDeepLink(trimmed)) {
        is com.libraryz.data.DeepLink.ResetPassword -> link.token
        is com.libraryz.data.DeepLink.VerifyEmail -> link.token
        null -> trimmed.filterNot { it.isWhitespace() }
    }
}
