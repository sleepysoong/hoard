package com.sleepysoong.hoard.engine

import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.ChatSession
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.estimateTokens
import com.sleepysoong.hoard.data.isCompaction
import com.sleepysoong.hoard.data.isCompactionSummary

/**
 * Everything a real backend needs for one reply. Built from the store at the moment
 * the reply starts, so edits to the system prompt / model / tools are always current.
 */
data class ReplyRequest(
    val modelId: String,
    val systemPrompt: String,
    /**
     * Oldest → newest, already trimmed to [contextLimit]; ends with the message being answered.
     * Starts with the latest compaction summary when there is one (it stands in for everything before it).
     */
    val history: List<ChatMessage>,
    val contextLimit: Int,
    /** Oldest messages (after the compaction summary, if any) left out to fit [contextLimit]. */
    val droppedCount: Int,
    /** Active goal, injected as ephemeral developer text (never stored in the conversation). */
    val goalContext: String? = null,
    /** Hidden runtime turn (goal continuation / budget summary): sent last, not stored. */
    val hiddenUserMessage: String? = null,
    /** Final budget-summary turn: tools offered but not callable. */
    val forbidTools: Boolean = false,
    /** Authoritative execution state, captured at turn start; never stored as chat history. */
    val todoReminder: String? = null
) {
    val question: String get() = history.lastOrNull { it.role == MessageRole.User }?.text.orEmpty()
    val hasAttachments: Boolean get() = history.lastOrNull { it.role == MessageRole.User }?.attachments?.isNotEmpty() == true
    val promptTokens: Int get() = contextTokens(systemPrompt, history)

    companion object {
        /**
         * [conversation] is every message before the reply (the answered user message last).
         * Keeps the system prompt, the latest compaction summary and the newest messages after it
         * that fit; the answered message is always kept even if it alone exceeds the limit.
         */
        fun build(session: ChatSession, conversation: List<ChatMessage>, modelId: String = session.modelId): ReplyRequest {
            val usable = contextWindow(conversation).filter(::isSendable)
            // A compaction summary stands in for everything before it: always sent, first.
            val summary = usable.firstOrNull()?.takeIf { it.isCompactionSummary }
            val rest = if (summary == null) usable else usable.drop(1)
            var budget = session.contextLimit - estimateTokens(session.systemPrompt) - (summary?.let(::messageTokens) ?: 0)
            val kept = ArrayDeque<ChatMessage>()
            for (m in rest.asReversed()) {
                val cost = messageTokens(m)
                if (kept.isNotEmpty() && cost > budget) break
                kept.addFirst(m)
                budget -= cost
            }
            val dropped = rest.size - kept.size
            summary?.let(kept::addFirst)
            return ReplyRequest(
                modelId = modelId,
                systemPrompt = session.systemPrompt,
                history = kept.toList(),
                contextLimit = session.contextLimit,
                droppedCount = dropped
            )
        }

        /**
         * What the model sees of [conversation]: the latest finished compaction summary (it stands
         * in for everything before it) and everything after it, minus compaction markers that never
         * became a summary (running or failed).
         */
        fun contextWindow(conversation: List<ChatMessage>): List<ChatMessage> {
            val from = conversation.indexOfLast { it.isCompactionSummary }.coerceAtLeast(0)
            return conversation.subList(from, conversation.size).filter { !it.isCompaction || it.isCompactionSummary }
        }

        /** Token estimate of a prompt: system prompt + every message text. Also the top-bar usage (over [contextWindow]). */
        fun contextTokens(systemPrompt: String, messages: List<ChatMessage>): Int =
            estimateTokens(systemPrompt) + messages.filter { it.text.isNotBlank() }.sumOf(::messageTokens)

        private fun messageTokens(message: ChatMessage): Int =
            estimateTokens(if (message.isCompactionSummary) Compaction.forModel(message.text) else message.text)

        /** Whether a message goes to the model: finished, non-empty, and not a compaction marker that never became a summary. */
        fun isSendable(m: ChatMessage): Boolean =
            !m.isStreaming && m.text.isNotBlank() && (!m.isCompaction || m.isCompactionSummary)
    }
}
