package com.sleepysoong.hoard.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ForkRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.MockData
import com.sleepysoong.hoard.ui.glass.GlassAnchoredMenu
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.sleepysoong.hoard.ui.glass.GlassAnimatedVisibility
import com.sleepysoong.hoard.ui.glass.GlassFloatingBar
import com.sleepysoong.hoard.ui.glass.GlassIconButton
import com.sleepysoong.hoard.ui.glass.GlassSheetAction
import com.sleepysoong.hoard.ui.glass.GlassSurface
import com.sleepysoong.hoard.ui.glass.GlassTone
import com.sleepysoong.hoard.ui.glass.glassMaterial
import com.sleepysoong.hoard.ui.glass.liquidClickable
import kotlinx.coroutines.launch

private const val GROUP_WINDOW_MS = 3 * 60 * 1000L

@Composable
fun ChatScreen(
    vm: ChatViewModel = viewModel(),
    showBackButton: Boolean = true,
    onBack: () -> Unit = {},
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
    var menuTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    // Save the bubble anchor across configuration change; Rect has no built-in
    // Saver, so persist the four plain floats.
    var menuAnchorRect by rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.Saver<Rect?, Any>(
            save = { it?.let { r -> listOf(r.left, r.top, r.right, r.bottom) } },
            restore = { s -> (s as? List<*>)?.let { l -> Rect(l[0] as Float, l[1] as Float, l[2] as Float, l[3] as Float) } }
        )
    ) { mutableStateOf<Rect?>(null) }
    var settingsAnchor by remember { mutableStateOf<Rect?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // Background replies post a notification; ask once, on the first send.
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

    val menuTarget = state.messages.firstOrNull { it.id == menuTargetId }
    val lastUserMessageId = state.messages.lastOrNull { it.role == MessageRole.User }?.id

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Shared floating bar; overrides via slots (back/title/action).
            GlassFloatingBar(
                title = session?.name ?: "Hoard",
                subtitle = "${session?.modelId ?: "hoard"} · ${state.usedTokens}/${session?.contextLimit ?: 32_000} 토큰",
                onTitleClick = { showModels = true },
                navigationIcon = {
                    GlassIconButton(
                        onClick = onBack,
                        enabled = showBackButton
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "세션 목록으로"
                        )
                    }
                },
                actions = {
                    Box(
                        Modifier.onGloballyPositioned { settingsAnchor = it.boundsInRoot() }
                    ) {
                        GlassIconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Rounded.Settings, contentDescription = "세션 설정")
                        }
                    }
                }
            )
            LinearProgressIndicator(
                progress = {
                    (state.usedTokens.toFloat() / (session?.contextLimit ?: 32_000).toFloat()).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                trackColor = Color.Transparent,
                drawStopIndicator = {}
            )

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val maxBubbleWidth = maxWidth * 0.78f
                if (state.messages.isEmpty()) {
                    // iOS-style empty state: glass mark, headline, suggestion chips.
                    Column(
                        Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            Modifier
                                .size(72.dp)
                                .glassMaterial(
                                    RoundedCornerShape(24.dp),
                                    MaterialTheme.colorScheme.surface,
                                    GlassTone.Thick
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "H",
                                style = MaterialTheme.typography.displayMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            "무엇을 도와드릴까요?",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(
                                "이 코드 리팩터링해 줘",
                                "요약해 줘",
                                "/summarize 세션 요약"
                            ).forEach { suggestion ->
                                GlassSuggestionChip(suggestion) {
                                    vm.input = suggestion + " "
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 6.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(state.messages, key = { _, m -> m.id }) { index, msg ->
                            val prev = state.messages.getOrNull(index - 1)
                            val next = state.messages.getOrNull(index + 1)
                            val grouped = prev != null &&
                                prev.role == msg.role &&
                                msg.createdAt - prev.createdAt < GROUP_WINDOW_MS
                            val footer = !grouped || next == null || next.role != msg.role ||
                                next.createdAt - msg.createdAt >= GROUP_WINDOW_MS
                            MessageBubble(
                                message = msg,
                                modelName = msg.modelId?.let { id ->
                                    MockData.models.firstOrNull { it.id == id }?.displayName
                                },
                                maxBubbleWidth = maxBubbleWidth,
                                groupedWithPrevious = grouped,
                                showFooter = footer,
                                isLastUserMessage = msg.id == lastUserMessageId,
                                onLongPress = { rect -> menuTargetId = msg.id; menuAnchorRect = rect },
                                modifier = Modifier.padding(
                                    top = if (grouped) 2.dp else 10.dp
                                )
                            )
                        }
                    }
                    // Floating glass scroll-to-bottom button.
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
                            modifier = Modifier.padding(10.dp)
                        ) {
                            Icon(
                                Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "맨 아래로",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            ChatInputBar(
                value = vm.input,
                onValueChange = { vm.input = it },
                attachments = vm.attachments,
                onAttachmentsChange = { vm.attachments = it },
                onSend = { if (session != null) sendReply(vm.input, session.modelId) }
            )
        }

        // Liquid-glass popup, drawn in the same window so the shader stays valid.
        GlassAnimatedVisibility(
            visible = menuTarget != null,
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.fillMaxSize()
        ) {
            if (menuTarget != null) {
                val isUser = menuTarget.role == MessageRole.User
                GlassAnchoredMenu(
                    anchor = menuAnchorRect,
                    title = if (isUser) "내 메시지" else "Hoard의 메시지",
                    message = menuTarget.text.take(80).ifBlank { "(첨부파일)" }
                        .let { if (menuTarget.text.length > 80) "$it…" else it },
                    actions = buildList {
                        add(
                            GlassSheetAction(
                                icon = Icons.Rounded.ContentCopy,
                                label = "복사",
                                onClick = { clipboard.setText(AnnotatedString(menuTarget.text)) }
                            )
                        )
                        if (isUser) {
                            add(
                                GlassSheetAction(
                                    icon = Icons.Rounded.Edit,
                                    label = "수정 후 다시 생성",
                                    onClick = { editTarget = menuTarget.id; editText = menuTarget.text }
                                )
                            )
                        } else {
                            add(
                                GlassSheetAction(
                                    icon = Icons.Rounded.Refresh,
                                    label = "다시 생성",
                                    onClick = { vm.retryFrom(menuTarget.id) }
                                )
                            )
                        }
                        add(
                            GlassSheetAction(
                                icon = Icons.Rounded.ForkRight,
                                label = "이 메시지에서 브랜치",
                                onClick = { branchTarget = menuTarget.id }
                            )
                        )
                        add(
                            GlassSheetAction(
                                icon = Icons.Rounded.Delete,
                                label = "삭제",
                                destructive = true,
                                onClick = { deleteTarget = menuTarget.id }
                            )
                        )
                    },
                    onDismiss = { menuTargetId = null; menuAnchorRect = null }
                )
            }
        }
    }

    if (showModels && session != null) {
        ModelPickerSheet(
            currentModelId = session.modelId,
            anchor = settingsAnchor,   // anchored right below the title bar
            onPick = { vm.setModel(it) },
            onDismiss = { showModels = false }
        )
    }
    if (showSettings && session != null) {
        SessionSettingsSheet(
            session = session,
            anchor = settingsAnchor,
            onRename = { vm.renameSession(it) },
            onSystemPrompt = { vm.setSystemPrompt(it) },
            onContextLimit = { vm.setContextLimit(it) },
            onDismiss = { showSettings = false; settingsAnchor = null }
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

/** Glass suggestion chip for the empty state. */
@Composable
private fun GlassSuggestionChip(text: String, onClick: () -> Unit) {
    GlassSurface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        tone = GlassTone.Thin,
        lifted = false
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .liquidClickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}

/** iOS-style tap target without the Material ripple. */
@Composable
private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interaction,
        indication = null,
        onClick = onClick
    )
}
