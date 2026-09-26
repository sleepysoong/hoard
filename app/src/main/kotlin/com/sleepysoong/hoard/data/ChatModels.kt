package com.sleepysoong.hoard.data

import android.net.Uri
import kotlinx.serialization.Serializable

enum class MessageRole { User, Assistant, System }

@Serializable
data class AttachmentMeta(
    val id: String,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val uri: String = ""
)

data class UiAttachment(
    val id: String,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val uri: Uri? = null
)

data class ThinkingStep(
    val title: String,
    val detail: String,
    val durationMs: Long
)

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val text: String,
    val modelId: String? = null,
    val thinking: List<ThinkingStep> = emptyList(),
    val elapsedMs: Long = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val attachments: List<UiAttachment> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val branchedFromId: String? = null,
    val isStreaming: Boolean = false
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

data class ChatSession(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val modelId: String,
    val contextLimit: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val branchedFrom: String? = null
)

data class AiModel(
    val id: String,
    val displayName: String,
    val vendor: String,
    val description: String,
    val supportsVision: Boolean = true,
    val supportsThinking: Boolean = true
)

data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val toolCount: Int,
    val status: String
)

data class PluginItem(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean
)

data class SkillItem(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean
)

data class SlashCommand(
    val command: String,
    val description: String,
    val hint: String
)

/** Allowed context window range (tokens) for typed input. */
val CONTEXT_LIMIT_RANGE = 1_000..2_000_000

/** Parses a typed context limit ("32000", "32,000"); null when not a number or out of range. */
fun parseContextLimit(text: String): Int? =
    text.filterNot { it == ',' || it.isWhitespace() }.toIntOrNull()?.takeIf { it in CONTEXT_LIMIT_RANGE }

fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

fun formatElapsed(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> String.format("%.1f초", ms / 1000f)
    else -> String.format("%d분 %d초", ms / 60_000, (ms % 60_000) / 1000)
}

/** Apple-style relative timestamp for list rows. Day boundaries are calendar days in the local zone. */
fun formatRelativeTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val diff = (now - epochMs).coerceAtLeast(0L)
    val minute = 60_000L
    val hour = 60 * minute
    val zone = java.time.ZoneId.systemDefault()
    val date = java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        diff < minute -> "방금"
        diff < hour -> "${diff / minute}분 전"
        date == today -> "${diff / hour}시간 전"
        date == today.minusDays(1) -> "어제"
        date.year == today.year -> "${date.monthValue}월 ${date.dayOfMonth}일"
        else -> "${date.year}.${date.monthValue}.${date.dayOfMonth}"
    }
}
