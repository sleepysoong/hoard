package com.sleepysoong.hoard.ui.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.ChatSession
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.data.UiAttachment
import com.sleepysoong.hoard.data.isCompaction
import com.sleepysoong.hoard.engine.Compaction
import com.sleepysoong.hoard.engine.ReplyRequest
import com.sleepysoong.hoard.skills.SkillRuntime
import com.sleepysoong.hoard.skills.SkillStore
import com.sleepysoong.hoard.work.ChatResponseWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class SessionPreview(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long
)

data class ChatUiState(
    val session: ChatSession? = null,
    val messages: List<ChatMessage> = emptyList(),
    val usedTokens: Int = 0,
    val sessions: List<ChatSession> = emptyList(),
    val previews: Map<String, SessionPreview> = emptyMap(),
    /** The open session's current goal (null = none, or cleared). */
    val goal: com.sleepysoong.hoard.data.Goal? = null,
    val todos: List<com.sleepysoong.hoard.data.todo.TodoItem> = emptyList()
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = HoardRepository.get()
    private val _activeSessionId = MutableStateFlow(repo.sessions.value.firstOrNull()?.id ?: "")

    val settings: StateFlow<SettingsStore.Settings> = SettingsStore.flow(app)
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.Settings())

    /** Draft + attachments live in the ViewModel so folding/unfolding never loses them. */
    var input by mutableStateOf("")
    var attachments by mutableStateOf<List<UiAttachment>>(emptyList())

    private val goals = com.sleepysoong.hoard.goal.GoalService(repo)

    /** Feedback for /goal and /compact commands (shown under the goal bar), cleared on the next command. */
    private val _goalNotice = MutableStateFlow<String?>(null)
    val goalNotice: StateFlow<String?> = _goalNotice

    /** "/goal" with no argument opens the goal details. */
    var goalSheetOpen by mutableStateOf(false)

    val uiState: StateFlow<ChatUiState> = combine(
        repo.sessions, repo.messages, _activeSessionId, repo.goals, repo.todos.state
    ) { sessions, allMessages, activeId, allGoals, allTodos ->
        val id = activeId.ifBlank { sessions.firstOrNull()?.id.orEmpty() }
        val msgs = allMessages[id].orEmpty()
        val previews = allMessages.mapNotNull { (key, list) ->
            list.lastOrNull { !it.isCompaction }?.let {
                key to SessionPreview(it.text, it.role == MessageRole.User, it.createdAt)
            }
        }.toMap()
        val session = sessions.firstOrNull { it.id == id }
        ChatUiState(
            session = session,
            messages = msgs,
            // The latest checkpoint replaces the older history only in model-facing context.
            usedTokens = session?.let { ReplyRequest.contextTokens(it.systemPrompt, ReplyRequest.contextWindow(msgs)) } ?: 0,
            sessions = sessions,
            previews = previews,
            goal = allGoals.lastOrNull { it.sessionId == id && it.status != com.sleepysoong.hoard.data.GoalStatus.Cleared },
            todos = allTodos[id].orEmpty()
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    fun selectSession(id: String) { _activeSessionId.value = id }

    /** New sessions start from the defaults chosen in Settings. */
    fun newSession(name: String = "새 세션"): String {
        val s = createDefaultSession(name)
        _activeSessionId.value = s.id
        return s.id
    }

    private fun createDefaultSession(name: String = "새 세션"): ChatSession {
        val d = settings.value
        // The model typed in Settings; blank = the router's first entry (its first group).
        val model = d.defaultModel.trim().ifEmpty { repo.modelCatalog().firstOrNull()?.id.orEmpty() }
        return repo.createSession(name, modelId = model, contextLimit = d.defaultContext)
    }

    fun send(text: String, attachments: List<UiAttachment>, modelId: String) {
        val session = uiState.value.session ?: return
        val clean = text.trim()
        if (clean.isEmpty() && attachments.isEmpty()) return
        if (attachments.isEmpty() && (clean == "/goal" || clean.startsWith("/goal "))) {
            input = ""
            goalCommand(clean.removePrefix("/goal").trim(), modelId)
            return
        }
        if (attachments.isEmpty() && (clean == "/compact" || clean.startsWith("/compact "))) {
            input = ""
            compactCommand(clean.removePrefix("/compact").trim(), modelId)
            return
        }
        // The user said something: a spin-suppressed goal may continue again.
        goals.suppressContinuation(session.id, false)
        val userMsg = ChatMessage(
            id = "msg-" + UUID.randomUUID().toString().take(8),
            role = MessageRole.User,
            text = clean.ifEmpty { "(첨부파일)" },
            attachments = attachments
        )
        repo.appendMessage(session.id, userMsg)
        input = ""
        this.attachments = emptyList()
        val replyId = "msg-" + UUID.randomUUID().toString().take(8)
        // Worker owns the reply so it survives the app going to background.
        ChatResponseWorker.enqueue(
            getApplication(), session.id, modelId, replyId, parentId = userMsg.id,
            needsNetwork = usesRouter()
        )
    }

    fun editUserMessage(messageId: String, newText: String) {
        val session = uiState.value.session ?: return
        val clean = newText.trim()
        if (clean.isEmpty()) return
        val messages = repo.messagesOf(session.id)
        val idx = messages.indexOfFirst { it.id == messageId }
        val target = messages.getOrNull(idx) ?: return
        if (target.role != MessageRole.User) return
        // Save & regenerate: the old reply and every later turn answered the old
        // text, so drop them and answer the edited message right after it.
        repo.replaceSessionTail(session.id, idx + 1)
        repo.updateMessage(session.id, messageId) { it.copy(text = clean) }
        val replyId = "msg-" + UUID.randomUUID().toString().take(8)
        ChatResponseWorker.enqueue(
            getApplication(), session.id, session.modelId, replyId,
            parentId = messageId, replacePending = true, needsNetwork = usesRouter()
        )
    }

    private fun usesRouter() = settings.value.routerUrl.isNotBlank()

    /** Stop the reply being generated in the open session (keeps what arrived so far). An active goal is paused. */
    fun stopReply() {
        val session = uiState.value.session ?: return
        ChatResponseWorker.cancel(getApplication(), session.id)
        if (goals.active(session.id) != null) {
            goals.pause(session.id, com.sleepysoong.hoard.goal.Actor.User)
            _goalNotice.value = "중지해서 목표를 일시정지했습니다 · /goal resume 으로 재개"
        }
    }

    /**
     * `/goal <objective>` sets a goal and starts on it; `/goal` shows it;
     * `/goal pause|resume|clear` are the user's lifecycle controls (the model has none of them).
     */
    fun goalCommand(arg: String, modelId: String) {
        val session = uiState.value.session ?: return
        _goalNotice.value = null
        try {
            when (arg.lowercase()) {
                "" -> goalSheetOpen = true
                "pause" -> goals.pause(session.id, com.sleepysoong.hoard.goal.Actor.User)
                "resume" -> {
                    goals.resume(session.id, com.sleepysoong.hoard.goal.Actor.User)
                    continueGoal(session.id, modelId)
                }
                "clear" -> goals.clear(session.id, com.sleepysoong.hoard.goal.Actor.User)
                else -> {
                    goals.create(session.id, arg, com.sleepysoong.hoard.goal.Actor.User)
                    // The objective becomes the turn that starts the work.
                    val msg = ChatMessage("msg-" + UUID.randomUUID().toString().take(8), MessageRole.User, arg, trigger = "goal")
                    repo.appendMessage(session.id, msg)
                    ChatResponseWorker.enqueue(
                        getApplication(), session.id, modelId, "msg-" + UUID.randomUUID().toString().take(8),
                        parentId = msg.id, needsNetwork = usesRouter()
                    )
                }
            }
        } catch (e: com.sleepysoong.hoard.goal.GoalException) {
            _goalNotice.value = e.message
        }
    }

    /** Queued behind replies so the checkpoint includes their completed output. */
    fun compactCommand(focus: String, modelId: String) {
        val session = uiState.value.session ?: return
        _goalNotice.value = null
        val messages = repo.messagesOf(session.id)
        if (Compaction.plan(session, messages) == null) {
            _goalNotice.value = "요약할 이전 대화가 없습니다"
            return
        }
        if (!usesRouter()) {
            _goalNotice.value = "대화 요약에는 라우터 연결이 필요합니다 · 설정에서 연결하세요"
            return
        }
        ChatResponseWorker.enqueue(
            getApplication(), session.id, modelId, "msg-" + UUID.randomUUID().toString().take(8),
            parentId = messages.last().id, needsNetwork = true,
            mode = ChatResponseWorker.MODE_COMPACT, focus = focus.take(Compaction.FOCUS_MAX_CHARS).ifBlank { null }
        )
    }

    /** UI buttons (goal sheet). */
    fun pauseGoal() = goalCommand("pause", uiState.value.session?.modelId.orEmpty())
    fun resumeGoal() = goalCommand("resume", uiState.value.session?.modelId.orEmpty())
    fun clearGoal() = goalCommand("clear", uiState.value.session?.modelId.orEmpty())

    /** Resume = pick the work up again with a continuation turn after the last message. */
    private fun continueGoal(sessionId: String, modelId: String) {
        val last = repo.messagesOf(sessionId).lastOrNull() ?: return
        ChatResponseWorker.enqueue(
            getApplication(), sessionId, modelId, "msg-" + UUID.randomUUID().toString().take(8),
            parentId = last.id, needsNetwork = usesRouter(), mode = ChatResponseWorker.MODE_CONTINUE
        )
    }

    fun deleteMessage(messageId: String) {
        val session = uiState.value.session ?: return
        repo.deleteMessage(session.id, messageId)
    }

    fun retryFrom(messageId: String) {
        val session = uiState.value.session ?: return
        // Act on the store, not the (possibly one frame stale) UI snapshot.
        val messages = repo.messagesOf(session.id)
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        // The reply answers the closest user message before it; everything after
        // the target is dropped below, so later prompts must never be used.
        val parent = messages.subList(0, idx).lastOrNull { it.role == MessageRole.User && !it.isCompaction }
            ?: return
        // Remove the old reply (same position) and everything after; then
        // regenerate at that same spot.
        repo.replaceSessionTail(session.id, idx)
        val replyId = "msg-" + UUID.randomUUID().toString().take(8)
        ChatResponseWorker.enqueue(
            getApplication(), session.id, session.modelId, replyId,
            parentId = parent.id, replacePending = true, needsNetwork = usesRouter()
        )
    }

    fun branchFrom(messageId: String, branchName: String): String? {
        val session = uiState.value.session ?: return null
        val branch = repo.branchFrom(session.id, messageId, branchName) ?: return null
        // The branch gets an independent snapshot of the open goal (parent_goal_id → original).
        goals.forkInto(session.id, branch.id)
        SkillRuntime.forkSession(SkillStore.get(getApplication()), session.id, branch.id)
        _activeSessionId.value = branch.id
        return branch.id
    }

    fun renameSession(name: String) {
        val session = uiState.value.session ?: return
        repo.renameSession(session.id, name.ifBlank { "제목 없음" })
    }

    fun deleteSession(id: String) {
        ChatResponseWorker.cancel(getApplication(), id)
        SkillRuntime.clearSession(SkillStore.get(getApplication()), id)
        repo.deleteSession(id)
        viewModelScope.launch {
            val remaining = repo.sessions.value
            _activeSessionId.value = remaining.firstOrNull()?.id ?: createDefaultSession().id
        }
    }

    fun setModel(modelId: String) {
        val session = uiState.value.session ?: return
        repo.updateSession(session.id) { it.copy(modelId = modelId) }
    }

    fun setSystemPrompt(prompt: String) {
        val session = uiState.value.session ?: return
        repo.updateSession(session.id) { it.copy(systemPrompt = prompt) }
    }

    fun setContextLimit(limit: Int) {
        val session = uiState.value.session ?: return
        repo.updateSession(session.id) { it.copy(contextLimit = limit) }
    }
}
