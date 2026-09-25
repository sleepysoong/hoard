package com.sleepysoong.hoard.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sleepysoong.hoard.ui.chat.ChatViewModel
import com.sleepysoong.hoard.ui.chat.DeleteConfirmDialog
import com.sleepysoong.hoard.ui.glass.GlassButton
import com.sleepysoong.hoard.ui.glass.GlassCard
import com.sleepysoong.hoard.ui.glass.GlassEmptyState
import com.sleepysoong.hoard.ui.glass.GlassIconButton

@Composable
fun SessionsScreen(
    vm: ChatViewModel,
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by vm.uiState.collectAsState()
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("세션", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            GlassIconButton(onClick = { vm.newSession(); onOpenChat() }) {
                Icon(Icons.Default.Add, contentDescription = "새 세션")
            }
        }
        if (state.sessions.isEmpty()) {
            GlassEmptyState(
                title = "세션이 없어요",
                description = "첫 목업 세션을 만들어 보세요.",
                action = { GlassButton(onClick = { vm.newSession(); onOpenChat() }) { Text("새 세션") } }
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.sessions, key = { it.id }) { s ->
                    val selected = s.id == state.session?.id
                    GlassCard(
                        modifier = Modifier.fillMaxWidth().clickable { vm.selectSession(s.id); onOpenChat() }
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    (if (selected) "● " else "") + s.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "${s.modelId} · 컨텍스트 ${s.contextLimit}${s.branchedFrom?.let { " · 브랜치" } ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            GlassIconButton(onClick = { pendingDelete = s.id }) {
                                Icon(Icons.Default.Delete, contentDescription = "세션 삭제")
                            }
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
