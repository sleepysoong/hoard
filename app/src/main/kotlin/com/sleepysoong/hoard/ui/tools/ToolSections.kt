package com.sleepysoong.hoard.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.ui.glass.GlassPillButton
import com.sleepysoong.hoard.ui.glass.GlassPillTint
import com.sleepysoong.hoard.ui.glass.GlassSwitch
import com.sleepysoong.hoard.ui.glass.GlassTextField
import kotlinx.coroutines.launch

/**
 * web_search (Brave, the user's own key) + web_fetch, offered to the model in
 * router mode. The key is typed by the user and stored on this device only —
 * nothing is bundled with the app.
 */
@Composable
internal fun WebToolsSection(enabled: Boolean, savedKey: String) {
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

/**
 * termux_exec opt-in. Turning it on asks for Termux's RUN_COMMAND permission;
 * the status line says what is still missing (Termux, permission), and the
 * one-time Termux setup (allow-external-apps) is shown to copy.
 */
@Composable
internal fun TermuxSection(enabled: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val platform = androidx.compose.runtime.remember { com.sleepysoong.hoard.termux.AndroidTermuxPlatform(ctx) }
    var refresh by androidx.compose.runtime.remember { mutableStateOf(0) }
    val installed = androidx.compose.runtime.remember(refresh) { platform.isTermuxInstalled() }
    val granted = androidx.compose.runtime.remember(refresh) { platform.hasRunCommandPermission() }
    val requestPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { refresh++ }

    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Termux 명령 실행", style = MaterialTheme.typography.bodyLarge)
                Text("모델이 termux_exec로 단발성 셸 명령을 실행 (라우터 연결 시)", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            GlassSwitch(
                checked = enabled,
                onCheckedChange = { v ->
                    scope.launch { SettingsStore.setTermuxEnabled(ctx, v) }
                    if (v && installed && !granted) requestPermission.launch(com.termux.shared.termux.TermuxConstants.PERMISSION_RUN_COMMAND)
                },
                modifier = Modifier.testTag("termux-switch")
            )
        }
        val (text, color) = when {
            !enabled -> "꺼짐 · 모델에 termux_exec를 주지 않음" to scheme.onSurfaceVariant
            !installed -> "Termux가 설치돼 있지 않습니다" to scheme.error
            !granted -> "RUN_COMMAND 권한이 필요합니다" to scheme.error
            else -> "사용 가능 · 모델이 이 기기에서 명령을 실행할 수 있습니다" to scheme.tertiary
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.testTag("termux-status"))
        if (enabled && installed && !granted) {
            GlassPillButton(
                label = "권한 허용",
                tint = GlassPillTint.Accent,
                onClick = { requestPermission.launch(com.termux.shared.termux.TermuxConstants.PERMISSION_RUN_COMMAND) }
            )
        }
        if (enabled) {
            Text(
                "Termux에서 한 번 실행:\nmkdir -p ~/.termux\necho allow-external-apps=true >> ~/.termux/termux.properties\ntermux-reload-settings",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = scheme.onSurfaceVariant
            )
        }
    }
}

/**
 * read_file / write_file / edit_file / glob / grep. By default they only see Hoard's
 * private workspace folder. "기기 전체 저장소" adds the phone's shared storage
 * (Download, Documents, DCIM…) like a file manager; Android asks for "All files access".
 */
@Composable
internal fun FileToolsSection(enabled: Boolean, fullStorage: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val ws = androidx.compose.runtime.remember { com.sleepysoong.hoard.tools.files.FileTools.workspace(ctx) }
    val count by androidx.compose.runtime.produceState(0, enabled) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ws.root.walkTopDown().count { it.isFile } }
    }
    // Re-checked when coming back from the system permission screen.
    var granted by androidx.compose.runtime.remember { mutableStateOf(com.sleepysoong.hoard.tools.files.FileTools.hasAllFilesAccess()) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        granted = com.sleepysoong.hoard.tools.files.FileTools.hasAllFilesAccess()
        onPauseOrDispose { }
    }
    fun requestAccess() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            android.net.Uri.parse("package:${ctx.packageName}")
        )
        runCatching { ctx.startActivity(intent) }
            .onFailure { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
    }

    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("파일 읽기 · 쓰기 · 검색", style = MaterialTheme.typography.bodyLarge)
                Text("read_file, write_file, edit_file, glob, grep (라우터 연결 시)", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            GlassSwitch(
                checked = enabled,
                onCheckedChange = { v -> scope.launch { SettingsStore.setFileToolsEnabled(ctx, v) } },
                modifier = Modifier.testTag("file-tools-switch")
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("기기 전체 저장소", style = MaterialTheme.typography.bodyLarge, color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant)
                Text("다운로드·문서·사진 등 공유 저장소까지 (파일 탐색기처럼)", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            GlassSwitch(
                checked = fullStorage,
                enabled = enabled,
                onCheckedChange = { v ->
                    scope.launch { SettingsStore.setFileToolsFullStorage(ctx, v) }
                    if (v && !granted) requestAccess()
                },
                modifier = Modifier.testTag("file-full-storage-switch")
            )
        }
        val (text, color) = when {
            !enabled -> "꺼짐 · 모델에 파일 도구를 주지 않음" to scheme.onSurfaceVariant
            fullStorage && !granted -> "\"모든 파일 접근\" 권한이 필요합니다 · 지금은 앱 작업 폴더만" to scheme.error
            fullStorage -> "사용 가능 · 앱 작업 폴더 + 기기 저장소 ${android.os.Environment.getExternalStorageDirectory().path}" to scheme.tertiary
            else -> "사용 가능 · 앱 작업 폴더만 접근 (파일 ${count}개)" to scheme.tertiary
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.testTag("file-tools-status"))
        if (enabled && fullStorage && !granted) {
            GlassPillButton(label = "권한 허용", tint = GlassPillTint.Accent, onClick = ::requestAccess, modifier = Modifier.testTag("file-access-grant"))
        }
        if (enabled && fullStorage) {
            Text(
                "모델이 사진·문서를 읽고 덮어쓸 수 있습니다. 모르는 웹페이지를 읽게 할 때는 주의하세요.",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}
