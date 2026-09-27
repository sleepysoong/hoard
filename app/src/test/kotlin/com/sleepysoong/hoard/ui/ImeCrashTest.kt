package com.sleepysoong.hoard.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.MainActivity
import com.sleepysoong.hoard.data.HoardRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression: tapping a text field while the tab bar is visible (설정 → 라우터 주소) crashed the app ~1 s later.
 * The keyboard makes the bottom reserve spring from 108.dp to 0.dp; the spring's overshoot went negative
 * and Modifier.padding threw "Padding must be non-negative". Replays the keyboard insets frame by frame.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ImeCrashTest {
    @get:Rule(order = 0) val reset = object : org.junit.rules.ExternalResource() {
        override fun before() = HoardRepository.resetForTests()
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun keyboard(heightPx: Int) = compose.runOnUiThread {
        val root = compose.activity.window.decorView
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, heightPx))
            .setVisible(WindowInsetsCompat.Type.ime(), heightPx > 0)
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 60))
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 80, 0, 0))
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
    }

    @Test fun tapRouterFieldThenKeyboardOpens() {
        compose.onNodeWithTag("tab-설정").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("라우터 주소")).performClick()
        // Keyboard slides in over ~300 ms, then stays.
        for (h in listOf(200, 500, 800, 900)) { keyboard(h); compose.mainClock.advanceTimeBy(80) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        keyboard(0)
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    @Test fun mcpAddPopupFieldWithKeyboard() {
        compose.onNodeWithTag("tab-도구").performClick()
        compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasText("MCP 서버 추가 (목업)")).performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("URL")).performClick()
        for (h in listOf(200, 500, 800, 900)) { keyboard(h); compose.mainClock.advanceTimeBy(80) }
        compose.mainClock.advanceTimeBy(2_000)
        keyboard(0)
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }
}
