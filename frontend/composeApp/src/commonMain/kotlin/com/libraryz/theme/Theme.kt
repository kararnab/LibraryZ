package com.libraryz.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import libraryz.composeapp.generated.resources.Res
import libraryz.composeapp.generated.resources.figtree
import libraryz.composeapp.generated.resources.literata
import org.jetbrains.compose.resources.Font

// "LibraryZ Visual Refresh" (Claude Design, 2026-10-10): a calm, bookish
// M3 theme. Warm paper surfaces, a deep library green (seed #2E5E4E),
// brass secondary for ratings, slate-blue tertiary for formats. Every
// on-* pair meets 4.5:1.
private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF2E5E4E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC6E7D8),
    onPrimaryContainer = Color(0xFF0A3428),
    secondary = Color(0xFF6B5B3A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1E2BE),
    onSecondaryContainer = Color(0xFF241A04),
    tertiary = Color(0xFF47607A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3E4FA),
    onTertiaryContainer = Color(0xFF021D33),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFBF8F2),
    onBackground = Color(0xFF1D1C18),
    surface = Color(0xFFFBF8F2),
    onSurface = Color(0xFF1D1C18),
    surfaceVariant = Color(0xFFE4E0D6),
    onSurfaceVariant = Color(0xFF4A4740),
    surfaceTint = Color(0xFF2E5E4E),
    inverseSurface = Color(0xFF32302B),
    inverseOnSurface = Color(0xFFF5F0E7),
    inversePrimary = Color(0xFF98D1BB),
    outline = Color(0xFF7A766C),
    outlineVariant = Color(0xFFCDC8BC),
    scrim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F2EA),
    surfaceContainer = Color(0xFFF0ECE3),
    surfaceContainerHigh = Color(0xFFEAE6DC),
    surfaceContainerHighest = Color(0xFFE4E0D6),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF98D1BB),
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF15473A),
    onPrimaryContainer = Color(0xFFB4EDD6),
    secondary = Color(0xFFD7C6A0),
    onSecondary = Color(0xFF3A2F16),
    secondaryContainer = Color(0xFF52462B),
    onSecondaryContainer = Color(0xFFF1E2BE),
    tertiary = Color(0xFFAFC9E6),
    onTertiary = Color(0xFF18324A),
    tertiaryContainer = Color(0xFF2F4861),
    onTertiaryContainer = Color(0xFFD3E4FA),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF15140F),
    onBackground = Color(0xFFE8E3D9),
    surface = Color(0xFF15140F),
    onSurface = Color(0xFFE8E3D9),
    surfaceVariant = Color(0xFF37352F),
    onSurfaceVariant = Color(0xFFCBC6BA),
    surfaceTint = Color(0xFF98D1BB),
    inverseSurface = Color(0xFFE8E3D9),
    inverseOnSurface = Color(0xFF32302B),
    inversePrimary = Color(0xFF2E5E4E),
    outline = Color(0xFF959084),
    outlineVariant = Color(0xFF4A4740),
    scrim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF100F0B),
    surfaceContainerLow = Color(0xFF1D1C17),
    surfaceContainer = Color(0xFF22201B),
    surfaceContainerHigh = Color(0xFF2C2A25),
    surfaceContainerHighest = Color(0xFF37352F),
)

// Cards medium, sheets/panes extra large, buttons/search/chips full or small.
private val LibraryZShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Literata for titles and reading text. */
@Composable
fun serifFamily(): FontFamily = FontFamily(
    Font(Res.font.literata, FontWeight.Normal),
    Font(Res.font.literata, FontWeight.Medium),
    Font(Res.font.literata, FontWeight.SemiBold),
)

/** Figtree for the interface. */
@Composable
fun sansFamily(): FontFamily = FontFamily(
    Font(Res.font.figtree, FontWeight.Normal),
    Font(Res.font.figtree, FontWeight.Medium),
    Font(Res.font.figtree, FontWeight.SemiBold),
    Font(Res.font.figtree, FontWeight.Bold),
)

private fun style(family: FontFamily, weight: FontWeight, size: Int, line: Int, tracking: Double = 0.0) =
    TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking.em,
    )

private fun typography(serif: FontFamily, sans: FontFamily) = Typography(
    displayLarge = style(serif, FontWeight.Normal, 57, 64, -0.01),
    displayMedium = style(serif, FontWeight.Normal, 45, 52, -0.01),
    displaySmall = style(serif, FontWeight.Normal, 36, 44, -0.01),
    headlineLarge = style(serif, FontWeight.Medium, 32, 40, -0.005),
    headlineMedium = style(serif, FontWeight.Medium, 28, 36, -0.005),
    headlineSmall = style(serif, FontWeight.Medium, 24, 32),
    titleLarge = style(serif, FontWeight.SemiBold, 20, 28),
    titleMedium = style(sans, FontWeight.SemiBold, 16, 24, 0.005),
    titleSmall = style(sans, FontWeight.SemiBold, 14, 20, 0.01),
    bodyLarge = style(sans, FontWeight.Normal, 16, 24),
    bodyMedium = style(sans, FontWeight.Normal, 14, 20, 0.01),
    bodySmall = style(sans, FontWeight.Normal, 12, 16, 0.02),
    labelLarge = style(sans, FontWeight.SemiBold, 14, 20, 0.01),
    labelMedium = style(sans, FontWeight.SemiBold, 12, 16, 0.04),
    labelSmall = style(sans, FontWeight.SemiBold, 11, 16, 0.05),
)

/** Tokens the M3 scheme has no slot for. Read via [LibraryZ]. */
@Immutable
data class LibraryZTokens(
    val serif: FontFamily,
    /** Book titles in lists and cards: Literata 600 · 16/22. */
    val bookTitle: TextStyle,
    /** Behind a rendered PDF page. */
    val readerBackdrop: Color,
    val dark: Boolean,
)

private val LocalLibraryZ = staticCompositionLocalOf<LibraryZTokens> {
    error("LibraryZTheme not applied")
}

object LibraryZ {
    val tokens: LibraryZTokens
        @Composable get() = LocalLibraryZ.current
}

/**
 * Reading themes for flowing text. Independent of the app theme, so you
 * can read sepia inside a dark app.
 */
enum class ReadingTheme(
    val label: String,
    val background: Color,
    val ink: Color,
    val muted: Color,
    val rule: Color,
) {
    Light("Light", Color(0xFFFFFDF8), Color(0xFF24221D), Color(0xFF6E695E), Color(0xFFE6E0D3)),
    Sepia("Sepia", Color(0xFFF3EAD5), Color(0xFF47382A), Color(0xFF7D6A52), Color(0xFFDCCDAE)),
    Dark("Dark", Color(0xFF121110), Color(0xFFCFC8BB), Color(0xFF8E887C), Color(0xFF2A2824)),
}

/**
 * The OS light/dark preference, for platforms where Compose's
 * [isSystemInDarkTheme] can't see it (Linux desktop always reports light).
 * Null means "ask Compose".
 */
val LocalSystemDarkTheme = compositionLocalOf<Boolean?> { null }

@Composable
fun LibraryZTheme(
    darkTheme: Boolean = LocalSystemDarkTheme.current ?: isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val serif = serifFamily()
    val sans = sansFamily()
    val tokens = LibraryZTokens(
        serif = serif,
        bookTitle = style(serif, FontWeight.SemiBold, 16, 22),
        readerBackdrop = if (darkTheme) Color(0xFF0D0C0A) else Color(0xFFE2DDD2),
        dark = darkTheme,
    )
    CompositionLocalProvider(LocalLibraryZ provides tokens) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = typography(serif, sans),
            shapes = LibraryZShapes,
            content = content,
        )
    }
}
