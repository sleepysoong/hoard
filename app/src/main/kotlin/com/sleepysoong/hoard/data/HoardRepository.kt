@file:OptIn(kotlinx.coroutines.FlowPreview::class) // debounce() for the store saver

package com.sleepysoong.hoard.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.util.UUID

/**
 * Conversation store: in-memory StateFlows, persisted to [HoardStore] so sessions,
 * messages (with routing traces) survive process death.
 * Writes are batched (debounced) off the main thread; [flush] forces one.
 */
class HoardRepository(private val store: HoardStore? = null) {
    val todos = com.sleepysoong.hoard.data.todo.TodoService(store?.todoDatabaseFile)
    /** No seeded sessions: a fresh install starts with an empty list. */
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<ChatMessage>>> = _messages.asStateFlow()

    /** Goals of every session (history included: cleared/completed goals are kept). */
    private val _goals = MutableStateFlow<List<Goal>>(emptyList())
    val goals: StateFlow<List<Goal>> = _goals.asStateFlow()

    private val _schedules = MutableStateFlow<List<Schedule>>(emptyList())
    val schedules: StateFlow<List<Schedule>> = _schedules.asStateFlow()

    private val _scheduleRuns = MutableStateFlow<List<ScheduleRun>>(emptyList())
    val scheduleRuns: StateFlow<List<ScheduleRun>> = _scheduleRuns.asStateFlow()

    /** Atomic read-modify-write for the goal/schedule services (they own the rules). */
    fun updateGoals(transform: (List<Goal>) -> List<Goal>) = _goals.update(transform)
    fun updateSchedules(transform: (List<Schedule>) -> List<Schedule>) = _schedules.update(transform)
    fun updateScheduleRuns(transform: (List<ScheduleRun>) -> List<ScheduleRun>) = _scheduleRuns.update(transform)

    /** Adds a whole new session (a scheduled run's isolated session). */
    fun addSession(session: ChatSession, messages: List<ChatMessage>) {
        // Registered first, like createSession: the todo tool refuses unknown sessions.
        todos.registerSessions(listOf(session.id))
        _sessions.update { listOf(session) + it }
        _messages.update { it + (session.id to messages) }
    }

    /** Router groups/models (empty = no router connected: nothing to pick). */
    private val _routerModels = MutableStateFlow<List<AiModel>>(emptyList())
    val routerModels: StateFlow<List<AiModel>> = _routerModels.asStateFlow()

    fun setRouterModels(models: List<AiModel>) {
        _routerModels.value = models
        if (models.isNotEmpty()) migrateSessionModels(models)
    }

    /**
     * Sessions created offline (or before the router's catalog changed) may carry a
     * model the router doesn't know, e.g. from an earlier router. The router would
     * silently fall back to its default group while the top bar keeps showing the
     * stale ID — move those sessions to the router's first entry (its first group).
     */
    private fun migrateSessionModels(models: List<AiModel>) {
        val known = models.mapTo(HashSet()) { it.id }
        val target = models.first().id
        _sessions.update { list -> list.map { if (it.modelId in known) it else it.copy(modelId = target) } }
    }

    /** Models offered in the picker / Settings. */
    fun modelCatalog(): List<AiModel> = _routerModels.value

    fun messagesOf(sessionId: String): List<ChatMessage> = _messages.value[sessionId].orEmpty()

    fun sessionOf(sessionId: String): ChatSession? = _sessions.value.firstOrNull { it.id == sessionId }

    fun createSession(
        name: String = "새 세션",
        modelId: String = "",
        contextLimit: Int = Defaults.CONTEXT_LIMIT
    ): ChatSession {
        val s = ChatSession(
            id = "session-" + UUID.randomUUID().toString().take(8),
            name = name,
            systemPrompt = Defaults.SYSTEM_PROMPT,
            modelId = modelId,
            contextLimit = contextLimit
        )
        todos.registerSessions(listOf(s.id))
        _sessions.update { listOf(s) + it }
        _messages.update { it + (s.id to emptyList()) }
        flush()
        return s
    }

    /** Tests: put a fully formed session (and its messages) at the top of the list. */
    internal fun insertSessionForTests(session: ChatSession, messages: List<ChatMessage>) {
        todos.registerSessions(listOf(session.id))
        _sessions.update { listOf(session) + it.filterNot { s -> s.id == session.id } }
        _messages.update { it + (session.id to messages) }
    }

    fun renameSession(id: String, name: String) {
        _sessions.update { list -> list.map { if (it.id == id) it.copy(name = name, updatedAt = System.currentTimeMillis()) else it } }
    }

    fun deleteSession(id: String) {
        todos.deleteSession(id)
        _sessions.update { it.filterNot { s -> s.id == id } }
        _messages.update { it - id }
        _goals.update { it.filterNot { g -> g.sessionId == id } }
        flush()
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

    /** Insert a reply beside the message it answers, ahead of later queued prompts.
     * The parent is looked up in the atomic update: deletion never resurrects it.
     */
    fun insertMessageAfter(sessionId: String, parentId: String, message: ChatMessage): Boolean {
        var inserted = false
        _messages.update { map ->
            val list = map[sessionId]
            val parent = list?.indexOfFirst { it.id == parentId } ?: -1
            inserted = parent >= 0
            if (!inserted) map else map + (sessionId to list!!.toMutableList().apply { add(parent + 1, message) })
        }
        if (inserted) touch(sessionId)
        return inserted
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

    /**
     * Inserts [message] right before [beforeId] (at the end when null). False when the session or
     * [beforeId] is gone, so a late writer never lands in the wrong place.
     */
    fun insertMessageBefore(sessionId: String, beforeId: String?, message: ChatMessage, expectedPrefix: List<ChatMessage>): Boolean {
        var inserted = false
        _messages.update { map ->
            val list = map[sessionId]
            val at = when {
                list == null -> -1
                beforeId == null -> list.size
                else -> list.indexOfFirst { it.id == beforeId }
            }
            // A send/edit/delete raced planning: never append the marker past unseen messages.
            inserted = at == expectedPrefix.size && list?.take(at) == expectedPrefix
            if (!inserted) map else map + (sessionId to list!!.toMutableList().apply { add(at, message) })
        }
        if (inserted) touch(sessionId)
        return inserted
    }

    /** Publish only if the source history still matches what was summarized, atomically. */
    fun finishCompaction(sessionId: String, markerId: String, expectedPrefix: List<ChatMessage>, summary: String, problem: String?): Boolean {
        var accepted = false
        _messages.update { map ->
            val list = map[sessionId] ?: return@update map
            val at = list.indexOfFirst { it.id == markerId }
            if (at < 0) return@update map
            // A stop may race the final summary frame. Never publish over a
            // marker already stopped (or otherwise finalized) by another writer.
            if (!list[at].isStreaming) return@update map
            val unchanged = at == expectedPrefix.size && list.take(at) == expectedPrefix
            accepted = unchanged && problem == null
            val error = if (unchanged) problem else "요약 중 이전 대화가 바뀌어 쓰지 않았습니다"
            map + (sessionId to list.map { if (it.id == markerId) it.copy(text = summary, isStreaming = false, errorText = error) else it })
        }
        touch(sessionId)
        return accepted
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
            val deletedAt = list.indexOfFirst { it.id == messageId }
            if (deletedAt < 0) return@update map
            // A later checkpoint may quote the deleted message (or earlier checkpoint).
            map + (sessionId to list.mapIndexedNotNull { index, message ->
                when {
                    index == deletedAt -> null
                    index > deletedAt && message.isCompaction -> message.copy(isStreaming = false, errorText = "이전 대화가 삭제되어 요약을 해제했습니다")
                    else -> message
                }
            })
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
        todos.forkSession(sessionId, branch.id)
        _sessions.update { listOf(branch) + it }
        _messages.update { it + (branch.id to list.take(idx + 1).map { m ->
            // The source session's worker cannot finish a reply copied into a branch:
            // any in-flight message is left visibly stopped there, never a phantom spinner.
            if (m.isStreaming) m.copy(
                branchedFromId = m.id, isStreaming = false,
                errorText = if (m.isCompaction) "진행 중인 요약은 브랜치에 적용하지 않았습니다"
                    else "브랜치 시점에 진행 중이던 답변입니다 · 원래 세션에서 이어집니다"
            ) else m.copy(branchedFromId = m.id)
        }) }
        flush()
        return branch
    }

    private fun touch(sessionId: String) {
        _sessions.update { list -> list.map { if (it.id == sessionId) it.copy(updatedAt = System.currentTimeMillis()) else it } }
    }

    // ---- persistence -----------------------------------------------------------

    private val saver = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    init {
        store?.load()?.let(::restore)
        todos.registerSessions(_sessions.value.map { it.id })
        if (store != null) {
            val initialState = listOf(_sessions.value, _messages.value, _goals.value, _schedules.value, _scheduleRuns.value)
            saver.launch {
                // Any change → save at most every SAVE_DEBOUNCE_MS (streaming updates are frequent).
                // combine() emits once per change of any of them. Skip only what equals the
                // state captured *before* this coroutine started: a plain drop(1) raced — a change
                // made before collection began arrived as the first value and was never saved.
                kotlinx.coroutines.flow.combine(_sessions, _messages, _goals, _schedules, _scheduleRuns) { a, b, c, d, e -> listOf(a, b, c, d, e) }
                    .dropWhile { it == initialState }
                    .debounce(SAVE_DEBOUNCE_MS)
                    .collect { flush() }
            }
        }
    }

    /** Last persistence failure (null = healthy). Surfaced in settings; writes keep retrying. */
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    /** Write the current state now (also called on app background by HoardApp). */
    fun flush() {
        val s = store ?: return
        try {
            s.save(snapshot())
            if (_saveError.value != null) _saveError.value = null
        } catch (e: Exception) {
            val msg = e.message ?: "저장 실패"
            android.util.Log.e("HoardStore", "persist failed: $msg", e)
            if (_saveError.value != msg) _saveError.value = msg
        }
    }

    private fun snapshot() = with(HoardStore) {
        HoardStore.Snapshot(
            sessions = _sessions.value.map { it.toS() },
            messages = _messages.value.mapValues { (_, list) -> list.map { it.toS() } },
            goals = _goals.value,
            schedules = _schedules.value,
            scheduleRuns = _scheduleRuns.value
        )
    }

    private fun restore(s: HoardStore.Snapshot) = with(HoardStore) {
        // Stores from older versions carry the canned welcome bubble (and its session,
        // when nothing else was said there): not user data, drop it.
        val msgs = s.messages.mapValues { (_, list) -> list.filterNot { it.id.startsWith(Defaults.LEGACY_WELCOME_PREFIX) } }
        val sessions = s.sessions.filterNot { it.id == Defaults.LEGACY_WELCOME_SESSION && msgs[it.id].isNullOrEmpty() }
        _goals.value = s.goals.filter { g -> sessions.any { it.id == g.sessionId } }
        _schedules.value = s.schedules
        _scheduleRuns.value = s.scheduleRuns
        if (sessions.isEmpty()) return@with
        _sessions.value = sessions.map { it.toModel() }
        _messages.value = sessions.associate { sess ->
            sess.id to msgs[sess.id].orEmpty().map { m ->
                val msg = m.toModel()
                // A reply that was streaming when the process died: its worker will either
                // resume it (same bubble) or it's gone — never leave a spinner forever.
                // (A compaction cut off this way is a failed marker: never sent, retried later.)
                if (msg.isStreaming) {
                    msg.copy(isStreaming = false, errorText = msg.errorText ?: if (msg.isCompaction) "앱이 종료되어 요약이 중단됐습니다." else "앱이 종료되어 답변이 중단됐습니다.")
                } else msg
            }
        }
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
            instance?.todos?.close()
            instance = null
        }
    }
}
