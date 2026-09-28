package com.sleepysoong.hoard.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.ui.glass.GlassFloatingBar
import com.sleepysoong.hoard.ui.glass.GlassTokens
import com.sleepysoong.hoard.ui.glass.IOSGroupedSection
import com.sleepysoong.hoard.ui.glass.IOSSectionHeader

/**
 * The tools the model can actually call (run on this device, offered through
 * sleepyrouter's function calling): web_search / web_fetch and termux_exec.
 */
@Composable
fun ToolsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val settings by SettingsStore.flow(ctx).collectAsState(SettingsStore.Settings())
    val scheme = MaterialTheme.colorScheme

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassFloatingBar(title = "도구", subtitle = "모델이 호출하는 기기 도구")
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = GlassTokens.shadowBleed),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (settings.routerUrl.isBlank()) {
                Text(
                    "라우터를 연결해야 모델이 도구를 쓸 수 있습니다 (설정 → 라우터)",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("tools-no-router")
                )
            }
            IOSSectionHeader("웹")
            IOSGroupedSection {
                WebToolsSection(settings.webToolsEnabled, settings.braveApiKey)
            }
            IOSSectionHeader("파일")
            IOSGroupedSection {
                FileToolsSection(settings.fileToolsEnabled, settings.fileToolsFullStorage)
            }
            IOSSectionHeader("Termux")
            IOSGroupedSection {
                TermuxSection(settings.termuxEnabled)
            }
        }
    }
}
