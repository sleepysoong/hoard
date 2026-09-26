package com.sleepysoong.hoard.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch

/**
 * The app's motion grammar — one physical system for every press, popup, screen
 * and bubble, tuned like iOS: things arrive with a soft overshoot, leave quickly
 * without bounce, and follow the finger while pressed.
 *
 * Springs are described the way Apple does (response ≈ how long, damping ≈ how
 * bouncy); never use raw tween for things that move — only for pure fades/colours.
 */
object GlassMotion {
    // ---- Spring vocabulary ------------------------------------------------------

    /** Arrivals: popups, new bubbles, buttons appearing. Visible, soft overshoot. */
    fun bouncy(): SpringSpec<Float> = spring(dampingRatio = 0.68f, stiffness = 380f, visibilityThreshold = SCALE_THRESHOLD)

    /** Release after a press / selection moves: quick with a small wobble. */
    fun snappy(): SpringSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 700f, visibilityThreshold = SCALE_THRESHOLD)

    /** Screen transitions and layout changes: fluid, barely any overshoot. */
    fun smooth(): SpringSpec<Float> = spring(dampingRatio = 0.86f, stiffness = 420f, visibilityThreshold = SCALE_THRESHOLD)

    /** Pressing in and leaving: fast and critically damped — never bounce away. */
    fun exit(): SpringSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1100f, visibilityThreshold = SCALE_THRESHOLD)

    fun offsetSmooth(): SpringSpec<IntOffset> = spring(dampingRatio = 0.86f, stiffness = 420f, visibilityThreshold = IntOffset(1, 1))
    fun sizeSmooth(): SpringSpec<IntSize> = spring(dampingRatio = 0.86f, stiffness = 420f, visibilityThreshold = IntSize(1, 1))
    fun offsetBouncy(): SpringSpec<IntOffset> = spring(dampingRatio = 0.72f, stiffness = 380f, visibilityThreshold = IntOffset(1, 1))

    /** Finger lifted: springs back past 1.0 and settles — the iOS rebound. */
    fun release(): SpringSpec<Float> = spring(dampingRatio = 0.5f, stiffness = 600f, visibilityThreshold = SCALE_THRESHOLD)

    /**
     * Scale/alpha animations must use this fine threshold. Compose's default (0.01)
     * ends a spring as soon as it is within 1% of the target — which silently
     * swallows the small overshoot that makes motion feel bouncy.
     */
    const val SCALE_THRESHOLD = 0.0005f

    /** Scale a pressed surface sinks to. Big surfaces sink less. */
    const val PRESS_SCALE = 0.955f
    const val PRESS_SCALE_LARGE = 0.975f

    // ---- Legacy names (kept for existing call sites) ---------------------------
    val springs: SpringSpec<Float> = bouncy()
    val springSnappy: SpringSpec<Float> = snappy()

    /** Fades and colour changes only. */
    val fast = TweenSpec<Float>(180, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
    val fastColor = TweenSpec<androidx.compose.ui.graphics.Color>(180, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
    val relaxed = TweenSpec<Float>(240, easing = CubicBezierEasing(0.25f, 0f, 0f, 1f))
    fun <T> fade(): FiniteAnimationSpec<T> = TweenSpec(160, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
}

/**
 * iOS press physics for anything tappable: sinks to [pressedScale] while the
 * finger is down (fast, no bounce), then springs back past 1.0 and settles on
 * release. Reads the same [interactionSource] the clickable uses.
 */
@Composable
fun Modifier.liquidPress(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = GlassMotion.PRESS_SCALE
): Modifier {
    val scale = remember { Animatable(1f, visibilityThreshold = GlassMotion.SCALE_THRESHOLD) }
    LaunchedEffect(interactionSource, enabled) {
        interactionSource.interactions.collect { i ->
            if (!enabled) return@collect
            when (i) {
                is PressInteraction.Press -> launch { scale.animateTo(pressedScale, GlassMotion.exit()) }
                is PressInteraction.Release, is PressInteraction.Cancel ->
                    launch { scale.animateTo(1f, GlassMotion.release()) }
            }
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
