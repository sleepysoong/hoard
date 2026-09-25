package com.sleepysoong.hoard.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.formatElapsed
import com.sleepysoong.hoard.ui.glass.GlassBadge
import com.sleepysoong.hoard.ui.glass.GlassCard
import com.sleepysoong.hoard.ui.glass.GlassSeverity

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
    val clipboard = LocalClipboardManager.current
    var thinkingOpen by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(if (isUser) 1f else 1f),
            shape = RoundedCornerShape(
                topStart = 20.dp, topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 20.dp
            )
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isUser && modelName != null) {
                    GlassBadge(modelName, severity = GlassSeverity.Info)
                }
                if (message.attachments.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        message.attachments.forEach { a ->
                            GlassBadge("📎 ${a.name}", severity = GlassSeverity.Success)
                        }
                    }
                }
                if (!isUser && message.thinking.isNotEmpty()) {
                    Row(
                        Modifier
                            .clickable { thinkingOpen = !thinkingOpen }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Thinking (${message.thinking.size} steps)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            if (thinkingOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null
                        )
                    }
                    AnimatedVisibility(thinkingOpen) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            message.thinking.forEach { step ->
                                Column {
                                    Text(step.title, style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        "${step.detail} · ${formatElapsed(step.durationMs)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                if (message.text.isEmpty() && message.isStreaming) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Thinking…", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(message.text, style = MaterialTheme.typography.bodyMedium)
                }
                if (!isUser) {
                    Text(
                        "${formatElapsed(message.elapsedMs)} · ${message.totalTokens} tokens",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                    }
                    if (isUser) {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                    } else {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry")
                        }
                    }
                    IconButton(onClick = onBranch) {
                        Icon(Icons.Default.ForkRight, contentDescription = "Branch")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }
            }
        }
    }
}
