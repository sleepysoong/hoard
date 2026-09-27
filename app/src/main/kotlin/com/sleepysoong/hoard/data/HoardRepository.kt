package com.sleepysoong.hoard.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.util.UUID

/**
 * Conversation store: in-memory StateFlows, persisted to [HoardStore] so sessions,
 * messages (with routing traces) and tool toggles survive process death.
 * Writes are batched (debounced) off the main thread; [flush] forces one.
 */
class HoardRepository(private val store: HoardStore? = null) {
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

    /** Router groups/models (empty = no router: fall back to the mock catalog). */
    private val _routerModels = MutableStateFlow<List<AiModel>>(emptyList())
    val routerModels: StateFlow<List<AiModel>> = _routerModels.asStateFlow()

    fun setRouterModels(models: List<AiModel>) {
        _routerModels.value = models
        if (models.isNotEmpty()) migrateSessionModels(models)
    }

    /**
     * Sessions created offline (or before the router's catalog changed) may carry a
     * model the router doesn't know, e.g. the mock "hoard-1-pro". The router would
     * silently fall back to its default group while the top bar keeps showing the
     * stale ID — move those sessions to the router's first entry (its first group).
     */
    private fun migrateSessionModels(models: List<AiModel>) {
        val known = models.mapTo(HashSet()) { it.id }
        val target = models.first().id
        _sessions.update { list -> list.map { if (it.modelId in known) it else it.copy(modelId = target) } }
    }

    /** Models offered in the picker / Settings. */
    fun modelCatalog(): List<AiModel> = _routerModels.value.ifEmpty { MockData.models }

    fun messagesOf(sessionId: String): List<ChatMessage> = _messages.value[sessionId].orEmpty()

    fun sessionOf(sessionId: String): ChatSession? = _sessions.value.firstOrNull { it.id == sessionId }

    fun createSession(
        name: String = "새 세션",
        modelId: String = MockData.models[1].id,
        contextLimit: Int = 32_000
    ): ChatSession {
        val s = ChatSession(
            id = "session-" + UUID.randomUUID().toString().take(8),
            name = name,
            systemPrompt = MockData.DEFAULT_SYSTEM_PROMPT,
            modelId = modelId,
            contextLimit = contextLimit
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

    /**
     * Appends to an existing session only. Returns false when the session is gone
     * (deleted, or lost with the process) so late writers never resurrect it.
     */
    fun appendMessage(sessionId: String, message: ChatMessage): Boolean {
        var appended = false
        _messages.update { map ->
            val list = map[sessionId]
            appended = list != null
            if (list == null) map else map + (sessionId to list + message)
        }
        if (appended) touch(sessionId)
        return appended
    }

    /** Returns false when the session or the message no longer exists. */
    fun updateMessage(sessionId: String, messageId: String, transform: (ChatMessage) -> ChatMessage): Boolean {
        var found = false
        _messages.update { map ->
            val list = map[sessionId]
            found = list?.any { it.id == messageId } == true
            if (!found) map else map + (sessionId to list!!.map { if (it.id == messageId) transform(it) else it })
        }
        if (found) touch(sessionId)
        return found
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
        _messages.update { map ->
            val list = map[sessionId] ?: return@update map
            map + (sessionId to list.filterNot { it.id == messageId })
        }
        touch(sessionId)
    }

    fun truncateAfter(sessionId: String, messageId: String) {
        _messages.update { map ->
            val list = map[sessionId] ?: return@update map
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

    /** Enabled plugins, MCP servers and skills, as offered to the model. */
    fun enabledToolNames(): List<String> =
        _plugins.value.filter { it.enabled }.map { it.name } +
            _mcpServers.value.filter { it.enabled }.map { it.name } +
            _skills.value.filter { it.enabled }.map { it.name }

    private fun touch(sessionId: String) {
        _sessions.update { list -> list.map { if (it.id == sessionId) it.copy(updatedAt = System.currentTimeMillis()) else it } }
    }

    // ---- persistence -----------------------------------------------------------

    private val saver = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    init {
        store?.load()?.let(::restore)
        if (store != null) {
            saver.launch {
                // Any change → save at most every SAVE_DEBOUNCE_MS (streaming updates are frequent).
                // combine() emits once per change of any of the five; skip the initial state.
                kotlinx.coroutines.flow.combine(_sessions, _messages, _mcpServers, _plugins, _skills) { a, b, c, d, e -> listOf(a, b, c, d, e) }
                    .drop(1)
                    .debounce(SAVE_DEBOUNCE_MS)
                    .collect { flush() }
            }
        }
    }

    /** Write the current state now (also called on app background by HoardApp). */
    fun flush() {
        val s = store ?: return
        runCatching { s.save(snapshot()) }
    }

    private fun snapshot() = with(HoardStore) {
        HoardStore.Snapshot(
            sessions = _sessions.value.map { it.toS() },
            messages = _messages.value.mapValues { (_, list) -> list.map { it.toS() } },
            mcpServers = _mcpServers.value.map { HoardStore.SMcp(it.id, it.name, it.url, it.enabled, it.toolCount, it.status) },
            pluginEnabled = _plugins.value.associate { it.id to it.enabled },
            skillEnabled = _skills.value.associate { it.id to it.enabled }
        )
    }

    private fun restore(s: HoardStore.Snapshot) = with(HoardStore) {
        if (s.sessions.isEmpty()) return@with
        _sessions.value = s.sessions.map { it.toModel() }
        _messages.value = s.sessions.associate { sess ->
            sess.id to s.messages[sess.id].orEmpty().map { m ->
                val msg = m.toModel()
                // A reply that was streaming when the process died: its worker will either
                // resume it (same bubble) or it's gone — never leave a spinner forever.
                if (msg.isStreaming) msg.copy(isStreaming = false, errorText = msg.errorText ?: "앱이 종료되어 답변이 중단됐습니다.") else msg
            }
        }
        if (s.mcpServers.isNotEmpty()) {
            _mcpServers.value = s.mcpServers.map { McpServer(it.id, it.name, it.url, it.enabled, it.toolCount, it.status) }
        }
        _plugins.value = _plugins.value.map { p -> s.pluginEnabled[p.id]?.let { p.copy(enabled = it) } ?: p }
        _skills.value = _skills.value.map { k -> s.skillEnabled[k.id]?.let { k.copy(enabled = it) } ?: k }
    }

    companion object {
        const val SAVE_DEBOUNCE_MS = 400L
        @Volatile private var instance: HoardRepository? = null
        @Volatile private var storeFile: java.io.File? = null

        /** Called once from Application.onCreate: where the conversation file lives. */
        fun init(file: java.io.File) { storeFile = file }

        fun get(): HoardRepository = instance ?: synchronized(this) {
            instance ?: HoardRepository(storeFile?.let(::HoardStore)).also { instance = it }
        }

        internal fun initForTests(file: java.io.File?) { storeFile = file }

        /** Simulates a fresh process: memory is gone, whatever reached disk is reloaded. */
        internal fun resetForTests() = synchronized(this) {
            instance?.saver?.cancel()
            instance = null
        }
    }
}
