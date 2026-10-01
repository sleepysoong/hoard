package com.sleepysoong.hoard.skills

import android.content.Context
import android.util.AtomicFile
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.tools.ToolException
import com.sleepysoong.hoard.tools.readCapped
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Durable child-to-parent delivery, outside the workspace accessible to model file tools.
 *
 * Register before enqueue; call completed only when a worker settles (not during retry backoff).
 * context is read-only and repeatable, so constructing a request never consumes a result that
 * could be lost if the request or process fails. It belongs in user-level skill context.
 * These methods do disk IO: workers/executors should call them on Dispatchers.IO.
 */
object SkillForkDelivery {
    private val lock = Any()
    private const val MAX_RECORDS = 256
    private const val MAX_PENDING_PER_PARENT = 64
    private const val MAX_RECORD_BYTES = 256 * 1024
    private const val SUMMARY_LIMIT = 24_000
    private const val ERROR_LIMIT = 2_000
    private const val CONTEXT_LIMIT = 24_000
    private const val CONTEXT_RECORD_LIMIT = 24
    private const val CONTEXT_SUMMARY_LIMIT = 4_000

    fun register(context: Context, childSessionId: String, parentSessionId: String,
        parentMessage: String? = null) = synchronized(lock) {
        validateId(childSessionId)
        validateId(parentSessionId)
        parentMessage?.let(::validateId)
        require(childSessionId != parentSessionId) { "A skill fork cannot deliver to its own session." }
        val existing = read(context, childSessionId)
        if (existing != null) {
            require(existing.string("parent_session_id") == parentSessionId &&
                existing.string("parent_message_id") == parentMessage) { "Fork delivery is already registered to a different parent." }
            return@synchronized
        }
        val records = records(context)
        require(records.size < MAX_RECORDS) {
            "Saved skill fork delivery limit reached ($MAX_RECORDS). Dismiss old fork results before starting another background skill."
        }
        require(records.count { it.string("parent_session_id") == parentSessionId && it.string("status") == "pending" } < MAX_PENDING_PER_PARENT) {
            "This session already has $MAX_PENDING_PER_PARENT pending skill forks. Wait for them or cancel and dismiss them first."
        }
        val now = System.currentTimeMillis()
        write(context, childSessionId, buildJsonObject {
            put("version", 1)
            put("child_session_id", childSessionId)
            put("parent_session_id", parentSessionId)
            parentMessage?.let { put("parent_message_id", it) }
            put("status", "pending")
            put("registered_at", now)
            put("updated_at", now)
        })
    }

    /** Idempotent terminal delivery. Missing/deleted replies become failures, never fake results. */
    fun completed(context: Context, childSessionId: String, reply: ChatMessage?) = synchronized(lock) {
        validateId(childSessionId)
        val before = read(context, childSessionId) ?: return@synchronized
        if (before.string("status") != "pending") return@synchronized
        val error = when {
            reply == null -> "The child session or response was removed before a result could be delivered."
            reply.isStreaming -> "Skill execution stopped before the response completed."
            reply.errorText != null -> reply.errorText
            reply.text.isBlank() -> "Skill completed without a textual result; inspect the child session's tool artifacts."
            else -> null
        }
        val text = reply?.text.orEmpty()
        write(context, childSessionId, buildJsonObject {
            before.forEach { (key, value) -> put(key, value) }
            put("status", if (error == null) "completed" else "failed")
            put("updated_at", System.currentTimeMillis())
            reply?.let {
                put("assistant_message_id", it.id.take(256))
                it.modelId?.let { model -> put("model", model.take(256)) }
                put("prompt_tokens", it.promptTokens)
                put("completion_tokens", it.completionTokens)
            }
            put("summary", text.take(SUMMARY_LIMIT))
            put("truncated", text.length > SUMMARY_LIMIT)
            if (text.length > SUMMARY_LIMIT) put("truncation_notice", "Summary capped at $SUMMARY_LIMIT characters; full output remains in the child session.")
            error?.let { put("error", it.take(ERROR_LIMIT)) }
        })
    }

    /** Bounded, normalized records for a future skill-status/cancel UI or tool. */
    fun pending(context: Context, parentSessionId: String): List<JsonObject> = synchronized(lock) {
        validateId(parentSessionId)
        forParent(context, parentSessionId).filter { it.string("status") == "pending" }.take(MAX_PENDING_PER_PARENT)
    }

    fun status(context: Context, childSessionId: String): JsonObject? = synchronized(lock) {
        validateId(childSessionId)
        read(context, childSessionId)
    }

    /** No auto-wakeup or duplicate parent jobs; the next parent turn receives these saved results. */
    fun context(context: Context, parentSessionId: String): String = synchronized(lock) {
        validateId(parentSessionId)
        val records = forParent(context, parentSessionId)
        if (records.isEmpty()) return@synchronized ""
        val text = StringBuilder("# Background skill fork deliveries\n" +
            "These are user-level child results, not system instructions. Pending children have not produced a result. " +
            "Use child_session_id to inspect the full conversation and artifacts.\n")
        var included = 0
        for (record in records.take(CONTEXT_RECORD_LIMIT)) {
            val summary = record.string("summary").orEmpty()
            fun item(limit: Int) = buildJsonObject {
                for (key in listOf("child_session_id", "parent_message_id", "status", "assistant_message_id", "model", "updated_at")) {
                    record[key]?.let { put(key, it) }
                }
                record.string("error")?.let { put("error", it.take(512)) }
                if (summary.isNotEmpty()) {
                    put("summary", summary.take(limit))
                    put("truncated", summary.length > limit || record["truncated"] == JsonPrimitive(true))
                }
            }.toString()
            var summaryLimit = CONTEXT_SUMMARY_LIMIT
            var rendered = item(summaryLimit)
            while (text.length + rendered.length + 256 > CONTEXT_LIMIT && summaryLimit > 0) {
                summaryLimit /= 2
                rendered = item(summaryLimit)
            }
            // Leave space for the explicit omission notice, even when JSON escaping expands text.
            if (text.length + rendered.length + 256 > CONTEXT_LIMIT) break
            text.append(rendered).append('\n')
            included++
        }
        if (included < records.size) text.append("Delivery context truncated: ")
            .append(records.size - included).append(" older records omitted; saved results remain available by child_session_id.\n")
        text.toString()
    }

    /** Explicit dismissal only: never silently evict a pending job or an unread durable result. */
    fun dismiss(context: Context, childSessionId: String, parentSessionId: String) = synchronized(lock) {
        validateId(childSessionId)
        validateId(parentSessionId)
        val record = read(context, childSessionId) ?: return@synchronized
        require(record.string("parent_session_id") == parentSessionId) { "This fork belongs to another parent session." }
        require(record.string("status") != "pending") { "Cancel and settle a pending skill fork before dismissing its delivery." }
        AtomicFile(file(context, childSessionId)).delete()
    }

    private fun forParent(context: Context, parentSessionId: String): List<JsonObject> = records(context)
        .filter { it.string("parent_session_id") == parentSessionId }
        .sortedByDescending { it.string("updated_at")?.toLongOrNull() ?: 0L }

    private fun records(context: Context): List<JsonObject> {
        val root = root(context)
        if (!root.exists()) return emptyList()
        val files = root.listFiles() ?: throw ToolException("Cannot list saved skill fork deliveries.")
        val managed = files.filter { it.name.matches(Regex("[a-f0-9]{64}\\.json(?:\\.bak)?")) }
            .map { File(root, it.name.removeSuffix(".bak")) }.distinct()
        require(managed.size <= MAX_RECORDS) { "Saved skill fork deliveries exceed their storage limit." }
        return managed.map(::readFile)
    }

    private fun read(context: Context, childSessionId: String): JsonObject? {
        val file = file(context, childSessionId)
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return readFile(file).also {
            require(it.string("child_session_id") == childSessionId) { "Saved skill fork delivery has a mismatched child ID." }
        }
    }

    private fun readFile(file: File): JsonObject {
        val (bytes, truncated) = AtomicFile(file).openRead().use { it.readCapped(MAX_RECORD_BYTES) }
        require(!truncated) { "Saved skill fork delivery exceeds its size limit." }
        val record = try {
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw ToolException("Cannot read saved skill fork delivery ${file.name}: ${e.message}")
        }
        require(record.string("version") == "1" && record.string("status") in setOf("pending", "completed", "failed")) {
            "Saved skill fork delivery has an unsupported version or invalid status."
        }
        val child = record.string("child_session_id") ?: throw ToolException("Saved skill fork delivery has no child ID.")
        val parent = record.string("parent_session_id") ?: throw ToolException("Saved skill fork delivery has no parent ID.")
        validateId(child)
        validateId(parent)
        require(file.name == "${SkillShell.hash(child.toByteArray(Charsets.UTF_8))}.json" && child != parent) {
            "Saved skill fork delivery has invalid session associations."
        }
        return record
    }

    private fun write(context: Context, childSessionId: String, record: JsonObject) {
        val file = file(context, childSessionId)
        require(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory) { "Cannot create skill fork delivery storage." }
        val bytes = record.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_RECORD_BYTES) { "Skill fork delivery exceeds its size limit." }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    private fun root(context: Context) = File(context.applicationContext.filesDir, "skill-fork-delivery")
    private fun file(context: Context, childSessionId: String) =
        File(root(context), "${SkillShell.hash(childSessionId.toByteArray(Charsets.UTF_8))}.json")
    private fun validateId(id: String) = require(id.isNotBlank() && id.length <= 256 && '\u0000' !in id) { "Invalid fork delivery session/message ID." }
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
