package com.sleepysoong.hoard.browser

import com.sleepysoong.hoard.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the browser_use tool talks to (the tool knows nothing about SSH or CDP). */
interface RemoteBrowser {
    suspend fun execute(action: BrowserAction): BrowserResult
    val supportsPreview: Boolean get() = false
    /** Read-only JPEG of the current tab; null until a real connection/page is ready. */
    suspend fun previewFrame(quality: Int = 55): ByteArray? = null
}

/**
 * The VPS to drive, from Settings → 원격 브라우저. The login secret ([keyEncrypted] or
 * [passwordEncrypted], per [authMethod]) is a Keystore blob ([SecretStore]); it is
 * decrypted only when a connection is opened.
 */
data class RemoteBrowserConfig(
    val host: String,
    val port: Int,
    val user: String,
    /** "key" or "password". */
    val authMethod: String,
    val keyEncrypted: String = "",
    val passwordEncrypted: String = "",
    val hostKey: PinnedKey
) {
    /** Host + user + the chosen method's secret. */
    val hasLogin: Boolean
        get() = host.isNotBlank() && user.isNotBlank() &&
            (if (authMethod == AUTH_PASSWORD) passwordEncrypted else keyEncrypted).isNotBlank()

    /** The SSH target with the decrypted secret (memory only). */
    fun target(decrypt: (String) -> String = SecretStore::decrypt): SshTarget {
        val auth = try {
            if (authMethod == AUTH_PASSWORD) SshAuth.Password(decrypt(passwordEncrypted))
            else SshAuth.Key(decrypt(keyEncrypted))
        } catch (e: Exception) {
            val what = if (authMethod == AUTH_PASSWORD) "비밀번호" else "SSH 키"
            throw FatalBrowserException("저장된 $what 를 풀 수 없습니다 (앱을 다시 설치한 경우 등). 설정 → 원격 브라우저에서 다시 저장하세요")
        }
        return SshTarget(host, port, user, auth)
    }

    override fun toString() = "RemoteBrowserConfig($user@$host:$port, auth=$authMethod, hostKey=${hostKey.fingerprint})"

    companion object {
        const val AUTH_KEY = "key"
        const val AUTH_PASSWORD = "password"

        /** Null until host, user, the chosen login's secret and a verified host key are all set. */
        fun from(s: SettingsStore.Settings): RemoteBrowserConfig? {
            val config = RemoteBrowserConfig(
                s.browserHost.trim(), s.browserPort, s.browserUser.trim(),
                s.browserAuthMethod, s.browserKeyEncrypted, s.browserPasswordEncrypted,
                PinnedKey.parse(s.browserHostKey) ?: return null
            )
            return config.takeIf { it.hasLogin }
        }
    }
}

/**
 * Keeps the remote browser alive across calls and turns, and runs every action through
 * the same flow:
 *
 *   SSH connected? (else connect) → ensure-browser-runtime (ready=true?) →
 *   CDP tunnel alive? (else create) → CDP connected? (else connect) → action → result
 *
 * If SSH, the tunnel or CDP turn out to be broken while preparing, everything is torn
 * down and rebuilt once in that order (reconnect → ensure → tunnel → CDP). Chrome and
 * its profile live on the VPS and are only restarted by ensure-browser-runtime.
 * One action at a time.
 *
 *   RemoteBrowserManager
 *   ├─ SshClient              (connection, commands)
 *   ├─ BrowserRuntimeManager  (ensure-browser-runtime)
 *   ├─ SshTunnelManager       (127.0.0.1:<port> → VPS 127.0.0.1:9222)
 *   └─ BrowserService         (CDP browser control)
 */
class RemoteBrowserManager(
    val config: RemoteBrowserConfig
) : RemoteBrowser {
    private val lock = Mutex()
    private var ssh: SshClient? = null
    private val runtime = BrowserRuntimeManager()
    private val tunnel = SshTunnelManager()
    @Volatile private var cdp: CdpConnection? = null
    private val browser = BrowserService()

    override val supportsPreview = true

    override suspend fun previewFrame(quality: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (cdp?.isOpen != true) return@withContext null
        // Deliberately outside the action lock: long navigation/settling must not
        // freeze the view. Never reconnect, restart Chrome or create a tab here.
        browser.previewFrame(quality)
    }

    override suspend fun execute(action: BrowserAction): BrowserResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val status = try {
                prepare()
            } catch (e: FatalBrowserException) {
                throw e
            } catch (e: BrowserException) {
                // Broken connection somewhere: rebuild the whole path once.
                teardown()
                prepare()
            }
            val result = try {
                browser.perform(action)
            } catch (e: CdpClosedException) {
                closeCdp()
                throw BrowserException("브라우저 연결이 작업 중에 끊겼습니다. 다음 호출에서 다시 연결합니다 (action=state로 페이지를 확인하세요)")
            }
            if (status.restarted.isEmpty()) result
            else BrowserResult(JsonObject(result.json + buildJsonObject {
                put("runtime_restarted", buildJsonArray { status.restarted.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            }), result.screenshot)
        }
    }

    /** One pass of the flow up to a live CDP connection. */
    private suspend fun prepare(): RuntimeStatus {
        val client = ssh?.takeIf { it.isConnected } ?: run {
            teardown()
            SshClient(config.target(), config.hostKey).also { it.connect(); ssh = it }
        }
        val status = runtime.ensure(client)
        if (status.chromeRestarted) {
            // New Chrome process: the old socket and every target id are gone.
            closeCdp()
            browser.reset()
        }
        val port = tunnel.ensure(client)
        cdp?.takeIf { it.isOpen && it.localPort == port }?.let { live ->
            // A cheap round trip proves the socket (and the tunnel under it) still works.
            if (runCatching { live.send("Browser.getVersion", timeoutMs = 5_000) }.isSuccess) return status
        }
        closeCdp()
        val conn = CdpConnection.open(port)
        cdp = conn
        browser.bind(conn)
        return status
    }

    private fun closeCdp() {
        cdp?.close()
        cdp = null
    }

    private fun teardown() {
        closeCdp()
        tunnel.reset(ssh)
        ssh?.disconnect()
        ssh = null
    }

    fun close() {
        BrowserPreviews.clear(this)
        teardown()
    }
}

/**
 * The process-wide browser: one [RemoteBrowserManager] per configuration, so SSH,
 * tunnel and CDP session survive between tool calls and turns. A changed
 * configuration (host, user, key, pinned host key) replaces it.
 */
object RemoteBrowsers {
    private var current: RemoteBrowserManager? = null

    @Synchronized
    fun get(config: RemoteBrowserConfig): RemoteBrowserManager {
        current?.takeIf { it.config == config }?.let { return it }
        current?.close()
        return RemoteBrowserManager(config).also { current = it }
    }

    /** Settings changed or were cleared: drop the live connection. */
    @Synchronized
    fun reset() {
        current?.close()
        current = null
    }

    /** Settings "연결 확인": connect with the pinned key and run the runtime check once. */
    suspend fun check(config: RemoteBrowserConfig): RuntimeStatus = withContext(Dispatchers.IO) {
        reset()
        val client = SshClient(config.target(), config.hostKey)
        try {
            client.connect()
            BrowserRuntimeManager().ensure(client)
        } finally {
            client.disconnect()
        }
    }
}
