package com.sleepysoong.hoard.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.formatElapsed
import com.sleepysoong.hoard.ui.glass.IOSTypingDots
import com.sleepysoong.hoard.ui.theme.IOSIncomingDark
import com.sleepysoong.hoard.ui.theme.IOSIncomingLight

/**
 * iMessage-style bubbles: blue for me, system gray for Hoard.
 * Chrome stays liquid glass; bubbles are iOS-solid like the real Messages app.
 */
@OptIn(ExperimentalLayoutApi::class)
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
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        message.thinking.forEach { step ->
                            Column {
                                Text(step.title, style = MaterialTheme.typography.labelSmall, color = textColor)
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
        Row(
            modifier = Modifier.padding(top = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            val actionTint = MaterialTheme.colorScheme.onSurfaceVariant
            val small = Modifier.size(36.dp)
            IconButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }, small) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "복사", tint = actionTint, modifier = Modifier.size(18.dp))
            }
            if (isUser) {
                IconButton(onClick = onEdit, small) {
                    Icon(Icons.Rounded.Edit, contentDescription = "수정", tint = actionTint, modifier = Modifier.size(18.dp))
                }
            } else {
                IconButton(onClick = onRetry, small) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "다시 생성", tint = actionTint, modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = onBranch, small) {
                Icon(Icons.Rounded.ForkRight, contentDescription = "브랜치", tint = actionTint, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, small) {
                Icon(Icons.Rounded.Delete, contentDescription = "삭제", tint = actionTint, modifier = Modifier.size(18.dp))
            }
        }
    }
}
