package com.sleepysoong.hoard.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.MainActivity
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.StepKind
import com.sleepysoong.hoard.data.ThinkingStep
import com.sleepysoong.hoard.engine.MockAiEngine
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Small phone (320dp) with the largest system font (200%): every screen keeps
 * everything inside the screen width (no sideways scrolling / clipped rows) and
 * every tappable element offers a >= 48dp touch target. Screenshots: build/test-artifacts/responsive/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi", fontScale = 2.0f)
class ResponsiveLayoutTest {
    @get:Rule(order = 0) val reset = object : org.junit.rules.ExternalResource() {
        override fun before() { HoardRepository.resetForTests(); MockAiEngine.pace = 50f }
        override fun after() { MockAiEngine.pace = 1f }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val outDir = File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts", "responsive").apply { mkdirs() }

    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(1500); compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun allNodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    /** Nothing may start left of the screen or end right of it (1px slack for rounding). */
    private fun assertFitsWidth(screen: String) {
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        val over = allNodes().filter { it.boundsInRoot.width > 0f && (it.boundsInRoot.right > width + 1f || it.boundsInRoot.left < -1f) }
            // Content inside a horizontally scrollable container (wide tables/code) may extend by design.
            .filterNot { n -> generateSequence(n.parent) { it.parent }.any { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange) != null } }
        assertTrue("$screen: ${over.size} node(s) overflow the ${width}px screen: " +
            over.take(5).joinToString { "${it.config}".take(120) + " @ ${it.boundsInRoot}" }, over.isEmpty())
    }

    /** Every clickable node's touch area is at least 48dp in both directions. */
    private fun assertTapTargets(screen: String) {
        val minPx = with(compose.density) { 48.dp.toPx() } - 1f
        val small = allNodes().filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
            // Not on screen (outgoing tab content, scrolled away): nothing to tap.
            .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
            // Layout size (a press/pop animation may scale the drawn button for a moment).
            .filter { maxOf(it.size.width.toFloat(), it.touchBoundsInRoot.width) < minPx || maxOf(it.size.height.toFloat(), it.touchBoundsInRoot.height) < minPx }
        assertTrue("$screen: tap targets under 48dp: " +
            small.take(5).joinToString { "${it.config}".take(100) + " ${it.touchBoundsInRoot}" }, small.isEmpty())
    }

    private fun check(screen: String) {
        shot(screen)
        assertFitsWidth(screen)
        assertTapTargets(screen)
    }

    @Test fun sessionsToolsSettingsFitAtLargeFont() {
        check("sessions")
        compose.onNodeWithTag("tab-도구").performClick()
        check("tools")
        compose.onNodeWithTag("tab-설정").performClick()
        check("settings")
    }

    @Test fun chatWithLongContentFitsAtLargeFont() {
        compose.runOnUiThread {
            val repo = HoardRepository.get()
            repo.renameSession("session-welcome", "아주아주 긴 세션 이름이 상단 바를 넘치지 않는지 확인하는 세션")
            repo.appendMessage("session-welcome", ChatMessage(
                id = "u-long", role = MessageRole.User,
                text = "https://example.com/an/extremely/long/url/without/any/spaces/that/must/wrap/instead/of/overflowing"
            ))
            repo.appendMessage("session-welcome", ChatMessage(
                id = "a-long", role = MessageRole.Assistant, modelId = "nvidia-nim/kimi-k3",
                text = "**결과** `averyveryverylonginlinecodetokenwithoutspaces` 와 긴 문장입니다.\n\n| 열1 | 열2 | 열3 |\n|--|--|--|\n| 값 | 값 | 값 |",
                thinking = listOf(ThinkingStep("웹 검색", "서울 날씨\n결과 5개", 900, StepKind.Tool))
            ))
        }
        compose.onNodeWithText("아주아주 긴 세션", substring = true).performClick()
        compose.waitForIdle()
        check("chat-top")
        // Down to the long URL / inline code / table reply, open its 작업 cards, type in the composer.
        compose.onNodeWithContentDescription("맨 아래로").performClick()
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithTag("work-pill", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("입력창도 넘치면 안 됩니다")
        compose.onNodeWithText("averyveryverylonginlinecode", substring = true, useUnmergedTree = true).assertExists()
        check("chat-bottom")
    }
}
