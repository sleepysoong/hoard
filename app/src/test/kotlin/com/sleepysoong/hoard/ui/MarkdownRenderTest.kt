package com.sleepysoong.hoard.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.ui.chat.MessageBubble
import com.sleepysoong.hoard.ui.theme.HoardTheme
import com.sleepysoong.hoard.ui.theme.LocalHoardDarkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Model replies render as Markdown (bold/italic/strike/code, lists, tables,
 * quotes, LaTeX); what the user typed stays verbatim. Screenshots:
 * build/test-artifacts/markdown/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MarkdownRenderTest {
    @get:Rule val compose = createComposeRule()

    private val outDir = File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts", "markdown").apply { mkdirs() }
    private fun shot(name: String) {
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val sample = """
        ## 요약
        이건 **굵게**, *기울임*, ~~취소선~~, `inline code` 입니다.

        - 첫째 항목
        - 둘째 항목
          1. 번호 하위

        | 모델 | 속도 | 비고 |
        |:--|--:|:-:|
        | kimi-k3 | 빠름 | **추천** |
        | glm-5.3 | 보통 | - |

        > 인용문입니다.

        인라인 수식 ${'$'}E = mc^2${'$'} 과 블록 수식:

        ${'$'}${'$'}
        \int_0^1 x^2\,dx = \frac{1}{3}
        ${'$'}${'$'}

        ```kotlin
        fun hello() = println("hi")
        ```
    """.trimIndent()

    private fun render(dark: Boolean, message: ChatMessage) {
        compose.setContent {
            HoardTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalHoardDarkTheme provides dark) {
                    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
                        MessageBubble(
                            message = message,
                            modelName = "kimi-k3",
                            maxBubbleWidth = 340.dp,
                            groupedWithPrevious = false,
                            showFooter = true,
                            onLongPress = {},
                            animateEntrance = false
                        )
                    }
                }
            }
        }
    }

    private fun assistant(text: String, streaming: Boolean = false) =
        ChatMessage(id = "a1", role = MessageRole.Assistant, text = text, modelId = "nvidia-nim/kimi-k3", isStreaming = streaming)

    @Test
    fun assistantReplyRendersMarkdownLight() {
        render(dark = false, assistant(sample))
        assertRendered()
        shot("light")
    }

    @Test
    fun assistantReplyRendersMarkdownDark() {
        render(dark = true, assistant(sample))
        assertRendered()
        shot("dark")
    }

    private fun assertRendered() {
        // The renderer parses off the main thread; wait for the first layout.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("굵게", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // Styled, not raw: the markers are gone, the words and table cells remain.
        compose.onNodeWithText("굵게", substring = true).assertExists()
        compose.onNodeWithText("빠름", substring = true).assertExists()
        assertEquals("no literal ** left", 0, compose.onAllNodesWithText("**", substring = true).fetchSemanticsNodes().size)
        assertEquals("no table pipe rows left", 0, compose.onAllNodesWithText("|:--", substring = true).fetchSemanticsNodes().size)
        assertEquals("block math is not shown as source", 0, compose.onAllNodesWithText("\\int_0", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun userTextStaysVerbatim() {
        render(dark = false, ChatMessage(id = "u1", role = MessageRole.User, text = "**그대로** ${'$'}x${'$'}"))
        compose.onNodeWithText("**그대로** ${'$'}x${'$'}").assertExists()
        shot("user-verbatim")
    }

    @Test
    fun streamingReplyUpdatesAsTokensArrive() {
        var text by mutableStateOf("")
        compose.setContent {
            HoardTheme(darkTheme = false) {
                MessageBubble(
                    message = assistant(text, streaming = true), modelName = null, maxBubbleWidth = 340.dp,
                    groupedWithPrevious = false, showFooter = false, onLongPress = {}, animateEntrance = false
                )
            }
        }
        // Token-by-token, including a half-written bold and table.
        val chunks = listOf("표", "를 **정", "리**하면:\n\n| a | b |\n|--|--|\n| 1 ", "| 2 |\n")
        for (c in chunks) { text += c; compose.waitForIdle() }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("정리", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // A short first token must be visible right away (no coalescing hold-back).
        text = ""; compose.waitForIdle()
        text = "짧음"
        compose.waitUntil(3_000) { compose.onAllNodesWithText("짧음", substring = true).fetchSemanticsNodes().isNotEmpty() }
        text = "표를 **정리**하면:\n\n| a | b |\n|--|--|\n| 1 | 2 |\n"
        compose.waitUntil(10_000) { compose.onAllNodesWithText("정리", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("**", substring = true).fetchSemanticsNodes().isEmpty())
        shot("streaming")
    }
}
