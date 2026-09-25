package com.sleepysoong.hoard.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.MockData
import com.sleepysoong.hoard.ui.glass.GlassButton
import com.sleepysoong.hoard.ui.glass.GlassDialog
import com.sleepysoong.hoard.ui.glass.GlassSecondaryButton
import com.sleepysoong.hoard.ui.glass.GlassSwitch
import com.sleepysoong.hoard.ui.glass.GlassTextField
import com.sleepysoong.hoard.ui.glass.IOSGroupedSection
import com.sleepysoong.hoard.ui.glass.IOSRowDivider
import com.sleepysoong.hoard.ui.glass.IOSSegmentedControl
import com.sleepysoong.hoard.ui.glass.LargeTitle

/** iOS-style tools: large title, segmented tabs, grouped switch rows. */
@Composable
fun ToolsScreen(modifier: Modifier = Modifier) {
    val repo = remember { HoardRepository.get() }
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("MCP", "플러그인", "스킬", "명령어")
    val scheme = MaterialTheme.colorScheme

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LargeTitle("도구", modifier = Modifier.padding(start = 4.dp))
        Text(
            "목업 데이터",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
        IOSSegmentedControl(
            options = tabs,
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.fillMaxWidth()
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> McpTab(repo)
                1 -> {
                    val plugins by repo.plugins.collectAsState()
                    IOSGroupedSection {
                        plugins.forEachIndexed { i, p ->
                            if (i > 0) IOSRowDivider()
                            SwitchRow(p.name, p.description, p.enabled) { repo.setPluginEnabled(p.id, it) }
                        }
                    }
                }
                2 -> {
                    val skills by repo.skills.collectAsState()
                    IOSGroupedSection {
                        skills.forEachIndexed { i, s ->
                            if (i > 0) IOSRowDivider()
                            SwitchRow(s.name, s.description, s.enabled) { repo.setSkillEnabled(s.id, it) }
                        }
                    }
                }
                3 -> {
                    IOSGroupedSection {
                        MockData.slashCommands.forEachIndexed { i, cmd ->
                            if (i > 0) IOSRowDivider()
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(cmd.command, style = MaterialTheme.typography.bodyLarge)
                                Text(cmd.description, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                                Text(cmd.hint, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(name: String, desc: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        GlassSwitch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun McpTab(repo: HoardRepository) {
    val servers by repo.mcpServers.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("MCP 서버 추가 (목업)") }
        servers.forEach { s ->
            IOSGroupedSection {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (s.enabled) "●" else "○",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (s.enabled) scheme.secondary else scheme.onSurfaceVariant
                        )
                        Text(s.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(s.status, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                    Text(s.url, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                IOSRowDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("도구 ${s.toolCount}개", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    GlassSwitch(checked = s.enabled, onCheckedChange = { repo.setMcpEnabled(s.id, it) })
                }
                IOSRowDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(
                        "테스트",
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                    Text(
                        "제거",
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { repo.removeMcpServer(s.id) }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )
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
