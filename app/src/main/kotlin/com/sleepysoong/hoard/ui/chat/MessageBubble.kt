package com.sleepysoong.hoard.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ForkRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.formatElapsed
import com.sleepysoong.hoard.ui.glass.GlassActionCapsule
import com.sleepysoong.hoard.ui.glass.GlassSurface
import com.sleepysoong.hoard.ui.glass.IOSTypingDots
import com.sleepysoong.hoard.ui.theme.IOSIncomingDark
import com.sleepysoong.hoard.ui.theme.IOSIncomingLight

/**
 * iMessage-style bubbles: blue for me, system gray for Hoard.
 * Chrome stays liquid glass; bubbles are iOS-solid like the real Messages app.
 * Long-press opens the floating glass action capsule (iMessage interaction).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    modelName: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBranch: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.User
    val dark = isSystemInDarkTheme()
    val clipboard = LocalClipboardManager.current
    var thinkingOpen by remember { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val bubbleColor: Color
    val textColor: Color
    if (isUser) {
        bubbleColor = MaterialTheme.colorScheme.primary
        textColor = Color.White
    } else {
        bubbleColor = if (dark) IOSIncomingDark else IOSIncomingLight
        textColor = MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // iMessage-style floating glass action menu, anchored above the bubble.
        AnimatedVisibility(
            visible = actionsOpen,
            enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.86f),
            exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.86f)
        ) {
            GlassActionCapsule(
                actions = buildList {
                    add(Icons.Rounded.ContentCopy to { clipboard.setText(AnnotatedString(message.text)) })
                    if (isUser) add(Icons.Rounded.Edit to onEdit) else add(Icons.Rounded.Refresh to onRetry)
                    add(Icons.Rounded.ForkRight to onBranch)
                    add(Icons.Rounded.Delete to onDelete)
                },
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .then(
                        if (isUser) Modifier.align(Alignment.End)
                        else Modifier.align(Alignment.Start)
                    )
            )
        }
        if (!isUser && modelName != null) {
            Text(
                modelName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 2.dp)
            )
        }
        Column(
            Modifier
                .fillMaxWidth(0.85f)
                .clip(
                    RoundedCornerShape(
                        topStart = 20.dp, topEnd = 20.dp,
                        bottomStart = if (isUser) 20.dp else 6.dp,
                        bottomEnd = if (isUser) 6.dp else 20.dp
                    )
                )
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        actionsOpen = !actionsOpen
                    },
                    onLongClickLabel = "메시지 메뉴"
                )
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (message.attachments.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    message.attachments.forEach { a ->
                        Text(
                            "📎 ${a.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor
                        )
                    }
                }
            }
            if (!isUser && message.thinking.isNotEmpty()) {
                Row(
                    Modifier
                        .clickable { thinkingOpen = !thinkingOpen }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        "생각 중 ${message.thinking.size}단계",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (dark) Color(0xFF9AB8FF) else MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        if (thinkingOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AnimatedVisibility(thinkingOpen) {
                    // Glass inset panel for the reasoning trace.
                    GlassSurface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            message.thinking.forEach { step ->
                                Column {
                                    Text(
                                        step.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "${step.detail} · ${formatElapsed(step.durationMs)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (message.text.isEmpty() && message.isStreaming) {
                IOSTypingDots(Modifier.padding(vertical = 6.dp))
            } else {
                Text(message.text, style = MaterialTheme.typography.bodyLarge, color = textColor)
            }
        }
        if (!isUser) {
            Text(
                "${formatElapsed(message.elapsedMs)} · ${message.totalTokens} 토큰",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 2.dp)
            )
        }
    }
}
