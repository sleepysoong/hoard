package com.sleepysoong.hoard.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.ChatSession
import com.sleepysoong.hoard.data.MockData
import com.sleepysoong.hoard.ui.glass.GlassButton
import com.sleepysoong.hoard.ui.glass.GlassCard
import com.sleepysoong.hoard.ui.glass.GlassDialog
import com.sleepysoong.hoard.ui.glass.GlassModalBottomSheet
import com.sleepysoong.hoard.ui.glass.GlassSecondaryButton
import com.sleepysoong.hoard.ui.glass.GlassSlider
import com.sleepysoong.hoard.ui.glass.GlassTextField

@Composable
fun ModelPickerSheet(
    currentModelId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    GlassModalBottomSheet(onDismissRequest = onDismiss) {
        Text("모델 선택 (목업)", style = MaterialTheme.typography.titleLarge)
        Text(
            "응답은 목업 데이터로 생성됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(MockData.models, key = { it.id }) { model ->
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(model.id); onDismiss() }
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                            if (model.id == currentModelId) Text("●", color = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            "${model.vendor} · ${model.description}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SessionSettingsSheet(
    session: ChatSession,
    onRename: (String) -> Unit,
    onSystemPrompt: (String) -> Unit,
    onContextLimit: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(session.id) { mutableStateOf(session.name) }
    var prompt by remember(session.id) { mutableStateOf(session.systemPrompt) }
    var context by remember(session.id) { mutableFloatStateOf(session.contextLimit.toFloat()) }
    val options = listOf(4_000, 8_000, 16_000, 32_000, 64_000, 128_000)

    GlassModalBottomSheet(onDismissRequest = onDismiss) {
        Text("세션 설정", style = MaterialTheme.typography.titleLarge)
        GlassTextField(value = name, onValueChange = { name = it }, label = { Text("세션 이름") }, singleLine = true)
        GlassTextField(
            value = prompt, onValueChange = { prompt = it },
            label = { Text("시스템 프롬프트") }, minLines = 3, maxLines = 8
        )
        Text("컨텍스트: ${context.toInt()} 토큰", style = MaterialTheme.typography.labelLarge)
        GlassSlider(
            value = options.indexOf(options.minByOrNull { kotlin.math.abs(it - context.toInt()) } ?: 32_000).toFloat(),
            onValueChange = { idx -> context = options[idx.toInt().coerceIn(0, options.lastIndex)].toFloat() },
            valueRange = 0f..(options.lastIndex.toFloat()),
            steps = options.size - 2
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassSecondaryButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("취소") }
            GlassButton(
                onClick = {
                    onRename(name); onSystemPrompt(prompt); onContextLimit(context.toInt()); onDismiss()
                },
                modifier = Modifier.weight(1f)
            ) { Text("저장") }
        }
    }
}

@Composable
fun EditMessageDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("메시지 수정") },
        text = {
            GlassTextField(value = text, onValueChange = { text = it }, maxLines = 10, minLines = 3)
        },
        dismissButton = { GlassSecondaryButton(onClick = onDismiss) { Text("취소") } },
        confirmButton = { GlassButton(onClick = { onConfirm(text) }) { Text("저장하고 다시 생성") } }
    )
}

@Composable
fun BranchDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("브랜치") }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("여기서 브랜치 만들기") },
        text = {
            GlassTextField(value = name, onValueChange = { name = it }, label = { Text("브랜치 이름") }, singleLine = true)
        },
        dismissButton = { GlassSecondaryButton(onClick = onDismiss) { Text("취소") } },
        confirmButton = { GlassButton(onClick = { onConfirm(name) }) { Text("브랜치 만들기") } }
    )
}

@Composable
fun DeleteConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        dismissButton = { GlassSecondaryButton(onClick = onDismiss) { Text("취소") } },
        confirmButton = { GlassButton(onClick = onConfirm) { Text("삭제") } }
    )
}
