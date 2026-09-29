package com.sleepysoong.hoard.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.ui.glass.GlassFloatingBar

/**
 * Tools tab — intentionally empty for now. Every tool is always on (no toggles);
 * the Brave Search key lives in Settings, Android permissions are asked at app start.
 */
@Composable
fun ToolsScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassFloatingBar(title = "도구", subtitle = "모델이 호출하는 기기 도구")
    }
}
