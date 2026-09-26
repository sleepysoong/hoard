package com.sleepysoong.hoard.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.ChatSession
import com.sleepysoong.hoard.data.MockData
import com.sleepysoong.hoard.ui.glass.GlassPopup
import com.sleepysoong.hoard.ui.glass.GlassPopupDivider
import com.sleepysoong.hoard.ui.glass.GlassPopupRow
import com.sleepysoong.hoard.ui.glass.GlassSlider
import com.sleepysoong.hoard.ui.glass.GlassTextField

// Every popup here is the shared GlassPopup (header card · body card · buttons).
// Only the body differs; never hand-build cards or buttons in this file.

@Composable
fun ModelPickerSheet(
    currentModelId: String,
    anchor: Rect?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    GlassPopup(
        onDismiss = onDismiss,
        title = "모델 선택",
        message = "응답은 목업 데이터로 생성됩니다.",
        anchor = anchor
    ) {
        MockData.models.forEachIndexed { i, model ->
            if (i > 0) GlassPopupDivider()
            GlassPopupRow(
                label = model.displayName,
                subtitle = "${model.vendor} · ${model.description}",
                selected = model.id == currentModelId,
                onClick = { onPick(model.id); onDismiss() }
            )
        }
    }
}

/** Context sizes offered for a session (tokens). */
val CONTEXT_OPTIONS = listOf(4_000, 8_000, 16_000, 32_000, 64_000, 128_000)

@Composable
fun SessionSettingsSheet(
    session: ChatSession,
    anchor: Rect?,
    onRename: (String) -> Unit,
    onSystemPrompt: (String) -> Unit,
    onContextLimit: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable(session.id) { mutableStateOf(session.name) }
    var prompt by rememberSaveable(session.id) { mutableStateOf(session.systemPrompt) }
    var contextIdx by rememberSaveable(session.id) {
        mutableIntStateOf(
            CONTEXT_OPTIONS.indices.minBy { kotlin.math.abs(CONTEXT_OPTIONS[it] - session.contextLimit) }
        )
    }

    GlassPopup(
        onDismiss = onDismiss,
        title = "세션 설정",
        message = "이 세션에만 적용됩니다 (목업).",
        anchor = anchor,
        confirmLabel = "저장",
        confirmEnabled = name.isNotBlank(),
        onConfirm = {
            onRename(name.trim()); onSystemPrompt(prompt); onContextLimit(CONTEXT_OPTIONS[contextIdx]); onDismiss()
        },
        bodyPadding = PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassTextField(
                value = name, onValueChange = { name = it },
                label = { Text("세션 이름") }, singleLine = true
            )
            GlassTextField(
                value = prompt, onValueChange = { prompt = it },
                label = { Text("시스템 프롬프트") }, minLines = 3, maxLines = 8
            )
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text("컨텍스트", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        "%,d 토큰".format(CONTEXT_OPTIONS[contextIdx]),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                GlassSlider(
                    value = contextIdx.toFloat(),
                    onValueChange = { contextIdx = it.toInt().coerceIn(0, CONTEXT_OPTIONS.lastIndex) },
                    valueRange = 0f..CONTEXT_OPTIONS.lastIndex.toFloat(),
                    steps = CONTEXT_OPTIONS.size - 2
                )
            }
        }
    }
}

@Composable
fun EditMessageDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    GlassPopup(
        onDismiss = onDismiss,
        title = "메시지 수정",
        message = "이후 대화는 지워지고 이 메시지부터 다시 생성합니다.",
        confirmLabel = "다시 생성",
        confirmEnabled = text.isNotBlank(),
        onConfirm = { onConfirm(text) },
        bodyPadding = PaddingValues(16.dp)
    ) {
        GlassTextField(value = text, onValueChange = { text = it }, maxLines = 10, minLines = 3)
    }
}

@Composable
fun BranchDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("브랜치") }
    GlassPopup(
        onDismiss = onDismiss,
        title = "여기서 브랜치 만들기",
        message = "이 메시지까지의 대화로 새 세션을 만듭니다.",
        confirmLabel = "만들기",
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onConfirm(name.trim()) },
        bodyPadding = PaddingValues(16.dp)
    ) {
        GlassTextField(value = name, onValueChange = { name = it }, label = { Text("브랜치 이름") }, singleLine = true)
    }
}

@Composable
fun RenameSessionDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    GlassPopup(
        onDismiss = onDismiss,
        title = "세션 이름 변경",
        confirmLabel = "변경",
        confirmEnabled = text.isNotBlank(),
        onConfirm = { onConfirm(text.trim()) },
        bodyPadding = PaddingValues(16.dp)
    ) {
        GlassTextField(value = text, onValueChange = { text = it }, label = { Text("이름") }, singleLine = true)
    }
}

@Composable
fun DeleteConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    GlassPopup(
        onDismiss = onDismiss,
        title = title,
        message = message,
        confirmLabel = "삭제",
        confirmDestructive = true,
        onConfirm = onConfirm
    )
}
