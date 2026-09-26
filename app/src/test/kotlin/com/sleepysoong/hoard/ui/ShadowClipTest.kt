package com.sleepysoong.hoard.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.MainActivity
import com.sleepysoong.hoard.data.HoardRepository
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * A glass card's drop shadow must fade out, never stop at a straight line.
 * A scrolling list clips drawing at its edges, so if the list ends right under a
 * card the shadow is cut and the area below looks "painted white".
 *
 * Scans straight down from each card's bottom edge and fails on any sudden
 * brightness jump.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ShadowClipTest {
    @get:Rule(order = 0) val reset = object : org.junit.rules.ExternalResource() {
        override fun before() = HoardRepository.resetForTests()
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun luminance(c: Int) = 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)

    /** Largest brightness step between neighbouring pixel rows in the 40dp below [cardBottomPx]. */
    private fun maxStepBelow(name: String, cardBottomPx: Int, xPx: Int): Double {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts", "shadow").apply { mkdirs() }
            .let { File(it, "$name.png") }.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val density = compose.activity.resources.displayMetrics.density
        val from = cardBottomPx + (2 * density).toInt() // skip the anti-aliased rim
        val to = (cardBottomPx + 40 * density).toInt().coerceAtMost(bmp.height - 1)
        return (from until to).maxOf { y -> abs(luminance(bmp.getPixel(xPx, y + 1)) - luminance(bmp.getPixel(xPx, y))) }
    }

    /** [belowTextDp]: distance from the node's bottom to the card's bottom edge. */
    private fun assertNoCut(name: String, nodeText: String, belowTextDp: Float = 0f) {
        val nodes = compose.onAllNodesWithText(nodeText, substring = true).fetchSemanticsNodes()
        val b = nodes.maxBy { it.boundsInRoot.bottom }.boundsInRoot
        val d = compose.activity.resources.displayMetrics.density
        // A soft shadow changes by ~1 level per row; a clip edge jumps several at once.
        val step = maxStepBelow(name, (b.bottom + belowTextDp * d).toInt(), (b.left + 60 * d).toInt())
        assertTrue("$name: shadow cut off below the card (brightness jump %.1f)".format(step), step < 3.0)
    }

    @Test fun sessionsListSingleCard() = assertNoCut("sessions", "Hoard에 오신 것을 환영합니다")

    @Test fun toolsLastCard() {
        compose.onNodeWithTag("tab-도구").performClick()
        // Last MCP server card: its "제거" row ends ~12dp above the card edge.
        assertNoCut("tools", "제거", belowTextDp = 14f)
    }
}
