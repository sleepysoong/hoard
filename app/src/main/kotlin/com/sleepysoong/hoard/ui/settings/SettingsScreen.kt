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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.Defaults
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.data.parseContextLimit
import com.sleepysoong.hoard.ui.glass.GlassTokenField
import com.sleepysoong.hoard.ui.glass.GlassSlider
import com.sleepysoong.hoard.ui.glass.GlassTokens
import com.sleepysoong.hoard.ui.glass.GlassGroupedSection
import com.sleepysoong.hoard.ui.glass.GlassRowDivider
import com.sleepysoong.hoard.ui.glass.GlassSectionHeader
import com.sleepysoong.hoard.ui.glass.GlassSegmentedControl
import com.sleepysoong.hoard.ui.glass.GlassFloatingBar
import com.sleepysoong.hoard.ui.glass.liquidClickable
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Settings: floating glass top bar, grouped glass sections, liquid controls. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsStore.flow(ctx).collectAsState(SettingsStore.Settings())
    val scheme = MaterialTheme.colorScheme
    val themeIndex = listOf("system", "light", "dark").indexOf(settings.theme).coerceAtLeast(0)
    var logOpen by rememberSaveable { mutableStateOf(false) }

    // Root Box: the log viewer popup must overlay the whole screen, not sit inside the
    // scrollable section (an overlay inside scroll content gets clipped/never anchored).
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    // Same floating top bar as 세션; stays put while the settings scroll beneath it.
    GlassFloatingBar(title = "설정")
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = GlassTokens.shadowBleed),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {

        GlassSectionHeader("화면 스타일")
        GlassGroupedSection {
            GlassSegmentedControl(
                options = listOf("시스템", "라이트", "다크"),
                selectedIndex = themeIndex,
                onSelect = { scope.launch { SettingsStore.setTheme(ctx, listOf("system", "light", "dark")[it]) } },
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
        }

        GlassSectionHeader("라우터")
        GlassGroupedSection {
            RouterSection(settings.routerUrl, settings.routerToken)
        }

        val routerModels by HoardRepository.get().routerModels.collectAsState()
        GlassSectionHeader("기본 모델")
        GlassGroupedSection {
            // Typed, saved as you type: new sessions start on it. Blank = the router's first group.
            var modelText by rememberSaveable { mutableStateOf<String?>(null) }
            GlassTextField(
                value = modelText ?: settings.defaultModel,
                onValueChange = { v ->
                    modelText = v
                    scope.launch { SettingsStore.setDefaultModel(ctx, v.trim()) }
                },
                label = { Text("새 세션의 모델") },
                placeholder = { Text("예: coding") },
                singleLine = true,
                supportingText = {
                    Text(
                        if (routerModels.isEmpty()) "비우면 라우터의 첫 그룹을 씁니다"
                        else "라우터: " + routerModels.joinToString(", ") { it.id },
                        maxLines = 2
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                modifier = Modifier.padding(12.dp).testTag("default-model")
            )
        }

        GlassSectionHeader("웹 검색")
        GlassGroupedSection {
            // The user's own Brave Search key (web_search); stored on this device only.
            // Secrets stay in memory only: never into the saved-state bundle.
            var key by remember(settings.braveApiKey) { mutableStateOf(settings.braveApiKey) }
            GlassTextField(
                value = key,
                onValueChange = { v -> key = v; scope.launch { SettingsStore.setBraveApiKey(ctx, v) } },
                label = { Text("Brave Search API 키") },
                placeholder = { Text("api-dashboard.search.brave.com에서 발급") },
                singleLine = true,
                supportingText = { Text(if (key.isBlank()) "없으면 web_fetch만 씁니다" else "web_search 사용 · 이 기기에만 저장") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.padding(12.dp).testTag("brave-key")
            )
        }

        GlassSectionHeader("원격 브라우저")
        GlassGroupedSection {
            RemoteBrowserSection(settings)
        }

        GlassSectionHeader("기본 컨텍스트")
        GlassGroupedSection {
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
        GlassSectionHeader("자동 압축")
        GlassGroupedSection {
            // Save once on release, rather than writing DataStore on every drag frame.
            var percent by remember(settings.autoCompactPercent) { mutableFloatStateOf(settings.autoCompactPercent.toFloat()) }
            val range = Defaults.AUTO_COMPACT_RANGE
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("이전 대화를 요약할 시점", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("${percent.roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = scheme.primary,
                        modifier = Modifier.testTag("auto-compact-value"))
                }
                GlassSlider(
                    value = percent,
                    onValueChange = { percent = it },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    steps = (range.last - range.first) / Defaults.AUTO_COMPACT_STEP - 1,
                    onValueChangeFinished = {
                        val saved = percent.roundToInt()
                        scope.launch { SettingsStore.setAutoCompactPercent(ctx, saved) }
                    },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "자동 압축 기준"
                        stateDescription = "${percent.roundToInt()}%"
                    }.testTag("auto-compact-slider")
                )
                Text("세션 컨텍스트의 ${percent.roundToInt()}%부터 자동 요약 · /compact 로 직접 실행 · 4,000 토큰 미만은 기존 자르기 사용",
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
        }
        GlassSectionHeader("로그")
        GlassGroupedSection {
            LogSection(onOpen = { logOpen = true })
        }
    }
    }
    if (logOpen) {
        LogViewerPopup(onDismiss = { logOpen = false })
    }
    }
}

/** In-app diagnostics: the on-device log tail (no adb needed), copy and clear. */
@Composable
private fun LogSection(onOpen: () -> Unit) {
    LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val tail by com.sleepysoong.hoard.diagnostics.AppLog.tail.collectAsState()
    Column(Modifier.padding(12.dp).testTag("log-section"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("앱이 기록한 상세 로그입니다. 문제가 생기면 여기 내용을 복사해 공유하세요. 토큰·키·메시지 내용은 저장되지 않습니다.",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        GlassPillButton("로그 보기 (${tail.size}줄)", onClick = onOpen,
            tint = GlassPillTint.Accent, modifier = Modifier.testTag("open-logs"))
    }
}

/** Screen-root overlay (settings content scrolls; an overlay must anchor to the window). */
@Composable
private fun LogViewerPopup(onDismiss: () -> Unit) {
    val tail by com.sleepysoong.hoard.diagnostics.AppLog.tail.collectAsState()
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    com.sleepysoong.hoard.ui.glass.GlassPopup(
        title = "앱 로그",
        message = "최근 ${tail.size}줄 · 이 기기에만 저장",
        onDismiss = onDismiss,
        dismissLabel = "닫기",
        bodyPadding = PaddingValues(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPillButton("복사", onClick = {
                scope.launch {
                    clipboard.setClipEntry(android.content.ClipData.newPlainText("Hoard logs", tail.joinToString("\n")).toClipEntry())
                }
                onDismiss()
            }, modifier = Modifier.testTag("copy-logs"))
            GlassPillButton("지우기", tint = GlassPillTint.Destructive, onClick = {
                com.sleepysoong.hoard.diagnostics.AppLog.clear()
            }, modifier = Modifier.testTag("clear-logs"))
        }
        androidx.compose.foundation.text.selection.SelectionContainer(
            modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(top = 10.dp)
        ) {
            Text(
                tail.ifEmpty { listOf("(로그 없음)") }.joinToString("\n"),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                modifier = Modifier.testTag("log-text")
            )
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
    // Secrets stay in memory only: never into the saved-state bundle.
    var token by remember(savedToken) { mutableStateOf(savedToken) }
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
                // Submitting / done / failed are all visible: the label while checking,
                // the status line right next to it for the outcome.
                label = when {
                    status is RouterStatus.Checking -> "연결 중…"
                    url.isBlank() -> "연결 해제"
                    else -> "연결"
                },
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
                RouterStatus.Offline -> "미연결" to scheme.onSurfaceVariant
                RouterStatus.Checking -> "확인 중…" to scheme.onSurfaceVariant
                is RouterStatus.Connected -> "연결됨 · 그룹 ${st.groups} · 모델 ${st.models}" to scheme.tertiary
                is RouterStatus.Failed -> "연결 실패 · ${st.reason}" to scheme.error
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 2, modifier = Modifier.testTag("router-status"))
        }
    }
}
