package com.sleepysoong.hoard.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.ChatSession
import com.sleepysoong.hoard.data.parseContextLimit
import com.sleepysoong.hoard.ui.glass.GlassPopup
import com.sleepysoong.hoard.ui.glass.GlassPopupDivider
import com.sleepysoong.hoard.ui.glass.GlassPopupRow
import com.sleepysoong.hoard.ui.glass.GlassTokenField
import com.sleepysoong.hoard.ui.glass.GlassTextField

// Every popup here is the shared GlassPopup (header card · body card · buttons).
// Only the body differs; never hand-build cards or buttons in this file.

@Composable
fun ModelPickerSheet(
    currentModelId: String,
    models: List<com.sleepysoong.hoard.data.AiModel>,
    fromRouter: Boolean,
    anchor: Rect?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    GlassPopup(
        onDismiss = onDismiss,
        title = "모델 선택",
        message = if (fromRouter) "sleepyrouter 그룹·모델" else "라우터가 연결되지 않았습니다 · 설정 → 라우터",
        anchor = anchor
    ) {
        if (models.isEmpty()) {
            GlassPopupRow(label = "모델 없음", subtitle = "라우터를 연결하면 그룹과 모델이 여기에 나옵니다", closesPopup = true, onClick = onDismiss)
        }
        models.forEachIndexed { i, model ->
            if (i > 0) GlassPopupDivider()
            GlassPopupRow(
                label = model.displayName,
                subtitle = "${model.vendor} · ${model.description}",
                selected = model.id == currentModelId,
                closesPopup = true,
                onClick = { onPick(model.id); onDismiss() }
            )
        }
    }
}

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
    var contextText by rememberSaveable(session.id) { mutableStateOf(session.contextLimit.toString()) }
    val contextLimit = parseContextLimit(contextText)

    GlassPopup(
        onDismiss = onDismiss,
        title = "세션 설정",
        message = "이 세션에만 적용됩니다.",
        anchor = anchor,
        confirmLabel = "저장",
        confirmEnabled = name.isNotBlank() && contextLimit != null,
        onConfirm = {
            onRename(name.trim()); onSystemPrompt(prompt); onContextLimit(contextLimit!!); onDismiss()
        },
        bodyPadding = PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassTextField(
                value = name, onValueChange = { name = it },
                label = { Text("세션 이름") }, singleLine = true,
                isError = name.isBlank(), supportingText = if (name.isBlank()) { { Text("이름을 입력하세요") } } else null
            )
            GlassTextField(
                value = prompt, onValueChange = { prompt = it },
                label = { Text("시스템 프롬프트") }, minLines = 3, maxLines = 8
            )
            GlassTokenField(value = contextText, onValueChange = { contextText = it }, valid = contextLimit != null)
        }
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
        GlassTextField(value = name, onValueChange = { name = it }, label = { Text("브랜치 이름") }, singleLine = true, isError = name.isBlank(), supportingText = if (name.isBlank()) { { Text("이름을 입력하세요") } } else null)
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
        GlassTextField(value = text, onValueChange = { text = it }, label = { Text("이름") }, singleLine = true, isError = text.isBlank(), supportingText = if (text.isBlank()) { { Text("이름을 입력하세요") } } else null)
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
