package com.sleepysoong.hoard.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.ui.glass.GlassPillButton
import com.sleepysoong.hoard.ui.glass.GlassPillTint
import com.sleepysoong.hoard.ui.glass.GlassSwitch
import com.sleepysoong.hoard.ui.glass.IOSSegmentedControl
import com.sleepysoong.hoard.ui.theme.HoardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Liquid controls behave like iOS 26: switch toggles on tap and on drag, its thumb
 * swells into glass while pressed; the segmented thumb glides with overshoot, can be
 * dragged, and release picks the nearest segment. Screenshots (rest + pressed, light
 * + dark) go to build/test-artifacts/liquid/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LiquidControlsTest {
    @get:Rule val compose = createComposeRule()
    private val outDir = File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts", "liquid").apply { mkdirs() }
    private var checked by mutableStateOf(false)
    private var seg by mutableIntStateOf(0)

    private fun shot(name: String) {
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun content(dark: Boolean = false) = compose.setContent {
        HoardTheme(darkTheme = dark) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                IOSSegmentedControl(listOf("MCP", "플러그인", "스킬", "명령어"), seg, { seg = it },
                    Modifier.fillMaxWidth().testTag("segmented"))
                Box(Modifier.size(120.dp, 60.dp), contentAlignment = Alignment.Center) {
                    GlassSwitch(checked, { checked = it }, Modifier.testTag("switch"))
                }
                Box(Modifier.padding(4.dp)) {
                    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPillButton("테스트", {}, tint = GlassPillTint.Accent)
                        GlassPillButton("제거", {}, tint = GlassPillTint.Destructive)
                    }
                }
            }
        }
    }

    // Thumbs sit inside merged (toggleable) nodes: look them up in the unmerged tree.
    private fun thumbScale(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().let { it.boundsInRoot.height / it.size.height }
    private fun thumbX(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x

    @Test fun switchTogglesOnTapAndThumbSlidesWithBounce() {
        content()
        compose.waitForIdle()
        val off = thumbX("switch-thumb")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("switch").performClick()
        val xs = (1..40).map { compose.mainClock.advanceTimeByFrame(); thumbX("switch-thumb") }
        compose.mainClock.advanceTimeBy(800)
        val on = thumbX("switch-thumb")
        println("switch x: " + xs.joinToString { "%.0f".format(it) })
        assertTrue(checked)
        compose.onNodeWithTag("switch").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        assertTrue("moved right", on > off + 50)
        assertTrue("overshoots past the end then settles: max=${xs.max()} rest=$on", xs.max() > on + 1f)
    }

    /** Leftmost non-background pixel on the switch's centre line. */
    private fun drawnLeftEdge(): Int {
        val b = compose.onNodeWithTag("switch").fetchSemanticsNode().boundsInRoot
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val y = b.center.y.toInt()
        val bg = bmp.getPixel(2, y)
        return (0 until b.right.toInt()).first { x ->
            val c = bmp.getPixel(x, y)
            listOf(16, 8, 0).sumOf { sh -> kotlin.math.abs(((c shr sh) and 0xFF) - ((bg shr sh) and 0xFF)) } > 12
        }
    }

    @Test fun switchThumbSwellsIntoGlassWhilePressed() {
        content()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val rest = drawnLeftEdge()
        compose.onNodeWithTag("switch").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(300)
        val held = drawnLeftEdge()
        shot("light-pressed-switch")
        compose.onNodeWithTag("switch").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(800)
        // The swell is drawn inside the backdrop layer, so measure pixels, not bounds.
        assertTrue("thumb swells past the track while held: rest=$rest held=$held", held < rest - 8)
        assertEquals("back to rest", rest.toFloat(), drawnLeftEdge().toFloat(), 2f)
        assertTrue("tap toggled", checked)
    }

    @Test fun switchDragOnAndOff() {
        content()
        compose.waitForIdle()
        compose.onNodeWithTag("switch").performTouchInput {
            down(Offset(width * 0.3f, centerY)); repeat(8) { moveBy(Offset(10f, 0f)) }; up()
        }
        compose.waitForIdle()
        assertTrue("dragged on", checked)
        compose.onNodeWithTag("switch").performTouchInput {
            down(Offset(width * 0.7f, centerY)); repeat(8) { moveBy(Offset(-10f, 0f)) }; up()
        }
        compose.waitForIdle()
        assertTrue("dragged off", !checked)
    }

    @Test fun segmentTapGlidesWithOvershootAndDragPicksNearest() {
        content()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("스킬").performClick()
        val xs = (1..40).map { compose.mainClock.advanceTimeByFrame(); thumbX("segment-thumb") }
        compose.mainClock.advanceTimeBy(800)
        val rest = thumbX("segment-thumb")
        println("segment x: " + xs.joinToString { "%.0f".format(it) })
        assertEquals(2, seg)
        assertTrue("overshoots then settles: max=${xs.max()} rest=$rest", xs.max() > rest + 1f)
        shot("light-rest")
        compose.mainClock.autoAdvance = true

        // Drag the thumb from 스킬 back towards the start and let go near 플러그인.
        compose.onNodeWithTag("segmented").performTouchInput {
            val segW = width / 4f
            down(Offset(segW * 2.5f, centerY))
            repeat(10) { moveBy(Offset(-segW / 10f, 0f)) }
            up()
        }
        compose.waitForIdle()
        assertEquals("release picks nearest segment", 1, seg)
    }

    @Test fun darkModeShots() {
        checked = true
        content(dark = true)
        compose.waitForIdle()
        shot("dark-rest")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("switch").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(300)
        shot("dark-pressed-switch")
        compose.onNodeWithTag("switch").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("segment-thumb", useUnmergedTree = true).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(300)
        shot("dark-pressed-segment")
    }
}
