package com.libraryz.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset

/**
 * The platform's "reduce motion" accessibility setting. When on, nothing
 * slides, scales or pulses; content still crossfades, briefly, so changes
 * stay noticeable. Each platform's entry point provides it.
 */
val LocalReduceMotion = compositionLocalOf { false }

/**
 * Motion tokens, after Material 3's: durations by how far things travel,
 * emphasized easing for things that move across the screen and standard
 * easing for small, in-place changes.
 */
object Motion {
    /** Colours, icons, selection: in place and small. */
    const val SHORT = 150
    /** Content swaps, list items, expanding sections. */
    const val MEDIUM = 250
    /** Screen transitions. */
    const val LONG = 350
    /** How far a shared-axis transition travels, in dp. */
    const val SHIFT_DP = 30

    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** A small in-place change (colour, size) in [ms]. */
    fun <T> standard(ms: Int = SHORT): FiniteAnimationSpec<T> = tween(ms, easing = Standard)

    /**
     * Shared axis X: the new content slides in [shiftPx] from the side it
     * comes from ([forward] = from the end) while the old fades out ahead
     * of it. For moves deeper into the app and back out (push / pop) and
     * for turning pages.
     */
    fun sharedAxisX(forward: Boolean, shiftPx: Int, reduce: Boolean, ms: Int = LONG): ContentTransform {
        if (reduce) return crossfade()
        val sign = if (forward) 1 else -1
        val out = ms * 3 / 10
        return (
            slideInHorizontally(tween(ms, easing = EmphasizedDecelerate)) { sign * shiftPx } +
                fadeIn(tween(ms - out, delayMillis = out, easing = EmphasizedDecelerate))
            ) togetherWith (
            slideOutHorizontally(tween(ms, easing = EmphasizedAccelerate)) { -sign * shiftPx } +
                fadeOut(tween(out, easing = EmphasizedAccelerate))
            )
    }

    /**
     * A page turn: the new page slides in beside the old, which slides away,
     * like an e-reader. No fade, so the paper never dims mid-turn. Not
     * clipped, so the pages travel across the whole reading area.
     */
    fun pageTurn(forward: Boolean, reduce: Boolean, ms: Int = MEDIUM + 50): ContentTransform {
        if (reduce) return crossfade()
        val sign = if (forward) 1 else -1
        return ContentTransform(
            targetContentEnter = slideInHorizontally(tween(ms, easing = Standard)) { sign * it },
            initialContentExit = slideOutHorizontally(tween(ms, easing = Standard)) { -sign * it },
            sizeTransform = SizeTransform(clip = false),
        )
    }

    /**
     * Fade through: the old content fades out, then the new fades in while
     * growing slightly. For peers with no spatial relation: top-level tabs,
     * signing in, loading → content.
     */
    fun fadeThrough(reduce: Boolean, ms: Int = MEDIUM + 50): ContentTransform {
        if (reduce) return crossfade()
        val out = ms * 3 / 10
        return (
            fadeIn(tween(ms - out, delayMillis = out, easing = EmphasizedDecelerate)) +
                scaleIn(tween(ms - out, delayMillis = out, easing = EmphasizedDecelerate), initialScale = 0.96f)
            ) togetherWith fadeOut(tween(out, easing = EmphasizedAccelerate))
    }

    /** A plain, quick crossfade: what everything becomes when motion is reduced. */
    fun crossfade(ms: Int = SHORT): ContentTransform =
        fadeIn(tween(ms, easing = Standard)) togetherWith fadeOut(tween(ms, easing = Standard))

    /** Something appearing in a column: it fades in as its space opens. */
    fun reveal(reduce: Boolean): EnterTransition =
        fadeIn(tween(MEDIUM, easing = Standard)) +
            if (reduce) EnterTransition.None else expandVertically(tween(MEDIUM, easing = Standard))

    /** [reveal] in reverse. */
    fun conceal(reduce: Boolean): ExitTransition =
        fadeOut(tween(SHORT, easing = Standard)) +
            if (reduce) ExitTransition.None else shrinkVertically(tween(MEDIUM, easing = Standard))

    /** No transition at all: the new content simply replaces the old. */
    val none: ContentTransform get() = EnterTransition.None togetherWith ExitTransition.None
}

/**
 * Lets a lazy list's items fade in, fade out and slide to their new places
 * (a dismissed recommendation, a review approved, search results arriving)
 * instead of jumping. Placement is skipped when motion is reduced.
 */
@Composable
fun LazyItemScope.itemMotion(): Modifier {
    val reduce = LocalReduceMotion.current
    return Modifier.animateItem(
        fadeInSpec = tween(Motion.MEDIUM, easing = Motion.Standard),
        placementSpec = if (reduce) null else tween<IntOffset>(Motion.MEDIUM, easing = Motion.Standard),
        fadeOutSpec = tween(Motion.SHORT, easing = Motion.Standard),
    )
}

/** [itemMotion] for lazy grids. */
@Composable
fun LazyGridItemScope.itemMotion(): Modifier {
    val reduce = LocalReduceMotion.current
    return Modifier.animateItem(
        fadeInSpec = tween(Motion.MEDIUM, easing = Motion.Standard),
        placementSpec = if (reduce) null else tween<IntOffset>(Motion.MEDIUM, easing = Motion.Standard),
        fadeOutSpec = tween(Motion.SHORT, easing = Motion.Standard),
    )
}

/**
 * A slow breathe for loading placeholders, so a skeleton reads as "on its
 * way" rather than as empty boxes. Still when motion is reduced.
 */
@Composable
fun Modifier.loadingPulse(): Modifier {
    if (LocalReduceMotion.current) return this
    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(900, easing = Motion.Standard), RepeatMode.Reverse),
        label = "pulse alpha",
    )
    return graphicsLayer { this.alpha = alpha }
}

/**
 * Dissolves a change of [color], a page's background: the old colour lies
 * over the new content and fades away, so the page and its text change
 * together instead of the text flashing against the old background.
 * Draw-only: cheap even over a whole book's text.
 */
@Composable
fun Modifier.dissolveFrom(color: Color): Modifier {
    val previous = remember { ColorHolder(color) }
    val from = remember(color) { previous.value }
    val fade = remember(color) { Animatable(if (from == color) 0f else 1f) }
    SideEffect { previous.value = color }
    LaunchedEffect(fade) { fade.animateTo(0f, tween(Motion.LONG, easing = Motion.Standard)) }
    return drawWithContent {
        drawContent()
        if (fade.value > 0f) drawRect(from, alpha = fade.value)
    }
}

/** The colour [dissolveFrom] last drew; not state, only read when the colour changes. */
private class ColorHolder(var value: Color)

/** [Motion.standard], or an instant change when motion is reduced. For things that move or resize. */
@Composable
fun <T> movementSpec(ms: Int = Motion.MEDIUM): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else tween(ms, easing = Motion.Standard)
