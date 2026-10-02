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
    val durationMs: Long,
    /** Model reasoning vs. a tool call (shown in separate groups under "작업"). */
    val kind: StepKind = StepKind.Reasoning,
    /** A tool call that failed (its error went back to the model). */
    val failed: Boolean = false,
    /** Still running (tool executing right now). */
    val running: Boolean = false
)

enum class StepKind { Reasoning, Tool }

/** One candidate the router tried for a reply (from sleepyrouter's routing trace). */
data class RouteAttempt(
    val index: Int,
    val model: String,
    val provider: String,
    val upstreamModel: String? = null,
    /** succeeded | failed | skipped | streaming | (upstream status, e.g. incomplete) */
    val outcome: String,
    val errorClass: String? = null,
    val statusCode: Int? = null,
    val reason: String? = null,
    val failedOver: Boolean? = null,
    val durationMs: Long = 0
) {
    val isFailure: Boolean get() = outcome == "failed" || outcome == "skipped"
}

/** How the router answered: requested → tried → selected. */
data class RoutingInfo(
    val requestedModel: String,
    val routeReason: String = "",
    val candidates: List<String> = emptyList(),
    val selectedModel: String? = null,
    val selectedProvider: String? = null,
    val attempts: List<RouteAttempt> = emptyList()
) {
    val failures: List<RouteAttempt> get() = attempts.filter { it.isFailure }

    /**
     * The router reports the selected candidate as "streaming" when the stream
     * commits; once the stream actually finishes the client knows the outcome.
     */
    fun withSelectedOutcome(outcome: String): RoutingInfo {
        val i = attempts.indexOfLast { it.model == selectedModel && it.outcome == "streaming" }
        if (i < 0) return this
        return copy(attempts = attempts.toMutableList().also { it[i] = it[i].copy(outcome = outcome) })
    }
}

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
    val isStreaming: Boolean = false,
    /** Router trace for assistant replies (null when the router sent none). */
    val routing: RoutingInfo? = null,
    /** Set when the reply failed; shown in the bubble instead of silently empty text. */
    val errorText: String? = null,
    /**
     * A user-role message the runtime created, not typed: "goal" (/goal objective),
     * "wakeup" (schedule_wakeup fired), "schedule" (a scheduled run's prompt),
     * [TRIGGER_COMPACT] (a compaction summary, see [compaction]).
     * Shown as a compact notice; sent to the model with a label.
     */
    val trigger: String? = null,
    /** Set on a compaction summary: what it covers. */
    val compaction: CompactionInfo? = null
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

/** [ChatMessage.trigger] of a compaction summary (/compact or the auto-compact threshold). */
const val TRIGGER_COMPACT = "compact"

/** A compaction marker: summarizing, done, or failed. */
val ChatMessage.isCompaction: Boolean get() = trigger == TRIGGER_COMPACT

/** A finished summary: requests start from the last one and skip everything before it. */
val ChatMessage.isCompactionSummary: Boolean
    get() = isCompaction && !isStreaming && errorText == null && text.isNotBlank()

/** What a compaction summary replaced. */
data class CompactionInfo(
    /** Started by the context threshold (true) or by /compact (false). */
    val auto: Boolean,
    /** Messages folded into this summary (the earlier summary it rolled up not counted). */
    val summarized: Int = 0,
    /** Estimated context tokens right before compacting. */
    val tokensBefore: Int = 0,
    /** `/compact <focus>` instructions, if any. */
    val focus: String? = null
)

data class ChatSession(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val modelId: String,
    val contextLimit: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val branchedFrom: String? = null,
    /** Set on the isolated session a scheduled run executes in. */
    val scheduleId: String? = null
)

data class AiModel(
    val id: String,
    val displayName: String,
    val vendor: String,
    val description: String,
    val supportsVision: Boolean = true,
    val supportsThinking: Boolean = true
)

/** Allowed context window range (tokens) for typed input. */
val CONTEXT_LIMIT_RANGE = 1_000..2_000_000

/** Parses a typed context limit ("32000", "32,000"); null when not a number or out of range. */
fun parseContextLimit(text: String): Int? =
    text.filterNot { it == ',' || it.isWhitespace() }.toIntOrNull()?.takeIf { it in CONTEXT_LIMIT_RANGE }

/**
 * Token estimate without a tokenizer. Latin text is ~4 chars/token, but Hangul, kanji
 * and kana cost about one token per character — `length / 4` under-counted Korean by
 * ~4×, so context trimming sent far more than the session limit.
 */
fun estimateTokens(text: String): Int {
    var cjk = 0
    var other = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (isWideScript(cp)) cjk++ else other++
        i += Character.charCount(cp)
    }
    return (cjk + (other + 3) / 4).coerceAtLeast(1)
}

private fun isWideScript(cp: Int): Boolean =
    cp in 0xAC00..0xD7A3 || // Hangul syllables
        cp in 0x1100..0x11FF || cp in 0x3130..0x318F || // Hangul jamo
        cp in 0x3040..0x30FF || // kana
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF // CJK ideographs

fun formatElapsed(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> String.format("%.1f초", ms / 1000f)
    else -> String.format("%d분 %d초", ms / 60_000, (ms % 60_000) / 1000)
}

/** Relative timestamp for list rows. Day boundaries are calendar days in the local zone. */
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
