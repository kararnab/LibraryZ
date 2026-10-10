package com.libraryz.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.libraryz.data.api.ApiClient
import com.libraryz.data.api.ApiException
import com.libraryz.data.api.LoginRequest
import com.libraryz.data.api.Session
import com.libraryz.data.api.SignUpRequest
import com.libraryz.ui.components.Banner
import com.libraryz.ui.components.BannerTone
import com.libraryz.ui.components.BookCover
import com.libraryz.ui.components.CoverSize
import kotlinx.coroutines.launch

private const val WIDE_DP = 840
private const val MIN_PASSWORD = 8

/** What went wrong with the last attempt, each with its own banner. */
private sealed interface AuthProblem {
    data object WrongCredentials : AuthProblem
    data object Throttled : AuthProblem
    data object Network : AuthProblem
    /** A 4xx whose text is meant for people (e.g. "email already registered"). */
    data class Server(val message: String) : AuthProblem
    data object MissingFields : AuthProblem
}

/**
 * Sign in / create account. A bookish welcome (generated covers on a
 * shelf) above the form on phones, beside it on wide screens. [sessionExpired]
 * shows the "please sign in again" banner after an expired session sends
 * the user back here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthGateScreen(
    api: ApiClient,
    onAuthenticated: suspend (Session) -> Unit,
    modifier: Modifier = Modifier,
    sessionExpired: Boolean = false,
    // After a password reset: "Password changed. Log in with your new one."
    passwordChanged: Boolean = false,
    onForgotPassword: ((email: String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<AuthProblem?>(null) }
    var inFlight by remember { mutableStateOf(false) }
    val signUp = tab == 1

    fun submit() {
        if (inFlight) return
        val e = email.trim()
        val n = name.trim()
        if (e.isEmpty() || password.isEmpty() || (signUp && n.isEmpty())) {
            problem = AuthProblem.MissingFields
            return
        }
        problem = null
        inFlight = true
        scope.launch {
            try {
                // Sign-up signs in too: it returns a token pair.
                val session = if (signUp) {
                    api.signUp(SignUpRequest(email = e, password = password, name = n))
                } else {
                    api.login(LoginRequest(email = e, password = password))
                }
                onAuthenticated(session)
            } catch (ex: ApiException) {
                problem = when (ex.status) {
                    401 -> AuthProblem.WrongCredentials
                    429 -> AuthProblem.Throttled
                    in 400..499 -> AuthProblem.Server(ex.userMessage)
                    else -> AuthProblem.Server(if (signUp) "Couldn't create your account. Please try again." else "Couldn't sign you in. Please try again.")
                }
                if (problem == AuthProblem.WrongCredentials) password = ""
            } catch (_: Throwable) {
                problem = AuthProblem.Network
            } finally {
                inFlight = false
            }
        }
    }

    val form = @Composable { wide: Boolean ->
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (wide) {
                Text(
                    if (signUp) "Join the library" else "Welcome back",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            PrimaryTabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                Tab(selected = !signUp, onClick = { tab = 0; problem = null }, text = { Text("Log in") })
                Tab(selected = signUp, onClick = { tab = 1; problem = null }, text = { Text("Create account") })
            }
            if (passwordChanged && problem == null) {
                Banner(BannerTone.Info, Icons.Rounded.CheckCircle, "Password changed. Log in with your new password.")
            } else if (sessionExpired && problem == null) {
                Banner(BannerTone.Info, Icons.Rounded.Schedule, "Your session expired. Please sign in again.")
            }
            when (val p = problem) {
                AuthProblem.WrongCredentials -> Banner(BannerTone.Error, Icons.Rounded.Error, "Incorrect email or password.")
                AuthProblem.Throttled -> Banner(
                    BannerTone.Calm,
                    Icons.Rounded.HourglassTop,
                    title = "Signing in is paused for a moment",
                    body = "After several attempts we pause sign-in to keep your account safe. Try again in a few minutes.",
                )
                AuthProblem.Network -> Banner(
                    BannerTone.Plain,
                    Icons.Rounded.CloudOff,
                    "Can’t reach the library. Check your connection.",
                    action = { TextButton(onClick = ::submit) { Text("Retry") } },
                )
                AuthProblem.MissingFields -> Banner(BannerTone.Error, Icons.Rounded.Error, "Please fill in every field.")
                is AuthProblem.Server -> Banner(BannerTone.Error, Icons.Rounded.Error, p.message)
                null -> Unit
            }
            if (signUp) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !inFlight,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                placeholder = { Text("you@example.org") },
                singleLine = true,
                enabled = !inFlight,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; if (problem == AuthProblem.WrongCredentials) problem = null },
                label = { Text("Password") },
                singleLine = true,
                enabled = !inFlight,
                isError = problem == AuthProblem.WrongCredentials,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
                supportingText = if (signUp) {
                    { PasswordHint(password.length) }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!signUp && onForgotPassword != null) {
                TextButton(
                    onClick = { onForgotPassword(email.trim()) },
                    modifier = Modifier.align(Alignment.End).offset(y = (-8).dp),
                ) { Text("Forgot password?") }
            }
            Button(
                onClick = ::submit,
                enabled = !inFlight,
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 0.dp),
            ) {
                if (inFlight) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    when {
                        inFlight && signUp -> "Creating account…"
                        inFlight -> "Logging in…"
                        signUp -> "Create account"
                        else -> "Log in"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                if (signUp) "Passwords can be 8 to 1024 characters." else "Accounts are free. Your reading stays private.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = maxWidth
            if (width.value >= WIDE_DP) {
                Row(Modifier.fillMaxSize()) {
                    WelcomePanel(Modifier.width(minOf(640f, width.value / 2).dp).fillMaxHeight())
                    Box(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.widthIn(max = 400.dp)) { form(true) }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    CompactHero()
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(Modifier.widthIn(max = 440.dp)) { form(false) }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** "At least 8 characters · 3 more to go", turning green once it's long enough. */
@Composable
internal fun PasswordHint(length: Int) {
    val ok = length >= MIN_PASSWORD
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            if (ok) "At least 8 characters · looks good" else "At least 8 characters · ${MIN_PASSWORD - length} more to go",
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val HeroBooks = listOf(
    "Persuasion" to "Jane Austen",
    "Walden" to "H. D. Thoreau",
    "Middlemarch" to "George Eliot",
    "Jane Eyre" to "Charlotte Brontë",
    "Dracula" to "Bram Stoker",
)

/** Phones: five overlapping, slightly tilted covers on a shelf, then the wordmark. */
@Composable
private fun CompactHero() {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Covers overlap by 12dp, the middle one on top.
            Row(Modifier.height(112.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy((-12).dp)) {
                val tilts = listOf(-6f, -2f, 0f, -2f, 5f)
                val z = listOf(1f, 2f, 3f, 2f, 1f)
                HeroBooks.forEachIndexed { i, (t, a) ->
                    BookCover(
                        t, a, CoverSize.M,
                        modifier = Modifier.zIndex(z[i]).rotate(tilts[i]),
                    )
                }
            }
            Shelf(Modifier.width(300.dp).offset(y = (-4).dp))
            Wordmark(Modifier.padding(top = 6.dp), size = 30)
            Text(
                "A community library, kept by its readers.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Wide screens: the welcome panel beside the form. */
@Composable
private fun WelcomePanel(modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Column(Modifier.padding(horizontal = 64.dp, vertical = 56.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Wordmark(size = 40)
            Text(
                "A community library, kept by its readers.",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Normal),
                modifier = Modifier.widthIn(max = 440.dp),
            )
            BoxWithConstraints(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
              // As many 72dp covers (10dp apart) as fit; never squeeze one.
              val perRow = ((maxWidth.value - 16 + 10) / 82).toInt().coerceIn(1, 5)
              Column(verticalArrangement = Arrangement.Center) {
                val rows = listOf(
                    listOf("Middlemarch" to "George Eliot", "Persuasion" to "Jane Austen", "Moby-Dick" to "Herman Melville", "Walden" to "H. D. Thoreau", "Little Women" to "L. M. Alcott"),
                    listOf("Jane Eyre" to "Charlotte Brontë", "North and South" to "Elizabeth Gaskell", "Frankenstein" to "Mary Shelley", "The Age of Innocence" to "Edith Wharton", "Wuthering Heights" to "Emily Brontë"),
                )
                rows.forEachIndexed { r, row ->
                    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                        row.take(perRow).forEachIndexed { i, (t, a) ->
                            val tilt = if ((i + r) % 4 == 2) -2f else 0f
                            BookCover(t, a, CoverSize.M, modifier = Modifier.rotate(tilt))
                        }
                    }
                    Shelf(Modifier.fillMaxWidth().padding(bottom = 26.dp).offset(y = (-2).dp))
                }
              }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Feature(Icons.Outlined.Explore, "Browse a catalog the community builds together")
                Feature(Icons.Outlined.AutoStories, "Keep your own shelves, ratings and notes")
                Feature(Icons.AutoMirrored.Rounded.MenuBook, "Read PDF and plain-text editions right here")
            }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Shelf(modifier: Modifier) {
    Box(modifier.height(6.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(3.dp)))
}
