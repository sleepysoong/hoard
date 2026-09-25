package com.sleepysoong.hoard.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import com.sleepysoong.hoard.data.MockData
import com.sleepysoong.hoard.ui.glass.GlassAnimatedVisibility
import com.sleepysoong.hoard.ui.glass.GlassIconButton
import com.sleepysoong.hoard.ui.glass.GlassSurface
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    vm: ChatViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val state by vm.uiState.collectAsState()
    val session = state.session
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var editTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var editText by rememberSaveable { mutableStateOf("") }
    var branchTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Background replies post a notification; ask once, when the first reply is sent.
    val context = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val sendReply: (String, String) -> Unit = { prompt, modelId ->
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.send(prompt, vm.attachments, modelId)
    }
    // Follow new replies only while the user is already at the bottom.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1
        }
    }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text) {
        if (atBottom && state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // iOS-style nav bar: centered title, glass chrome.
        GlassSurface(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(onClick = { vm.newSession() }) {
                        androidx.compose.material3.Icon(Icons.Rounded.Add, contentDescription = "새 세션")
                    }
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            session?.name ?: "Hoard",
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        val limit = session?.contextLimit ?: 32_000
                        val ratio = (state.usedTokens.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
                        Text(
                            "${state.usedTokens} / $limit 토큰 (${(ratio * 100).toInt()}%) · ${session?.modelId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                    GlassIconButton(onClick = { showSettings = true }) {
                        androidx.compose.material3.Icon(Icons.Rounded.Settings, contentDescription = "세션 설정")
                    }
                }
                val limit = session?.contextLimit ?: 32_000
                LinearProgressIndicator(
                    progress = { (state.usedTokens.toFloat() / limit.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 8.dp, end = 8.dp)
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.messages.isEmpty()) {
                Text(
                    "대화를 시작해 보세요 — 전부 목업 데이터입니다.",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
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
                // iOS-style floating glass scroll-to-bottom button.
                GlassAnimatedVisibility(
                    visible = !atBottom,
                    enter = fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.8f),
                    exit = fadeOut(tween(120)) + scaleOut(tween(140), targetScale = 0.8f),
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) {
                    GlassIconButton(
                        onClick = {
                            scope.launch {
                                if (state.messages.isNotEmpty()) {
                                    listState.animateScrollToItem(state.messages.lastIndex)
                                }
                            }
                        },
                        modifier = Modifier.padding(8.dp)
                    ) {
                        androidx.compose.material3.Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "맨 아래로",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        val modelName = MockData.models.firstOrNull { it.id == session?.modelId }?.displayName ?: session?.modelId.orEmpty()
        ChatInputBar(
            value = vm.input,
            onValueChange = { vm.input = it },
            attachments = vm.attachments,
            onAttachmentsChange = { vm.attachments = it },
            modelName = modelName,
            onModelClick = { showModels = true },
            onSend = {
                if (session != null) {
                    sendReply(vm.input, session.modelId)
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
            title = "메시지를 삭제할까요?",
            message = "세션에서 메시지가 삭제됩니다 (목업).",
            onConfirm = { vm.deleteMessage(deleteTarget!!); deleteTarget = null },
            onDismiss = { deleteTarget = null }
        )
    }
}
