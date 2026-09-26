package com.sleepysoong.hoard.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.MainActivity
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.engine.MockAiEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Chat motion, per frame on the real screen:
 *  - bubbles already in the chat are at rest when it opens (no mass pop)
 *  - a sent message pops out of its tail corner: grows from small, overshoots, settles
 *  - the thinking panel grows open over several frames (not a jump)
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ChatMotionTest {
    @get:Rule(order = 0) val reset = object : org.junit.rules.ExternalResource() {
        override fun before() { HoardRepository.resetForTests(); MockAiEngine.pace = 50f } // reply stays pending
        override fun after() { MockAiEngine.pace = 1f } // don't leak into other tests in this JVM
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val outDir = File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts", "motion").apply { mkdirs() }

    private fun frame(name: String) {
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun scaleOf(text: String): Float? = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes()
        .firstOrNull()?.let { it.boundsInRoot.width / it.size.width }

    private fun openChat() {
        compose.onNodeWithText("Hoard에 오신 것을 환영합니다").performClick()
        compose.waitForIdle()
    }

    @Test fun existingBubblesDoNotPopWhenChatOpens() {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Hoard에 오신 것을 환영합니다").performClick()
        val scales = (1..30).mapNotNull { compose.mainClock.advanceTimeByFrame(); scaleOf("안녕하세요, Hoard입니다") }
        assertTrue("welcome bubble rendered", scales.isNotEmpty())
        assertTrue("already at full size, not popping: ${scales.min()}", scales.all { it > 0.995f })
    }

    @Test fun sentMessagePopsInWithOvershoot() {
        openChat()
        compose.onNode(hasSetTextAction()).performTextInput("모션 확인용 메시지")
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("보내기").performClick()
        val curve = mutableListOf<Float>()
        for (i in 0 until 40) {
            compose.mainClock.advanceTimeByFrame()
            scaleOf("모션 확인용 메시지")?.takeIf { compose.onAllNodesWithText("모션 확인용 메시지").fetchSemanticsNodes().size == 1 }
                ?.let { curve += it }
            if (i in listOf(2, 5, 9)) frame("bubble-f%02d".format(i))
        }
        println("bubble curve: " + curve.joinToString { "%.3f".format(it) })
        assertTrue("starts small: ${curve.first()}", curve.first() < 0.85f)
        assertTrue("overshoots: ${curve.max()}", curve.max() > 1.005f)
        repeat(60) { compose.mainClock.advanceTimeByFrame() }
        assertEquals(1f, scaleOf("모션 확인용 메시지")!!, 0.002f)
    }

    @Test fun thinkingPanelGrowsOpen() {
        openChat()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("추론 2단계", substring = true).performClick()
        val heights = (1..30).map {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithText("요청 파악", substring = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.bottom ?: 0f
        }
        println("thinking reveal: " + heights.joinToString { "%.0f".format(it) })
        val distinct = heights.filter { it > 0f }.distinct()
        assertTrue("opens over several frames, not a jump: $distinct", distinct.size >= 5)
    }
}
