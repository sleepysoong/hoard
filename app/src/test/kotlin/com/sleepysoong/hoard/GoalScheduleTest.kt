package com.sleepysoong.hoard

import com.sleepysoong.hoard.data.ChatSession
import com.sleepysoong.hoard.data.CatchUpPolicy
import com.sleepysoong.hoard.data.GoalStatus
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.OverlapPolicy
import com.sleepysoong.hoard.data.PermissionProfile
import com.sleepysoong.hoard.data.RunStatus
import com.sleepysoong.hoard.data.ScheduleStatus
import com.sleepysoong.hoard.data.ScheduleTrigger
import com.sleepysoong.hoard.goal.Actor
import com.sleepysoong.hoard.goal.ContinuationDecision
import com.sleepysoong.hoard.goal.ContinuationEvaluator
import com.sleepysoong.hoard.goal.GoalBudget
import com.sleepysoong.hoard.goal.GoalException
import com.sleepysoong.hoard.goal.GoalService
import com.sleepysoong.hoard.goal.SessionActivity
import com.sleepysoong.hoard.goal.TurnOutcome
import com.sleepysoong.hoard.schedule.AgentRunner
import com.sleepysoong.hoard.schedule.CreateScheduleInput
import com.sleepysoong.hoard.schedule.CronExpression
import com.sleepysoong.hoard.schedule.ScheduleException
import com.sleepysoong.hoard.schedule.ScheduleService
import com.sleepysoong.hoard.schedule.SchedulerBackend
import com.sleepysoong.hoard.schedule.SchedulerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Goal lifecycle/authority/continuation rules, cron math and the scheduler engine (fake clock).
 * Robolectric only because the repository opens the todo SQLite database.
 */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@org.robolectric.annotation.Config(sdk = [35])
class GoalScheduleTest {
    private var now = ZonedDateTime.of(2026, 9, 28, 19, 41, 0, 0, SEOUL).toInstant().toEpochMilli()
    private val repo = HoardRepository(null).apply {
        addSession(ChatSession("s1", "작업", "sys", "coding", 32_000), emptyList())
    }
    private val goals = GoalService(repo, { now }, GoalBudget(maxAutoTurns = 3, maxTokens = 1_000, maxWallTimeMs = 60_000))

    private inline fun <reified E : Throwable> fails(block: () -> Unit): E {
        try { block() } catch (e: Throwable) { if (e is E) return e; throw e }
        fail("expected ${E::class.simpleName}"); throw IllegalStateException()
    }

    // ---------------------------------------------------------------- goal

    @Test fun lifecycleAndAuthority() {
        val g = goals.create("s1", "로그인 통합 테스트 전부 통과", Actor.Model, verification = "auth integration suite")
        assertEquals(GoalStatus.Active, g.status); assertEquals("model", g.createdBy)
        assertTrue("one open goal per session", fails<GoalException> { goals.create("s1", "또", Actor.Model) }.message!!.contains("already has"))
        // The model can't pause / resume / clear.
        listOf<() -> Unit>({ goals.pause("s1", Actor.Model) }, { goals.clear("s1", Actor.Model) }, { goals.resume("s1", Actor.Model) })
            .forEach { assertTrue(fails<GoalException>(it).message!!.contains("only the user")) }
        // Completion needs evidence.
        assertTrue(fails<GoalException> { goals.complete("s1", "done", Actor.Model) }.message!!.contains("evidence"))
        goals.pause("s1", Actor.User)
        assertEquals(GoalStatus.Paused, goals.current("s1")!!.status)
        assertTrue("paused goals can't complete", fails<GoalException> { goals.complete("s1", "47 tests pass in auth suite", Actor.Model) }.message!!.contains("paused"))
        goals.resume("s1", Actor.User)
        val done = goals.complete("s1", "All 47 auth integration tests pass (gradle test output).", Actor.Model)
        assertEquals(GoalStatus.Completed, done.status); assertTrue(done.completedAt != null)
        // A completed goal no longer blocks a new one; old goals stay as history.
        goals.create("s1", "다음 목표", Actor.User)
        goals.clear("s1", Actor.User)
        assertNull("cleared = no current goal", goals.current("s1")?.takeIf { it.objective == "다음 목표" })
        assertEquals(listOf(GoalStatus.Completed, GoalStatus.Cleared), goals.history("s1").map { it.status })
    }

    @Test fun blockAndResumeFromBlocked() {
        goals.create("s1", "배포 확인", Actor.Model)
        goals.block("s1", "fixture unavailable", Actor.Model)
        assertEquals("fixture unavailable", goals.current("s1")!!.blockedReason)
        assertEquals(GoalStatus.Active, goals.resume("s1", Actor.User).status)
        assertNull(goals.current("s1")!!.blockedReason)
    }

    @Test fun budgetAndContinuationDecisions() {
        goals.create("s1", "목표", Actor.Model)
        val ev = ContinuationEvaluator(goals)
        val idle = SessionActivity(workQueued = false, wakeupPending = false)
        val progressed = TurnOutcome(succeeded = true, automatic = true, toolCalls = 1, goalChanged = false)
        assertEquals(ContinuationDecision.Continue, ev.decide("s1", progressed, idle))
        assertTrue(ev.decide("s1", progressed, idle.copy(workQueued = true)) is ContinuationDecision.Stop)
        assertTrue(ev.decide("s1", progressed, idle.copy(wakeupPending = true)) is ContinuationDecision.Stop)
        assertTrue(ev.decide("s1", progressed.copy(succeeded = false), idle) is ContinuationDecision.Stop)
        // Spin prevention: an automatic turn with no tool call and no goal change.
        assertEquals(ContinuationDecision.Suppress, ev.decide("s1", progressed.copy(toolCalls = 0), idle))
        goals.suppressContinuation("s1", true)
        assertTrue(ev.decide("s1", progressed, idle) is ContinuationDecision.Stop)
        goals.suppressContinuation("s1", false)
        // Budget: automatic turns, tokens, wall time.
        repeat(3) { goals.recordTurn("s1", 10, automatic = true) }
        assertTrue(ev.decide("s1", progressed, idle) is ContinuationDecision.BudgetLimited)
        goals.markBudgetLimited("s1")
        assertEquals(GoalStatus.BudgetLimited, goals.current("s1")!!.status)
        assertTrue("budget_limited is not completed", ev.decide("s1", progressed, idle) is ContinuationDecision.Stop)
        // Resume grants a fresh budget.
        goals.resume("s1", Actor.User)
        assertEquals(0, goals.current("s1")!!.usedTurns)
        goals.recordTurn("s1", 5_000, automatic = false)
        assertTrue("tokens", ev.decide("s1", progressed, idle) is ContinuationDecision.BudgetLimited)
    }

    @Test fun wallTimeBudget() {
        goals.create("s1", "목표", Actor.Model)
        now += 61_000
        assertEquals("1 minutes", goals.budgetExceeded(goals.current("s1")!!))
    }

    @Test fun forkSnapshotsTheOpenGoal() {
        repo.addSession(ChatSession("s2", "브랜치", "sys", "coding", 32_000), emptyList())
        val g = goals.create("s1", "원래 목표", Actor.User)
        val copy = goals.forkInto("s1", "s2")!!
        assertEquals(g.id, copy.parentGoalId)
        goals.complete("s2", "branch evidence: tests pass", Actor.Model)
        assertEquals("independent after the fork", GoalStatus.Active, goals.current("s1")!!.status)
    }

    // ---------------------------------------------------------------- cron

    @Test fun cronParsesAndFindsNextRuns() {
        val weekdays9 = CronExpression.parse("0 9 * * 1-5")
        val fri = ZonedDateTime.of(2026, 10, 2, 10, 0, 0, 0, SEOUL).toInstant() // Friday after 9
        assertEquals(ZonedDateTime.of(2026, 10, 5, 9, 0, 0, 0, SEOUL).toInstant(), weekdays9.next(fri, SEOUL)) // Monday
        assertEquals(Instant.parse("2026-09-28T10:45:00Z"), CronExpression.parse("*/15 * * * *").next(Instant.parse("2026-09-28T10:31:10Z"), ZoneId.of("UTC")))
        // day-of-month OR day-of-week when both are restricted (Vixie cron).
        val c = CronExpression.parse("0 0 13 * 5")
        assertEquals(ZonedDateTime.of(2026, 10, 2, 0, 0, 0, 0, SEOUL).toInstant(), c.next(ZonedDateTime.of(2026, 9, 28, 0, 0, 0, 0, SEOUL).toInstant(), SEOUL)) // Friday before the 13th
        assertEquals("7 = Sunday", ZonedDateTime.of(2026, 10, 4, 12, 0, 0, 0, SEOUL).toInstant(), CronExpression.parse("0 12 * * 7").next(fri, SEOUL))
        // DST: 02:30 doesn't exist in New York on 2027-03-14 → next day.
        val ny = ZoneId.of("America/New_York")
        val nxt = CronExpression.parse("30 2 * * *").next(ZonedDateTime.of(2027, 3, 14, 0, 0, 0, 0, ny).toInstant(), ny)!!
        assertEquals(15, nxt.atZone(ny).dayOfMonth)
        for (bad in listOf("* * * *", "0 9 * * * *", "60 * * * *", "0 9 * * MON", "5-1 * * * *", "*/0 * * * *")) {
            assertTrue(bad, runCatching { CronExpression.parse(bad) }.isFailure)
        }
    }

    // ---------------------------------------------------------------- scheduler

    private class FakeBackend : SchedulerBackend, AgentRunner {
        val armed = mutableMapOf<String, Pair<Long, Long>>()
        val started = mutableListOf<Triple<String, String, String?>>()
        override fun arm(scheduleId: String, plannedAt: Long, delayMs: Long) { armed[scheduleId] = plannedAt to delayMs }
        override fun disarm(scheduleId: String) { armed.remove(scheduleId) }
        override fun startReply(sessionId: String, modelId: String, parentMessageId: String, scheduleRunId: String?) { started += Triple(sessionId, parentMessageId, scheduleRunId) }
    }

    private val backend = FakeBackend()
    private val service = ScheduleService(repo, backend) { now }
    private val engine = SchedulerEngine(repo, service, backend, backend) { now }
    private val perms = PermissionProfile(web = true, files = false, fullStorage = false, termux = false)

    private fun create(trigger: ScheduleTrigger, catchUp: CatchUpPolicy? = null, overlap: OverlapPolicy = OverlapPolicy.Skip, maxRuns: Int? = null) =
        service.create(CreateScheduleInput("점검", "의존성 점검해", trigger, "coding", "s1", perms, catchUp, overlap, maxRuns))

    @Test fun createValidatesAndArms() {
        val at = ZonedDateTime.of(2026, 9, 29, 9, 0, 0, 0, SEOUL).toInstant().toEpochMilli()
        val s = create(ScheduleTrigger.At(at))
        assertEquals(at, s.nextRunAt); assertEquals(CatchUpPolicy.Latest, s.catchUp)
        assertEquals(at to at - now, backend.armed[s.id])
        assertTrue(fails<ScheduleException> { create(ScheduleTrigger.At(now - 1)) }.message!!.contains("past"))
        assertTrue(fails<ScheduleException> { create(ScheduleTrigger.Every(60_000, now)) }.message!!.contains("at least"))
        assertTrue(fails<ScheduleException> { create(ScheduleTrigger.Cron("* * * * *", "Asia/Seoul")) }.message!!.contains("more often"))
        assertTrue(fails<ScheduleException> { create(ScheduleTrigger.Cron("0 9 * * 1", "Mars/Olympus")) }.message!!.contains("timezone"))
        assertTrue(fails<ScheduleException> { create(ScheduleTrigger.At(at)) }.message!!.contains("identical"))
        assertEquals(CatchUpPolicy.Skip, create(ScheduleTrigger.Cron("0 9 * * 1", "Asia/Seoul")).catchUp)
    }

    @Test fun firingCreatesAnIsolatedRunOnceAndArmsTheNext() {
        val s = create(ScheduleTrigger.Cron("0 9 * * *", "Asia/Seoul"))
        val planned = s.nextRunAt!!
        now = planned + 30_000
        engine.fire(s.id, planned)
        engine.fire(s.id, planned) // duplicate firing
        val runs = service.runs(s.id)
        assertEquals("idempotent per planned_at", 1, runs.size)
        val run = runs.single()
        assertEquals(RunStatus.Queued, run.status)
        val session = repo.sessionOf(run.runSessionId!!)!!
        assertEquals(s.id, session.scheduleId)
        assertEquals("prompt only, as a schedule trigger", listOf("의존성 점검해"), repo.messagesOf(session.id).map { it.text })
        assertEquals("schedule", repo.messagesOf(session.id).single().trigger)
        assertTrue("creator session untouched", repo.messagesOf("s1").isEmpty())
        assertEquals(Triple(session.id, repo.messagesOf(session.id).single().id, run.id), backend.started.single())
        assertTrue("run session usable by the todo tool", repo.todos.list(session.id).isEmpty())
        assertEquals("next day 09:00", planned + 86_400_000L, repo.schedules.value.single().nextRunAt)
        assertEquals(planned + 86_400_000L, backend.armed[s.id]!!.first)
        engine.onRunStarted(run.id); engine.onRunFinished(run.id, RunStatus.Succeeded, summary = "3 outdated")
        assertEquals(RunStatus.Succeeded, service.runs(s.id).single().status)
    }

    @Test fun overlapSkipQueueAndIntervalWithoutDrift() {
        val every = create(ScheduleTrigger.Every(3_600_000, now + 3_600_000))
        val p1 = every.nextRunAt!!
        now = p1 + 7 * 60_000 // run started late…
        engine.fire(every.id, p1)
        assertEquals("next = planned + interval, not finish + interval", p1 + 3_600_000, repo.schedules.value.first { it.id == every.id }.nextRunAt)
        // previous run still in progress at the next firing: skipped (default)
        now = p1 + 3_600_000
        engine.fire(every.id, p1 + 3_600_000)
        assertEquals(listOf(RunStatus.Skipped, RunStatus.Queued), service.runs(every.id).map { it.status })
        // queue policy: re-arms the same firing instead of skipping
        val q = service.create(CreateScheduleInput("큐", "다른 일", ScheduleTrigger.Every(3_600_000, now + 60_000), "coding", "s1", perms, overlap = OverlapPolicy.Queue))
        now = q.nextRunAt!!
        engine.fire(q.id, q.nextRunAt!!)
        val qrun = service.runs(q.id).single()
        now += 3_600_000
        engine.fire(q.id, q.nextRunAt!! + 3_600_000)
        assertEquals(q.nextRunAt!! + 3_600_000 to SchedulerEngine.QUEUE_RETRY_MS, backend.armed[q.id])
        assertEquals(1, service.runs(q.id).size)
        engine.onRunFinished(qrun.id, RunStatus.Succeeded)
    }

    @Test fun missedRunsFollowTheCatchUpPolicy() {
        val skip = create(ScheduleTrigger.Cron("0 9 * * *", "Asia/Seoul"))
        val latest = service.create(CreateScheduleInput("최신", "최신만", ScheduleTrigger.Cron("0 9 * * *", "Asia/Seoul"), "coding", "s1", perms, CatchUpPolicy.Latest))
        val first = skip.nextRunAt!!
        now = first + 3 * 86_400_000L + 3_600_000 // phone off for 3 days
        engine.reconcile()
        assertEquals("skip: nothing runs, marked skipped", listOf(RunStatus.Skipped), service.runs(skip.id).map { it.status })
        val lrun = service.runs(latest.id)
        assertEquals("latest: exactly one run, for the most recent occurrence", 1, lrun.size)
        assertEquals(first + 3 * 86_400_000L, lrun.single().plannedAt)
        assertTrue("next is in the future", repo.schedules.value.all { (it.nextRunAt ?: Long.MAX_VALUE) > now })
    }

    @Test fun maxRunsPauseResumeCancelAndStaleRecovery() {
        val s = create(ScheduleTrigger.Every(3_600_000, now + 3_600_000), maxRuns = 1)
        now = s.nextRunAt!!
        engine.fire(s.id, s.nextRunAt!!)
        assertEquals(ScheduleStatus.Finished, repo.schedules.value.single().status)
        assertNull(backend.armed[s.id])

        val c = create(ScheduleTrigger.Cron("0 9 * * *", "Asia/Seoul"))
        service.pause(c.id); assertNull(backend.armed[c.id])
        now += 5 * 86_400_000L
        val resumed = service.resume(c.id)
        assertTrue("resumes from now: missed ones aren't run", resumed.nextRunAt!! > now && service.runs(c.id).isEmpty())
        service.cancel(c.id)
        assertEquals(ScheduleStatus.Cancelled, repo.schedules.value.first { it.id == c.id }.status)
        assertFalse(service.list().any { it.id == c.id })

        // A run left "running" by a killed process is failed as stale on the next start.
        val run = service.runs(s.id).single()
        engine.onRunStarted(run.id)
        now += SchedulerEngine.STALE_MS + 1
        engine.reconcile()
        assertEquals(RunStatus.Failed, service.runs(s.id).single().status)
        assertTrue(service.runs(s.id).single().error!!.contains("stale"))
    }

    companion object {
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
