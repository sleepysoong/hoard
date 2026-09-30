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
}

/**
 * The VPS to drive, from Settings → 원격 브라우저. [keyEncrypted] is the Keystore
 * blob ([SecretStore]); it is decrypted only when a connection is opened.
 */
data class RemoteBrowserConfig(
    val host: String,
    val port: Int,
    val user: String,
    val keyEncrypted: String,
    val hostKey: PinnedKey
) {
    override fun toString() = "RemoteBrowserConfig($user@$host:$port, hostKey=${hostKey.fingerprint})"

    companion object {
        /** Null until host, user, key and a verified host key are all set. */
        fun from(s: SettingsStore.Settings): RemoteBrowserConfig? {
            if (s.browserHost.isBlank() || s.browserUser.isBlank() || s.browserKeyEncrypted.isBlank()) return null
            val pinned = PinnedKey.parse(s.browserHostKey) ?: return null
            return RemoteBrowserConfig(s.browserHost.trim(), s.browserPort, s.browserUser.trim(), s.browserKeyEncrypted, pinned)
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
    val config: RemoteBrowserConfig,
    private val decryptKey: (String) -> String = SecretStore::decrypt
) : RemoteBrowser {
    private val lock = Mutex()
    private var ssh: SshClient? = null
    private val runtime = BrowserRuntimeManager()
    private val tunnel = SshTunnelManager()
    private var cdp: CdpConnection? = null
    private val browser = BrowserService()

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
            val key = try {
                decryptKey(config.keyEncrypted)
            } catch (e: Exception) {
                throw FatalBrowserException("저장된 SSH 키를 풀 수 없습니다 (앱을 다시 설치한 경우 등). 설정 → 원격 브라우저에서 키를 다시 저장하세요")
            }
            val target = SshTarget(config.host, config.port, config.user, key)
            SshClient(target, config.hostKey).also { it.connect(); ssh = it }
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

    fun close() = teardown()
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
        val client = SshClient(SshTarget(config.host, config.port, config.user, SecretStore.decrypt(config.keyEncrypted)), config.hostKey)
        try {
            client.connect()
            BrowserRuntimeManager().ensure(client)
        } finally {
            client.disconnect()
        }
    }
}
