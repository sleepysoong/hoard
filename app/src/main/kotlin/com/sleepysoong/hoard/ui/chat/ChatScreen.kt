package com.sleepysoong.hoard.ui.chat

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.browser.BrowserPreviews
import com.sleepysoong.hoard.skills.SkillStore
import com.sleepysoong.hoard.data.isCompaction
import com.sleepysoong.hoard.ui.glass.GlassAnchoredMenu
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.sleepysoong.hoard.ui.glass.GlassAnimatedVisibility
import com.sleepysoong.hoard.ui.glass.GlassFloatingBar
import com.sleepysoong.hoard.ui.glass.GlassIconButton
import com.sleepysoong.hoard.ui.glass.GlassMotion
import com.sleepysoong.hoard.ui.glass.GlassSheetAction
import com.sleepysoong.hoard.ui.glass.GlassSurface
import com.sleepysoong.hoard.ui.glass.GlassTone
import com.sleepysoong.hoard.ui.glass.glassMaterial
import com.sleepysoong.hoard.ui.glass.liquidClickable
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val GROUP_WINDOW_MS = 3 * 60 * 1000L

@Composable
fun ChatScreen(
    vm: ChatViewModel = viewModel(),
    showBackButton: Boolean = true,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by vm.uiState.collectAsState()
    val settings by vm.settings.collectAsState()
    val routerReady by vm.routerReady.collectAsState()
    val replying by vm.replying.collectAsState()
    val browserPreview by BrowserPreviews.target.collectAsState()
    val context = LocalContext.current
    var skillStore by remember(context) { mutableStateOf<SkillStore?>(null) }
    val skills = skillStore?.skills?.collectAsState()?.value.orEmpty()
    var skillRefreshError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(context) {
        try {
            val loaded = withContext(Dispatchers.IO) {
                SkillStore.get(context).also { it.refresh() }
            }
            skillStore = loaded
            skillRefreshError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            skillRefreshError = "스킬을 새로고침하지 못했습니다: ${failure.message ?: "알 수 없는 오류"}"
        }
    }
    val routerModels by com.sleepysoong.hoard.data.HoardRepository.get().routerModels.collectAsState()
    val catalog = routerModels
    val session = state.session
    val retiringState = remember(session?.id) { mutableStateOf<String?>(null) }
    var retiringReply by retiringState
    androidx.compose.runtime.DisposableEffect(session?.id) {
        val retiringSession = session?.id
        onDispose {
            // Leaving the chat removes its old bubble too; do not abandon the
            // user's regeneration if navigation/rotation interrupts the exit.
            retiringState.value?.let { vm.retryFrom(it, retiringSession) }
        }
    }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var branchTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var compactionTarget by rememberSaveable { mutableStateOf<String?>(null) }
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
    // Model picker drops down from the top bar (its title opens it).
    var barAnchor by remember { mutableStateOf<Rect?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(retiringReply) {
        val targetId = retiringReply ?: return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == targetId } }
            .collect { visible ->
                if (!visible && retiringReply == targetId) {
                    retiringReply = null
                    vm.retryFrom(targetId, session?.id)
                }
            }
    }
    val clipboard = LocalClipboard.current

    // Notification permission is asked at app start (PermissionGate).
    val sendReply: (String, String) -> Unit = { prompt, modelId -> vm.send(prompt, vm.attachments, modelId) }

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

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Shared floating bar; overrides via slots (back/title/action).
            GlassFloatingBar(
                title = session?.name ?: "Hoard",
                subtitle = "${session?.modelId ?: "hoard"} · ${state.usedTokens}/${session?.contextLimit ?: 32_000} 토큰",
                onTitleClick = { showModels = true },
                modifier = Modifier.onGloballyPositioned { barAnchor = it.boundsInRoot() },
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
            // Context usage lives in the top bar subtitle ("96/32000 토큰"); the 2dp bar that
            // was here showed up as a stray dot under the bar at low usage.
            val goalNotice by vm.goalNotice.collectAsState()
            GoalBar(state.goal, goalNotice, onOpen = { vm.goalSheetOpen = true }, modifier = Modifier.padding(top = 6.dp))

            session?.let { TodoPanel(it.id, state.todos) }

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
                                "오늘 할 일 정리해 줘"
                            ).forEach { suggestion ->
                                GlassSuggestionChip(suggestion) {
                                    vm.input = suggestion + " "
                                }
                            }
                        }
                    }
                } else {
                    // Bubbles present when this chat opened appear at rest; only new ones pop in.
                    val initialIds = remember(session?.id) { state.messages.map { it.id }.toSet() }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 6.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(state.messages, key = { _, m -> m.id }) { index, msg ->
                            val prev = state.messages.getOrNull(index - 1)
                            val next = state.messages.getOrNull(index + 1)
                            val grouped = prev != null && prev.trigger == null && msg.trigger == null &&
                                prev.role == msg.role &&
                                msg.createdAt - prev.createdAt < GROUP_WINDOW_MS
                            val footer = !grouped || next == null || next.role != msg.role || next.trigger != null ||
                                next.createdAt - msg.createdAt >= GROUP_WINDOW_MS
                            if (msg.isCompaction) {
                                CompactionNotice(msg, onOpen = { compactionTarget = msg.id },
                                     modifier = Modifier.animateItem(fadeInSpec = null, placementSpec = GlassMotion.offsetSmooth(), fadeOutSpec = null).padding(top = 10.dp))
                            } else if (msg.trigger != null) {
                                TriggerNotice(msg, Modifier.animateItem(fadeInSpec = null, placementSpec = GlassMotion.offsetSmooth(), fadeOutSpec = null).padding(top = 10.dp))
                            } else MessageBubble(
                                message = msg,
                                modelName = msg.modelId?.let { id ->
                                    // Router models have no display name: show the ID itself.
                                    catalog.firstOrNull { it.id == id }?.displayName ?: id
                                },
                                maxBubbleWidth = maxBubbleWidth,
                                groupedWithPrevious = grouped,
                                showFooter = footer,
                                onLongPress = { rect -> menuTargetId = msg.id; menuAnchorRect = rect },
                                 animateEntrance = msg.id !in initialIds,
                                 exiting = msg.id == retiringReply,
                                 onExitFinished = {
                                     retiringReply = null
                                     vm.retryFrom(msg.id, session?.id)
                                 },
                                modifier = Modifier
                                    // Neighbours glide when a message is added, deleted or regenerated.
                                    .animateItem(
                                        fadeInSpec = null,
                                        placementSpec = GlassMotion.offsetSmooth(),
                                        fadeOutSpec = null
                                    )
                                    .padding(top = if (grouped) 2.dp else 10.dp)
                            )
                        }
                    }
                    // Floating glass scroll-to-bottom button.
                    GlassAnimatedVisibility(
                        visible = !atBottom,
                        enter = fadeIn(GlassMotion.fade()) + scaleIn(GlassMotion.bouncy(), initialScale = 0.5f),
                        exit = fadeOut(GlassMotion.fade()) + scaleOut(GlassMotion.exit(), targetScale = 0.6f),
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

            skillRefreshError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            browserPreview?.takeIf { it.sessionId == session?.id }?.let { preview ->
                BrowserLivePreview(preview, settings.browserPreviewQuality, vm::setBrowserPreviewQuality)
            }
            if (!routerReady) Text("라우터 연결 후 메시지를 보낼 수 있습니다 · 설정 → 라우터",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChatInputBar(
                value = vm.input,
                onValueChange = { vm.input = it },
                attachments = vm.attachments,
                onAttachmentsChange = { vm.attachments = it },
                onSend = { if (session != null) sendReply(vm.input, session.modelId) },
                replying = replying && vm.editingMessageId == null,
                onStop = { vm.stopReply() },
                goal = state.goal,
                skills = skills,
                sendEnabled = routerReady && retiringReply == null,
                editing = vm.editingMessageId != null,
                onCancelEdit = vm::cancelEditing
            )
        }

        // Liquid-glass popup, drawn in the same window so the shader stays valid.
        // (GlassAnchoredOverlay animates its own entry and exit.)
        run {
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
                                onClick = {
                                    val text = menuTarget.text
                                    scope.launch { clipboard.setClipEntry(android.content.ClipData.newPlainText("Hoard", text).toClipEntry()) }
                                }
                            )
                        )
                        if (isUser) {
                            add(
                                GlassSheetAction(
                                    icon = Icons.Rounded.Edit,
                                    label = "수정",
                                    onClick = { vm.beginEditing(menuTarget.id) }
                                )
                            )
                        } else {
                            add(
                                GlassSheetAction(
                                    icon = Icons.Rounded.Refresh,
                                    label = "다시 생성",
                                    onClick = {
                                        if (routerReady && retiringReply == null) {
                                            session?.let { com.sleepysoong.hoard.work.ChatResponseWorker.cancel(context, it.id) }
                                            retiringReply = menuTarget.id
                                            // Offscreen targets cannot finish an animation.
                                            if (listState.layoutInfo.visibleItemsInfo.none { it.key == menuTarget.id }) {
                                                retiringReply = null
                                                vm.retryFrom(menuTarget.id)
                                            }
                                        }
                                    }
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

    if (vm.goalSheetOpen) {
        GoalSheet(
            goal = state.goal,
            onPause = vm::pauseGoal, onResume = vm::resumeGoal, onClear = vm::clearGoal,
            onDismiss = { vm.goalSheetOpen = false }
        )
    }
    state.messages.firstOrNull { it.id == compactionTarget && it.isCompaction }?.let { target ->
        CompactionSheet(
            message = target,
            onCopy = {
                scope.launch { clipboard.setClipEntry(android.content.ClipData.newPlainText("Hoard", target.text).toClipEntry()) }
            },
            onRemove = { vm.deleteMessage(target.id) },
            onDismiss = { compactionTarget = null }
        )
    }
    if (showModels && session != null) {
        ModelPickerSheet(
            currentModelId = session.modelId,
            models = catalog,
            fromRouter = routerModels.isNotEmpty(),
            anchor = barAnchor,   // drops down right below the title bar
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
    if (branchTarget != null) {
        BranchDialog(
            onConfirm = { vm.branchFrom(branchTarget!!, it); branchTarget = null },
            onDismiss = { branchTarget = null }
        )
    }
    if (deleteTarget != null) {
        DeleteConfirmDialog(
            title = "메시지를 삭제할까요?",
            message = "세션에서 메시지가 삭제됩니다.",
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
