package com.sleepysoong.hoard.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.MockData
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.ui.glass.GlassCard
import com.sleepysoong.hoard.ui.glass.GlassFilterChip
import com.sleepysoong.hoard.ui.glass.GlassSlider
import com.sleepysoong.hoard.ui.glass.GlassSwitch
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsStore.flow(ctx).collectAsState(SettingsStore.Settings())
    val options = listOf(4_000, 8_000, 16_000, 32_000, 64_000, 128_000)

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("설정", style = MaterialTheme.typography.headlineSmall)

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("화면 스타일", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "시스템", "light" to "라이트", "dark" to "다크").forEach { (id, label) ->
                        GlassFilterChip(
                            selected = settings.theme == id,
                            onClick = { scope.launch { SettingsStore.setTheme(ctx, id) } },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("기본 모델 (목업)", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MockData.models.forEach { m ->
                        GlassFilterChip(
                            selected = settings.defaultModel == m.id,
                            onClick = { scope.launch { SettingsStore.setDefaultModel(ctx, m.id) } },
                            label = { Text(m.displayName) }
                        )
                    }
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("기본 컨텍스트: ${settings.defaultContext} 토큰", style = MaterialTheme.typography.titleSmall)
                val idx = options.indexOf(settings.defaultContext).coerceAtLeast(0)
                GlassSlider(
                    value = idx.toFloat(),
                    onValueChange = { scope.launch { SettingsStore.setDefaultContext(ctx, options[it.toInt().coerceIn(0, options.lastIndex)]) } },
                    valueRange = 0f..options.lastIndex.toFloat(),
                    steps = options.size - 2
                )
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("백그라운드 답변", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "앱을 나가도 답변을 계속 생성합니다 (목업 워커).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                GlassSwitch(
                    checked = settings.backgroundWork,
                    onCheckedChange = { scope.launch { SettingsStore.setBackground(ctx, it) } }
                )
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Hoard", style = MaterialTheme.typography.titleSmall)
                Text("sleepysoong 제작 · https://github.com/sleepysoong/hoard", style = MaterialTheme.typography.bodySmall)
                Text("리퀴드 글래스 껍데기 · 목업 데이터 전용 · 그라데이션 없음", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
