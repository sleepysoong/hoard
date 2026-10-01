package com.sleepysoong.hoard.work

import com.sleepysoong.hoard.data.Goal
import com.sleepysoong.hoard.data.PermissionProfile
import com.sleepysoong.hoard.data.RunStatus
import com.sleepysoong.hoard.data.StepKind
import com.sleepysoong.hoard.goal.ContinuationDecision
import com.sleepysoong.hoard.goal.ContinuationEvaluator
import com.sleepysoong.hoard.goal.GoalRuntime
import com.sleepysoong.hoard.goal.GoalService
import com.sleepysoong.hoard.goal.SessionActivity
import com.sleepysoong.hoard.goal.TurnOutcome
import com.sleepysoong.hoard.schedule.WakeupService
import com.sleepysoong.hoard.schedule.WorkManagerScheduler
import androidx.work.WorkInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sleepysoong.hoard.R
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.ChatSession
import com.sleepysoong.hoard.data.CompactionInfo
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.TRIGGER_COMPACT
import com.sleepysoong.hoard.data.isCompaction
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.engine.AttachmentEncoder
import com.sleepysoong.hoard.engine.Compaction
import com.sleepysoong.hoard.engine.Engines
import com.sleepysoong.hoard.engine.RouterException
import com.sleepysoong.hoard.engine.ReplyRequest
import com.sleepysoong.hoard.tools.AndroidToolServices
import com.sleepysoong.hoard.browser.RemoteBrowserConfig
import com.sleepysoong.hoard.tools.ToolContext
import com.sleepysoong.hoard.tools.ToolKit
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Produces a reply from sleepyrouter in the background
 * so leaving the app after send still finishes the response. The foreground service type is dataSync.
 */
class ChatResponseWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val parentId = inputData.getString(KEY_PARENT) ?: return Result.failure()
        val modelId = inputData.getString(KEY_MODEL).orEmpty()
        val messageId = inputData.getString(KEY_MESSAGE) ?: ("msg-" + UUID.randomUUID().toString().take(8))
        val mode = inputData.getString(KEY_MODE) ?: MODE_REPLY
        val runId = inputData.getString(KEY_RUN)
        val focus = inputData.getString(KEY_FOCUS)?.takeIf { it.isNotBlank() }
        val repo = HoardRepository.get()
        val goals = GoalService(repo)
        val (_, scheduler, wakeups) = WorkManagerScheduler.services(applicationContext)
        // Session gone (deleted / lost with the process), or the prompt was
        // deleted/edited away while this reply was queued.
        val session = repo.sessionOf(sessionId) ?: return Result.success()
        val cfg = SettingsStore.current(applicationContext)
        // The model this reply asked for; a manual /compact has none of its own.
        val model = modelId.ifBlank { session.modelId }
        var before = repo.messagesOf(sessionId)
        var parentIdx = before.indexOfFirst { it.id == parentId }

        if (mode == MODE_COMPACT) {
            if (parentIdx < 0) return Result.success()
            // Include completed replies queued before the command, but never fold later user
            // prompts that are waiting behind it (those must still be answered verbatim).
            val nextPrompt = before.indices.firstOrNull { it > parentIdx && before[it].role == MessageRole.User && !before[it].isCompaction }
            compact(repo, session, cfg, model, auto = false, focus = focus, conversation = before, point = nextPrompt ?: before.size, markerId = messageId)
            return Result.success()
        }

        if (parentIdx < 0) return Result.success()
        // A continuation queued before the user paused/cleared the goal: nothing to do.
        val goal = if (mode == MODE_BUDGET_SUMMARY) goals.current(sessionId) else goals.active(sessionId)
        if (mode == MODE_CONTINUE && goal == null) return Result.success()

        // The next turn would carry more than the auto-compact share of the session's limit:
        // fold the older conversation into a summary marker first (the answered message and
        // the latest turn stay verbatim below it). A failed or stopped compaction never blocks
        // the reply — requests start from the last finished summary and trimming fits the rest.
        if (Compaction.autoDue(session, before.take(parentIdx + 1), cfg.autoCompactPercent) && Engines.canAnswer(cfg.routerUrl)) {
            try {
                compact(repo, session, cfg, model, auto = true, focus = null, conversation = before, point = parentIdx, markerId = "$messageId-cmp")
            } catch (e: CancellationException) {
                runId?.let { scheduler.onRunFinished(it, RunStatus.Cancelled, error = "stopped") }
                throw e
            }
            before = repo.messagesOf(sessionId)
            parentIdx = before.indexOfFirst { it.id == parentId }
            if (parentIdx < 0) return Result.success()
        }

        // Built now, from the store: current system prompt, context limit, tools and
        // exactly the turns up to the answered message. The goal and any hidden runtime
        // prompt are ephemeral: sent with this request, never stored in the conversation.
        val request = ReplyRequest.build(
            session = session,
            conversation = before.take(parentIdx + 1),
            modelId = model
        ).copy(
            goalContext = goal?.let(GoalRuntime::context),
            hiddenUserMessage = when (mode) {
                MODE_CONTINUE -> GoalRuntime.CONTINUE_MESSAGE
                MODE_BUDGET_SUMMARY -> GoalRuntime.BUDGET_SUMMARY_MESSAGE
                else -> null
            },
            forbidTools = mode == MODE_BUDGET_SUMMARY
        )
        val goalBefore = goals.current(sessionId)

        // A reminder is context, not a precondition: a todo-store problem must never fail the reply.
        val turnRequest = request.copy(todoReminder = runCatching { repo.todos.reminder(sessionId) }.getOrNull())
        val placeholder = ChatMessage(id = messageId, role = MessageRole.Assistant, text = "", modelId = model, isStreaming = true)
        val hasTarget = if (runAttemptCount == 0) {
            // Placeholder streaming bubble owned by the worker. Fails when the session
            // is gone — e.g. a job WorkManager restored after the in-memory store died.
            repo.appendMessage(sessionId, placeholder)
        } else {
            // Retry streams into the same bubble instead of appending a duplicate.
            // Fails when the bubble was deleted/regenerated during backoff.
            repo.updateMessage(sessionId, messageId) { placeholder.copy(createdAt = it.createdAt, routing = it.routing) }
        }
        // Nobody is waiting for this reply any more: finish quietly, never resurrect it.
        if (!hasTarget) return Result.success()

        // Every tool is always on (no toggles). A scheduled run still gets no more than what
        // was snapshotted when it was created, and no schedule/wakeup tools of its own.
        // Shared storage / Termux work once their Android permissions are granted (asked at
        // app start); until then the tool reports exactly what's missing.
        val scheduledPerms = runId?.let { scheduler.runOf(it) }?.let { r -> repo.schedules.value.firstOrNull { it.id == r.scheduleId }?.permissions }
        val allowed = scheduledPerms ?: PermissionProfile.ALL
        val engine = Engines.forRouter(
            cfg.routerUrl,
            AttachmentEncoder(AttachmentEncoder.contentReader(applicationContext.contentResolver)),
            token = cfg.routerToken,
            // Every tool comes from ToolKit's modules; the context decides what's offered.
            tools = ToolKit.registry(
                ToolContext(
                    sessionId = sessionId,
                    modelId = session.modelId,
                    services = AndroidToolServices(applicationContext, cfg.braveApiKey, repo, RemoteBrowserConfig.from(cfg)),
                    permissions = allowed,
                    scheduledRun = runId != null
                )
            )
        )
        runId?.let(scheduler::onRunStarted)
        return try {
            promote("Hoard가 생각 중…")
            engine.streamReply(turnRequest) { ev ->
                val written = repo.updateMessage(sessionId, messageId) {
                    it.copy(
                        text = ev.deltaText,
                        thinking = ev.thinking,
                        elapsedMs = ev.elapsedMs,
                        promptTokens = ev.promptTokens,
                        completionTokens = ev.completionTokens,
                        isStreaming = !ev.done,
                        routing = ev.routing ?: it.routing,
                        // The model that actually answered, not just the one requested.
                        modelId = ev.routing?.selectedModel ?: it.modelId,
                        errorText = null
                    )
                }
                // Session or bubble deleted mid-stream: stop generating.
                if (!written) throw TargetGone()
                if (!ev.done) promote("Hoard가 답변 중…")
            }
            val reply = repo.messagesOf(sessionId).firstOrNull { it.id == messageId }
            runId?.let { scheduler.onRunFinished(it, RunStatus.Succeeded, summary = reply?.text, tokens = reply?.totalTokens) }
            afterTurn(sessionId, messageId, mode, reply, goals, goalBefore, wakeups, cfg.routerUrl.isNotBlank())
            notifyDone(sessionId)
            Result.success()
        } catch (_: TargetGone) {
            runId?.let { scheduler.onRunFinished(it, RunStatus.Cancelled, error = "run session deleted") }
            Result.success()
        } catch (e: CancellationException) {
            runId?.let { scheduler.onRunFinished(it, RunStatus.Cancelled, error = "stopped") }
            // Cancelled or stopped by the system: never leave a spinning bubble,
            // and let the coroutine machinery see the cancellation.
            // Stopped by the user (stop button / regenerate) or the system: keep whatever
            // text arrived, never leave a spinner.
            repo.updateMessage(sessionId, messageId) {
                it.copy(isStreaming = false, errorText = it.errorText ?: "사용자가 중지함")
            }
            throw e
        } catch (e: RouterException.Permanent) {
            // The router rejected the request itself: retrying sends the same thing again.
            repo.updateMessage(sessionId, messageId) {
                it.copy(isStreaming = false, errorText = e.message, routing = e.routing ?: it.routing)
            }
            runId?.let { scheduler.onRunFinished(it, RunStatus.Failed, error = e.message) }
            Result.failure()
        } catch (e: Exception) {
            val willRetry = runAttemptCount + 1 < MAX_ATTEMPTS
            val routing = (e as? RouterException)?.routing
            val reason = e.message ?: "연결 오류"
            repo.updateMessage(sessionId, messageId) {
                it.copy(
                    errorText = if (willRetry) "$reason · 곧 다시 시도합니다…" else "$reason · 답변이 중단됐습니다.",
                    routing = routing ?: it.routing,
                    isStreaming = false
                )
            }
            if (!willRetry) runId?.let { scheduler.onRunFinished(it, RunStatus.Failed, error = reason) }
            if (willRetry) Result.retry() else Result.failure()
        }
    }

    /**
     * Fold the conversation before [point] into a compaction summary marker ([Compaction]).
     * The marker streams like a reply; on failure it stays as a failed notice, never sent,
     * and the caller carries on (the next request just goes out uncompacted, trimmed to fit).
     * False = nothing was folded.
     */
    private suspend fun compact(
        repo: HoardRepository,
        session: ChatSession,
        cfg: SettingsStore.Settings,
        modelId: String,
        auto: Boolean,
        focus: String?,
        conversation: List<ChatMessage>,
        point: Int = conversation.size,
        markerId: String
    ): Boolean {
        val plan = Compaction.plan(session, conversation, point) ?: return false
        if (repo.messagesOf(session.id).any { it.id == markerId }) return true // already done for this reply (a retry)
        val marker = ChatMessage(
            id = markerId, role = MessageRole.User, text = "", modelId = modelId, isStreaming = true,
            trigger = TRIGGER_COMPACT,
            compaction = CompactionInfo(auto = auto, summarized = plan.head.size, tokensBefore = plan.tokensBefore, focus = focus)
        )
        val insertion = plan.insertBeforeId?.let { id -> conversation.indexOfFirst { it.id == id } } ?: conversation.size
        val prefix = conversation.take(insertion)
        if (!repo.insertMessageBefore(session.id, plan.insertBeforeId, marker, prefix)) return false
        return try {
            promote("Hoard가 대화를 요약 중…")
            currentCoroutineContext().ensureActive()
            // One plain stream, no tools, no attachments: the serialized transcript with the
            // summarizer's own instructions (tool note / goal context stay off on purpose).
            val engine = Engines.forRouter(cfg.routerUrl, attachments = null, token = cfg.routerToken, tools = null)
            var raw = ""
            var completed = false
            var incomplete = false
            engine.streamReply(Compaction.request(session, plan, modelId, focus)) { ev ->
                currentCoroutineContext().ensureActive()
                raw = ev.deltaText
                completed = completed || ev.done
                incomplete = incomplete || ev.incomplete
                if (!repo.updateMessage(session.id, markerId) {
                        it.copy(text = raw, routing = ev.routing ?: it.routing, modelId = ev.routing?.selectedModel ?: it.modelId)
                    }) throw TargetGone()
            }
            currentCoroutineContext().ensureActive()
            val summary = Compaction.clean(raw)
            val problem = when {
                !completed || incomplete -> "응답이 완료되지 않아 요약을 쓰지 않았습니다"
                else -> Compaction.problem(summary, plan, session.contextLimit)
            }
            repo.finishCompaction(session.id, markerId, prefix, summary, problem)
        } catch (_: TargetGone) {
            false
        } catch (e: CancellationException) {
            repo.updateMessage(session.id, markerId) { it.copy(isStreaming = false, errorText = it.errorText ?: "사용자가 중지함") }
            throw e
        } catch (e: Exception) {
            repo.updateMessage(session.id, markerId) {
                it.copy(
                    isStreaming = false,
                    errorText = e.message ?: "요약에 실패했습니다",
                    routing = (e as? RouterException)?.routing ?: it.routing
                )
            }
            false
        }
    }

    /**
     * Goal continuation engine: after a turn settles, account it against the goal and
     * decide whether the thread keeps going on its own (a hidden continuation turn
     * queued behind anything else in the session), stops, gets suppressed (the
     * continuation did nothing), or ran out of budget (one final summary turn).
     */
    private suspend fun afterTurn(
        sessionId: String, messageId: String, mode: String, reply: ChatMessage?,
        goals: GoalService, before: Goal?, wakeups: WakeupService, needsNetwork: Boolean
    ) {
        if (mode == MODE_BUDGET_SUMMARY) return
        val automatic = mode == MODE_CONTINUE
        // Changed by this turn's tool calls (created, completed, blocked…) — compared before
        // the token/turn bookkeeping below, which alone is not progress.
        val after = goals.current(sessionId)
        val goalChanged = before?.id != after?.id || before?.status != after?.status ||
            before?.evidence != after?.evidence || before?.blockedReason != after?.blockedReason
        goals.recordTurn(sessionId, reply?.totalTokens ?: 0, automatic, goalId = before?.takeIf { it.status == com.sleepysoong.hoard.data.GoalStatus.Active }?.id)
        val turn = TurnOutcome(
            succeeded = reply != null && reply.errorText == null,
            automatic = automatic,
            toolCalls = reply?.thinking?.count { it.kind == StepKind.Tool } ?: 0,
            goalChanged = goalChanged
        )
        val queued = withContext(Dispatchers.IO) {
            runCatching { WorkManager.getInstance(applicationContext).getWorkInfosForUniqueWork(uniqueName(sessionId)).get() }.getOrDefault(emptyList())
        }.any { it.id != id && COMPACTION_TAG !in it.tags && (it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED) }
        val decision = ContinuationEvaluator(goals).decide(sessionId, turn, SessionActivity(queued, wakeups.pending(sessionId)))
        when (decision) {
            ContinuationDecision.Continue -> enqueue(
                applicationContext, sessionId, inputData.getString(KEY_MODEL).orEmpty(), "msg-" + UUID.randomUUID().toString().take(8),
                parentId = messageId, needsNetwork = needsNetwork, mode = MODE_CONTINUE
            )
            ContinuationDecision.Suppress -> goals.suppressContinuation(sessionId, true)
            is ContinuationDecision.BudgetLimited -> {
                goals.markBudgetLimited(sessionId)
                enqueue(
                    applicationContext, sessionId, inputData.getString(KEY_MODEL).orEmpty(), "msg-" + UUID.randomUUID().toString().take(8),
                    parentId = messageId, needsNetwork = needsNetwork, mode = MODE_BUDGET_SUMMARY
                )
            }
            is ContinuationDecision.Stop -> Unit
        }
    }

    /**
     * Foreground promotion is best-effort. Some devices/OEMs refuse dataSync FGS
     * (or the platform drops the type declaration), and a reply must never be
     * lost because of a notification — WorkManager keeps running as background work.
     */
    private suspend fun promote(text: String) {
        try {
            setForeground(foregroundInfo(text))
        } catch (_: Throwable) {
            // Intentionally ignored: continue as regular background work.
        }
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, "hoard-replies")
            .setContentTitle("Hoard")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_hoard)
            .setOngoing(true)
            .setSilent(true)
            .build()
        // minSdk 31: the typed constructor is always available.
        return ForegroundInfo(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notifyDone(sessionId: String) {
        val session = HoardRepository.get().sessionOf(sessionId) ?: return
        val notification = NotificationCompat.Builder(applicationContext, "hoard-replies")
            .setContentTitle("Hoard — ${session.name}")
            .setContentText("답변이 준비됐습니다.")
            .setSmallIcon(R.drawable.ic_stat_hoard)
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            ?.notify(sessionId.hashCode(), notification)
    }

    private class TargetGone : Exception()

    companion object {
        const val KEY_SESSION = "session_id"
        const val KEY_MODEL = "model_id"
        const val KEY_MESSAGE = "message_id"
        /** The user message being answered; the reply is skipped if it was removed while queued. */
        const val KEY_PARENT = "parent_id"
        const val KEY_MODE = "mode"
        const val KEY_RUN = "schedule_run_id"
        /** [MODE_COMPACT]: the user's `/compact <focus>` note (blank = none). */
        const val KEY_FOCUS = "focus"
        const val MODE_REPLY = "reply"
        const val MODE_CONTINUE = "continue"
        const val MODE_BUDGET_SUMMARY = "budget_summary"
        const val MODE_COMPACT = "compact"
        private const val COMPACTION_TAG = "hoard-compaction"

        /** First run + 2 retries; a dead backend must not spin forever. */
        const val MAX_ATTEMPTS = 3

        fun enqueue(
            ctx: Context,
            sessionId: String,
            modelId: String,
            messageId: String,
            parentId: String,
            replacePending: Boolean = false,
            /** Router mode: wait for a network instead of burning retries offline. */
            needsNetwork: Boolean = false,
            /** [MODE_REPLY] (answer [parentId]), [MODE_CONTINUE] (goal continuation), [MODE_BUDGET_SUMMARY], [MODE_COMPACT] (/compact). */
            mode: String = MODE_REPLY,
            /** Set when this reply is a scheduled run. */
            scheduleRunId: String? = null,
            /** [MODE_COMPACT] only: the `/compact <focus>` the user typed (capped in [Compaction]). */
            focus: String? = null
        ) {
            val req = OneTimeWorkRequestBuilder<ChatResponseWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SESSION to sessionId,
                        KEY_MODEL to modelId,
                        KEY_MESSAGE to messageId,
                        KEY_PARENT to parentId,
                        KEY_MODE to mode,
                        KEY_RUN to scheduleRunId,
                        KEY_FOCUS to focus?.take(Compaction.FOCUS_MAX_CHARS)
                    )
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .apply {
                    if (mode == MODE_COMPACT) addTag(COMPACTION_TAG)
                    if (needsNetwork) {
                        setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    }
                }
                .addTag("hoard-reply-$sessionId")
                .build()
            // One reply at a time per session, in send order. Regenerate/edit rewrites the
            // tail, so whatever was running or queued for the old tail is replaced.
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                uniqueName(sessionId),
                if (replacePending) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                req
            )
        }

        fun cancel(ctx: Context, sessionId: String) {
            WorkManager.getInstance(ctx).cancelUniqueWork(uniqueName(sessionId))
        }

        internal fun uniqueName(sessionId: String) = "hoard-reply-$sessionId"
    }
}
