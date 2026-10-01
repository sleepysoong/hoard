package com.sleepysoong.hoard.engine

import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.ChatSession
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.estimateTokens

/**
 * Everything a real backend needs for one reply. Built from the store at the moment
 * the reply starts, so edits to the system prompt / model / tools are always current.
 */
data class ReplyRequest(
    val modelId: String,
    val systemPrompt: String,
    /** Oldest → newest, already trimmed to [contextLimit]; ends with the message being answered. */
    val history: List<ChatMessage>,
    val contextLimit: Int,
    /** Oldest messages left out to fit [contextLimit]. */
    val droppedCount: Int,
    /** Active goal, injected as ephemeral developer text (never stored in the conversation). */
    val goalContext: String? = null,
    /** Hidden runtime turn (goal continuation / budget summary): sent last, not stored. */
    val hiddenUserMessage: String? = null,
    /** Final budget-summary turn: tools offered but not callable. */
    val forbidTools: Boolean = false,
    /** Authoritative execution state, captured at turn start; never stored as chat history. */
    val todoReminder: String? = null,
    /** Previously activated skills, user-level context rather than privileged instructions. */
    val skillContext: String? = null,
    /** Responses reasoning.effort, only when the invoking skill explicitly sets it. */
    val reasoningEffort: String? = null
) {
    val question: String get() = history.lastOrNull { it.role == MessageRole.User }?.text.orEmpty()
    val hasAttachments: Boolean get() = history.lastOrNull { it.role == MessageRole.User }?.attachments?.isNotEmpty() == true
    val promptTokens: Int get() = contextTokens(systemPrompt, history) +
        (skillContext?.takeIf { it.isNotBlank() }?.let(::estimateTokens) ?: 0)

    /** Reserve activated instructions before retaining old chat turns. Never silently cut a skill. */
    fun withSkillContext(content: String?): ReplyRequest {
        if (content.isNullOrBlank()) return this
        val reserved = estimateTokens(systemPrompt) + estimateTokens(content)
        if (reserved >= contextLimit) throw com.sleepysoong.hoard.tools.ToolException(
            "활성 스킬 지침이 세션 컨텍스트 한도보다 큽니다 · 컨텍스트 한도를 높이거나 스킬을 비활성화하세요"
        )
        var budget = contextLimit - reserved
        val kept = ArrayDeque<ChatMessage>()
        for (message in history.asReversed()) {
            val cost = estimateTokens(message.text)
            if (kept.isNotEmpty() && cost > budget) break
            kept.addFirst(message)
            budget -= cost
        }
        return copy(skillContext = content, history = kept.toList(), droppedCount = droppedCount + history.size - kept.size)
    }

    companion object {
        /**
         * [conversation] is every message before the reply (the answered user message last).
         * Keeps the system prompt and the newest messages that fit; the answered message is
         * always kept even if it alone exceeds the limit.
         */
        fun build(session: ChatSession, conversation: List<ChatMessage>, modelId: String = session.modelId): ReplyRequest {
            val usable = conversation.filter(::isSendable)
            var budget = session.contextLimit - estimateTokens(session.systemPrompt)
            val kept = ArrayDeque<ChatMessage>()
            for (m in usable.asReversed()) {
                val cost = estimateTokens(m.text)
                if (kept.isNotEmpty() && cost > budget) break
                kept.addFirst(m)
                budget -= cost
            }
            return ReplyRequest(
                modelId = modelId,
                systemPrompt = session.systemPrompt,
                history = kept.toList(),
                contextLimit = session.contextLimit,
                droppedCount = usable.size - kept.size
            )
        }

        /** Token estimate of a prompt: system prompt + every message text. Also the top-bar usage. */
        fun contextTokens(systemPrompt: String, messages: List<ChatMessage>): Int =
            estimateTokens(systemPrompt) + messages.filter { it.text.isNotBlank() }.sumOf { estimateTokens(it.text) }

        /** Whether a message goes to the model: finished and non-empty. */
        fun isSendable(m: ChatMessage): Boolean = !m.isStreaming && m.text.isNotBlank()
    }
}
