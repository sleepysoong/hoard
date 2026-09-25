package com.sleepysoong.hoard.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
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
import com.sleepysoong.hoard.ui.glass.GlassAnchoredOverlay
import com.sleepysoong.hoard.ui.glass.GlassDialog
import com.sleepysoong.hoard.ui.glass.GlassTone
import com.sleepysoong.hoard.ui.glass.glassMaterial
import com.sleepysoong.hoard.ui.glass.GlassSecondaryButton
import com.sleepysoong.hoard.ui.glass.GlassSlider
import com.sleepysoong.hoard.ui.glass.GlassTextField
import com.sleepysoong.hoard.ui.glass.SheetGrabber
import com.sleepysoong.hoard.ui.glass.liquidClickable

@Composable
fun ModelPickerSheet(
    currentModelId: String,
    anchor: androidx.compose.ui.geometry.Rect?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Anchored glass popup (same overlay as settings/long-press menu).
    GlassAnchoredOverlay(anchor = anchor, onDismiss = onDismiss) {
        Text("모델 선택", style = MaterialTheme.typography.titleLarge)
        Text(
            "응답은 목업 데이터로 생성됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(MockData.models, key = { it.id }) { model ->
                val selected = model.id == currentModelId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .liquidClickable { onPick(model.id); onDismiss() }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(model.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${model.vendor} · ${model.description}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (selected) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = "선택됨",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Session settings & model picker use the same in-window anchored glass
 * overlay as the long-press menu. A ModalBottomSheet opens its own window,
 * and that window cannot sample the Activity backdrop — so we render these
 * settings as a real glass panel next to the gear instead.
 */
@Composable
fun SessionSettingsSheet(
    session: ChatSession,
    anchor: androidx.compose.ui.geometry.Rect?,
    onRename: (String) -> Unit,
    onSystemPrompt: (String) -> Unit,
    onContextLimit: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(session.id) { mutableStateOf(session.name) }
    var prompt by remember(session.id) { mutableStateOf(session.systemPrompt) }
    var context by remember(session.id) { mutableFloatStateOf(session.contextLimit.toFloat()) }
    val options = listOf(4_000, 8_000, 16_000, 32_000, 64_000, 128_000)
    val scheme = MaterialTheme.colorScheme
    val cornerRad = 20.dp

    GlassAnchoredOverlay(anchor = anchor, onDismiss = onDismiss, maxCardW = 380.dp) {
        // Header card — matches the action menus.
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(cornerRad))
                .glassMaterial(RoundedCornerShape(cornerRad), scheme.surface)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("세션 설정", style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                Text(
                    "이 세션에만 적용됩니다 (목업).",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Form card — one glass surface housing all editable rows.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(cornerRad))
                .glassMaterial(RoundedCornerShape(cornerRad), scheme.surface)
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            GlassTextField(
                value = name, onValueChange = { name = it },
                label = { Text("세션 이름") }, singleLine = true
            )
            HorizontalDivider(
                thickness = 0.5.dp,
                color = scheme.onSurface.copy(alpha = 0.12f),
                modifier = Modifier.padding(start = 16.dp)
            )
            GlassTextField(
                value = prompt, onValueChange = { prompt = it },
                label = { Text("시스템 프롬프트") }, minLines = 3, maxLines = 8
            )
            HorizontalDivider(
                thickness = 0.5.dp,
                color = scheme.onSurface.copy(alpha = 0.12f),
                modifier = Modifier.padding(start = 16.dp)
            )
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    "컨텍스트: ${context.toInt()} 토큰",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurface
                )
                GlassSlider(
                    value = options.indexOf(options.minByOrNull { kotlin.math.abs(it - context.toInt()) } ?: 32_000).toFloat(),
                    onValueChange = { idx -> context = options[idx.toInt().coerceIn(0, options.lastIndex)].toFloat() },
                    valueRange = 0f..(options.lastIndex.toFloat()),
                    steps = options.size - 2
                )
            }
        }

        // Save / cancel — same split-capsule idiom as the action menus.
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(54.dp)
                    .clip(RoundedCornerShape(cornerRad))
                    .glassMaterial(RoundedCornerShape(cornerRad), scheme.surface, tone = GlassTone.Thin)
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Text("취소", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(54.dp)
                    .clip(RoundedCornerShape(cornerRad))
                    .glassMaterial(RoundedCornerShape(cornerRad), scheme.primaryContainer, tone = GlassTone.Regular)
                    .liquidClickable {
                        onRename(name); onSystemPrompt(prompt); onContextLimit(context.toInt()); onDismiss()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("저장", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimaryContainer)
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
fun RenameSessionDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("세션 이름 변경") },
        text = {
            GlassTextField(value = text, onValueChange = { text = it }, label = { Text("이름") }, singleLine = true)
        },
        dismissButton = { GlassSecondaryButton(onClick = onDismiss) { Text("취소") } },
        confirmButton = { GlassButton(onClick = { onConfirm(text) }) { Text("변경") } }
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
