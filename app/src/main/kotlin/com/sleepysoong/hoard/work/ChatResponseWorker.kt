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
import com.sleepysoong.hoard.diagnostics.AppLog
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Produces a reply from sleepyrouter in the background
 * so leaving the app after send still finishes the response. The foreground service type is dataSync.
 */
class ChatResponseWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val job = currentCoroutineContext()[kotlinx.coroutines.Job] ?: return Result.failure()
        // Register before settings/skills/tools can block. WorkManager cancellation
        // is asynchronous; the stop button must close the network call immediately.
        synchronized(executionLock) {
            val generation = generations[sessionId]
            if (generation != null && generation != inputData.getLong(KEY_GENERATION, 0)) {
                AppLog.w(TAG, "skip stale work session=$sessionId gen=${inputData.getLong(KEY_GENERATION, 0)} current=$generation")
                return Result.success()
            }
            activeJobs[id] = sessionId to job
        }
        AppLog.i(TAG, "start session=${sessionId.takeLast(6)} mode=${inputData.getString(KEY_MODE)} attempt=$runAttemptCount")
        return try {
            currentCoroutineContext().ensureActive()
            runReply().also { AppLog.i(TAG, "finish session=${sessionId.takeLast(6)} result=${it::class.simpleName}") }
        } finally {
            synchronized(executionLock) { activeJobs.remove(id) }
        }
    }

    private suspend fun runReply(): Result {
        val sessionId = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val parentId = inputData.getString(KEY_PARENT) ?: return Result.failure()
        val modelId = inputData.getString(KEY_MODEL).orEmpty()
        val messageId = inputData.getString(KEY_MESSAGE) ?: ("msg-" + UUID.randomUUID().toString().take(8))
        val mode = inputData.getString(KEY_MODE) ?: MODE_REPLY
        val runId = inputData.getString(KEY_RUN)
        val skillFork = inputData.getBoolean(KEY_SKILL_FORK, false)
        val focus = inputData.getString(KEY_FOCUS)?.takeIf { it.isNotBlank() }
        val repo = HoardRepository.get()
        val goals = GoalService(repo)
        val (_, scheduler, wakeups) = WorkManagerScheduler.services(applicationContext)
        // Session gone (deleted / lost with the process), or the prompt was
        // deleted/edited away while this reply was queued.
        val session = repo.sessionOf(sessionId) ?: run {
            AppLog.w(TAG, "skip: session $sessionId is gone")
            return Result.success()
        }
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

        if (parentIdx < 0) {
            AppLog.w(TAG, "skip: parent message $parentId missing in session ${sessionId.takeLast(6)}")
            return Result.success()
        }
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
            repo.insertMessageAfter(sessionId, parentId, placeholder)
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
        // A fork's permissions are a security boundary: never widen them on a decode failure.
        val rawPermissions = inputData.getString(KEY_PERMISSIONS)
        val explicitPermissions = if (rawPermissions == null) null
            else runCatching { Json.decodeFromString<PermissionProfile>(rawPermissions) }.getOrNull() ?: return Result.failure()
        val allowed = explicitPermissions ?: scheduledPerms ?: PermissionProfile.ALL
        val forkReadOnly = inputData.getBoolean(KEY_READ_ONLY, false)
        return try {
            promote("Hoard가 생각 중…")
            AppLog.i(TAG, "placeholders ok session=${sessionId.takeLast(6)} model=$model")
            val prepStart = System.currentTimeMillis()
            val skillStore = SkillStore.get(applicationContext)
            withContext(Dispatchers.IO) { skillStore.refresh() }
            AppLog.i(TAG, "skills loaded ok skills=${skillStore.skills.value.size} (${System.currentTimeMillis() - prepStart}ms)")
            AppLog.i(TAG, "runtime ctor…")
            val runtime = withTimeoutOrNull(15_000L) {
                SkillRuntime(skillStore, TermuxBridge(applicationContext), sessionId, allowShell = allowed.termux)
            } ?: run {
                val dump = Thread.getAllStackTraces().filter { (t, _) -> t.name != "main" }
                    .entries.joinToString("\n") { (t, s) ->
                        "${t.name}: " + s.take(3).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    }.take(6_000)
                AppLog.e(TAG, "skill runtime init stalled 15s:\n$dump")
                throw IllegalStateException("스킬 런타임 준비가 15초를 넘었습니다 · 로그를 확인하세요")
            }
            AppLog.i(TAG, "runtime ok loadedContext=${runtime.context().length}b")
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
                AppLog.i(TAG, "hook pass start")
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
            AppLog.i(TAG, "hooks/slash ok (${System.currentTimeMillis() - prepStart}ms)")
            val completionContext = withContext(Dispatchers.IO) { SkillForkDelivery.context(applicationContext, sessionId) }
            AppLog.i(TAG, "fork records ok (${System.currentTimeMillis() - prepStart}ms)")
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
            AppLog.i(TAG, "tools ready tools=${registry.tools.size} (${System.currentTimeMillis() - prepStart}ms)")
            val engine = Engines.forRouter(
                cfg.routerUrl,
                AttachmentEncoder(AttachmentEncoder.contentReader(applicationContext.contentResolver)),
                token = cfg.routerToken,
                // Every tool comes from ToolKit's modules; the context decides what's offered.
                tools = registry
            )
            AppLog.i(TAG, "engine start session=${sessionId.takeLast(6)} url=${cfg.routerUrl}")
            runId?.let(scheduler::onRunStarted)
            val generate: suspend () -> Unit = {
                engine.streamReply(skillRequest) { ev ->
                    currentCoroutineContext().ensureActive()
                    var stopped = false
                    val written = repo.updateMessage(sessionId, messageId) {
                        // cancelUniqueWork is asynchronous. The stop button marks the
                        // bubble immediately; a late frame must never undo that mark.
                        stopped = !it.isStreaming && it.errorText == USER_STOPPED
                        if (stopped) it else it.copy(
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
                    if (stopped) throw CancellationException(USER_STOPPED)
                    // Session or bubble deleted mid-stream: stop generating.
                    if (!written) throw TargetGone()
                    if (!ev.done) promote("Hoard가 답변 중…")
                }
            }
            try {
                if (skillFork) withTimeout(10 * 60_000L) { generate() } else generate()
                currentCoroutineContext().ensureActive()
            } catch (e: RouterException) {
                // Observational error hooks cannot replace the original API failure or trigger replay.
                try { runtime.hook("StopFailure", buildJsonObject { put("error", e.message.orEmpty()); put("error_type", "server_error") }) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Preserve the router error. */ }
                throw e
            }
            val reply = repo.messagesOf(sessionId).firstOrNull { it.id == messageId }
            if (reply?.errorText == USER_STOPPED) throw CancellationException(USER_STOPPED)
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
                it.copy(isStreaming = false, errorText = it.errorText ?: USER_STOPPED,
                    thinking = it.thinking.map { step -> step.copy(running = false) })
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
            AppLog.e(TAG, "reply failed session=${sessionId.takeLast(6)} retry=$willRetry reason=$reason", e)
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
                var stopped = false
                if (!repo.updateMessage(session.id, markerId) {
                        stopped = !it.isStreaming && it.errorText == USER_STOPPED
                        if (stopped) it else it.copy(text = raw, routing = ev.routing ?: it.routing, modelId = ev.routing?.selectedModel ?: it.modelId)
                    }) throw TargetGone()
                if (stopped) throw CancellationException(USER_STOPPED)
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
            repo.updateMessage(session.id, markerId) { it.copy(isStreaming = false, errorText = it.errorText ?: USER_STOPPED) }
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
        private val executionLock = Any()
        private val activeJobs = mutableMapOf<UUID, Pair<String, kotlinx.coroutines.Job>>()
        // An enqueue racing stop must not start a new bubble after stop marked the
        // existing bubbles. WorkManager remains the persistent cancellation owner.
        private val generations = mutableMapOf<String, Long>()
        private const val KEY_GENERATION = "session_generation"
        private const val TAG = "ChatResponseWorker"
        const val USER_STOPPED = "사용자가 중지함"
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
            /** Skill fork: run a skill's background agent instead of answering in this session. */
            skillFork: Boolean = false,
            /** The fork's own permission snapshot (never widened on decode failure). */
            permissionOverride: PermissionProfile? = null,
            /** A read-only fork agent (Explore/Plan style): no writes, no Termux, no browser. */
            readOnly: Boolean = false,
            /** [MODE_COMPACT] only: the `/compact <focus>` the user typed (capped in [Compaction]). */
            focus: String? = null
        ) {
            val generation = synchronized(executionLock) { generations.getOrPut(sessionId) { 0L } }
            val req = OneTimeWorkRequestBuilder<ChatResponseWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SESSION to sessionId,
                        KEY_GENERATION to generation,
                        KEY_MODEL to modelId,
                        KEY_MESSAGE to messageId,
                        KEY_PARENT to parentId,
                        KEY_MODE to mode,
                        KEY_RUN to scheduleRunId,
                        KEY_SKILL_FORK to skillFork,
                        KEY_PERMISSIONS to permissionOverride?.let { Json.encodeToString(PermissionProfile.serializer(), it) },
                        KEY_READ_ONLY to readOnly,
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
            // Stopping a skill fork's child must also cancel the parent turn: an inline
            // fork executes inside the parent session's worker.
            val targets = buildList {
                add(sessionId)
                com.sleepysoong.hoard.skills.SkillForkExecutor.activeInline[sessionId]?.let(::add)
            }
            val jobs = synchronized(executionLock) {
                targets.forEach { generations[it] = (generations[it] ?: 0L) + 1L }
                activeJobs.values.filter { it.first in targets }.map { it.second }
            }
            // Show the stop immediately, even if a platform/tool call takes time to
            // unwind. Preserve partial text and make the send button available again.
            val repo = HoardRepository.get()
            targets.forEach { sid ->
                repo.messagesOf(sid).filter { it.isStreaming }.forEach { message ->
                    repo.updateMessage(sid, message.id) {
                        if (!it.isStreaming) it else it.copy(isStreaming = false, errorText = USER_STOPPED,
                            thinking = it.thinking.map { step -> step.copy(running = false) })
                    }
                }
            }
            jobs.forEach { it.cancel(CancellationException(USER_STOPPED)) }
            val wm = WorkManager.getInstance(ctx)
            targets.forEach { wm.cancelUniqueWork(uniqueName(it)) }
            AppLog.i(TAG, "cancel: targets=${targets.map { it.takeLast(6) }} liveJobs=${jobs.size}")
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
