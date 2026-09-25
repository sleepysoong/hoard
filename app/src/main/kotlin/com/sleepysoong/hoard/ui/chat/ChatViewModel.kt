package com.sleepysoong.hoard.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.ChatSession
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.UiAttachment
import com.sleepysoong.hoard.work.ChatResponseWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatUiState(
    val session: ChatSession? = null,
    val messages: List<ChatMessage> = emptyList(),
    val usedTokens: Int = 0,
    val sessions: List<ChatSession> = emptyList()
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = HoardRepository.get()
    private val _activeSessionId = MutableStateFlow(repo.sessions.value.firstOrNull()?.id ?: "")

    val uiState: StateFlow<ChatUiState> = combine(
        repo.sessions, repo.messages, _activeSessionId
    ) { sessions, allMessages, activeId ->
        val id = activeId.ifBlank { sessions.firstOrNull()?.id.orEmpty() }
        val msgs = allMessages[id].orEmpty()
        ChatUiState(
            session = sessions.firstOrNull { it.id == id },
            messages = msgs,
            usedTokens = msgs.sumOf { it.totalTokens },
            sessions = sessions
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    fun selectSession(id: String) { _activeSessionId.value = id }

    fun newSession(name: String = "New session"): String {
        val base = uiState.value.session
        val s = repo.createSession(name, base)
        _activeSessionId.value = s.id
        return s.id
    }

    fun send(text: String, attachments: List<UiAttachment>, modelId: String) {
        val session = uiState.value.session ?: return
        val clean = text.trim()
        if (clean.isEmpty() && attachments.isEmpty()) return
        val userMsg = ChatMessage(
            id = "msg-" + UUID.randomUUID().toString().take(8),
            role = MessageRole.User,
            text = clean.ifEmpty { "(attachment)" },
            attachments = attachments
        )
        repo.appendMessage(session.id, userMsg)
        val replyId = "msg-" + UUID.randomUUID().toString().take(8)
        // Worker owns the reply so it survives the app going to background.
        ChatResponseWorker.enqueue(
            getApplication(), session.id, clean, modelId, replyId, attachments.isNotEmpty()
        )
    }

    fun editUserMessage(messageId: String, newText: String) {
        val session = uiState.value.session ?: return
        repo.updateMessage(session.id, messageId) { it.copy(text = newText) }
        // Regenerate the next assistant reply for the edited prompt.
        val replyId = "msg-" + UUID.randomUUID().toString().take(8)
        ChatResponseWorker.enqueue(
            getApplication(), session.id, newText, session.modelId, replyId, false
        )
    }

    fun deleteMessage(messageId: String) {
        val session = uiState.value.session ?: return
        repo.deleteMessage(session.id, messageId)
    }

    fun retryFrom(messageId: String) {
        val state = uiState.value
        val session = state.session ?: return
        val msg = state.messages.firstOrNull { it.id == messageId } ?: return
        val prompt = if (msg.role == MessageRole.User) msg.text else {
            state.messages.lastOrNull { it.role == MessageRole.User }?.text.orEmpty()
        }
        val replyId = "msg-" + UUID.randomUUID().toString().take(8)
        ChatResponseWorker.enqueue(getApplication(), session.id, prompt, session.modelId, replyId, false)
    }

    fun branchFrom(messageId: String, branchName: String): String? {
        val session = uiState.value.session ?: return null
        val branch = repo.branchFrom(session.id, messageId, branchName) ?: return null
        _activeSessionId.value = branch.id
        return branch.id
    }

    fun renameSession(name: String) {
        val session = uiState.value.session ?: return
        repo.renameSession(session.id, name.ifBlank { "Untitled" })
    }

    fun deleteSession(id: String) {
        repo.deleteSession(id)
        viewModelScope.launch {
            val remaining = repo.sessions.value
            _activeSessionId.value = remaining.firstOrNull()?.id ?: repo.createSession().id
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
