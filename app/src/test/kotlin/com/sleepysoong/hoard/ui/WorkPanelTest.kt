package com.sleepysoong.hoard.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.RouteAttempt
import com.sleepysoong.hoard.data.RoutingInfo
import com.sleepysoong.hoard.data.StepKind
import com.sleepysoong.hoard.data.ThinkingStep
import com.sleepysoong.hoard.ui.chat.MessageBubble
import com.sleepysoong.hoard.ui.theme.HoardTheme
import com.sleepysoong.hoard.ui.theme.LocalHoardDarkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 작업 pill → 추론 / 도구 사용 groups; # pill → router log. Screenshots: build/test-artifacts/work/. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class WorkPanelTest {
    @get:Rule val compose = createComposeRule()
    private val outDir = File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts", "work").apply { mkdirs() }
    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(1500); compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val message = ChatMessage(
        id = "a1", role = MessageRole.Assistant, modelId = "nvidia-nim/kimi-k3",
        text = "오늘 서울은 **맑고** 최고 24°C예요.",
        elapsedMs = 8400, promptTokens = 120, completionTokens = 80,
        thinking = listOf(
            ThinkingStep("모델 추론", "사용자가 서울 날씨를 묻는다. 최신 정보가 필요하니 웹 검색을 하자.", 1800),
            ThinkingStep("웹 검색", "서울 오늘 날씨\n결과 5개: 서울 날씨 - 기상청 · 네이버 날씨", 900, StepKind.Tool),
            ThinkingStep("페이지 읽기", "www.weather.go.kr/w/index.do\n날씨누리 · 4120자", 1300, StepKind.Tool),
            ThinkingStep("페이지 읽기", "192.168.0.1/\n실패: blocked non-public address", 3, StepKind.Tool, failed = true),
            ThinkingStep("모델 추론", "기상청 페이지에서 최고 24도, 맑음을 확인했다. 간단히 답하자.", 700)
        ),
        routing = RoutingInfo(
            requestedModel = "coding", routeReason = "model-group",
            candidates = listOf("zen/muse", "nvidia-nim/kimi-k3"),
            selectedModel = "nvidia-nim/kimi-k3", selectedProvider = "nvidia-nim",
            attempts = listOf(
                RouteAttempt(1, "zen/muse", "zen", null, "failed", "rate_limit", 429, "HTTP 429 Too Many Requests", true, 120),
                RouteAttempt(2, "nvidia-nim/kimi-k3", "nvidia-nim", "moonshotai/kimi-k3", "succeeded", durationMs = 2100)
            )
        )
    )

    private fun render(dark: Boolean) = compose.setContent {
        HoardTheme(darkTheme = dark) {
            CompositionLocalProvider(LocalHoardDarkTheme provides dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
                    MessageBubble(message, "kimi-k3", 360.dp, groupedWithPrevious = false, showFooter = true, onLongPress = {}, animateEntrance = false)
                }
            }
        }
    }

    private fun openBoth() {
        compose.onNodeWithTag("work-pill", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("routing-summary", useUnmergedTree = true).performClick()
    }

    @Test fun workAndRoutingPanelsLight() {
        render(false)
        compose.onNodeWithText("작업 5단계", useUnmergedTree = true).assertExists()
        shot("light-collapsed")
        openBoth()
        compose.mainClock.advanceTimeBy(1500)
        assertEquals("reasoning and tool groups", 2, compose.onAllNodesWithTag("work-group", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(5, compose.onAllNodesWithTag("work-step", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(2, compose.onAllNodesWithTag("routing-attempt", useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("도구 사용", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("HTTP 429 · rate_limit · 120ms\nHTTP 429 Too Many Requests", useUnmergedTree = true).assertExists()
        // The # pill names the requested group; the log has no extra header line.
        compose.onNodeWithText("coding", useUnmergedTree = true).assertExists()
        assertEquals(0, compose.onAllNodesWithText("라우팅 로그", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals("model name no longer in the footer", 0, compose.onAllNodesWithTag("footer-model", useUnmergedTree = true).fetchSemanticsNodes().size)
        shot("light-open")
    }
}
