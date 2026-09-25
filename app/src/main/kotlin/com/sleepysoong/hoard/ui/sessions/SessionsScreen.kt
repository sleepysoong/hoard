package com.sleepysoong.hoard.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.ChatSession
import com.sleepysoong.hoard.data.formatRelativeTime
import com.sleepysoong.hoard.ui.chat.ChatViewModel
import com.sleepysoong.hoard.ui.chat.DeleteConfirmDialog
import com.sleepysoong.hoard.ui.chat.RenameSessionDialog
import com.sleepysoong.hoard.ui.glass.GlassAnchoredMenu
import com.sleepysoong.hoard.ui.glass.GlassButton
import com.sleepysoong.hoard.ui.glass.GlassEmptyState
import com.sleepysoong.hoard.ui.glass.GlassIconButton
import com.sleepysoong.hoard.ui.glass.GlassSheetAction
import com.sleepysoong.hoard.ui.glass.GlassSurface
import com.sleepysoong.hoard.ui.glass.GlassTokens
import com.sleepysoong.hoard.ui.glass.GlassTone

/**
 * Session hub. The floating glass bar matches the chat screen (centered
 * title + glass "+" button). Each session is its own liquid-glass card —
 * the cards read as tappable surfaces, and long-press opens the same
 * anchored glass action menu used in chat.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    vm: ChatViewModel,
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by vm.uiState.collectAsState()
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current

    var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var renameTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var menuTargetId by remember { mutableStateOf<String?>(null) }
    var menuAnchor by remember { mutableStateOf<Rect?>(null) }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Same floating glass bar as chat: centered title, glass "+" on the right.
            GlassSurface(shape = RoundedCornerShape(20.dp), tone = GlassTone.Thick) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Spacer matches the icon button width so the title centers.
                    Box(Modifier.size(44.dp))
                    Text(
                        "세션",
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    GlassIconButton(onClick = { vm.newSession(); onOpenChat() }) {
                        Icon(Icons.Rounded.Add, contentDescription = "새 세션")
                    }
                }
            }

            if (state.sessions.isEmpty()) {
                GlassEmptyState(
                    title = "세션이 없어요",
                    description = "첫 목업 세션을 만들어 보세요.",
                    action = { GlassButton(onClick = { vm.newSession(); onOpenChat() }) { Text("새 세션") } }
                )
            } else {
                Text(
                    "최근 대화 ${state.sessions.size}개",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp)
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    items(state.sessions, key = { it.id }) { s ->
                        SessionCard(
                            session = s,
                            previewText = state.previews[s.id]?.let { p ->
                                // Strip markdown-ish bold so "**word**" doesn't leak into previews.
                                val clean = p.text.replace(Regex("[*#`_~]"), "")
                                if (p.isUser) "나: $clean" else clean
                            },
                            onOpen = { vm.selectSession(s.id); onOpenChat() },
                            onLongPress = { rect ->
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuTargetId = s.id
                                menuAnchor = rect
                            }
                        )
                    }
                }
            }
        }

        if (menuTargetId != null) {
            val target = state.sessions.firstOrNull { it.id == menuTargetId }
            if (target != null) {
                GlassAnchoredMenu(
                    anchor = menuAnchor,
                    title = target.name,
                    message = null,
                    actions = listOf(
                        GlassSheetAction(
                            icon = Icons.Rounded.DriveFileRenameOutline,
                            label = "이름 바꾸기",
                            onClick = { renameTarget = menuTargetId }
                        ),
                        GlassSheetAction(
                            icon = Icons.Rounded.Delete,
                            label = "삭제",
                            destructive = true,
                            onClick = { deleteTarget = menuTargetId }
                        )
                    ),
                    onDismiss = { menuTargetId = null; menuAnchor = null }
                )
            }
        }
    }

    if (renameTarget != null) {
        val target = state.sessions.firstOrNull { it.id == renameTarget }
        if (target != null) {
            RenameSessionDialog(
                initial = target.name,
                onConfirm = { vm.renameSession(it); renameTarget = null },
                onDismiss = { renameTarget = null }
            )
        }
    }
    if (deleteTarget != null) {
        DeleteConfirmDialog(
            title = "세션을 삭제할까요?",
            message = "이 세션의 모든 메시지가 삭제됩니다.",
            onConfirm = { vm.deleteSession(deleteTarget!!); deleteTarget = null },
            onDismiss = { deleteTarget = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: ChatSession,
    previewText: String?,
    onOpen: () -> Unit,
    onLongPress: (Rect) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var bounds by remember { mutableStateOf(Rect.Zero) }

    GlassSurface(
        shape = RoundedCornerShape(GlassTokens.cardRadius),
        tone = GlassTone.Regular
    ) {
        val interaction = remember { MutableInteractionSource() }
        Row(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { bounds = it.boundsInRoot() }
                .combinedClickable(
                    interactionSource = interaction,
                    indication = androidx.compose.foundation.LocalIndication.current,
                    onClick = onOpen,
                    onLongClick = { onLongPress(bounds) },
                    onLongClickLabel = "세션 메뉴"
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        session.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (previewText != null) {
                        Text(
                            formatRelativeTime(session.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                    }
                }
                Text(
                    previewText ?: "새 대화",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}
