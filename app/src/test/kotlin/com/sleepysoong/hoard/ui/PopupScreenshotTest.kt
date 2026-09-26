package com.sleepysoong.hoard.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.MainActivity
import com.sleepysoong.hoard.data.HoardRepository
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Drives the real app to every popup and saves a screenshot of each to
 * build/test-artifacts/popups/<theme>-<popup>.png, so their shared layout
 * (header card · body card · button row) can be compared side by side.
 *
 * Also asserts the behavioural contract every popup shares: Cancel closes it,
 * Back closes it, and the confirm action applies.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
open class PopupScreenshotTest {
    protected open val theme = "light"
    @get:Rule(order = 0) val reset = object : org.junit.rules.ExternalResource() {
        override fun before() = HoardRepository.resetForTests()
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val outDir = File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts", "popups").apply { mkdirs() }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$theme-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Before fun openChat() {
        compose.onNodeWithText("Hoard에 오신 것을 환영합니다").performClick()
        compose.waitForIdle()
    }

    private fun assertClosed(title: String) {
        compose.waitForIdle()
        compose.onAllNodesWithText(title).fetchSemanticsNodes().let {
            // Only the top bar title may remain (the session name), never the popup header.
            check(it.none { n -> n.config.toString().contains("Heading") }) { "$title popup still open" }
        }
    }

    @Test fun sessionSettings() {
        compose.onNodeWithContentDescription("세션 설정").performClick()
        shot("session-settings")
        compose.onNodeWithText("취소").performClick()
        assertClosed("세션 설정")
    }

    @Test fun contextLimitIsTypedNotSlid() {
        compose.onNodeWithContentDescription("세션 설정").performClick()
        compose.onAllNodes(hasSetTextAction() and hasText("32000")).assertCountEquals(1)
        val field = compose.onNode(hasSetTextAction() and hasText("컨텍스트"))

        // Out of range / not a number → error shown, save disabled.
        field.performTextReplacement("12")
        shot("session-settings-context-invalid")
        compose.onNodeWithText("저장").assertIsNotEnabled()
        field.performTextReplacement("abc")
        compose.onNodeWithText("저장").assertIsNotEnabled()

        field.performTextReplacement("50000")
        compose.onNodeWithText("저장").assertIsEnabled().performClick()
        compose.waitForIdle()
        check(HoardRepository.get().sessionOf("session-welcome")!!.contextLimit == 50_000)
        compose.onNodeWithText("/50000 토큰", substring = true).assertExists()
    }

    @Test fun modelPicker() {
        compose.onAllNodesWithText("Hoard에 오신 것을 환영합니다").onFirst().performClick()
        shot("model-picker")
        compose.onNodeWithText("Hoard 1 울트라").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("hoard-1-ultra", substring = true).assertExists()
    }

    @Test fun messageMenuThenEditAndDelete() {
        compose.onNodeWithText("안녕하세요, Hoard입니다", substring = true).performTouchInput { longClick() }
        shot("message-menu")
        compose.onNodeWithText("삭제").performClick()
        shot("delete-confirm")
        compose.activity.onBackPressedDispatcher.onBackPressed()
        assertClosed("메시지를 삭제할까요?")
    }
}

@Config(qualifiers = "+night")
class PopupScreenshotDarkTest : PopupScreenshotTest() {
    override val theme = "dark"
}
