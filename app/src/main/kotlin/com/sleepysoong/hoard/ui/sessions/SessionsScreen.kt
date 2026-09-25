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
            Text("Sessions", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            GlassIconButton(onClick = { vm.newSession(); onOpenChat() }) {
                Icon(Icons.Default.Add, contentDescription = "New session")
            }
        }
        if (state.sessions.isEmpty()) {
            GlassEmptyState(
                title = "No sessions",
                description = "Create your first mock session.",
                action = { GlassButton(onClick = { vm.newSession(); onOpenChat() }) { Text("New session") } }
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
                                    "${s.modelId} · ctx ${s.contextLimit}${s.branchedFrom?.let { " · branched" } ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            GlassIconButton(onClick = { pendingDelete = s.id }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete session")
                            }
                        }
                    }
                }
            }
        }
    }
    if (pendingDelete != null) {
        DeleteConfirmDialog(
            title = "Delete session?",
            message = "All messages in this mock session will be removed.",
            onConfirm = { vm.deleteSession(pendingDelete!!); pendingDelete = null },
            onDismiss = { pendingDelete = null }
        )
    }
}
