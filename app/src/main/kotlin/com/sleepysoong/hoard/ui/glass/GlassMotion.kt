package com.sleepysoong.hoard.ui.glass

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * The app's motion grammar. Every animation uses these specs so a button
 * press, tab morph and popup entry all feel like the same physical system.
 */
object GlassMotion {
    /** iOS-feel spring: quick, slightly bouncy settle with minimal overshoot. */
    val springs = SpringSpec<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    /** Tabs/liquid morphing: snappy, almost no overshoot so the capsule
     *  doesn't overshoot the destination tab. */
    val springSnappy = SpringSpec<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh
    )
    /** Fast fade used for scrims, indicators, and cheap UI pings. */
    val fast = TweenSpec<Float>(180, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
    /** Same timing curve for color-driven transitions (button/label tints). */
    val fastColor = TweenSpec<androidx.compose.ui.graphics.Color>(
        180, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    )
    /**Slightly slower entrance for bigger elements (cards/overlays). */
    val relaxed = TweenSpec<Float>(240, easing = CubicBezierEasing(0.25f, 0f, 0f, 1f))
}
