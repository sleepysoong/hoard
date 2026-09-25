package com.sleepysoong.hoard.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.ui.chat.ChatViewModel
import com.sleepysoong.hoard.ui.chat.DeleteConfirmDialog
import com.sleepysoong.hoard.ui.glass.GlassButton
import com.sleepysoong.hoard.ui.glass.GlassEmptyState
import com.sleepysoong.hoard.ui.glass.CollapsingLargeTitle
import com.sleepysoong.hoard.ui.glass.IOSGroupedSection
import com.sleepysoong.hoard.ui.glass.IOSRowDivider
import com.sleepysoong.hoard.ui.glass.liquidClickable
import com.sleepysoong.hoard.ui.glass.rememberLargeTitleCollapseState

/** iOS Settings-style session list: large title, grouped rows, chevrons. */
@Composable
fun SessionsScreen(
    vm: ChatViewModel,
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by vm.uiState.collectAsState()
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val scheme = MaterialTheme.colorScheme
    val titleState = rememberLargeTitleCollapseState()
    val scroll = rememberScrollState()
    LaunchedEffect(scroll) { snapshotFlow { scroll.value }.collect { titleState.onScroll(it.toFloat()) } }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CollapsingLargeTitle("세션", titleState, modifier = Modifier.weight(1f).padding(start = 4.dp))
            IconButton(onClick = { vm.newSession(); onOpenChat() }) {
                Icon(Icons.Rounded.Add, contentDescription = "새 세션", tint = scheme.primary, modifier = Modifier.size(26.dp))
            }
        }
        if (state.sessions.isEmpty()) {
            GlassEmptyState(
                title = "세션이 없어요",
                description = "첫 목업 세션을 만들어 보세요.",
                action = { GlassButton(onClick = { vm.newSession(); onOpenChat() }) { Text("새 세션") } }
            )
        } else {
            IOSGroupedSection(Modifier.weight(1f)) {
                Column(
                    Modifier
                        .verticalScroll(scroll)
                        .padding(top = 2.dp, bottom = 12.dp)
                ) {
                    state.sessions.forEachIndexed { i, s ->
                        if (i > 0) IOSRowDivider()
                        val selected = s.id == state.session?.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 60.dp)
                                .liquidClickable { vm.selectSession(s.id); onOpenChat() }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (selected) scheme.primary else scheme.onSurface,
                                    maxLines = 1
                                )
                                Text(
                                    "${s.modelId} · 컨텍스트 ${s.contextLimit}${s.branchedFrom?.let { " · 브랜치" } ?: ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            IconButton(
                                onClick = { pendingDelete = s.id },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Rounded.Delete, contentDescription = "세션 삭제", tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
    if (pendingDelete != null) {
        DeleteConfirmDialog(
            title = "세션을 삭제할까요?",
            message = "이 세션의 모든 메시지가 삭제됩니다.",
            onConfirm = { vm.deleteSession(pendingDelete!!); pendingDelete = null },
            onDismiss = { pendingDelete = null }
        )
    }
}
