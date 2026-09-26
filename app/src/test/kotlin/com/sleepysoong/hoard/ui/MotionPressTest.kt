package com.sleepysoong.hoard.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.ui.glass.GlassMotion
import com.sleepysoong.hoard.ui.glass.liquidClickable
import com.sleepysoong.hoard.ui.theme.HoardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Samples the rendered scale of a tappable surface frame by frame (virtual clock)
 * and checks the iOS press curve: sinks fast while held, overshoots past 1.0 on
 * release, then settles exactly at 1.0.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MotionPressTest {
    @get:Rule val compose = createComposeRule()

    private fun scaleNow(): Float {
        val node = compose.onNodeWithTag("target").fetchSemanticsNode()
        // Layer scale shows up in the node's bounds relative to its unscaled layout size.
        return node.boundsInRoot.width / node.size.width
    }

    @Test fun pressSinksThenReleaseOvershootsAndSettles() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HoardTheme {
                // Room around the target so bounds aren't clipped when it scales past 1.0.
                Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(200.dp).testTag("target").liquidClickable {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(1f, scaleNow(), 0.001f)

        compose.onNodeWithTag("target").performTouchInput { down(Offset(100f, 100f)) }
        compose.mainClock.advanceTimeBy(250)
        val held = scaleNow()
        assertEquals("sinks to press scale", GlassMotion.PRESS_SCALE, held, 0.004f)

        compose.onNodeWithTag("target").performTouchInput { up() }
        val frames = (1..40).map { compose.mainClock.advanceTimeByFrame(); scaleNow() }
        val peak = frames.max()
        assertTrue("springs back past 1.0 (peak=$peak) — the bounce", peak > 1.003f)
        assertTrue("overshoot stays subtle (peak=$peak)", peak < 1.03f)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("settles at rest", 1f, scaleNow(), 0.001f)
        println("press curve: held=$held frames=" + frames.joinToString { "%.3f".format(it) })
    }
}
