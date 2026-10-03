package com.sleepysoong.hoard.ui.tools

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.skills.InstalledSkill
import com.sleepysoong.hoard.skills.SkillInstaller
import com.sleepysoong.hoard.skills.SkillStore
import com.sleepysoong.hoard.tools.readCapped
import com.sleepysoong.hoard.ui.glass.GlassFloatingBar
import com.sleepysoong.hoard.ui.glass.GlassPillButton
import com.sleepysoong.hoard.ui.glass.GlassPillTint
import com.sleepysoong.hoard.ui.glass.GlassPopup
import com.sleepysoong.hoard.ui.glass.GlassSurface
import com.sleepysoong.hoard.ui.glass.GlassSwitch
import com.sleepysoong.hoard.ui.glass.GlassTextField
import com.sleepysoong.hoard.ui.glass.GlassTokens
import com.sleepysoong.hoard.ui.glass.LocalPopupCloser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val INSTALL_WARNING = "외부 스킬의 지침과 스크립트는 신뢰할 수 없는 콘텐츠입니다. 내용을 검토한 뒤 사용하세요. 설치 중에는 스크립트를 실행하지 않으며, 스킬 사용과 코드 실행 신뢰는 별도로 설정합니다."

/** Device tools remain always on. Skills and executable-code trust are managed separately. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var store by remember(context) { mutableStateOf<SkillStore?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(context, retry) {
        loadError = null
        try {
            store = withContext(Dispatchers.IO) { SkillStore.get(context) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            loadError = "스킬 저장소를 열지 못했습니다: ${failure.message ?: "알 수 없는 오류"}"
        }
    }
    val loaded = store
    if (loaded != null) {
        ToolsContent(loaded, modifier)
    } else {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassFloatingBar(title = "도구", subtitle = "모델이 호출하는 기기 도구")
            if (loadError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            loadError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                GlassPillButton("다시 시도", onClick = { retry++ })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolsContent(store: SkillStore, modifier: Modifier) {
    val context = LocalContext.current
    val installer = remember(store) { SkillInstaller(store) }
    val skills by store.skills.collectAsState()
    val discoveryErrors by store.refreshErrors.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showGithub by rememberSaveable { mutableStateOf(false) }
    var githubSource by rememberSaveable { mutableStateOf("") }
    // Don't persist: OpenDocument grants are transient, so a restored URI would fail anyway.
    var pendingArchive by remember { mutableStateOf<String?>(null) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmation by rememberSaveable { mutableStateOf<String?>(null) }
    val detail = skills.firstOrNull { it.id == detailId }

    fun runOperation(label: String, success: String? = null, action: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        error = null
        notice = null
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                notice = success
            } catch (cancelled: CancellationException) {
                notice = "작업을 취소했습니다."
                throw cancelled
            } catch (failure: Exception) {
                error = "$label 실패: ${failure.message ?: "알 수 없는 오류"}"
            } finally {
                busy = null
                job = null
            }
        }
    }

    LaunchedEffect(store) {
        runOperation("스킬 새로고침") { store.refresh() }
    }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingArchive = uri.toString()
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassFloatingBar(title = "도구", subtitle = "모델이 호출하는 기기 도구")
            if (busy != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(busy!!, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    GlassPillButton("취소", onClick = { job?.cancel() })
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (discoveryErrors.isNotEmpty()) {
                Text("일부 스킬을 불러오지 못했습니다:\n${discoveryErrors.take(5).joinToString("\n")}",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 8)
            }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = GlassTokens.shadowBleed),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    GlassSurface {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("기기 도구", style = MaterialTheme.typography.titleMedium)
                            Text("기기 도구는 항상 사용할 수 있습니다. 검색 API 키와 연결 정보는 설정에서 관리하세요.", style = MaterialTheme.typography.bodySmall)
                            Text("/goal은 목표 관리를 위한 기본 명령입니다. 스킬로 바꾸거나 제거할 수 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("스킬 · ${skills.size}개", style = MaterialTheme.typography.titleMedium)
                        Text("SKILL.md의 지침을 채팅에 추가합니다. 자동 명령·훅 실행 신뢰는 별도입니다. 모델의 일반 도구 호출은 기존 권한을 따릅니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassPillButton("GitHub에서 설치", onClick = { showGithub = true }, enabled = busy == null, tint = GlassPillTint.Accent)
                            GlassPillButton(".zip / .skill 가져오기", onClick = { archivePicker.launch(arrayOf("*/*")) }, enabled = busy == null)
                            GlassPillButton("새로고침", onClick = {
                                runOperation("스킬 새로고침", "스킬 목록을 새로고침했습니다.") { store.refresh() }
                            }, enabled = busy == null)
                        }
                    }
                }
                if (skills.isEmpty()) {
                    item {
                        GlassSurface {
                            Text("설치된 스킬이 없습니다. GitHub 주소 또는 .zip / .skill 파일로 추가하세요.",
                                modifier = Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                items(skills, key = { it.id }) { skill ->
                    GlassSurface {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SkillEnabledRow(skill, busy == null) { enabled ->
                                runOperation("스킬 사용 설정") { store.setEnabled(skill.id, enabled) }
                            }
                            SkillMetadata(skill)
                            GlassPillButton("내용 및 권한", onClick = { detailId = skill.id; confirmation = null }, enabled = busy == null)
                        }
                    }
                }
            }
        }

        if (showGithub) {
            GlassPopup(
                title = "GitHub 스킬 설치",
                onDismiss = { showGithub = false },
                confirmLabel = "확인 후 설치",
                confirmEnabled = githubSource.isNotBlank() && busy == null,
                onConfirm = {
                    val source = githubSource.trim()
                    showGithub = false
                    runOperation("GitHub 스킬 설치", "스킬을 설치했습니다. 내용을 검토하고 코드 실행 신뢰를 별도로 설정하세요.") {
                        installer.installGithub(source)
                    }
                },
                bodyPadding = PaddingValues(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassTextField(
                        value = githubSource, onValueChange = { githubSource = it },
                        label = { Text("GitHub 저장소 또는 스킬 폴더 주소") },
                        placeholder = { Text("https://github.com/owner/repo/tree/main/skills/example") },
                        maxLines = 3
                    )
                    Text("예: https://github.com/owner/repo · owner/repo · 저장소 안의 스킬 폴더 링크", style = MaterialTheme.typography.bodySmall)
                    Text(INSTALL_WARNING, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        pendingArchive?.let { selected ->
            GlassPopup(
                title = "스킬 파일을 가져올까요?",
                onDismiss = { pendingArchive = null },
                confirmLabel = "확인 후 가져오기",
                confirmEnabled = busy == null,
                onConfirm = {
                    pendingArchive = null
                    runOperation("스킬 파일 가져오기", "스킬을 가져왔습니다. 내용을 검토하고 코드 실행 신뢰를 별도로 설정하세요.") {
                        val uri = Uri.parse(selected)
                        val resolver = context.contentResolver
                        val sourceName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "skills.zip"
                        require(sourceName.endsWith(".zip", ignoreCase = true) || sourceName.endsWith(".skill", ignoreCase = true)) {
                            ".zip 또는 .skill 파일을 선택하세요."
                        }
                        val input = resolver.openInputStream(uri) ?: throw IllegalStateException("선택한 파일을 열 수 없습니다.")
                        input.use { installer.importArchive(it, sourceName) }
                    }
                },
                bodyPadding = PaddingValues(16.dp)
            ) {
                Text(INSTALL_WARNING, style = MaterialTheme.typography.bodyMedium)
                Text("확인하기 전에는 선택한 파일을 열거나 설치하지 않습니다.", modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        if (detail != null && confirmation == null) {
            SkillDetailPopup(
                skill = detail, enabled = busy == null,
                busy = busy, operationError = error, onCancel = { job?.cancel() },
                onDismiss = { detailId = null },
                onEnabledChange = { enabled -> runOperation("스킬 사용 설정") { store.setEnabled(detail.id, enabled) } },
                onTrustChange = { trusted ->
                    if (trusted) confirmation = "trust"
                    else runOperation("코드 실행 신뢰 해제") { store.setTrustedCode(detail.id, false) }
                },
                onRemove = { confirmation = "remove" },
                onUpdate = { confirmation = "update" }
            )
        }
        if (detail != null && confirmation != null) {
            val action = confirmation
            GlassPopup(
                title = when (action) {
                    "trust" -> "코드 실행을 신뢰할까요?"
                    "update" -> "GitHub 스킬을 업데이트할까요?"
                    else -> "스킬을 제거할까요?"
                },
                onDismiss = { confirmation = null },
                confirmLabel = when (action) { "trust" -> "코드 실행 신뢰"; "update" -> "업데이트"; else -> "제거" },
                confirmDestructive = action == "remove",
                confirmEnabled = busy == null,
                onConfirm = {
                    confirmation = null
                    detailId = null
                    when (action) {
                        "trust" -> runOperation("코드 실행 신뢰 설정", "${detail.document.name}의 코드 실행을 신뢰하도록 설정했습니다.") { store.setTrustedCode(detail.id, true) }
                        "update" -> runOperation("스킬 업데이트", "스킬을 업데이트했습니다. 변경된 내용과 코드 실행 신뢰를 다시 확인하세요.") { installer.update(detail) }
                        else -> runOperation("스킬 제거", "스킬을 제거했습니다.") { store.remove(detail.id) }
                    }
                },
                bodyPadding = PaddingValues(16.dp)
            ) {
                Text(detail.document.name, style = MaterialTheme.typography.titleMedium)
                Text(when (action) {
                    "trust" -> "스킬 사용을 켜는 것과 별도의 권한입니다. 동적 컨텍스트(!`…`)와 hooks에 포함된 명령·스크립트가 실행되어 파일이나 연결된 환경에 접근할 수 있습니다. SKILL.md와 스크립트를 직접 검토한 경우에만 허용하세요. 기본값은 꺼짐이며 언제든 신뢰를 해제할 수 있습니다."
                    "update" -> "$INSTALL_WARNING\n\nGitHub의 새 내용으로 설치 파일이 바뀝니다. 업데이트 후 SKILL.md와 스크립트를 다시 검토하세요."
                    else -> "설치된 스킬 파일과 설정을 제거합니다. 이 스킬은 더 이상 채팅에서 사용할 수 없습니다."
                }, modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SkillEnabledRow(skill: InstalledSkill, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(skill.document.name, style = MaterialTheme.typography.titleMedium)
            Text("/${skill.command}${skill.document.argumentHint.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Text(if (skill.enabled) "스킬 사용 중" else "스킬 사용 안 함", style = MaterialTheme.typography.labelSmall)
        }
        GlassSwitch(checked = skill.enabled, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "${skill.document.name} 스킬 사용" })
    }
}

@Composable
private fun SkillMetadata(skill: InstalledSkill) {
    val scheme = MaterialTheme.colorScheme
    Text(skill.document.description, style = MaterialTheme.typography.bodyMedium)
    Text("출처: ${skill.source ?: "로컬 파일"}${skill.sourcePath?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    Text("리비전: ${skill.revision ?: "없음"}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    Text("호환성: ${skill.document.compatibility ?: "명시되지 않음"}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    if (!skill.document.userInvocable) Text("직접 호출 숨김 · 슬래시 메뉴에 표시하지 않음", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    if (skill.document.disableModelInvocation) Text("모델 자동 호출 안 함", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    Text(if (skill.trustedCode) "코드 실행 신뢰: 켜짐" else "코드 실행 신뢰: 꺼짐", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
}

private fun InstalledSkill.isGithubSource(): Boolean =
    source?.startsWith("https://github.com/") == true

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkillDetailPopup(
    skill: InstalledSkill,
    enabled: Boolean,
    busy: String?,
    operationError: String?,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onTrustChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onUpdate: () -> Unit
) {
    var raw by remember(skill.id, skill.revision) { mutableStateOf<String?>(null) }
    var readError by remember(skill.id, skill.revision) { mutableStateOf<String?>(null) }
    LaunchedEffect(skill.id, skill.revision) {
        try {
            raw = withContext(Dispatchers.IO) {
                require(skill.skillFile.length() <= com.sleepysoong.hoard.skills.SkillDocument.MAX_DOCUMENT_BYTES) {
                    "SKILL.md가 2 MiB를 초과합니다."
                }
                val (bytes, truncated) = skill.skillFile.inputStream().use { it.readCapped(com.sleepysoong.hoard.skills.SkillDocument.MAX_DOCUMENT_BYTES) }
                require(!truncated) { "SKILL.md가 2 MiB를 초과합니다." }
                bytes.toString(Charsets.UTF_8)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            readError = "SKILL.md를 읽지 못했습니다: ${failure.message ?: "알 수 없는 오류"}"
        }
    }
    GlassPopup(title = "스킬 내용 및 권한", onDismiss = onDismiss, dismissLabel = "닫기", bodyPadding = PaddingValues(16.dp)) {
        val close = LocalPopupCloser.current
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(busy, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    GlassPillButton("취소", onClick = onCancel)
                }
            }
            operationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            SkillEnabledRow(skill, enabled, onEnabledChange)
            SkillMetadata(skill)
            skill.document.context?.let { Text("컨텍스트: $it", style = MaterialTheme.typography.bodySmall) }
            skill.document.model?.let { Text("모델: $it", style = MaterialTheme.typography.bodySmall) }
            skill.document.effort?.let { Text("추론 강도: $it", style = MaterialTheme.typography.bodySmall) }
            if (skill.document.context == "fork") {
                Text("별도 대화(${if (skill.document.background) "백그라운드" else "대기"})에서 실행되며 결과는 원래 대화로 전달됩니다",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            skill.document.disallowedTools.takeIf { it.isNotEmpty() }?.let { tools ->
                Text("이 턴에 제한되는 도구: ${tools.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            }
            if (skill.document.allowedTools.isNotEmpty()) {
                Text("allowed-tools는 승인 예고일 뿐이며 도구를 제한하지 않습니다: ${skill.document.allowedTools.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (skill.document.hooks.isNotEmpty()) {
                Text("hooks: ${skill.document.hooks.keys.joinToString(", ")} · bash 명령 훅만 실행", style = MaterialTheme.typography.bodySmall)
            }
            if (skill.document.frontmatter["paths"] != null) {
                Text("paths: ${skill.document.paths.joinToString(", ")} · 해당 파일을 다룰 때만 활성화", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("코드 실행 신뢰", style = MaterialTheme.typography.titleSmall)
                    Text("동적 컨텍스트 및 hooks · 스킬 사용과 별도", style = MaterialTheme.typography.bodySmall)
                }
                GlassSwitch(checked = skill.trustedCode, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "${skill.document.name} 코드 실행 신뢰" }, onCheckedChange = { trusted ->
                    if (trusted) close { onTrustChange(true) } else onTrustChange(false)
                })
            }
            Text("기본값은 꺼짐입니다. 코드 실행을 신뢰하기 전에 스킬 지침과 포함된 스크립트를 검토하세요.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (skill.isGithubSource()) GlassPillButton("GitHub 업데이트", onClick = { close { onUpdate() } }, enabled = enabled)
                GlassPillButton("제거", onClick = { close { onRemove() } }, tint = GlassPillTint.Destructive, enabled = enabled)
            }
            Text("SKILL.md 원문", style = MaterialTheme.typography.titleSmall)
            readError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (raw == null && readError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            raw?.let { text ->
                SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
