package com.sleepysoong.hoard.tools

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.skills.SkillForkDelivery
import com.sleepysoong.hoard.work.ChatResponseWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive

/** SkillForkDelivery records are plain JSON, distinct from the tools' argument objects. */
private fun JsonObject.deliveryString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

interface SkillTaskService {
    suspend fun execute(parentSessionId: String, taskSessionId: String, action: String): JsonObject
}

/** Only this session's skill forks are readable/cancellable; not arbitrary chat sessions. */
class AndroidSkillTaskService(private val context: Context, private val repo: HoardRepository) : SkillTaskService {
    override suspend fun execute(parentSessionId: String, taskSessionId: String, action: String): JsonObject = withContext(Dispatchers.IO) {
        val saved = SkillForkDelivery.status(context, taskSessionId)
        val session = repo.sessionOf(taskSessionId)
        val ownsLive = session?.branchedFrom == parentSessionId && repo.messagesOf(taskSessionId).firstOrNull()?.trigger == "skill"
        if ((!ownsLive && saved?.deliveryString("parent_session_id") != parentSessionId) ||
            (saved != null && saved.deliveryString("parent_session_id") != parentSessionId)) {
            throw ToolException("This task does not belong to the current session")
        }
        if (action == "dismiss") {
            SkillForkDelivery.dismiss(context, taskSessionId, parentSessionId)
            return@withContext buildJsonObject { put("session_id", taskSessionId); put("status", "dismissed") }
        }
        if (action == "cancel") {
            WorkManager.getInstance(context).cancelUniqueWork(ChatResponseWorker.uniqueName(taskSessionId)).result.get()
            // Record the outcome once, so a later worker result cannot overwrite a user cancellation.
            SkillForkDelivery.completed(context, taskSessionId, ChatMessage("skill-cancel", MessageRole.Assistant, "", errorText = "사용자가 스킬 작업을 취소했습니다"))
        }
        val reply = session?.let { repo.messagesOf(taskSessionId).lastOrNull { m -> m.role == MessageRole.Assistant } }
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ChatResponseWorker.uniqueName(taskSessionId)).get()
        // A settled delivery record outranks live state: the worker already finished (or was cancelled).
        val settled = saved?.deliveryString("status")?.takeIf { it != "pending" }
        val status = when {
            action == "cancel" -> "cancellation_requested"
            settled != null -> settled
            reply?.isStreaming == true -> "running"
            reply?.errorText != null -> "failed"
            reply?.text?.isNotBlank() == true -> "completed"
            work.any { it.state == WorkInfo.State.RUNNING } -> "running"
            work.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> "queued"
            work.any { it.state == WorkInfo.State.CANCELLED } -> "cancelled"
            saved != null -> "pending"
            else -> "interrupted"
        }
        val summary = reply?.text.orEmpty().ifBlank { saved?.deliveryString("summary").orEmpty() }
        buildJsonObject {
            put("session_id", taskSessionId)
            put("status", status)
            put("summary", summary.take(16_000))
            put("truncated", summary.length > 16_000)
            (reply?.errorText ?: saved?.deliveryString("error"))?.let { put("error", it) }
        }
    }
}

class SkillTaskTool(private val service: SkillTaskService, private val sessionId: String) : Tool {
    override val name = "skill_task"
    override val title = "스킬 작업"
    override val description = "Read the result/status of an isolated background skill task, or cancel it. Only tasks created by this session are accessible."
    override val guidance = "A forked skill may return a queued child session_id. Use skill_task to retrieve its real result. Never claim a queued task completed. Do not busy-poll; use schedule_wakeup or wait for the user's next turn."
    override val parameters = toolParameters {
        string("session_id", "Child session_id returned by a forked skill.", required = true)
        string("action", "Get status/result, request cancellation, or dismiss a settled result from future parent context (keeps its child conversation).", enum = listOf("get", "cancel", "dismiss"))
    }
    override fun subject(args: JsonObject) = args.string("session_id").orEmpty()
    override fun summarize(output: JsonObject) = output.string("status").orEmpty()
    override suspend fun execute(args: JsonObject): JsonObject {
        val action = args.string("action") ?: "get"
        if (action !in setOf("get", "cancel", "dismiss")) throw ToolException("action must be get, cancel or dismiss")
        return service.execute(sessionId, args.requireString("session_id"), action)
    }
}
