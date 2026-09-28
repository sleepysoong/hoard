package com.sleepysoong.hoard.schedule

import com.sleepysoong.hoard.data.CatchUpPolicy
import com.sleepysoong.hoard.data.ContextMode
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.OverlapPolicy
import com.sleepysoong.hoard.data.PermissionProfile
import com.sleepysoong.hoard.data.RunStatus
import com.sleepysoong.hoard.data.Schedule
import com.sleepysoong.hoard.data.ScheduleRun
import com.sleepysoong.hoard.data.ScheduleStatus
import com.sleepysoong.hoard.data.ScheduleTrigger
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class ScheduleException(message: String) : Exception(message)

/**
 * Where firings are armed. The scheduler (this package) never knows about the AI: it
 * only knows when something is due. The app uses WorkManager ([WorkManagerScheduler]),
 * which survives process death and reboots.
 */
interface SchedulerBackend {
    /** Arm (or re-arm, replacing) the next firing of [scheduleId]. */
    fun arm(scheduleId: String, plannedAt: Long, delayMs: Long)
    fun disarm(scheduleId: String)
}

/** Starts an agent turn (the scheduler's only way into the agent). */
interface AgentRunner {
    fun startReply(sessionId: String, modelId: String, parentMessageId: String, scheduleRunId: String? = null)
}

data class CreateScheduleInput(
    val name: String?,
    val prompt: String,
    val trigger: ScheduleTrigger,
    val modelId: String,
    val creatorSessionId: String?,
    val permissions: PermissionProfile,
    val catchUp: CatchUpPolicy? = null,
    val overlap: OverlapPolicy = OverlapPolicy.Skip,
    val maxRuns: Int? = null,
    val expiresAt: Long? = null,
    val contextMode: ContextMode = ContextMode.Clean,
    val contextSnapshot: String? = null
)

/** Durable schedule definitions: validation, next-run math, lifecycle. */
class ScheduleService(
    private val repo: HoardRepository = HoardRepository.get(),
    private val backend: SchedulerBackend,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun list(): List<Schedule> = repo.schedules.value.filter { it.status != ScheduleStatus.Cancelled }.sortedBy { it.createdAt }
    fun get(id: String): Schedule = repo.schedules.value.firstOrNull { it.id == id } ?: throw ScheduleException("schedule not found: $id")
    fun runs(id: String): List<ScheduleRun> = repo.scheduleRuns.value.filter { it.scheduleId == id }.sortedByDescending { it.plannedAt }

    fun create(input: CreateScheduleInput): Schedule {
        val prompt = input.prompt.trim()
        if (prompt.isEmpty()) throw ScheduleException("prompt is required")
        if (prompt.length > 4_000) throw ScheduleException("prompt is too long (max 4000 characters)")
        val now = clock()
        validate(input.trigger, now)
        if (input.maxRuns != null && input.maxRuns < 1) throw ScheduleException("max_runs must be >= 1")
        if (input.expiresAt != null && input.expiresAt <= now) throw ScheduleException("expires_at is in the past")
        if (input.contextMode == ContextMode.Snapshot && input.contextSnapshot.isNullOrBlank()) {
            throw ScheduleException("context_snapshot is required when context is \"snapshot\"")
        }
        if (list().count { it.status == ScheduleStatus.Active } >= MAX_ACTIVE) throw ScheduleException("too many active schedules (max $MAX_ACTIVE)")
        // Same prompt + trigger already active: don't create a duplicate.
        list().firstOrNull { it.status == ScheduleStatus.Active && it.prompt == prompt && sameTrigger(it.trigger, input.trigger) }?.let {
            throw ScheduleException("an identical schedule already exists (${it.id}); modify or cancel it instead")
        }
        val next = nextAfter(input.trigger, now - 1) ?: throw ScheduleException("the trigger never fires")
        val s = Schedule(
            id = "sched-" + UUID.randomUUID().toString().take(8),
            creatorSessionId = input.creatorSessionId,
            name = input.name?.trim()?.takeIf { it.isNotEmpty() }?.take(80) ?: prompt.lineSequence().first().take(40),
            prompt = prompt,
            trigger = input.trigger,
            modelId = input.modelId,
            status = ScheduleStatus.Active,
            nextRunAt = next,
            catchUp = input.catchUp ?: if (input.trigger is ScheduleTrigger.At) CatchUpPolicy.Latest else CatchUpPolicy.Skip,
            overlap = input.overlap,
            maxRuns = input.maxRuns,
            expiresAt = input.expiresAt,
            contextMode = input.contextMode,
            contextSnapshot = input.contextSnapshot?.trim()?.take(8_000),
            permissions = input.permissions,
            createdAt = now, updatedAt = now
        )
        repo.updateSchedules { it + s }
        backend.arm(s.id, next, (next - now).coerceAtLeast(0))
        return s
    }

    fun pause(id: String): Schedule {
        val s = get(id)
        if (s.status != ScheduleStatus.Active) throw ScheduleException("schedule is ${s.status.name.lowercase()}, not active")
        backend.disarm(id)
        return update(id) { it.copy(status = ScheduleStatus.Paused) }
    }

    /** Resumes from now: occurrences missed while paused are not run. */
    fun resume(id: String): Schedule {
        val s = get(id)
        if (s.status != ScheduleStatus.Paused) throw ScheduleException("schedule is ${s.status.name.lowercase()}, not paused")
        val now = clock()
        val next = nextAfter(s.trigger, now - 1)?.takeIf { s.expiresAt == null || it <= s.expiresAt }
        if (next == null) return update(id) { it.copy(status = ScheduleStatus.Finished, nextRunAt = null) }
        backend.arm(id, next, (next - now).coerceAtLeast(0))
        return update(id) { it.copy(status = ScheduleStatus.Active, nextRunAt = next) }
    }

    fun cancel(id: String): Schedule {
        val s = get(id)
        if (s.status == ScheduleStatus.Cancelled) throw ScheduleException("schedule is already cancelled")
        backend.disarm(id)
        return update(id) { it.copy(status = ScheduleStatus.Cancelled, nextRunAt = null) }
    }

    internal fun update(id: String, change: (Schedule) -> Schedule): Schedule {
        var out: Schedule? = null
        repo.updateSchedules { list -> list.map { if (it.id == id) change(it).copy(updatedAt = clock()).also { n -> out = n } else it } }
        return out ?: throw ScheduleException("schedule not found: $id")
    }

    companion object {
        const val MAX_ACTIVE = 20
        /** Recurring runs closer than this would mostly burn tokens. */
        const val MIN_INTERVAL_MS = 5 * 60_000L

        fun validate(t: ScheduleTrigger, now: Long) {
            when (t) {
                is ScheduleTrigger.At -> if (t.atMillis <= now) throw ScheduleException("\"at\" is in the past: ${Instant.ofEpochMilli(t.atMillis)}")
                is ScheduleTrigger.Every -> if (t.intervalMs < MIN_INTERVAL_MS) throw ScheduleException("interval must be at least ${MIN_INTERVAL_MS / 60_000} minutes")
                is ScheduleTrigger.Cron -> {
                    val zone = zone(t.timezone)
                    val cron = try { CronExpression.parse(t.expression) } catch (e: IllegalArgumentException) { throw ScheduleException("invalid cron: ${e.message}") }
                    val a = cron.next(Instant.ofEpochMilli(now), zone) ?: throw ScheduleException("cron never fires")
                    val b = cron.next(a, zone)
                    if (b != null && b.toEpochMilli() - a.toEpochMilli() < MIN_INTERVAL_MS) {
                        throw ScheduleException("cron fires more often than every ${MIN_INTERVAL_MS / 60_000} minutes")
                    }
                }
            }
        }

        fun zone(id: String): ZoneId = try { ZoneId.of(id) } catch (e: Exception) { throw ScheduleException("unknown timezone \"$id\" (use an IANA id like Asia/Seoul)") }

        /** First planned time strictly after [after]. Interval runs stay on their anchor grid (no drift). */
        fun nextAfter(t: ScheduleTrigger, after: Long): Long? = when (t) {
            is ScheduleTrigger.At -> t.atMillis.takeIf { it > after }
            is ScheduleTrigger.Every -> if (after < t.anchorMillis) t.anchorMillis
                else t.anchorMillis + ((after - t.anchorMillis) / t.intervalMs + 1) * t.intervalMs
            is ScheduleTrigger.Cron -> CronExpression.parse(t.expression).next(Instant.ofEpochMilli(after), zone(t.timezone))?.toEpochMilli()
        }

        private fun sameTrigger(a: ScheduleTrigger, b: ScheduleTrigger) = when {
            a is ScheduleTrigger.Every && b is ScheduleTrigger.Every -> a.intervalMs == b.intervalMs
            else -> a == b
        }

        fun describe(t: ScheduleTrigger): String = when (t) {
            is ScheduleTrigger.At -> "한 번 · " + fmt(t.atMillis)
            is ScheduleTrigger.Every -> "매 " + when {
                t.intervalMs % 86_400_000L == 0L -> "${t.intervalMs / 86_400_000L}일"
                t.intervalMs % 3_600_000L == 0L -> "${t.intervalMs / 3_600_000L}시간"
                else -> "${t.intervalMs / 60_000L}분"
            }
            is ScheduleTrigger.Cron -> "cron ${t.expression} · ${t.timezone}"
        }

        fun fmt(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm").format(Instant.ofEpochMilli(millis).atZone(zone))

        internal fun runOf(scheduleId: String, plannedAt: Long, status: RunStatus, now: Long, sessionId: String? = null, error: String? = null) =
            ScheduleRun(
                id = "run-" + UUID.randomUUID().toString().take(8), scheduleId = scheduleId, plannedAt = plannedAt,
                queuedAt = if (status == RunStatus.Queued) now else null, status = status, runSessionId = sessionId,
                error = error, createdAt = now, finishedAt = if (status.finished) now else null
            )
    }
}
