package com.sleepysoong.hoard.ui.settings

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sleepysoong.hoard.browser.PinnedKey
import com.sleepysoong.hoard.browser.RemoteBrowserConfig
import com.sleepysoong.hoard.browser.RemoteBrowsers
import com.sleepysoong.hoard.browser.RuntimeStatus
import com.sleepysoong.hoard.browser.SecretStore
import com.sleepysoong.hoard.browser.SshClient
import com.sleepysoong.hoard.browser.SshKeys
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.ui.glass.GlassPillButton
import com.sleepysoong.hoard.ui.glass.GlassPillTint
import com.sleepysoong.hoard.ui.glass.GlassTextField
import com.sleepysoong.hoard.ui.glass.IOSRowDivider
import com.sleepysoong.hoard.ui.glass.IOSSegmentedControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → 원격 브라우저 (browser_use): the VPS reached over SSH.
 *
 *  - Host / SSH port / user, saved on "연결 확인".
 *  - SSH private key: pasted or made here (Ed25519), stored only Keystore-encrypted;
 *    its public key is shown for ~/.ssh/authorized_keys.
 *  - Host key verification: the first "연결 확인" only reads the server's host key and
 *    shows its fingerprint; it is trusted (pinned) when the user confirms. Every later
 *    connection must present exactly that key.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteBrowserSection(settings: SettingsStore.Settings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val scheme = MaterialTheme.colorScheme

    var host by rememberSaveable(settings.browserHost) { mutableStateOf(settings.browserHost) }
    var port by rememberSaveable(settings.browserPort) { mutableStateOf(settings.browserPort.toString()) }
    var user by rememberSaveable(settings.browserUser) { mutableStateOf(settings.browserUser) }
    var pastedKey by remember { mutableStateOf("") }
    var typedPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError
    // A host key read from the server, waiting for the user to trust it.
    var offered by remember { mutableStateOf<PinnedKey?>(null) }

    val portNumber = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    val targetValid = host.isNotBlank() && user.isNotBlank() && portNumber != null
    val pinned = PinnedKey.parse(settings.browserHostKey)
    val hasKey = settings.browserKeyEncrypted.isNotBlank()

    fun run(label: String, block: suspend () -> Unit) {
        busy = label
        message = null
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                message = (e.message ?: e::class.simpleName.orEmpty()) to true
            } finally {
                busy = null
            }
        }
    }

    suspend fun saveTarget() {
        SettingsStore.setBrowserTarget(ctx, host, portNumber ?: 22, user)
        RemoteBrowsers.reset()
    }

    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "VPS의 Chrome을 SSH로 조작합니다 (browser_use). 화면은 VNC로 볼 수 있습니다.",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant
        )
        GlassTextField(
            value = host, onValueChange = { host = it },
            label = { Text("호스트") }, placeholder = { Text("예: 203.0.113.10 또는 vps.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.testTag("browser-host")
        )
        GlassTextField(
            value = port, onValueChange = { v -> port = v.filter(Char::isDigit).take(5) },
            label = { Text("SSH 포트") }, singleLine = true, isError = portNumber == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.testTag("browser-port")
        )
        GlassTextField(
            value = user, onValueChange = { user = it.trim() },
            label = { Text("사용자") }, placeholder = { Text("예: root") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            modifier = Modifier.testTag("browser-user")
        )

        IOSRowDivider()
        // ---- how to log in: an SSH key (recommended) or a password
        val passwordMode = settings.browserAuthMethod == "password"
        IOSSegmentedControl(
            options = listOf("SSH 키", "비밀번호"),
            selectedIndex = if (passwordMode) 1 else 0,
            onSelect = { i ->
                scope.launch {
                    SettingsStore.setBrowserAuthMethod(ctx, if (i == 1) "password" else "key")
                    RemoteBrowsers.reset()
                    offered = null
                    message = null
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("browser-auth-method")
        )
        if (passwordMode) {
            if (settings.browserPasswordEncrypted.isNotBlank()) {
                Text("비밀번호 저장됨 (Keystore로 암호화)", style = MaterialTheme.typography.labelLarge)
                GlassPillButton(label = "비밀번호 지우기", tint = GlassPillTint.Destructive, enabled = busy == null, onClick = {
                    scope.launch {
                        SettingsStore.setBrowserPasswordEncrypted(ctx, "")
                        RemoteBrowsers.reset()
                        message = "비밀번호를 지웠습니다" to false
                    }
                })
            } else {
                GlassTextField(
                    value = typedPassword, onValueChange = { typedPassword = it },
                    label = { Text("SSH 비밀번호") }, singleLine = true,
                    // Never shown in the clear; stored only encrypted.
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    supportingText = { Text("이 기기에만 Keystore 암호화로 저장 · VPS의 sshd에서 PasswordAuthentication yes 필요") },
                    modifier = Modifier.testTag("browser-password")
                )
                GlassPillButton(
                    label = "비밀번호 저장", tint = GlassPillTint.Accent,
                    enabled = busy == null && typedPassword.isNotEmpty(),
                    onClick = {
                        run("저장 중…") {
                            val blob = withContext(Dispatchers.IO) { SecretStore.encrypt(typedPassword) }
                            SettingsStore.setBrowserPasswordEncrypted(ctx, blob)
                            typedPassword = ""
                            RemoteBrowsers.reset()
                            message = "비밀번호를 저장했습니다" to false
                        }
                    }
                )
            }
        } else if (hasKey) {
            Text("SSH 키 저장됨 (Keystore로 암호화)", style = MaterialTheme.typography.labelLarge)
            if (settings.browserPublicKey.isNotBlank()) {
                Text(
                    settings.browserPublicKey,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = scheme.onSurfaceVariant, maxLines = 3,
                    modifier = Modifier.testTag("browser-public-key")
                )
                Text(
                    "이 공개 키를 VPS 사용자의 ~/.ssh/authorized_keys에 추가하세요.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (settings.browserPublicKey.isNotBlank()) {
                    GlassPillButton(label = "공개 키 복사", onClick = {
                        scope.launch { clipboard.setClipEntry(ClipData.newPlainText("SSH public key", settings.browserPublicKey).toClipEntry()) }
                    })
                }
                GlassPillButton(label = "키 지우기", tint = GlassPillTint.Destructive, enabled = busy == null, onClick = {
                    scope.launch {
                        SettingsStore.setBrowserKey(ctx, "", "")
                        RemoteBrowsers.reset()
                        message = "SSH 키를 지웠습니다" to false
                    }
                })
            }
        } else {
            GlassTextField(
                value = pastedKey, onValueChange = { pastedKey = it },
                label = { Text("SSH 개인 키") },
                placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                maxLines = 3,
                // Never shown in the clear; stored only encrypted.
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text("붙여넣고 저장하거나, 이 기기에서 새 키를 만드세요") },
                modifier = Modifier.testTag("browser-key")
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPillButton(label = "키 저장", tint = GlassPillTint.Accent, enabled = busy == null && pastedKey.isNotBlank(), onClick = {
                    run("키 확인 중…") {
                        val key = pastedKey
                        val info = withContext(Dispatchers.IO) { SshKeys.inspect(key) }
                        val blob = withContext(Dispatchers.IO) { SecretStore.encrypt(key.trim() + "\n") }
                        SettingsStore.setBrowserKey(ctx, blob, info.publicKey)
                        pastedKey = ""
                        RemoteBrowsers.reset()
                        message = "키 저장됨 · ${info.type} · ${info.fingerprint}" to false
                    }
                })
                GlassPillButton(label = "새 키 만들기", enabled = busy == null, onClick = {
                    run("키 만드는 중…") {
                        val (privateKey, info) = withContext(Dispatchers.IO) { SshKeys.generate() }
                        val blob = withContext(Dispatchers.IO) { SecretStore.encrypt(privateKey) }
                        SettingsStore.setBrowserKey(ctx, blob, info.publicKey)
                        RemoteBrowsers.reset()
                        message = "새 Ed25519 키를 만들었습니다. 공개 키를 VPS에 등록하세요" to false
                    }
                })
            }
        }

        IOSRowDivider()
        // ---- host key + connection check
        val loginWhat = if (settings.browserAuthMethod == "password") "비밀번호" else "SSH 키"
        val loginHint = if (settings.browserAuthMethod == "password") "비밀번호를 먼저 저장하세요" else "SSH 키를 먼저 저장하거나 만드세요"
        val hostKeyLine = when {
            offered != null -> "서버 지문: ${offered!!.fingerprint}"
            pinned != null -> "호스트 키 확인됨: ${pinned.fingerprint}"
            else -> "호스트 키: 아직 확인하지 않음"
        }
        Text(hostKeyLine, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.testTag("browser-host-key"))

        if (offered != null) {
            Text(
                "VPS에서 ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub 결과와 같을 때만 신뢰하세요.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (offered != null) {
                GlassPillButton(label = "신뢰하고 연결", tint = GlassPillTint.Accent, enabled = busy == null, onClick = {
                    val key = offered!!
                    run("연결 중…") {
                        SettingsStore.setBrowserHostKey(ctx, key.toString())
                        offered = null
                        val cfg = RemoteBrowserConfig.from(SettingsStore.current(ctx)) ?: throw IllegalStateException(loginHint)
                        message = describe(RemoteBrowsers.check(cfg)) to false
                    }
                })
                GlassPillButton(label = "취소", enabled = busy == null, onClick = { offered = null })
            } else {
                GlassPillButton(
                    label = busy ?: "연결 확인",
                    tint = GlassPillTint.Accent,
                    enabled = busy == null && targetValid,
                    onClick = {
                        run("확인 중…") {
                            saveTarget()
                            val now = SettingsStore.current(ctx)
                            if (PinnedKey.parse(now.browserHostKey) == null) {
                                // First contact: only read the host key; the user decides.
                                offered = withContext(Dispatchers.IO) { SshClient.probeHostKey(now.browserHost, now.browserPort, now.browserUser) }
                            } else {
                                val cfg = RemoteBrowserConfig.from(now) ?: throw IllegalStateException(loginHint)
                                message = describe(RemoteBrowsers.check(cfg)) to false
                            }
                        }
                    }
                )
                if (pinned != null) {
                    GlassPillButton(label = "호스트 키 초기화", tint = GlassPillTint.Destructive, enabled = busy == null, onClick = {
                        scope.launch {
                            SettingsStore.setBrowserHostKey(ctx, "")
                            RemoteBrowsers.reset()
                            message = "호스트 키를 지웠습니다. 연결 확인으로 다시 확인하세요" to false
                        }
                    })
                }
            }
        }
        val status = busy?.let { it to false } ?: message
        status?.let { (text, isError) ->
            Text(
                text, style = MaterialTheme.typography.labelMedium,
                color = if (isError) scheme.error else scheme.tertiary,
                modifier = Modifier.testTag("browser-status")
            )
        }
        if (busy == null && message == null) {
            Text(
                if (RemoteBrowserConfig.from(settings) != null) "설정 완료 · browser_use 사용 가능" else "호스트, 사용자, $loginWhat, 호스트 키 확인이 모두 필요합니다",
                style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant
            )
        }
    }
}

private fun describe(s: RuntimeStatus): String {
    val parts = listOf("화면" to s.display, "VNC" to s.vnc, "Chrome" to s.chrome, "CDP" to s.cdp)
        .joinToString(" · ") { (name, state) -> "$name ${state ?: "?"}" }
    val restarted = if (s.restarted.isEmpty()) "" else " (다시 켬: ${s.restarted.joinToString()})"
    return "연결됨 · 브라우저 준비됨 · $parts$restarted"
}
