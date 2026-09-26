package com.sleepysoong.hoard.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** In-memory shell repository. A real backend replaces MockData/MockAiEngine only. */
class HoardRepository {
    private val _sessions = MutableStateFlow<List<ChatSession>>(listOf(MockData.welcomeSession()))
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<ChatMessage>>>(
        mapOf("session-welcome" to MockData.welcomeMessages())
    )
    val messages: StateFlow<Map<String, List<ChatMessage>>> = _messages.asStateFlow()

    private val _mcpServers = MutableStateFlow(MockData.mockMcpServers())
    val mcpServers: StateFlow<List<McpServer>> = _mcpServers.asStateFlow()

    private val _plugins = MutableStateFlow(MockData.plugins)
    val plugins: StateFlow<List<PluginItem>> = _plugins.asStateFlow()

    private val _skills = MutableStateFlow(MockData.skills)
    val skills: StateFlow<List<SkillItem>> = _skills.asStateFlow()

    fun messagesOf(sessionId: String): List<ChatMessage> = _messages.value[sessionId].orEmpty()

    fun sessionOf(sessionId: String): ChatSession? = _sessions.value.firstOrNull { it.id == sessionId }

    fun createSession(name: String = "새 세션", copyFrom: ChatSession? = null): ChatSession {
        val s = ChatSession(
            id = "session-" + UUID.randomUUID().toString().take(8),
            name = name,
            systemPrompt = copyFrom?.systemPrompt ?: MockData.DEFAULT_SYSTEM_PROMPT,
            modelId = copyFrom?.modelId ?: MockData.models[1].id,
            contextLimit = copyFrom?.contextLimit ?: 32_000
        )
        _sessions.update { listOf(s) + it }
        _messages.update { it + (s.id to emptyList()) }
        return s
    }

    fun renameSession(id: String, name: String) {
        _sessions.update { list -> list.map { if (it.id == id) it.copy(name = name, updatedAt = System.currentTimeMillis()) else it } }
    }

    fun deleteSession(id: String) {
        _sessions.update { it.filterNot { s -> s.id == id } }
        _messages.update { it - id }
    }

    fun updateSession(id: String, transform: (ChatSession) -> ChatSession) {
        _sessions.update { list -> list.map { if (it.id == id) transform(it).copy(updatedAt = System.currentTimeMillis()) else it } }
    }

    fun appendMessage(sessionId: String, message: ChatMessage) {
        _messages.update { it + (sessionId to (it[sessionId].orEmpty() + message)) }
        touch(sessionId)
    }

    fun updateMessage(sessionId: String, messageId: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.update { map ->
            map + (sessionId to map[sessionId].orEmpty().map { if (it.id == messageId) transform(it) else it })
        }
        touch(sessionId)
    }

    // Regenerate in place: drop the target message and everything after,
    // then stream a new response at that same spot.
    fun replaceSessionTail(sessionId: String, fromIndex: Int) {
        _messages.update { map ->
            map[sessionId]?.let { full ->
                if (fromIndex < full.size) map + (sessionId to full.take(fromIndex)) else map
            } ?: map
        }
        touch(sessionId)
    }

    fun deleteMessage(sessionId: String, messageId: String) {
        _messages.update { map -> map + (sessionId to map[sessionId].orEmpty().filterNot { it.id == messageId }) }
        touch(sessionId)
    }

    fun truncateAfter(sessionId: String, messageId: String) {
        _messages.update { map ->
            val list = map[sessionId].orEmpty()
            val idx = list.indexOfFirst { it.id == messageId }
            if (idx < 0) map else map + (sessionId to list.take(idx + 1))
        }
        touch(sessionId)
    }

    /** Branch a new session starting from (and including) the given message. */
    fun branchFrom(sessionId: String, messageId: String, branchName: String): ChatSession? {
        val src = sessionOf(sessionId) ?: return null
        val list = messagesOf(sessionId)
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx < 0) return null
        val branch = ChatSession(
            id = "session-" + UUID.randomUUID().toString().take(8),
            name = branchName,
            systemPrompt = src.systemPrompt,
            modelId = src.modelId,
            contextLimit = src.contextLimit,
            branchedFrom = sessionId
        )
        _sessions.update { listOf(branch) + it }
        _messages.update { it + (branch.id to list.take(idx + 1).map { m -> m.copy(branchedFromId = m.id) }) }
        return branch
    }

    fun setMcpEnabled(id: String, enabled: Boolean) {
        _mcpServers.update { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    }

    fun addMcpServer(name: String, url: String) {
        val s = McpServer(
            id = "mcp-" + UUID.randomUUID().toString().take(6),
            name = name.ifBlank { "새 MCP" },
            url = url.ifBlank { "https://mcp.mock/untitled" },
            enabled = true,
            toolCount = 3,
            status = "연결됨"
        )
        _mcpServers.update { it + s }
    }

    fun removeMcpServer(id: String) {
        _mcpServers.update { it.filterNot { s -> s.id == id } }
    }

    fun setPluginEnabled(id: String, enabled: Boolean) {
        _plugins.update { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    }

    fun setSkillEnabled(id: String, enabled: Boolean) {
        _skills.update { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    }

    private fun touch(sessionId: String) {
        _sessions.update { list -> list.map { if (it.id == sessionId) it.copy(updatedAt = System.currentTimeMillis()) else it } }
    }

    companion object {
        @Volatile private var instance: HoardRepository? = null
        fun get(): HoardRepository = instance ?: synchronized(this) {
            instance ?: HoardRepository().also { instance = it }
        }

        /** Simulates a fresh process: the in-memory store starts over. */
        internal fun resetForTests() = synchronized(this) { instance = null }
    }
}
