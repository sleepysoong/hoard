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
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (id, label) ->
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
                Text("Default model (mock)", style = MaterialTheme.typography.titleSmall)
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
                Text("Default context: ${settings.defaultContext} tokens", style = MaterialTheme.typography.titleSmall)
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
                    Text("Background replies", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Finish responses after leaving the app (mock worker).",
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
                Text("by sleepysoong · https://github.com/sleepysoong/hoard", style = MaterialTheme.typography.bodySmall)
                Text("Liquid-glass shell · mock data only · no gradients", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
