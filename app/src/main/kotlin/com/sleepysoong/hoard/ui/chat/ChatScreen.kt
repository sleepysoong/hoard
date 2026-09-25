package com.sleepysoong.hoard.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sleepysoong.hoard.data.MockData
import com.sleepysoong.hoard.data.UiAttachment
import com.sleepysoong.hoard.ui.glass.GlassIconButton
import com.sleepysoong.hoard.ui.glass.GlassSurface

@Composable
fun ChatScreen(
    vm: ChatViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val state by vm.uiState.collectAsState()
    val session = state.session
    var input by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<UiAttachment>>(emptyList()) }
    var showModels by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    var branchTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Top floating bar: session name + live context usage.
        GlassSurface(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            session?.name ?: "Hoard",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1
                        )
                        val limit = session?.contextLimit ?: 32_000
                        val ratio = (state.usedTokens.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
                        Text(
                            "${state.usedTokens} / $limit tokens (${(ratio * 100).toInt()}%) · ${session?.modelId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    GlassIconButton(onClick = { vm.newSession() }) {
                        androidx.compose.material3.Icon(Icons.Default.Add, contentDescription = "New session")
                    }
                    GlassIconButton(onClick = { showSettings = true }) {
                        androidx.compose.material3.Icon(Icons.Default.Settings, contentDescription = "Session settings")
                    }
                }
                val limit = session?.contextLimit ?: 32_000
                LinearProgressIndicator(
                    progress = { (state.usedTokens.toFloat() / limit.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.messages.isEmpty()) {
                Text(
                    "Start chatting — everything here is mock data.",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.messages, key = { it.id }) { msg ->
                        MessageBubble(
                            message = msg,
                            modelName = msg.modelId?.let { id -> MockData.models.firstOrNull { it.id == id }?.displayName },
                            onEdit = { editTarget = msg.id; editText = msg.text },
                            onDelete = { deleteTarget = msg.id },
                            onBranch = { branchTarget = msg.id },
                            onRetry = { vm.retryFrom(msg.id) }
                        )
                    }
                }
            }
        }

        val modelName = MockData.models.firstOrNull { it.id == session?.modelId }?.displayName ?: session?.modelId.orEmpty()
        ChatInputBar(
            value = input,
            onValueChange = { input = it },
            attachments = attachments,
            onAttachmentsChange = { attachments = it },
            modelName = modelName,
            onModelClick = { showModels = true },
            onSend = {
                if (session != null) {
                    vm.send(input, attachments, session.modelId)
                    input = ""
                    attachments = emptyList()
                }
            }
        )
    }

    if (showModels && session != null) {
        ModelPickerSheet(
            currentModelId = session.modelId,
            onPick = { vm.setModel(it) },
            onDismiss = { showModels = false }
        )
    }
    if (showSettings && session != null) {
        SessionSettingsSheet(
            session = session,
            onRename = { vm.renameSession(it) },
            onSystemPrompt = { vm.setSystemPrompt(it) },
            onContextLimit = { vm.setContextLimit(it) },
            onDismiss = { showSettings = false }
        )
    }
    if (editTarget != null) {
        EditMessageDialog(
            initial = editText,
            onConfirm = { vm.editUserMessage(editTarget!!, it); editTarget = null },
            onDismiss = { editTarget = null }
        )
    }
    if (branchTarget != null) {
        BranchDialog(
            onConfirm = { vm.branchFrom(branchTarget!!, it); branchTarget = null },
            onDismiss = { branchTarget = null }
        )
    }
    if (deleteTarget != null) {
        DeleteConfirmDialog(
            title = "Delete message?",
            message = "This removes the message from the session (mock).",
            onConfirm = { vm.deleteMessage(deleteTarget!!); deleteTarget = null },
            onDismiss = { deleteTarget = null }
        )
    }
}
