package com.sleepysoong.hoard.ui.tools

import androidx.compose.ui.platform.testTag
import com.sleepysoong.hoard.ui.glass.GlassEmptyState
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import com.sleepysoong.hoard.ui.glass.GlassPopup
import com.sleepysoong.hoard.ui.glass.GlassSecondaryButton
import com.sleepysoong.hoard.ui.glass.GlassSwitch
import com.sleepysoong.hoard.ui.glass.GlassTextField
import com.sleepysoong.hoard.ui.glass.GlassTokens
import com.sleepysoong.hoard.ui.glass.IOSGroupedSection
import com.sleepysoong.hoard.ui.glass.IOSRowDivider
import com.sleepysoong.hoard.ui.glass.IOSSegmentedControl
import com.sleepysoong.hoard.ui.glass.GlassPillButton
import com.sleepysoong.hoard.ui.glass.GlassPillTint
import com.sleepysoong.hoard.ui.glass.GlassFloatingBar
import com.sleepysoong.hoard.ui.glass.liquidClickable

/** Tools: floating glass top bar, liquid segmented tabs, grouped glass cards. */
@Composable
fun ToolsScreen(modifier: Modifier = Modifier) {
    val repo = remember { HoardRepository.get() }
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("MCP", "플러그인", "스킬", "명령어")
    val scheme = MaterialTheme.colorScheme

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Same floating top bar as 세션 (shared component, title + subtitle slots only).
        GlassFloatingBar(title = "도구", subtitle = "목업 데이터")
        IOSSegmentedControl(
            options = tabs,
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.fillMaxWidth()
        )
        // Bottom padding = shadow reach, so the last card's shadow isn't clipped at the scroll end.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = GlassTokens.shadowBleed)) {
            // Tab switch: the new list slides in from the side it was picked on.
            androidx.compose.animation.AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 500f)) { it / 6 * dir } +
                        androidx.compose.animation.fadeIn(com.sleepysoong.hoard.ui.glass.GlassMotion.fade())) togetherWith
                        androidx.compose.animation.fadeOut(com.sleepysoong.hoard.ui.glass.GlassMotion.fade())
                },
                label = "tools-tab"
            ) { current ->
            Column {
            when (current) {
                0 -> McpTab(repo)
                1 -> {
                    val plugins by repo.plugins.collectAsState()
                    if (plugins.isEmpty()) GlassEmptyState("플러그인이 없습니다", "설치된 플러그인이 여기에 표시됩니다.")
                    else IOSGroupedSection {
                        plugins.forEachIndexed { i, p ->
                            if (i > 0) IOSRowDivider()
                            SwitchRow(p.name, p.description, p.enabled) { repo.setPluginEnabled(p.id, it) }
                        }
                    }
                }
                2 -> {
                    val skills by repo.skills.collectAsState()
                    if (skills.isEmpty()) GlassEmptyState("스킬이 없습니다", "추가한 스킬이 여기에 표시됩니다.")
                    else IOSGroupedSection {
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
    }
}

@Composable
private fun SwitchRow(name: String, desc: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .liquidClickable(enabled = false) {}
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
        if (servers.isEmpty()) {
            GlassEmptyState("MCP 서버가 없습니다", "위의 버튼으로 서버를 추가하세요.", modifier = Modifier.testTag("mcp-empty"))
        }
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
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Mock check: 테스트 중… → 연결됨 / 실패, next to the button that started it.
                    var test by remember(s.id) { mutableStateOf<String?>(null) }
                    val scope = rememberCoroutineScope()
                    GlassPillButton(
                        if (test == "…") "테스트 중…" else "테스트",
                        enabled = test != "…",
                        onClick = {
                            test = "…"
                            scope.launch {
                                kotlinx.coroutines.delay(700)
                                test = if (s.url.startsWith("https://") || s.url.startsWith("http://")) "ok" else "fail"
                            }
                        },
                        tint = GlassPillTint.Accent,
                        modifier = Modifier.testTag("mcp-test")
                    )
                    GlassPillButton("제거", onClick = { repo.removeMcpServer(s.id) }, tint = GlassPillTint.Destructive)
                    androidx.compose.animation.AnimatedVisibility(test == "ok" || test == "fail", enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),
                        modifier = Modifier.align(Alignment.CenterVertically)) {
                        Text(
                            if (test == "ok") "연결됨 (목업)" else "연결 실패 · URL 확인",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (test == "ok") scheme.tertiary else scheme.error,
                            modifier = Modifier.testTag("mcp-test-result")
                        )
                    }
                }
            }
        }
    }
    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        GlassPopup(
            onDismiss = { showAdd = false },
            title = "MCP 서버 추가",
            confirmLabel = "등록",
            confirmEnabled = urlError(url) == null,
            onConfirm = { repo.addMcpServer(name, url.trim()); showAdd = false },
            bodyPadding = PaddingValues(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassTextField(value = name, onValueChange = { name = it }, label = { Text("이름") }, singleLine = true)
                // Error right under the field, only once something was typed.
                val err = urlError(url).takeIf { url.isNotEmpty() }
                GlassTextField(
                    value = url, onValueChange = { url = it }, label = { Text("URL") }, singleLine = true,
                    isError = err != null,
                    supportingText = err?.let { { Text(it, modifier = Modifier.testTag("mcp-url-error")) } },
                    placeholder = { Text("https://mcp.example.com") }
                )
            }
        }
    }
}

/** Inline validation for the MCP URL field; null = valid. */
internal fun urlError(url: String): String? {
    val u = url.trim()
    return when {
        u.isEmpty() -> "URL을 입력하세요"
        !(u.startsWith("http://") || u.startsWith("https://")) -> "http:// 또는 https:// 로 시작해야 합니다"
        runCatching { java.net.URI(u).host }.getOrNull().isNullOrBlank() -> "올바른 주소가 아닙니다"
        else -> null
    }
}
