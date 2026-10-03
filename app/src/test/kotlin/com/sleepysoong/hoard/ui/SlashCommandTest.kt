package com.sleepysoong.hoard.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.MainActivity
import com.sleepysoong.hoard.data.GoalStatus
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.goal.Actor
import com.sleepysoong.hoard.goal.GoalService
import com.sleepysoong.hoard.testing.TestData
import com.sleepysoong.hoard.ui.chat.SlashCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** "/" in the composer offers the /goal commands that apply right now; tapping one fills the field. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SlashCommandTest {
    @get:Rule(order = 0) val reset = object : org.junit.rules.ExternalResource() {
        override fun before() {
            HoardRepository.resetForTests(); TestData.seed(); TestData.useMockEngine()
            com.sleepysoong.hoard.engine.MockAiEngine.pace = 0f
            val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
            androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
                app, androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor())
                    .setTaskExecutor(androidx.work.testing.SynchronousExecutor()).build()
            )
        }
        override fun after() { com.sleepysoong.hoard.engine.MockAiEngine.pace = 1f }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun items() = compose.onAllNodesWithTag("slash-item", useUnmergedTree = true).fetchSemanticsNodes().size
    private val goals get() = GoalService(HoardRepository.get())

    @Test fun matchingFollowsTheTextAndTheGoalState() {
        assertEquals(listOf("/goal <목표>", "/goal", "/compact", "/compact <지시>"), SlashCommands.matching("/", null).map { it.label })
        assertTrue("typing an objective hides the menu", SlashCommands.matching("/goal 테스트 통과", null).isEmpty())
        assertTrue("not a command", SlashCommands.matching("안녕 /goal", null).isEmpty())
        goals.create(TestData.SESSION_ID, "목표", Actor.User)
        val active = goals.current(TestData.SESSION_ID)
        assertEquals(listOf("/goal <목표>", "/goal", "/goal pause", "/goal clear"), SlashCommands.matching("/goal", active).map { it.label })
        assertEquals(listOf("/goal pause"), SlashCommands.matching("/goal p", active).map { it.label })
        assertTrue("finished command: no menu", SlashCommands.matching("/goal pause", active).isEmpty())
        goals.pause(TestData.SESSION_ID, Actor.User)
        assertEquals(
            listOf("/goal <목표>", "/goal", "/goal resume", "/goal clear", "/compact", "/compact <지시>"),
            SlashCommands.matching("/", goals.current(TestData.SESSION_ID)).map { it.label }
        )
    }

    @Test fun typingSlashShowsTheMenuAndTappingRunsTheCommand() {
        compose.onNodeWithText(TestData.SESSION_NAME).performClick()
        compose.waitForIdle()
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("/")
        compose.waitForIdle()
        compose.onNode(hasTestTag("slash-menu")).assertExists()
        assertEquals(4, items())
        // Pick "/goal <목표>", type the objective, send.
        compose.onNodeWithText("/goal <목표>", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        field.performTextInput("문서 정리")
        compose.waitForIdle()
        assertEquals("menu hides while the objective is typed", 0, items())
        compose.onNodeWithContentDescription("보내기").performClick()
        compose.waitForIdle()
        assertEquals("the menu set the goal", "문서 정리", goals.current(TestData.SESSION_ID)!!.objective)
        // An active goal now keeps working; stop through the real UI, not the retired idle guard.
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("답변 중지")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("답변 중지").performClick()
        assertEquals(GoalStatus.Paused, goals.current(TestData.SESSION_ID)!!.status)
        val deadline = System.currentTimeMillis() + 15_000
        while (compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("보내기")).fetchSemanticsNodes().isEmpty()) {
            compose.mainClock.advanceTimeBy(200); compose.waitForIdle(); Thread.sleep(20)
            if (System.currentTimeMillis() >= deadline) {
                val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
                val wm = androidx.work.WorkManager.getInstance(app)
                val states = wm.getWorkInfos(androidx.work.WorkQuery.fromStates(*androidx.work.WorkInfo.State.entries.toTypedArray())).get()
                    .map { "${it.state}:${it.tags.filter { t -> t.startsWith("hoard") }}" }
                val msgs = com.sleepysoong.hoard.data.HoardRepository.get().messagesOf(TestData.SESSION_ID)
                    .map { "${it.role}:${it.text.take(30)}:streaming=${it.isStreaming}:err=${it.errorText}" }
                check(false) { "replies never finished; work=$states msgs=$msgs" }
            }
        }
        // A goal exists now, so "clear" is offered; pick it and send.
        field.performTextReplacement("/goal c")
        compose.waitForIdle()
        compose.onNodeWithText("/goal clear", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("보내기").performClick()
        compose.waitForIdle()
        assertEquals(null, goals.current(TestData.SESSION_ID))
        assertEquals(GoalStatus.Cleared, goals.history(TestData.SESSION_ID).last().status)
    }
}
