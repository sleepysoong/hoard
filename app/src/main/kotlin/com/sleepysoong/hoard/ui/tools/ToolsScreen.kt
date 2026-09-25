package com.sleepysoong.hoard.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.MockData
import com.sleepysoong.hoard.ui.glass.GlassBadge
import com.sleepysoong.hoard.ui.glass.GlassButton
import com.sleepysoong.hoard.ui.glass.GlassCard
import com.sleepysoong.hoard.ui.glass.GlassDialog
import com.sleepysoong.hoard.ui.glass.GlassSecondaryButton
import com.sleepysoong.hoard.ui.glass.GlassSeverity
import com.sleepysoong.hoard.ui.glass.GlassSwitch
import com.sleepysoong.hoard.ui.glass.GlassTextField

@Composable
fun ToolsScreen(modifier: Modifier = Modifier) {
    val repo = remember { HoardRepository.get() }
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("MCP", "플러그인", "스킬", "명령어")

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("도구 (목업)", style = MaterialTheme.typography.headlineSmall)
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> McpTab(repo)
            1 -> {
                val plugins by repo.plugins.collectAsState()
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(plugins, key = { it.id }) { p ->
                        ToolRow(p.name, p.description, p.enabled, { repo.setPluginEnabled(p.id, it) })
                    }
                }
            }
            2 -> {
                val skills by repo.skills.collectAsState()
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(skills, key = { it.id }) { s ->
                        ToolRow(s.name, s.description, s.enabled, { repo.setSkillEnabled(s.id, it) })
                    }
                }
            }
            3 -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MockData.slashCommands) { cmd ->
                        GlassCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(cmd.command, style = MaterialTheme.typography.titleSmall)
                                Text(cmd.description, style = MaterialTheme.typography.bodySmall)
                                Text(cmd.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolRow(name: String, desc: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GlassSwitch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun McpTab(repo: HoardRepository) {
    val servers by repo.mcpServers.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("MCP 서버 추가 (목업)") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(servers, key = { it.id }) { s ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(s.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            GlassBadge(s.status, severity = if (s.enabled) GlassSeverity.Success else GlassSeverity.Info)
                        }
                        Text(s.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassBadge("도구 ${s.toolCount}개")
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                                GlassSwitch(checked = s.enabled, onCheckedChange = { repo.setMcpEnabled(s.id, it) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassSecondaryButton(onClick = {}) { Text("테스트") }
                            GlassSecondaryButton(onClick = { repo.removeMcpServer(s.id) }) { Text("제거") }
                        }
                    }
                }
            }
        }
    }
    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        GlassDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("MCP 서버 추가") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassTextField(value = name, onValueChange = { name = it }, label = { Text("이름") }, singleLine = true)
                    GlassTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, singleLine = true)
                }
            },
            dismissButton = { GlassSecondaryButton(onClick = { showAdd = false }) { Text("취소") } },
            confirmButton = { GlassButton(onClick = { repo.addMcpServer(name, url); showAdd = false }) { Text("등록") } }
        )
    }
}
