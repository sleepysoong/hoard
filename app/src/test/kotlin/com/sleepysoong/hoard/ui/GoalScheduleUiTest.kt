package com.sleepysoong.hoard.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.MainActivity
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.GoalStatus
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.goal.Actor
import com.sleepysoong.hoard.goal.GoalService
import com.sleepysoong.hoard.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Goal bar + sheet (user controls) and the trigger notice. Screenshots: build/test-artifacts/goal/. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class GoalScheduleUiTest {
    @get:Rule(order = 0) val reset = object : org.junit.rules.ExternalResource() {
        override fun before() {
            HoardRepository.resetForTests(); TestData.seed(); TestData.useMockEngine()
            val repo = HoardRepository.get()
            repo.appendMessage(TestData.SESSION_ID, ChatMessage("m1", MessageRole.User, "auth 테스트 전부 통과", trigger = "goal"))
            repo.appendMessage(TestData.SESSION_ID, ChatMessage("m2", MessageRole.Assistant, "테스트 3개가 실패해서 고치는 중이에요."))
            GoalService(repo).create(TestData.SESSION_ID, "auth 테스트 전부 통과", Actor.User, verification = "gradle test")
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val outDir = File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts", "goal").apply { mkdirs() }
    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(1_200); compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun goalBarSheetAndTriggerNotice() {
        compose.onNodeWithText(TestData.SESSION_NAME).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("goal-bar", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("목표 진행 중 · 0/8턴", useUnmergedTree = true).assertExists()
        assertEquals(1, compose.onAllNodesWithTag("trigger-notice", useUnmergedTree = true).fetchSemanticsNodes().size)
        shot("chat-goal-bar")
        compose.onNodeWithTag("goal-bar", useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("gradle test", useUnmergedTree = true).assertExists()
        shot("goal-sheet")
        compose.onNodeWithTag("goal-pause", useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        assertEquals(GoalStatus.Paused, GoalService(HoardRepository.get()).current(TestData.SESSION_ID)!!.status)
        compose.onNodeWithText("목표 일시정지", useUnmergedTree = true).assertExists()
    }
}
