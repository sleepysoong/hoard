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
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.engine.AttachmentEncoder
import com.sleepysoong.hoard.engine.Engines
import com.sleepysoong.hoard.engine.RouterException
import com.sleepysoong.hoard.engine.ReplyRequest
import com.sleepysoong.hoard.engine.RouterConnection
import com.sleepysoong.hoard.tools.AndroidToolServices
import com.sleepysoong.hoard.browser.RemoteBrowserConfig
import com.sleepysoong.hoard.tools.ToolContext
import com.sleepysoong.hoard.tools.ToolKit
import com.sleepysoong.hoard.tools.ToolException
import com.sleepysoong.hoard.skills.SkillStore
import com.sleepysoong.hoard.skills.SkillRuntime
import com.sleepysoong.hoard.skills.SkillForkExecutor
import com.sleepysoong.hoard.skills.SkillPathActivation
import com.sleepysoong.hoard.skills.SkillForkDelivery
import com.sleepysoong.hoard.termux.TermuxBridge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.sleepysoong.hoard.tools.string
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

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
        val skillFork = inputData.getBoolean(KEY_SKILL_FORK, false)
        val repo = HoardRepository.get()
        val goals = GoalService(repo)
        val (_, scheduler, wakeups) = WorkManagerScheduler.services(applicationContext)
        // Session gone (deleted / lost with the process), or the prompt was
        // deleted/edited away while this reply was queued.
        val session = repo.sessionOf(sessionId) ?: return Result.success()
        val before = repo.messagesOf(sessionId)
        val parentIdx = before.indexOfFirst { it.id == parentId }
        if (parentIdx < 0) return Result.success()
        // A continuation queued before the user paused/cleared the goal: nothing to do.
        val goal = if (mode == MODE_BUDGET_SUMMARY) goals.current(sessionId) else goals.active(sessionId)
        if (mode == MODE_CONTINUE && goal == null) return Result.success()
        // Built now, from the store: current system prompt, context limit, tools and
        // exactly the turns up to the answered message. The goal and any hidden runtime
        // prompt are ephemeral: sent with this request, never stored in the conversation.
        val request = ReplyRequest.build(
            session = session,
            conversation = before.take(parentIdx + 1),
            modelId = modelId
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
        val placeholder = ChatMessage(id = messageId, role = MessageRole.Assistant, text = "", modelId = modelId, isStreaming = true)
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

        val cfg = SettingsStore.current(applicationContext)
        // Every tool is always on (no toggles). A scheduled run still gets no more than what
        // was snapshotted when it was created, and no schedule/wakeup tools of its own.
        // Shared storage / Termux work once their Android permissions are granted (asked at
        // app start); until then the tool reports exactly what's missing.
        val scheduledPerms = runId?.let { scheduler.runOf(it) }?.let { r -> repo.schedules.value.firstOrNull { it.id == r.scheduleId }?.permissions }
        val explicitPermissions = inputData.getString(KEY_PERMISSIONS)?.let {
            runCatching { Json.decodeFromString<PermissionProfile>(it) }.getOrNull()
        }
        val allowed = explicitPermissions ?: scheduledPerms ?: PermissionProfile.ALL
        val forkReadOnly = inputData.getBoolean(KEY_READ_ONLY, false)
        return try {
            promote("Hoard가 생각 중…")
            val skillStore = SkillStore.get(applicationContext)
            withContext(Dispatchers.IO) { skillStore.refresh() }
            val runtime = SkillRuntime(skillStore, TermuxBridge(applicationContext), sessionId, allowShell = allowed.termux)
            // A skill `model:` must be resolved against the real catalog before anything runs.
            val needsCatalog = skillFork || skillStore.skills.value.any { it.enabled && it.document.model?.let { it != "inherit" } == true }
            val skillModels = if (needsCatalog && repo.modelCatalog().isEmpty() && cfg.routerUrl.isNotBlank()) {
                runCatching { RouterConnection.clientFactory(cfg.routerUrl, cfg.routerToken).listModels().map { it.id } }
                    .getOrDefault(emptyList())
            } else repo.modelCatalog().map { it.id }
            runtime.resolveModel = { requested -> resolveSkillModel(requested, skillModels) }
            if (skillFork) runtime.resumeTurn()
            // A fork is a new context, not a recursive worker chain inheriting the parent's history.
            if (!skillFork) runtime.fork = SkillForkExecutor(applicationContext, cfg, session, allowed, repo)::execute
            var directSkillResult: JsonObject? = null
            val promptHookContext = mutableListOf<String>()
            if (mode == MODE_REPLY && !skillFork) {
                val parent = before[parentIdx]
                val typedPrompt = runId == null && parent.role == MessageRole.User && parent.trigger !in setOf("schedule", "wakeup", "skill")
                if (typedPrompt) {
                    runtime.hook("UserPromptSubmit", buildJsonObject { put("prompt", parent.text) })?.let { result ->
                        checkPromptHook(result)
                        promptHookContext += "[Skill UserPromptSubmit hook result]\n$result"
                    }
                }
                val slash = Regex("^/([^\\s]+)(?:\\s+([\\s\\S]*))?$").matchEntire(parent.text.trim())
                if (parent.role == MessageRole.User && slash != null && skillStore.find(slash.groupValues[1]) != null) {
                    if (typedPrompt) runtime.hook("UserPromptExpansion", buildJsonObject {
                        put("command_name", slash.groupValues[1]); put("command_args", slash.groupValues[2]); put("prompt", parent.text)
                    })?.let { result ->
                        checkPromptHook(result)
                        promptHookContext += "[Skill UserPromptExpansion hook result]\n$result"
                    }
                    runtime.invocationKey = parent.id
                    try {
                        directSkillResult = runtime.activate(
                            slash.groupValues[1], slash.groupValues[2],
                            userInvoked = runId == null && parent.trigger != "schedule"
                        )
                    } finally {
                        runtime.invocationKey = null
                    }
                }
            }
            val completionContext = withContext(Dispatchers.IO) { SkillForkDelivery.context(applicationContext, sessionId) }
            val directContext = directSkillResult?.takeIf { it.string("context") == "fork" }?.let {
                "[The user's slash skill has already been invoked. Do not invoke it again for this request. " +
                    "Report its actual task/result status, and use skill_task to read a queued background task.]\n$it"
            }.orEmpty()
            val skillRequest = turnRequest.withSkillContext(
                listOf(runtime.context(), completionContext, directContext, promptHookContext.joinToString("\n\n"))
                    .filter { it.isNotBlank() }.joinToString("\n\n").takeIf { it.isNotBlank() }
            )
            val registry = ToolKit.registry(
                ToolContext(
                    sessionId = sessionId,
                    modelId = session.modelId,
                    services = AndroidToolServices(applicationContext, cfg.braveApiKey, repo, RemoteBrowserConfig.from(cfg), skills = runtime),
                    permissions = allowed,
                    scheduledRun = runId != null || skillFork
                )
            ).let { built ->
                built.readOnly = forkReadOnly
                if (skillFork) SkillForkExecutor.restrictRegistry(built, forkReadOnly)
                else built.onWorkspaceFile = SkillPathActivation(skillStore, runtime)::onFile
                built
            }
            val engine = Engines.forRouter(
                cfg.routerUrl,
                AttachmentEncoder(AttachmentEncoder.contentReader(applicationContext.contentResolver)),
                token = cfg.routerToken,
                // Every tool comes from ToolKit's modules; the context decides what's offered.
                tools = registry
            )
            runId?.let(scheduler::onRunStarted)
            val generate: suspend () -> Unit = {
                engine.streamReply(skillRequest) { ev ->
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
            }
            try {
                if (skillFork) withTimeout(10 * 60_000L) { generate() } else generate()
            } catch (e: RouterException) {
                // Observational error hooks cannot replace the original API failure or trigger replay.
                try { runtime.hook("StopFailure", buildJsonObject { put("error", e.message.orEmpty()); put("error_type", "server_error") }) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Preserve the router error. */ }
                throw e
            }
            val reply = repo.messagesOf(sessionId).firstOrNull { it.id == messageId }
            if (skillFork) deliverSkillResult(sessionId, messageId)
            runId?.let { scheduler.onRunFinished(it, RunStatus.Succeeded, summary = reply?.text, tokens = reply?.totalTokens) }
            if (!skillFork) afterTurn(sessionId, messageId, mode, reply, goals, goalBefore, wakeups, cfg.routerUrl.isNotBlank())
            notifyDone(sessionId)
            Result.success()
        } catch (_: TargetGone) {
            if (skillFork) deliverSkillResult(sessionId, messageId)
            runId?.let { scheduler.onRunFinished(it, RunStatus.Cancelled, error = "run session deleted") }
            Result.success()
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive() // A cancelled parent is still cancellation, not timeout.
            repo.updateMessage(sessionId, messageId) { it.copy(isStreaming = false, errorText = "스킬 작업이 10분 실행 한도를 초과했습니다") }
            if (skillFork) deliverSkillResult(sessionId, messageId)
            Result.failure()
        } catch (e: CancellationException) {
            runId?.let { scheduler.onRunFinished(it, RunStatus.Cancelled, error = "stopped") }
            // Cancelled or stopped by the system: never leave a spinning bubble,
            // and let the coroutine machinery see the cancellation.
            // Stopped by the user (stop button / regenerate) or the system: keep whatever
            // text arrived, never leave a spinner.
            repo.updateMessage(sessionId, messageId) {
                it.copy(isStreaming = false, errorText = it.errorText ?: "사용자가 중지함")
            }
            if (skillFork) deliverSkillResult(sessionId, messageId)
            throw e
        } catch (e: RouterException.Permanent) {
            // The router rejected the request itself: retrying sends the same thing again.
            repo.updateMessage(sessionId, messageId) {
                it.copy(isStreaming = false, errorText = e.message, routing = e.routing ?: it.routing)
            }
            runId?.let { scheduler.onRunFinished(it, RunStatus.Failed, error = e.message) }
            if (skillFork) deliverSkillResult(sessionId, messageId)
            Result.failure()
        } catch (e: ToolException) {
            // Invalid/untrusted skill preprocessing is not a transient network error.
            repo.updateMessage(sessionId, messageId) { it.copy(isStreaming = false, errorText = e.message) }
            runId?.let { scheduler.onRunFinished(it, RunStatus.Failed, error = e.message) }
            if (skillFork) deliverSkillResult(sessionId, messageId)
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
            if (skillFork && !willRetry) deliverSkillResult(sessionId, messageId)
            if (willRetry) Result.retry() else Result.failure()
        }
    }

    private fun checkPromptHook(output: JsonObject) {
        if (output.string("decision") == "block" || output["continue"] == JsonPrimitive(false)) {
            throw ToolException(output.string("reason") ?: output.string("stopReason") ?: "스킬 훅이 프롬프트 처리를 중지했습니다")
        }
    }

    private fun deliverSkillResult(sessionId: String, messageId: String) {
        val repo = HoardRepository.get()
        try {
            SkillForkDelivery.completed(applicationContext, sessionId, repo.messagesOf(sessionId).firstOrNull { it.id == messageId })
        } catch (e: Exception) {
            // Never retry already-finished tool side effects because result delivery failed.
            repo.updateMessage(sessionId, messageId) { it.copy(isStreaming = false, errorText = "스킬 결과 전달 실패: ${e.message}") }
        }
        repo.flush()
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
        }.any { it.id != id && (it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED) }
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
        const val KEY_SKILL_FORK = "skill_fork"
        const val KEY_PERMISSIONS = "permission_override"
        const val KEY_READ_ONLY = "read_only"
        const val MODE_REPLY = "reply"
        const val MODE_CONTINUE = "continue"
        const val MODE_BUDGET_SUMMARY = "budget_summary"

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
            /** [MODE_REPLY] (answer [parentId]), [MODE_CONTINUE] (goal continuation), [MODE_BUDGET_SUMMARY]. */
            mode: String = MODE_REPLY,
            /** Set when this reply is a scheduled run. */
            scheduleRunId: String? = null,
            skillFork: Boolean = false,
            permissionOverride: PermissionProfile? = null,
            readOnly: Boolean = false
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
                        KEY_SKILL_FORK to skillFork,
                        KEY_PERMISSIONS to permissionOverride?.let { Json.encodeToString(PermissionProfile.serializer(), it) },
                        KEY_READ_ONLY to readOnly
                    )
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .apply {
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

        /** Claude aliases map to an actual configured model; never silently fall back to another group. */
        fun resolveSkillModel(requested: String, models: List<String>): String {
            if (models.isEmpty()) {
                throw ToolException("스킬 모델 '$requested' · 라우터 모델 목록을 먼저 연결하세요")
            }
            models.firstOrNull { it == requested }?.let { return it }
            if (requested in setOf("sonnet", "opus", "haiku")) {
                val family = Regex("(^|[-_])${Regex.escape(requested)}([-_]|$)")
                val matches = models.filter {
                    val id = it.substringAfterLast('/').substringAfterLast(':').lowercase()
                    id.startsWith("claude-") && family.containsMatchIn(id)
                }
                if (matches.size == 1) return matches.single()
                if (matches.size > 1) throw ToolException("스킬 모델 '$requested'이 여러 모델과 일치합니다 · 정확한 라우터 모델 ID를 지정하세요")
            }
            throw ToolException("스킬 모델 '$requested'은 라우터 목록에 없습니다")
        }
    }
}
