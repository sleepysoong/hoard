package com.sleepysoong.hoard.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.diagnostics.AppLog
import com.sleepysoong.hoard.work.ChatResponseWorker
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Scheduler backend on WorkManager: one unique delayed work per schedule. WorkManager
 * persists it across process death and reboot (the "OS background" backend). Timing
 * is best effort under Doze; firings within [SchedulerEngine.GRACE_MS] count as on time.
 */
class WorkManagerScheduler(private val context: Context) : SchedulerBackend, AgentRunner {
    private val wm get() = WorkManager.getInstance(context)

    override fun arm(scheduleId: String, plannedAt: Long, delayMs: Long) {
        val req = OneTimeWorkRequestBuilder<ScheduleFireWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_SCHEDULE to scheduleId, KEY_PLANNED to plannedAt))
            .addTag(TAG)
            .build()
        wm.enqueueUniqueWork(fireName(scheduleId), ExistingWorkPolicy.REPLACE, req)
    }

    override fun disarm(scheduleId: String) {
        wm.cancelUniqueWork(fireName(scheduleId))
    }

    override fun startReply(sessionId: String, modelId: String, parentMessageId: String, scheduleRunId: String?) {
        ChatResponseWorker.enqueue(
            context, sessionId, modelId, "msg-" + UUID.randomUUID().toString().take(8), parentMessageId,
            needsNetwork = true, scheduleRunId = scheduleRunId
        )
    }

    companion object {
        const val KEY_SCHEDULE = "schedule_id"
        const val KEY_PLANNED = "planned_at"
        const val TAG = "hoard-schedule"
        fun fireName(id: String) = "hoard-schedule-$id"

        fun services(context: Context): Triple<ScheduleService, SchedulerEngine, WakeupService> {
            val backend = WorkManagerScheduler(context.applicationContext)
            val service = ScheduleService(backend = backend)
            val engine = SchedulerEngine(service = service, backend = backend, runner = backend)
            return Triple(service, engine, WakeupService(WorkManagerWakeups(context.applicationContext)))
        }
    }
}

class ScheduleFireWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(WorkManagerScheduler.KEY_SCHEDULE) ?: return Result.success()
        val planned = inputData.getLong(WorkManagerScheduler.KEY_PLANNED, -1).takeIf { it > 0 } ?: return Result.success()
        // A throwing fire() (transient DB/store error) must not lose the firing until
        // the next app start: retry with WorkManager backoff instead.
        return try {
            WorkManagerScheduler.services(applicationContext).second.fire(id, planned)
            HoardRepository.get().flush()
            Result.success()
        } catch (e: Exception) {
            AppLog.e("ScheduleFire", "fire failed for $id", e)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}

// ---------------------------------------------------------------- wakeup

/** Arms session wakeups (one per session; a new one replaces the pending one). */
interface WakeupBackend {
    fun arm(sessionId: String, delayMs: Long, prompt: String)
    fun cancel(sessionId: String)
    fun pending(sessionId: String): Boolean
}

class WakeupException(message: String) : Exception(message)

/**
 * `schedule_wakeup`: the current session's agent wakes up after a delay (e.g. check a
 * deployment again in a minute) instead of busy-polling. Not a durable schedule: no run
 * history, same session, one pending per session. When it fires, a "wakeup" notice
 * with the prompt is added and a reply is queued behind whatever the session is
 * doing (so it only runs once the agent is idle).
 */
class WakeupService(private val backend: WakeupBackend, private val repo: HoardRepository = HoardRepository.get()) {
    fun schedule(sessionId: String, delayMs: Long, prompt: String) {
        if (delayMs !in MIN_DELAY_MS..MAX_DELAY_MS) throw WakeupException("delay_ms must be between $MIN_DELAY_MS and $MAX_DELAY_MS")
        val p = prompt.trim()
        if (p.isEmpty()) throw WakeupException("prompt is required")
        repo.sessionOf(sessionId) ?: throw WakeupException("session not found")
        backend.arm(sessionId, delayMs, p.take(2_000))
    }

    fun cancel(sessionId: String) = backend.cancel(sessionId)
    fun pending(sessionId: String) = backend.pending(sessionId)

    /** The wakeup went off: returns the notice message to answer, or null if the session is gone. */
    fun fire(sessionId: String, prompt: String): ChatMessage? {
        val msg = ChatMessage("msg-" + UUID.randomUUID().toString().take(8), MessageRole.User, prompt, trigger = "wakeup")
        return msg.takeIf { repo.appendMessage(sessionId, it) }
    }

    companion object {
        const val MIN_DELAY_MS = 5_000L
        const val MAX_DELAY_MS = 60 * 60_000L
    }
}

class WorkManagerWakeups(private val context: Context) : WakeupBackend {
    private val wm get() = WorkManager.getInstance(context)

    override fun arm(sessionId: String, delayMs: Long, prompt: String) {
        val req = OneTimeWorkRequestBuilder<WakeupWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_SESSION to sessionId, KEY_PROMPT to prompt))
            .addTag(TAG)
            .build()
        wm.enqueueUniqueWork(name(sessionId), ExistingWorkPolicy.REPLACE, req)
    }

    override fun cancel(sessionId: String) {
        wm.cancelUniqueWork(name(sessionId))
    }

    override fun pending(sessionId: String): Boolean =
        runCatching { wm.getWorkInfosForUniqueWork(name(sessionId)).get() }.getOrDefault(emptyList())
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }

    companion object {
        const val KEY_SESSION = "session_id"
        const val KEY_PROMPT = "prompt"
        const val TAG = "hoard-wakeup"
        fun name(sessionId: String) = "hoard-wakeup-$sessionId"
    }
}

class WakeupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(WorkManagerWakeups.KEY_SESSION) ?: return Result.success()
        val prompt = inputData.getString(WorkManagerWakeups.KEY_PROMPT) ?: return Result.success()
        val repo = HoardRepository.get()
        val session = repo.sessionOf(sessionId) ?: return Result.success()
        val notice = WakeupService(WorkManagerWakeups(applicationContext), repo).fire(sessionId, prompt) ?: return Result.success()
        val routerUrl = SettingsStore.current(applicationContext).routerUrl
        ChatResponseWorker.enqueue(
            applicationContext, sessionId, session.modelId, "msg-" + UUID.randomUUID().toString().take(8), notice.id,
            needsNetwork = routerUrl.isNotBlank()
        )
        return Result.success()
    }
}
