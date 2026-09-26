package com.sleepysoong.hoard.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.MainActivity
import com.sleepysoong.hoard.data.HoardRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Screen motion in the real app, sampled per frame:
 *  - opening a chat slides it in from the right (x decreases to rest), no jump
 *  - Back slides it out to the right
 *  - the tab bar drops away when entering chat and rises back afterwards
 * Filmstrips go to build/test-artifacts/motion/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenMotionTest {
    @get:Rule(order = 0) val reset = object : org.junit.rules.ExternalResource() {
        override fun before() = HoardRepository.resetForTests()
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val outDir = File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts", "motion").apply { mkdirs() }

    /** Left edge of the chat's back button, or null when chat isn't composed. */
    private fun chatX(): Float? = compose.onAllNodesWithContentDescription("세션 목록으로").fetchSemanticsNodes()
        .firstOrNull()?.boundsInRoot?.left

    private fun tabBarY(): Float? = compose.onAllNodesWithTag("tab-세션").fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top

    private fun frame(name: String) {
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun pushSlidesInFromRightAndBackSlidesOut() {
        compose.waitForIdle()
        val barRest = tabBarY()!!
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Hoard에 오신 것을 환영합니다").performClick()

        val xs = mutableListOf<Float>()
        val bars = mutableListOf<Float?>()
        for (i in 0 until 40) {
            compose.mainClock.advanceTimeByFrame()
            chatX()?.let { xs += it }
            bars += tabBarY()
            if (i in listOf(2, 5, 9)) frame("push-f%02d".format(i))
        }
        repeat(60) { compose.mainClock.advanceTimeByFrame() }
        val rest = chatX()!!
        println("push x: " + xs.joinToString { "%.0f".format(it) } + " rest=$rest")
        assertTrue("starts off to the right: ${xs.first()} vs $rest", xs.first() > rest + 200)
        assertTrue("moves monotonically leftwards (smooth, no jump back)",
            xs.zipWithNext().all { (a, b) -> b <= a + 1f })
        assertTrue("several in-between frames, not a cut", xs.count { it > rest + 5 } >= 6)
        assertTrue("tab bar moves down out of the way", bars.filterNotNull().any { it > barRest + 20 } || bars.last() == null)

        compose.onNodeWithContentDescription("세션 목록으로").performClick()
        val back = mutableListOf<Float>()
        for (i in 0 until 40) {
            compose.mainClock.advanceTimeByFrame()
            chatX()?.let { back += it }
            if (i == 5) frame("pop-f05")
        }
        println("pop x: " + back.joinToString { "%.0f".format(it) })
        assertTrue("slides out to the right", back.isNotEmpty() && back.max() > rest + 150)
        repeat(90) { compose.mainClock.advanceTimeByFrame() }
        assertEquals("chat gone", null, chatX())
        assertEquals("tab bar back at rest", barRest, tabBarY()!!, 1f)
    }
}
