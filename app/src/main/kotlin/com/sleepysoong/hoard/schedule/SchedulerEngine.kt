package com.sleepysoong.hoard.schedule

import com.sleepysoong.hoard.data.CatchUpPolicy
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.ChatSession
import com.sleepysoong.hoard.data.ContextMode
import com.sleepysoong.hoard.data.Defaults
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.OverlapPolicy
import com.sleepysoong.hoard.data.RunStatus
import com.sleepysoong.hoard.data.Schedule
import com.sleepysoong.hoard.data.ScheduleRun
import com.sleepysoong.hoard.data.ScheduleStatus
import com.sleepysoong.hoard.data.ScheduleTrigger
import java.util.UUID

/**
 * Turns due firings into isolated runs. Knows *when* and *whether* to run — never
 * what the AI does. Every firing:
 *
 *   claim (schedule_id, planned_at) — unique, so a duplicate firing never runs twice
 *   → overlap / catch-up policy → new isolated session (prompt + optional context
 *   snapshot, never the creator's live history) → AgentRunner → next firing armed.
 *
 * Output stays in the run's own session; nothing is appended to the creator session.
 */
class SchedulerEngine(
    private val repo: HoardRepository = HoardRepository.get(),
    private val service: ScheduleService,
    private val backend: SchedulerBackend,
    private val runner: AgentRunner,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** Called when an armed firing goes off (possibly late, possibly twice). */
    fun fire(scheduleId: String, plannedAt: Long) {
        val s = repo.schedules.value.firstOrNull { it.id == scheduleId } ?: return
        if (s.status != ScheduleStatus.Active) return
        val now = clock()
        if (s.nextRunAt != null && plannedAt < s.nextRunAt && runs(s.id).any { it.plannedAt == plannedAt }) return // stale duplicate

        // Late (app/scheduler was down): apply the catch-up policy to everything missed.
        val missed = occurrences(s.trigger, plannedAt - 1, now).ifEmpty { listOf(plannedAt) }
        val late = now - plannedAt > GRACE_MS
        val toRun: List<Long> = when {
            !late -> listOf(plannedAt)
            s.catchUp == CatchUpPolicy.Skip -> emptyList()
            s.catchUp == CatchUpPolicy.Latest -> listOf(missed.last())
            else -> missed.takeLast(MAX_CATCH_UP)
        }
        if (late && s.catchUp == CatchUpPolicy.Skip) claim(s.id, plannedAt, RunStatus.Skipped, error = "missed while Hoard was not running")

        for (p in toRun) {
            if (s.overlap != OverlapPolicy.Parallel && activeRun(s.id) != null) {
                if (s.overlap == OverlapPolicy.Queue) {
                    // Try the same firing again shortly; the next one is armed after it runs.
                    backend.arm(s.id, p, QUEUE_RETRY_MS)
                    return
                }
                claim(s.id, p, RunStatus.Skipped, error = "previous run still in progress")
                continue
            }
            execute(s, p)
        }
        advance(s.id, after = maxOf(now, missed.last()))
    }

    private fun execute(s: Schedule, plannedAt: Long) {
        val now = clock()
        val sessionId = "session-run-" + UUID.randomUUID().toString().take(8)
        val run = claim(s.id, plannedAt, RunStatus.Queued, sessionId = sessionId) ?: return // duplicate firing
        val prompt = ChatMessage(
            id = "msg-" + UUID.randomUUID().toString().take(8), role = MessageRole.User,
            text = s.prompt, trigger = "schedule", createdAt = now
        )
        val system = buildString {
            append(Defaults.SYSTEM_PROMPT)
            append("\n\nThis is an automatic scheduled run of \"").append(s.name).append("\" (").append(ScheduleService.describe(s.trigger))
            append("). The user may not be present: do the task and report the result concisely.")
            if (s.contextMode == ContextMode.Snapshot && !s.contextSnapshot.isNullOrBlank()) {
                append("\n\nContext saved when the schedule was created:\n").append(s.contextSnapshot)
            }
        }
        repo.addSession(
            ChatSession(
                id = sessionId, name = "예약 · ${s.name} · ${ScheduleService.fmt(plannedAt)}", systemPrompt = system,
                modelId = s.modelId, contextLimit = Defaults.CONTEXT_LIMIT, scheduleId = s.id
            ),
            listOf(prompt)
        )
        service.update(s.id) { it.copy(runCount = it.runCount + 1, lastRunAt = now) }
        runner.startReply(sessionId, s.modelId, prompt.id, scheduleRunId = run.id)
    }

    /** Arms the next firing (or finishes the schedule). */
    private fun advance(scheduleId: String, after: Long) {
        val s = repo.schedules.value.firstOrNull { it.id == scheduleId } ?: return
        if (s.status != ScheduleStatus.Active) return
        val next = ScheduleService.nextAfter(s.trigger, after)
        val done = next == null || (s.expiresAt != null && next > s.expiresAt) || (s.maxRuns != null && s.runCount >= s.maxRuns)
        if (done) {
            backend.disarm(scheduleId)
            service.update(scheduleId) { it.copy(status = ScheduleStatus.Finished, nextRunAt = null) }
            return
        }
        service.update(scheduleId) { it.copy(nextRunAt = next) }
        backend.arm(scheduleId, next!!, (next - clock()).coerceAtLeast(0))
    }

    /**
     * App start: re-arm every active schedule (catching up per policy if its time passed
     * while nothing ran) and fail runs left "running" by a killed process.
     */
    fun reconcile() {
        val now = clock()
        repo.updateScheduleRuns { list ->
            list.map { r ->
                val since = r.startedAt ?: r.queuedAt ?: r.createdAt
                if (!r.status.finished && now - since > STALE_MS) r.copy(status = RunStatus.Failed, error = "stale: Hoard stopped during the run", finishedAt = now) else r
            }
        }
        for (s in repo.schedules.value.filter { it.status == ScheduleStatus.Active }) {
            val next = s.nextRunAt ?: continue
            if (next <= now) fire(s.id, next) else backend.arm(s.id, next, next - now)
        }
    }

    // ---- run lifecycle (from the agent side) ------------------------------------

    fun onRunStarted(runId: String) = updateRun(runId) { if (it.status == RunStatus.Queued) it.copy(status = RunStatus.Running, startedAt = clock()) else it }

    fun onRunFinished(runId: String, status: RunStatus, summary: String? = null, error: String? = null, tokens: Int? = null) =
        updateRun(runId) {
            if (it.status.finished) it
            else it.copy(status = status, finishedAt = clock(), outputSummary = summary?.take(500), error = error?.take(500), tokensUsed = tokens)
        }

    fun runOf(runId: String): ScheduleRun? = repo.scheduleRuns.value.firstOrNull { it.id == runId }

    // ---- internals -------------------------------------------------------------

    private fun runs(scheduleId: String) = repo.scheduleRuns.value.filter { it.scheduleId == scheduleId }
    private fun activeRun(scheduleId: String) = runs(scheduleId).firstOrNull { !it.status.finished }

    /** Idempotent: returns null if (scheduleId, plannedAt) was already claimed. */
    private fun claim(scheduleId: String, plannedAt: Long, status: RunStatus, sessionId: String? = null, error: String? = null): ScheduleRun? {
        var created: ScheduleRun? = null
        repo.updateScheduleRuns { list ->
            if (list.any { it.scheduleId == scheduleId && it.plannedAt == plannedAt }) { created = null; list }
            else ScheduleService.runOf(scheduleId, plannedAt, status, clock(), sessionId, error).let { created = it; list + it }
        }
        return created
    }

    private fun updateRun(runId: String, change: (ScheduleRun) -> ScheduleRun) {
        repo.updateScheduleRuns { list -> list.map { if (it.id == runId) change(it) else it } }
    }

    /** Planned times in (after, until], bounded. */
    private fun occurrences(t: ScheduleTrigger, after: Long, until: Long): List<Long> {
        val out = ArrayList<Long>()
        var cursor = after
        while (out.size < 1_000) {
            val n = ScheduleService.nextAfter(t, cursor) ?: break
            if (n > until) break
            out += n; cursor = n
        }
        return out
    }

    companion object {
        /** A firing this late (Doze, a restart) still counts as on time. */
        const val GRACE_MS = 15 * 60_000L
        const val MAX_CATCH_UP = 3
        const val QUEUE_RETRY_MS = 60_000L
        const val STALE_MS = 60 * 60_000L
    }
}
