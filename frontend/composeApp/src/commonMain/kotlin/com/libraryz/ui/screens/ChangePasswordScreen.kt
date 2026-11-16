package com.libraryz.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.libraryz.data.api.ApiClient
import com.libraryz.data.api.ApiException
import com.libraryz.data.api.describe
import com.libraryz.data.api.rethrowIfCancelled
import com.libraryz.ui.components.Banner
import com.libraryz.ui.components.BannerTone
import com.libraryz.ui.components.MessageSlot
import kotlinx.coroutines.launch

/** What went wrong with a change, from the server's contract (internal/auth/password_change.go). */
internal sealed interface ChangePasswordProblem {
    /** 403: the current password didn't match. Shown under that field. */
    data object WrongCurrent : ChangePasswordProblem
    /** 400: the new password was refused; [message] is the server's. */
    data class Policy(val message: String) : ChangePasswordProblem
    /** 429: the sign-in budget ran out. */
    data class Throttled(val retryAfterSeconds: Long?) : ChangePasswordProblem
    /** Anything else, already worded for the reader. */
    data class Other(val message: String) : ChangePasswordProblem
}

internal fun classifyChangePasswordError(e: Throwable, describe: (Throwable) -> String): ChangePasswordProblem = when {
    e is ApiException && e.status == 403 -> ChangePasswordProblem.WrongCurrent
    e is ApiException && e.status == 400 -> ChangePasswordProblem.Policy(e.userMessage.replaceFirstChar { it.uppercase() })
    e is ApiException && e.status == 429 -> ChangePasswordProblem.Throttled(e.retryAfterSeconds)
    else -> ChangePasswordProblem.Other(describe(e))
}

/** "in 3 minutes" / "in 40 seconds" / "in a few minutes". */
internal fun retryPhrase(seconds: Long?): String = when {
    seconds == null || seconds <= 0 -> "in a few minutes"
    seconds < 60 -> if (seconds == 1L) "in 1 second" else "in $seconds seconds"
    else -> ((seconds + 59) / 60).let { if (it == 1L) "in 1 minute" else "in $it minutes" }
}

/**
 * Settings › Change password. Checks the current password, keeps this
 * device signed in and signs the others out; [onChanged] goes back and
 * says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePasswordScreen(
    api: ApiClient,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var showCurrent by remember { mutableStateOf(false) }
    var showNext by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<ChangePasswordProblem?>(null) }
    val canSave = current.isNotEmpty() && next.length >= MIN_PASSWORD && !saving

    fun save() {
        if (!canSave) return
        saving = true
        problem = null
        scope.launch {
            try {
                api.changePassword(current, next)
                onChanged()
            } catch (t: Throwable) {
                t.rethrowIfCancelled()
                problem = classifyChangePasswordError(t, api::describe)
            } finally {
                saving = false
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Change password", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "You’ll stay signed in here. Your other devices are signed out and need the new password.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MessageSlot(problem.takeUnless { it is ChangePasswordProblem.WrongCurrent }) { p ->
                    when (p) {
                        is ChangePasswordProblem.Throttled -> Banner(
                            BannerTone.Calm,
                            Icons.Rounded.HourglassTop,
                            title = "Too many attempts",
                            body = "For your security, try again ${retryPhrase(p.retryAfterSeconds)}. Signing in shares this limit.",
                        )
                        is ChangePasswordProblem.Policy -> Banner(BannerTone.Error, Icons.Rounded.Error, p.message)
                        is ChangePasswordProblem.Other -> Banner(BannerTone.Error, Icons.Rounded.Error, p.message)
                        ChangePasswordProblem.WrongCurrent -> Unit
                    }
                }
                PasswordField(
                    value = current,
                    onValueChange = {
                        current = it
                        if (problem == ChangePasswordProblem.WrongCurrent) problem = null
                    },
                    label = "Current password",
                    shown = showCurrent,
                    onToggleShown = { showCurrent = !showCurrent },
                    enabled = !saving,
                    isError = problem == ChangePasswordProblem.WrongCurrent,
                    supportingText = if (problem == ChangePasswordProblem.WrongCurrent) {
                        { Text("That’s not your current password.") }
                    } else null,
                    imeAction = ImeAction.Next,
                    onDone = {},
                )
                PasswordField(
                    value = next,
                    onValueChange = {
                        next = it
                        if (problem is ChangePasswordProblem.Policy) problem = null
                    },
                    label = "New password",
                    shown = showNext,
                    onToggleShown = { showNext = !showNext },
                    enabled = !saving,
                    isError = false,
                    supportingText = { PasswordHint(next.length) },
                    imeAction = ImeAction.Done,
                    onDone = ::save,
                )
                Button(onClick = ::save, enabled = canSave, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    if (saving) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (saving) "Saving…" else "Change password", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    shown: Boolean,
    onToggleShown: () -> Unit,
    enabled: Boolean,
    isError: Boolean,
    supportingText: (@Composable () -> Unit)?,
    imeAction: ImeAction,
    onDone: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = onToggleShown) {
                Icon(
                    if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (shown) "Hide password" else "Show password",
                )
            }
        },
        supportingText = supportingText,
        modifier = Modifier.fillMaxWidth(),
    )
}
