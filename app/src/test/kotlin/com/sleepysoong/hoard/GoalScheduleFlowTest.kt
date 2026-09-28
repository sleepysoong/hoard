package com.sleepysoong.hoard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import com.sleepysoong.hoard.data.GoalStatus
import com.sleepysoong.hoard.data.RunStatus
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.goal.GoalBudget
import com.sleepysoong.hoard.goal.GoalRuntime
import com.sleepysoong.hoard.goal.GoalService
import com.sleepysoong.hoard.schedule.WorkManagerScheduler
import com.sleepysoong.hoard.testing.ChatHarness
import com.sleepysoong.hoard.testing.FakeRouter
import com.sleepysoong.hoard.testing.FakeRouter.Companion.completed
import com.sleepysoong.hoard.testing.FakeRouter.Companion.created
import com.sleepysoong.hoard.testing.FakeRouter.Companion.routingFrame
import com.sleepysoong.hoard.testing.FakeRouter.Companion.toolCallCompleted
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Goal continuation, wakeup and scheduled runs through the whole app:
 * ViewModel → WorkManager → worker → (fake) router → tools → repository.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class GoalScheduleFlowTest {
    @get:Rule val name = TestName()
    private lateinit var h: ChatHarness
    private lateinit var router: FakeRouter

    private fun start() {
        h = ChatHarness()
        router = FakeRouter()
        runBlocking { SettingsStore.setRouterUrl(h.app, router.url) }
        val deadline = System.currentTimeMillis() + 5_000
        while (h.vm.settings.value.routerUrl != router.url) { h.idle(); Thread.sleep(5); check(System.currentTimeMillis() < deadline) }
    }

    @After fun tearDown() {
        GoalService.defaultBudget = GoalBudget()
        if (::router.isInitialized) router.close()
        if (::h.isInitialized) {
            router.requests.forEachIndexed { i, r -> h.note("router request #$i ${r.body.take(1200)}") }
            h.writeTranscript("GoalScheduleFlowTest.${name.methodName}")
        }
    }

    private fun bodies(): List<JsonObject> = router.requests.filter { it.path.endsWith("/responses") }.map { Json.parseToJsonElement(it.body).jsonObject }
    private fun JsonObject.inputTexts(): List<String> = this["input"]!!.jsonArray.map { it.jsonObject }
        .filter { it["type"]?.jsonPrimitive?.content == "message" }
        .map { m -> m["content"]!!.jsonArray.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() } }
    private fun JsonObject.tools(): List<String> = this["tools"]?.jsonArray?.map { it.jsonObject["name"]!!.jsonPrimitive.content }.orEmpty()
    private val sid get() = h.vm.uiState.value.session!!.id
    private fun sse(vararg frames: Pair<String, String>) = FakeRouter.Reply.Sse(listOf(routingFrame(), created()) + frames)

    @Test fun modelGoalContinuesOnItsOwnUntilCompletedWithEvidence() {
        start()
        router.enqueue(
            // Turn 1 (user): the model sets a goal, then says so.
            sse(toolCallCompleted(Triple("g1", "goal", """{"action":"create","objective":"auth 테스트 전부 통과","verification":"gradle test"}"""))),
            sse(completed("목표를 세웠어요. 시작할게요.")),
            // Turn 2 (automatic): progress with a tool call.
            sse(toolCallCompleted(Triple("g2", "goal", """{"action":"get"}"""))),
            sse(completed("테스트 3개가 아직 실패해요. 고치는 중.")),
            // Turn 3 (automatic): verified → complete with evidence.
            sse(toolCallCompleted(Triple("g3", "goal", """{"action":"complete","evidence":"gradle test: 47 tests, 0 failures (auth suite)"}"""))),
            sse(completed("모든 테스트가 통과했어요."))
        )
        h.vm.send("auth 테스트 다 통과시켜줘", emptyList(), "coding")
        h.awaitReplies(timeoutMs = 30_000)

        val b = bodies()
        assertEquals("1 user turn + 2 automatic turns, 2 requests each, then it stops", 6, b.size)
        assertTrue("goal tool offered", "goal" in b[0].tools())
        assertTrue(b[0]["instructions"]!!.jsonPrimitive.content.contains("Goals:"))
        assertFalse("no goal yet in turn 1", b[0]["instructions"]!!.jsonPrimitive.content.contains("<active_goal>"))
        // Continuations: goal context in the developer instructions + a hidden runtime prompt last.
        for (i in listOf(2, 4)) {
            assertTrue(b[i]["instructions"]!!.jsonPrimitive.content.contains("<objective>\nauth 테스트 전부 통과\n</objective>"))
            assertEquals(GoalRuntime.CONTINUE_MESSAGE, b[i].inputTexts().last())
        }
        val g = GoalService(h.repo).current(sid)!!
        assertEquals(GoalStatus.Completed, g.status)
        assertEquals("gradle test: 47 tests, 0 failures (auth suite)", g.evidence)
        assertEquals(2, g.usedTurns)
        // The hidden prompts are never stored in the conversation.
        val msgs = h.messages()
        assertTrue(msgs.none { it.text.contains("Automatic continuation") })
        assertEquals(listOf("auth 테스트 다 통과시켜줘", "목표를 세웠어요. 시작할게요.", "테스트 3개가 아직 실패해요. 고치는 중.", "모든 테스트가 통과했어요."), msgs.map { it.text })
    }

    @Test fun continuationThatDoesNothingIsSuppressed() {
        start()
        router.enqueue(sse(completed("알겠습니다.")), sse(completed("음… 계속 생각 중입니다.")))
        h.vm.send("/goal 문서 요약 파일 만들기", emptyList(), "coding")
        h.awaitReplies(timeoutMs = 20_000)
        assertEquals("user turn + one idle continuation, then suppressed", 2, bodies().size)
        val g = GoalService(h.repo).current(sid)!!
        assertEquals(GoalStatus.Active, g.status)
        assertTrue(g.continuationSuppressed)
        assertEquals("the /goal objective became a labelled turn", "Goal: 문서 요약 파일 만들기", bodies()[0].inputTexts().last())
        assertEquals("goal", h.messages().first().trigger)
        // The user's next message lifts the suppression.
        router.enqueue(sse(completed("네")), sse(completed("또 아무것도 안 함")))
        h.vm.send("계속해", emptyList(), "coding")
        h.awaitReplies(timeoutMs = 20_000)
        assertEquals(4, bodies().size)
    }

    @Test fun budgetEndsWithASummaryTurnNotCompletion() {
        GoalService.defaultBudget = GoalBudget(maxAutoTurns = 2)
        start()
        repeat(3) {
            router.enqueue(sse(toolCallCompleted(Triple("t$it", "goal", """{"action":"get"}"""))), sse(completed("작업 $it")))
        }
        router.enqueue(sse(completed("요약: 진행 2/3, 남은 일 있음")))
        h.vm.send("/goal 큰 작업", emptyList(), "coding")
        h.awaitReplies(timeoutMs = 30_000)
        val b = bodies()
        assertEquals("user turn + 2 automatic turns (2 requests each) + 1 summary", 7, b.size)
        assertEquals("none", b.last()["tool_choice"]!!.jsonPrimitive.content)
        assertEquals(GoalRuntime.BUDGET_SUMMARY_MESSAGE, b.last().inputTexts().last())
        assertEquals(GoalStatus.BudgetLimited, GoalService(h.repo).current(sid)!!.status)
        assertEquals("요약: 진행 2/3, 남은 일 있음", h.messages().last().text)
    }

    @Test fun userControlsPauseResumeClear() {
        start()
        router.enqueue(sse(completed("시작")), sse(completed("쉬는 중")))
        h.vm.send("/goal 긴 작업", emptyList(), "coding")
        h.awaitReplies(timeoutMs = 20_000)
        h.vm.goalCommand("pause", "coding")
        assertEquals(GoalStatus.Paused, GoalService(h.repo).current(sid)!!.status)
        h.vm.send("/goal 다른 목표", emptyList(), "coding")
        assertTrue("one open goal: explained, not replaced", h.vm.goalNotice.value!!.contains("already has"))
        router.enqueue(sse(toolCallCompleted(Triple("c", "goal", """{"action":"complete","evidence":"file written: summary.md (2 KB)"}"""))), sse(completed("끝")))
        h.vm.goalCommand("resume", "coding")
        h.awaitReplies(timeoutMs = 20_000)
        assertEquals(GoalStatus.Completed, GoalService(h.repo).current(sid)!!.status)
        assertEquals(GoalRuntime.CONTINUE_MESSAGE, bodies().let { it[it.size - 2] }.inputTexts().last())
        h.vm.goalCommand("clear", "coding")
        assertNull(h.vm.uiState.value.goal)
        // Stopping a reply pauses an active goal.
        GoalService(h.repo).create(sid, "새 목표", com.sleepysoong.hoard.goal.Actor.User)
        h.vm.stopReply()
        assertEquals(GoalStatus.Paused, GoalService(h.repo).current(sid)!!.status)
    }

    @Test fun wakeupWakesTheSessionLaterInsteadOfPolling() {
        start()
        router.enqueue(
            sse(toolCallCompleted(Triple("w", "schedule_wakeup", """{"delay_ms":60000,"prompt":"배포 상태 다시 확인"}"""))),
            sse(completed("1분 뒤에 다시 볼게요."))
        )
        h.vm.send("배포 끝나면 알려줘", emptyList(), "coding")
        h.awaitReplies()
        val armed = h.workManager.getWorkInfos(WorkQuery.Builder.fromTags(listOf("hoard-wakeup")).addStates(listOf(WorkInfo.State.ENQUEUED)).build()).get()
        assertEquals(1, armed.size)
        assertEquals(2, bodies().size)
        router.enqueue(sse(completed("배포가 끝났어요.")))
        h.fireDelayed("hoard-wakeup")
        h.awaitReplies()
        val msgs = h.messages()
        assertEquals("wakeup", msgs[msgs.size - 2].trigger)
        assertEquals("배포 상태 다시 확인", msgs[msgs.size - 2].text)
        assertEquals("배포가 끝났어요.", msgs.last().text)
        assertEquals("[Wakeup you scheduled] 배포 상태 다시 확인", bodies().last().inputTexts().last())
    }

    @Test fun scheduledRunIsIsolatedAndCannotScheduleMore() {
        start()
        router.enqueue(
            sse(toolCallCompleted(Triple("s", "schedule", """{"action":"create","name":"의존성 점검","prompt":"오래된 의존성을 찾아 보고해","trigger_type":"cron","cron":"0 9 * * *","timezone":"Asia/Seoul"}"""))),
            sse(completed("매일 오전 9시에 점검하도록 예약했어요."))
        )
        h.vm.send("매일 아침 9시에 의존성 점검해줘", emptyList(), "coding")
        h.awaitReplies()
        val s = h.repo.schedules.value.single()
        assertEquals("cron 0 9 * * * · Asia/Seoul", com.sleepysoong.hoard.schedule.ScheduleService.describe(s.trigger))
        val z = java.time.Instant.ofEpochMilli(s.nextRunAt!!).atZone(java.time.ZoneId.of("Asia/Seoul"))
        assertEquals(9, z.hour); assertEquals(0, z.minute)
        assertEquals("armed in WorkManager", 1, h.workManager.getWorkInfosForUniqueWork(WorkManagerScheduler.fireName(s.id)).get().count { it.state == WorkInfo.State.ENQUEUED })
        val creatorBefore = h.messages()

        router.enqueue(sse(completed("오래된 의존성 3개: a, b, c")))
        h.fireDelayed("hoard-schedule")
        h.awaitReplies()
        val run = h.repo.scheduleRuns.value.single()
        assertEquals(RunStatus.Succeeded, run.status)
        assertEquals("오래된 의존성 3개: a, b, c", run.outputSummary)
        val runMsgs = h.repo.messagesOf(run.runSessionId!!)
        assertEquals(listOf("오래된 의존성을 찾아 보고해", "오래된 의존성 3개: a, b, c"), runMsgs.map { it.text })
        assertEquals("creator session untouched", creatorBefore.map { it.id }, h.messages().map { it.id })
        val runReq = bodies().last()
        assertEquals("[Scheduled run] 오래된 의존성을 찾아 보고해", runReq.inputTexts().single())
        assertTrue("run history is not shared", runReq.inputTexts().none { it.contains("매일 아침") })
        assertFalse("an unattended run can't create more future work", runReq.tools().any { it == "schedule" || it == "schedule_wakeup" })
        assertTrue(runReq.tools().contains("goal"))
        assertTrue("next day re-armed", h.repo.schedules.value.single().nextRunAt!! > s.nextRunAt!!)
    }
}
