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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.MockData
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.data.parseContextLimit
import com.sleepysoong.hoard.ui.glass.GlassTokenField
import com.sleepysoong.hoard.ui.glass.GlassTokens
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
    val scheme = MaterialTheme.colorScheme
    val themeIndex = listOf("system", "light", "dark").indexOf(settings.theme).coerceAtLeast(0)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = GlassTokens.shadowBleed),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
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
            // Typed, saved as soon as the number is valid (new sessions start with it).
            var contextText by rememberSaveable { mutableStateOf<String?>(null) }
            val shown = contextText ?: settings.defaultContext.toString()
            val parsed = parseContextLimit(shown)
            GlassTokenField(
                value = shown,
                onValueChange = { text ->
                    contextText = text
                    parseContextLimit(text)?.let { scope.launch { SettingsStore.setDefaultContext(ctx, it) } }
                },
                valid = parsed != null,
                label = "새 세션의 컨텍스트",
                modifier = Modifier.padding(12.dp)
            )
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
