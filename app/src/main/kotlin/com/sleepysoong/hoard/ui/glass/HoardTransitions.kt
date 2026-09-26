package com.sleepysoong.hoard.ui.glass

import androidx.compose.animation.EnterTransition
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Screen transitions, Apple style.
 *  - Push into chat: new screen slides in from the right on a smooth spring; the
 *    list underneath drifts left a third of the way (parallax). No alpha on either
 *    side — a faded layer composites above its sibling and bleeds through.
 *  - Pop (back): exact reverse.
 *  - Tab switch: no sliding (tabs are siblings) — outgoing shrinks/fades quickly,
 *    incoming grows in from 96% with a gentle bounce.
 */
object HoardTransitions {
    /**
     * Opaque canvas behind a destination. Screens are otherwise transparent (the
     * GlassHost background shows through), so during a push the outgoing list would
     * show through the incoming chat. Bleeds past the NavHost's side padding so the
     * gutters are covered too.
     */
    @Composable
    fun Modifier.screenCanvas(): Modifier {
        val color = MaterialTheme.colorScheme.background
        return drawBehind {
            val bleed = 40.dp.toPx()
            drawRect(color, topLeft = Offset(-bleed, -bleed), size = Size(size.width + 2 * bleed, size.height + 2 * bleed))
        }
    }

    private const val CHAT = "chat"

    private fun isPush(from: String?, to: String?) = to == CHAT && from != CHAT
    private fun isPop(from: String?, to: String?) = from == CHAT && to != CHAT

    fun enter(from: String?, to: String?): EnterTransition = when {
        // Opaque slide, no fade: a half-transparent pushed screen shows the list through it.
        isPush(from, to) -> slideInHorizontally(GlassMotion.offsetSmooth()) { it }
        else -> tabIn()
    }

    fun exit(from: String?, to: String?): ExitTransition = when {
        isPush(from, to) -> slideOutHorizontally(GlassMotion.offsetSmooth()) { -it / 3 }
        else -> tabOut()
    }

    fun popEnter(from: String?, to: String?): EnterTransition = when {
        isPop(from, to) -> slideInHorizontally(GlassMotion.offsetSmooth()) { -it / 3 }
        else -> tabIn()
    }

    fun popExit(from: String?, to: String?): ExitTransition = when {
        isPop(from, to) -> slideOutHorizontally(GlassMotion.offsetSmooth()) { it }
        else -> tabOut()
    }

    private fun tabIn(): EnterTransition =
        fadeIn(tween(200, delayMillis = 40)) + scaleIn(GlassMotion.bouncy(), initialScale = 0.96f)

    private fun tabOut(): ExitTransition =
        fadeOut(tween(120)) + scaleOut(GlassMotion.exit(), targetScale = 0.98f)
}
