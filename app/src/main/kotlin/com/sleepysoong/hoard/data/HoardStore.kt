package com.sleepysoong.hoard.data

import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-disk form of the conversation store (sessions, messages incl. routing trace,
 * tool toggles). One JSON file, written atomically (temp file + rename), so a crash
 * mid-write leaves the previous version intact.
 *
 * Versioned: unknown future fields are ignored, and a file that can't be parsed is
 * moved aside as `*.corrupt-<time>` instead of being overwritten, so data is never
 * silently destroyed.
 */
class HoardStore(private val file: File) {

    @Serializable
    data class Snapshot(
        val version: Int = VERSION,
        val sessions: List<SSession> = emptyList(),
        val messages: Map<String, List<SMessage>> = emptyMap()
    )

    @Serializable data class SSession(
        val id: String, val name: String, val systemPrompt: String, val modelId: String,
        val contextLimit: Int, val createdAt: Long, val updatedAt: Long, val branchedFrom: String? = null
    )

    @Serializable data class SAttachment(val id: String, val name: String, val mime: String, val sizeBytes: Long, val uri: String? = null)
    @Serializable data class SThinking(val title: String, val detail: String, val durationMs: Long, val kind: String = "Reasoning", val failed: Boolean = false)
    @Serializable data class SAttempt(
        val index: Int, val model: String, val provider: String, val upstreamModel: String? = null, val outcome: String,
        val errorClass: String? = null, val statusCode: Int? = null, val reason: String? = null,
        val failedOver: Boolean? = null, val durationMs: Long = 0
    )
    @Serializable data class SRouting(
        val requestedModel: String, val routeReason: String = "", val candidates: List<String> = emptyList(),
        val selectedModel: String? = null, val selectedProvider: String? = null, val attempts: List<SAttempt> = emptyList()
    )
    @Serializable data class SMessage(
        val id: String, val role: String, val text: String, val modelId: String? = null,
        val thinking: List<SThinking> = emptyList(), val elapsedMs: Long = 0,
        val promptTokens: Int = 0, val completionTokens: Int = 0,
        val attachments: List<SAttachment> = emptyList(), val createdAt: Long = 0,
        val branchedFromId: String? = null, val isStreaming: Boolean = false,
        val routing: SRouting? = null, val errorText: String? = null
    )

    /** Null when there is no saved state yet (first launch) or it was unreadable. */
    fun load(): Snapshot? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString(Snapshot.serializer(), file.readText())
        } catch (e: Exception) {
            // Keep the bytes for recovery; start fresh rather than crash on every launch.
            runCatching { file.renameTo(File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis())) }
            null
        }
    }

    @Synchronized
    fun save(snapshot: Snapshot) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(Snapshot.serializer(), snapshot))
        if (!tmp.renameTo(file)) {
            // renameTo can fail across some filesystems when the target exists.
            file.delete()
            check(tmp.renameTo(file)) { "could not replace ${file.name}" }
        }
    }

    companion object {
        const val VERSION = 1
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun ChatSession.toS() = SSession(id, name, systemPrompt, modelId, contextLimit, createdAt, updatedAt, branchedFrom)
        fun SSession.toModel() = ChatSession(id, name, systemPrompt, modelId, contextLimit, createdAt, updatedAt, branchedFrom)

        fun ChatMessage.toS() = SMessage(
            id = id, role = role.name, text = text, modelId = modelId,
            thinking = thinking.map { SThinking(it.title, it.detail, it.durationMs, it.kind.name, it.failed) },
            elapsedMs = elapsedMs, promptTokens = promptTokens, completionTokens = completionTokens,
            attachments = attachments.map { SAttachment(it.id, it.name, it.mime, it.sizeBytes, it.uri?.toString()) },
            createdAt = createdAt, branchedFromId = branchedFromId, isStreaming = isStreaming,
            routing = routing?.let { r ->
                SRouting(r.requestedModel, r.routeReason, r.candidates, r.selectedModel, r.selectedProvider, r.attempts.map {
                    SAttempt(it.index, it.model, it.provider, it.upstreamModel, it.outcome, it.errorClass, it.statusCode, it.reason, it.failedOver, it.durationMs)
                })
            },
            errorText = errorText
        )

        fun SMessage.toModel(): ChatMessage = ChatMessage(
            id = id,
            role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.Assistant),
            text = text, modelId = modelId,
            thinking = thinking.map {
                ThinkingStep(it.title, it.detail, it.durationMs, runCatching { StepKind.valueOf(it.kind) }.getOrDefault(StepKind.Reasoning), it.failed)
            },
            elapsedMs = elapsedMs, promptTokens = promptTokens, completionTokens = completionTokens,
            attachments = attachments.map { UiAttachment(it.id, it.name, it.mime, it.sizeBytes, it.uri?.let(Uri::parse)) },
            createdAt = createdAt, branchedFromId = branchedFromId,
            isStreaming = isStreaming,
            routing = routing?.let { r ->
                RoutingInfo(r.requestedModel, r.routeReason, r.candidates, r.selectedModel, r.selectedProvider, r.attempts.map {
                    RouteAttempt(it.index, it.model, it.provider, it.upstreamModel, it.outcome, it.errorClass, it.statusCode, it.reason, it.failedOver, it.durationMs)
                })
            },
            errorText = errorText
        )
    }
}
