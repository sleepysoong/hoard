package com.sleepysoong.hoard.ui.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.MockData
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.ui.glass.GlassSlider
import com.sleepysoong.hoard.ui.glass.GlassSwitch
import com.sleepysoong.hoard.ui.glass.IOSGroupedSection
import com.sleepysoong.hoard.ui.glass.IOSRowDivider
import com.sleepysoong.hoard.ui.glass.IOSSectionHeader
import com.sleepysoong.hoard.ui.glass.IOSSegmentedControl
import com.sleepysoong.hoard.ui.glass.LargeTitle
import com.sleepysoong.hoard.ui.glass.liquidClickable
import kotlinx.coroutines.launch

/** iOS Settings-style: large title, grouped sections, segmented + checkmark rows. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsStore.flow(ctx).collectAsState(SettingsStore.Settings())
    val options = listOf(4_000, 8_000, 16_000, 32_000, 64_000, 128_000)
    val scheme = MaterialTheme.colorScheme
    val themeIndex = listOf("system", "light", "dark").indexOf(settings.theme).coerceAtLeast(0)

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LargeTitle("설정", modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))

        IOSSectionHeader("화면 스타일")
        IOSGroupedSection {
            IOSSegmentedControl(
                options = listOf("시스템", "라이트", "다크"),
                selectedIndex = themeIndex,
                onSelect = { scope.launch { SettingsStore.setTheme(ctx, listOf("system", "light", "dark")[it]) } },
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
        }

        IOSSectionHeader("기본 모델 (목업)")
        IOSGroupedSection {
            MockData.models.forEachIndexed { i, m ->
                if (i > 0) IOSRowDivider()
                val selected = settings.defaultModel == m.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .liquidClickable { scope.launch { SettingsStore.setDefaultModel(ctx, m.id) } }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(m.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(m.description, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                    }
                    if (selected) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }

        IOSSectionHeader("기본 컨텍스트")
        IOSGroupedSection {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("토큰 상한", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("${settings.defaultContext}", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                }
                val idx = options.indexOf(settings.defaultContext).coerceAtLeast(0)
                GlassSlider(
                    value = idx.toFloat(),
                    onValueChange = { scope.launch { SettingsStore.setDefaultContext(ctx, options[it.toInt().coerceIn(0, options.lastIndex)]) } },
                    valueRange = 0f..options.lastIndex.toFloat(),
                    steps = options.size - 2
                )
            }
        }

        IOSSectionHeader("백그라운드")
        IOSGroupedSection {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("백그라운드 답변", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "앱을 나가도 답변을 계속 생성합니다 (목업 워커).",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                GlassSwitch(
                    checked = settings.backgroundWork,
                    onCheckedChange = { scope.launch { SettingsStore.setBackground(ctx, it) } }
                )
            }
        }

        IOSSectionHeader("정보")
        IOSGroupedSection {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Hoard", style = MaterialTheme.typography.bodyLarge)
                Text("sleepysoong 제작 · github.com/sleepysoong/hoard", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Text("리퀴드 글래스 껍데기 · 목업 데이터 전용 · 그라데이션 없음", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}
