package com.sleepysoong.hoard.ui.settings

import com.sleepysoong.hoard.ui.glass.GlassTextField
import com.sleepysoong.hoard.ui.glass.GlassSwitch
import com.sleepysoong.hoard.ui.glass.GlassPillTint
import com.sleepysoong.hoard.ui.glass.GlassPillButton
import com.sleepysoong.hoard.engine.RouterStatus
import com.sleepysoong.hoard.engine.RouterAiEngine
import com.sleepysoong.hoard.engine.RouterConnection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardOptions
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
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.data.parseContextLimit
import com.sleepysoong.hoard.ui.glass.GlassTokenField
import com.sleepysoong.hoard.ui.glass.GlassTokens
import com.sleepysoong.hoard.ui.glass.IOSGroupedSection
import com.sleepysoong.hoard.ui.glass.IOSRowDivider
import com.sleepysoong.hoard.ui.glass.IOSSectionHeader
import com.sleepysoong.hoard.ui.glass.IOSSegmentedControl
import com.sleepysoong.hoard.ui.glass.GlassFloatingBar
import com.sleepysoong.hoard.ui.glass.liquidClickable
import kotlinx.coroutines.launch

/** Settings: floating glass top bar, grouped glass sections, liquid controls. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsStore.flow(ctx).collectAsState(SettingsStore.Settings())
    val scheme = MaterialTheme.colorScheme
    val themeIndex = listOf("system", "light", "dark").indexOf(settings.theme).coerceAtLeast(0)

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    // Same floating top bar as 세션; stays put while the settings scroll beneath it.
    GlassFloatingBar(title = "설정")
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = GlassTokens.shadowBleed),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {

        IOSSectionHeader("화면 스타일")
        IOSGroupedSection {
            IOSSegmentedControl(
                options = listOf("시스템", "라이트", "다크"),
                selectedIndex = themeIndex,
                onSelect = { scope.launch { SettingsStore.setTheme(ctx, listOf("system", "light", "dark")[it]) } },
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
        }

        IOSSectionHeader("라우터")
        IOSGroupedSection {
            RouterSection(settings.routerUrl, settings.routerToken)
        }

        IOSSectionHeader("웹 도구")
        IOSGroupedSection {
            WebToolsSection(settings.webToolsEnabled, settings.braveApiKey)
        }

        val routerModels by HoardRepository.get().routerModels.collectAsState()
        IOSSectionHeader(if (routerModels.isEmpty()) "기본 모델 (목업)" else "기본 모델 (라우터)")
        IOSGroupedSection {
            routerModels.ifEmpty { MockData.models }.forEachIndexed { i, m ->
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
                Text("sleepyrouter /hoard/v1/responses로 답변 · 라우터 미연결 시 목업", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
    }
    }
}

/**
 * Router URL + connection check. The URL is saved on "연결" (not per keystroke),
 * then /v1/models is fetched: success swaps the model catalog to the router's
 * groups/models, failure keeps the previous catalog and shows why.
 */
@Composable
private fun RouterSection(savedUrl: String, savedToken: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val status by RouterConnection.status.collectAsState()
    var url by rememberSaveable(savedUrl) { mutableStateOf(savedUrl) }
    var token by rememberSaveable(savedToken) { mutableStateOf(savedToken) }
    // Bare "host:port" is fine (→ http://); only reject other schemes.
    val normalized = RouterAiEngine.normalizeBaseUrl(url)
    val valid = url.isBlank() || normalized.startsWith("http://") || normalized.startsWith("https://")

    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("라우터 주소") },
            placeholder = { Text("http://192.168.0.10:4567") },
            singleLine = true,
            isError = !valid,
            supportingText = if (!valid) { { Text("http:// 또는 https:// 주소여야 합니다") } } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.testTag("router-url")
        )
        GlassTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("토큰 (선택)") },
            placeholder = { Text("auth_token_env를 켠 라우터만") },
            singleLine = true,
            // Never shown in the clear: it grants access to the provider keys behind the router.
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.testTag("router-token")
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPillButton(
                label = if (url.isBlank()) "연결 해제" else "연결",
                tint = GlassPillTint.Accent,
                enabled = valid && status !is RouterStatus.Checking,
                onClick = {
                    scope.launch {
                        url = normalized // show what will actually be used
                        SettingsStore.setRouterUrl(ctx, normalized)
                        SettingsStore.setRouterToken(ctx, token)
                        RouterConnection.refresh(normalized, token = token.trim())
                    }
                }
            )
            val (text, color) = when (val st = status) {
                RouterStatus.Offline -> "미연결 · 목업 응답" to scheme.onSurfaceVariant
                RouterStatus.Checking -> "확인 중…" to scheme.onSurfaceVariant
                is RouterStatus.Connected -> "연결됨 · 그룹 ${st.groups} · 모델 ${st.models}" to scheme.tertiary
                is RouterStatus.Failed -> "연결 실패 · ${st.reason}" to scheme.error
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 2, modifier = Modifier.testTag("router-status"))
        }
    }
}

/**
 * web_search (Brave, the user's own key) + web_fetch, offered to the model in
 * router mode. The key is typed by the user and stored on this device only —
 * nothing is bundled with the app.
 */
@Composable
private fun WebToolsSection(enabled: Boolean, savedKey: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var key by rememberSaveable(savedKey) { mutableStateOf(savedKey) }

    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("웹 검색 · 페이지 읽기", style = MaterialTheme.typography.bodyLarge)
                Text("모델이 web_search / web_fetch를 호출 (라우터 연결 시)", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            GlassSwitch(
                checked = enabled,
                onCheckedChange = { v -> scope.launch { SettingsStore.setWebToolsEnabled(ctx, v) } },
                modifier = Modifier.testTag("web-tools-switch")
            )
        }
        GlassTextField(
            value = key,
            onValueChange = { v ->
                key = v
                scope.launch { SettingsStore.setBraveApiKey(ctx, v) }
            },
            label = { Text("Brave Search API 키") },
            placeholder = { Text("api-dashboard.search.brave.com에서 발급") },
            singleLine = true,
            enabled = enabled,
            // Billed to the user: never shown in the clear.
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.testTag("brave-key")
        )
        val (text, color) = when {
            !enabled -> "꺼짐 · 모델에 도구를 주지 않음" to scheme.onSurfaceVariant
            key.isBlank() -> "web_fetch만 사용 · web_search는 키가 필요합니다" to scheme.onSurfaceVariant
            else -> "web_search + web_fetch 사용 · 키는 이 기기에만 저장" to scheme.tertiary
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.testTag("web-tools-status"))
    }
}
