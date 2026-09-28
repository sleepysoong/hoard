package com.sleepysoong.hoard.tools

import com.sleepysoong.hoard.data.todo.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** A single session-bound tool. The model never supplies a session ID. */
class TodoTool(private val service: TodoService, private val sessionId: String) : Tool {
    override val name = "todo"
    override val title = "할 일"
    override val description = "Track concrete steps of complex work in this session. Operations: create (content; always pending), " +
        "update (id and content and/or status), remove (id), list, clear. " +
        "Statuses: pending, in_progress, completed, cancelled. At most one in_progress. " +
        "Transitions: pending -> in_progress/cancelled; in_progress -> completed/pending/cancelled; " +
        "completed -> in_progress (reopen); cancelled -> pending (restore). " +
        "Returns the current ordered list with IDs. remove/clear permanently delete tasks; cancel obsolete work instead. " +
        "Tasks survive app restart and are private to this session."

    // A root object is compatible with the router's provider bridges. The op discriminator
    // is validated strictly below, including per-operation fields and required arguments.
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("op") {
                put("type", "string")
                put("enum", JsonArray(listOf("create", "update", "remove", "list", "clear").map(::JsonPrimitive)))
            }
            putJsonObject("content") { put("type", "string"); put("minLength", 1); put("maxLength", TodoService.MAX_CONTENT_LENGTH) }
            putJsonObject("id") { put("type", "string"); put("description", "Task ID returned by this session's todo tool.") }
            putJsonObject("status") {
                put("type", "string"); put("enum", JsonArray(TodoStatus.entries.map { JsonPrimitive(it.wire) }))
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("op"))))
        put("additionalProperties", false)
    }

    override fun subject(args: JsonObject) = when (args.string("op")) {
        "create" -> "추가 · ${args.string("content").orEmpty()}"
        "update" -> "수정 · ${args.string("id").orEmpty()}"
        "remove" -> "삭제 · ${args.string("id").orEmpty()}"
        "clear" -> "전체 삭제"
        else -> "목록 조회"
    }

    override fun summarize(output: JsonObject): String {
        val items = output["todos"]?.jsonArray.orEmpty()
        return "${items.size}개 · 완료 ${items.count { it.jsonObject["status"]?.jsonPrimitive?.content == "completed" }}개"
    }

    override suspend fun execute(args: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val request = parse(args)
        val items = try { service.execute(sessionId, request) }
        catch (e: TodoException) { throw ToolException(e.message ?: "Invalid todo operation") }
        catch (_: android.database.sqlite.SQLiteException) { throw ToolException("Could not commit todo state. No update was published; retry later.") }
        buildJsonObject { put("sessionId", sessionId); put("todos", JsonArray(items.map { it.toJson() })) }
    }

    private fun parse(args: JsonObject): TodoRequest {
        fun required(key: String): String = args.string(key)?.takeIf { it.isNotBlank() }
            ?: throw ToolException("$key must be a non-empty string")
        val op = required("op")
        val fields = when (op) {
            "create" -> setOf("op", "content")
            "update" -> setOf("op", "id", "content", "status")
            "remove" -> setOf("op", "id")
            "list", "clear" -> setOf("op")
            else -> throw ToolException("Unknown todo operation: $op")
        }
        if (args.keys.any { it !in fields }) throw ToolException("Unexpected fields for $op: ${(args.keys - fields).joinToString()}")
        return when (op) {
            "create" -> TodoRequest.Create(required("content"))
            "update" -> {
                if ("content" !in args && "status" !in args) throw ToolException("update requires content or status")
                val status = if ("status" in args) {
                    val wire = required("status")
                    TodoStatus.entries.find { it.wire == wire } ?: throw ToolException("Unknown status: $wire")
                } else null
                TodoRequest.Update(required("id"), if ("content" in args) required("content") else null, status)
            }
            "remove" -> TodoRequest.Remove(required("id"))
            "clear" -> TodoRequest.Clear
            else -> TodoRequest.ListAll
        }
    }

    companion object {
        const val INSTRUCTIONS = """Task tracking: Use todo for complex work with multiple distinct outcomes or work that needs progress across turns.
Do not use it for simple questions, trivial one-step changes, or individual tool calls.
Keep tasks concrete and outcome-oriented. Keep at most one task in_progress.
Start a task before working on it and mark it completed immediately after the work is actually finished.
Update the list when scope changes; mark obsolete work cancelled rather than silently dropping it.
Continue until all relevant tasks are completed or cancelled. A todo update does not perform the work itself.
The current todo state is authoritative over old conversation descriptions. Use list if you need to refresh it."""
    }
}
