package com.sleepysoong.hoard.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
 * Frame-by-frame capture of a popup opening and closing (virtual clock):
 *  - opens by growing from the anchor and overshooting past full size, then settles
 *  - Cancel plays an exit (shrink + fade) before the popup leaves the tree
 *  - a confirm action runs only after the exit finished
 * Filmstrips are saved to build/test-artifacts/motion/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class PopupMotionTest {
    @get:Rule(order = 0) val reset = object : org.junit.rules.ExternalResource() {
        override fun before() = HoardRepository.resetForTests()
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val outDir = File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts", "motion").apply { mkdirs() }

    private fun popupScale(): Float? {
        val nodes = compose.onAllNodesWithTag("glass-popup").fetchSemanticsNodes()
        val n = nodes.firstOrNull() ?: return null
        return n.boundsInRoot.width / n.size.width
    }

    private fun frame(name: String) {
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun openSettings() {
        compose.onNodeWithText("Hoard에 오신 것을 환영합니다").performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("세션 설정").performClick()
    }

    /** Step the clock frame by frame, like a real display, until the popup is at rest. */
    private fun settle(ms: Long = 1_000) {
        repeat((ms / 16).toInt()) { compose.mainClock.advanceTimeByFrame() }
    }

    /** Opens with the real clock (input fully processed), then hands frames to the test. */
    private fun openSettled() {
        compose.onNodeWithText("Hoard에 오신 것을 환영합니다").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("세션 설정").performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    @Test fun opensWithOvershootThenSettles() {
        openSettings()
        val curve = mutableListOf<Float>()
        for (i in 0 until 40) {
            compose.mainClock.advanceTimeByFrame()
            popupScale()?.let { curve += it }
            if (i in listOf(1, 3, 6, 10, 30)) frame("open-f%02d".format(i))
        }
        println("open curve: " + curve.joinToString { "%.3f".format(it) })
        assertTrue("starts smaller than rest: ${curve.first()}", curve.first() < 0.95f)
        assertTrue("pops past full size: max=${curve.max()}", curve.max() > 1.005f)
        settle()
        assertEquals("settles at rest", 1f, popupScale()!!, 0.002f)
    }

    @Test fun cancelPlaysExitBeforeClosing() {
        openSettled()
        compose.onNodeWithText("취소").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val mid = popupScale()
        frame("close-mid")
        assertTrue("still visible and shrinking during exit: $mid", mid != null && mid < 0.999f)
        settle(400)
        assertEquals("gone after exit", null, popupScale())
    }

    @Test fun confirmRunsAfterExit() {
        openSettled()
        compose.onNodeWithText("세션 이름", substring = true).assertExists()
        // Rename via the field, save, and check the change lands only after the exit.
        compose.onNode(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasText("세션 이름"))
            .performTextReplacement("모션 테스트")
        compose.onNodeWithText("저장").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertTrue("popup still animating out", popupScale() != null)
        assertEquals("not applied mid-exit", "Hoard에 오신 것을 환영합니다", HoardRepository.get().sessionOf("session-welcome")!!.name)
        settle(400)
        assertEquals(null, popupScale())
        assertEquals("applied after exit", "모션 테스트", HoardRepository.get().sessionOf("session-welcome")!!.name)
    }
}
