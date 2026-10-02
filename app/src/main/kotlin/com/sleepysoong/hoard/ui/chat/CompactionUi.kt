package com.sleepysoong.hoard.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.estimateTokens
import com.sleepysoong.hoard.ui.glass.GlassPopup
import com.sleepysoong.hoard.ui.glass.GlassPopupDivider
import com.sleepysoong.hoard.ui.glass.GlassPopupRow
import com.sleepysoong.hoard.ui.glass.GlassTone
import com.sleepysoong.hoard.ui.glass.GlassTypingDots
import com.sleepysoong.hoard.ui.glass.glassMaterial
import com.sleepysoong.hoard.ui.glass.liquidClickable

/**
 * A compaction marker in the chat: a quiet glass notice. Tap to read (or remove) the summary
 * the model now works from.
 */
@Composable
fun CompactionNotice(message: ChatMessage, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val failed = message.errorText != null
    val tint = if (failed) scheme.error else scheme.primary
    Box(modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .liquidClickable(onClick = onOpen)
                .glassMaterial(RoundedCornerShape(50), tint.copy(alpha = 0.10f), tone = GlassTone.Thin, enabled = !failed, lifted = false)
                .padding(horizontal = 14.dp, vertical = 7.dp)
                .testTag("compaction-notice"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                when {
                    message.isStreaming -> "대화 요약"
                    failed -> "요약 실패"
                    message.compaction?.auto == true -> "자동 압축"
                    else -> "대화 요약"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = tint,
                maxLines = 1
            )
            if (message.isStreaming) {
                GlassTypingDots()
            } else {
                val info = message.compaction
                Text(
                    when {
                        failed -> message.errorText ?: "요약에 실패했습니다"
                        info != null && info.summarized > 0 -> "메시지 ${info.summarized}개가 요약되었습니다"
                        else -> "이 대화는 요약되었습니다"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Read or remove a checkpoint (the preceding checkpoint or history becomes active again). */
@Composable
fun CompactionSheet(message: ChatMessage, onCopy: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val info = message.compaction
    GlassPopup(
        onDismiss = onDismiss,
        title = if (message.isStreaming) "대화 요약 중…" else "대화 요약",
        message = when {
            message.isStreaming -> "다음 답변부터는 이 요약과 최신 대화를 봅니다"
            message.errorText != null -> message.errorText
            info != null ->
                "메시지 ${info.summarized}개 요약" +
                    (if (info.tokensBefore > 0) " · 압축 전 컨텍스트 약 ${formatTokens(info.tokensBefore)} 토큰" else "") +
                    (if (info.auto) " · 자동" else "") +
                    (if (message.modelId.isNullOrBlank()) "" else " · ${message.modelId}")
            else -> null
        }
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (message.isStreaming) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassTypingDots()
                    Text("요약을 만드는 중입니다", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
            }
            info?.focus?.takeIf { it.isNotBlank() }?.let {
                WorkCard("남길 내용", it, scheme.onSurface)
            }
            if (message.text.isNotBlank()) {
                WorkCard("요약 · ${formatTokens(estimateTokens(message.text))} 토큰", message.text, scheme.onSurface, collapsedBodyLines = 10)
            }
            if (!message.isStreaming) {
                GlassPopupDivider()
                GlassPopupRow("복사", icon = Icons.Rounded.ContentCopy, onClick = onCopy)
                GlassPopupRow(
                    "요약 지우기",
                    icon = Icons.Rounded.Delete,
                    subtitle = "이전 요약 또는 원래 대화로 돌아갑니다",
                    destructive = true,
                    onClick = { onRemove(); onDismiss() }
                )
            }
        }
    }
}

/** 1,234-style grouping for big token counts. */
private fun formatTokens(n: Int): String = String.format("%,d", n)
