package com.sleepysoong.hoard.browser

import com.sleepysoong.hoard.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean

/** What the browser_use tool talks to (the tool knows nothing about SSH or CDP). */
interface RemoteBrowser {
    suspend fun execute(action: BrowserAction): BrowserResult
    val supportsPreview: Boolean get() = false
    /** Full desktop JPEG, including Chrome's tab/address bars; UI-only. */
    suspend fun previewFrame(quality: Int = 55): ByteArray? = null
    fun startPreview() {}
    fun stopPreview() {}
    /** Remember a viewer's geometry even before Chrome connects/opens its first page. */
    fun setPreviewViewport(width: Int, height: Int) {}
    val supportsInput: Boolean get() = false
    suspend fun acquireControl(): BrowserControl = throw BrowserException("직접 조작을 지원하지 않습니다")
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
    val hostKey: PinnedKey,
    val vncPort: Int = 5900,
    val vncPasswordEncrypted: String = ""
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

    fun vncPassword(decrypt: (String) -> String = SecretStore::decrypt): String =
        if (vncPasswordEncrypted.isBlank()) "" else try { decrypt(vncPasswordEncrypted) }
        catch (_: Exception) { throw BrowserException("저장된 VNC 비밀번호를 풀 수 없습니다. 설정 → 원격 브라우저에서 다시 저장하세요") }

    override fun toString() = "RemoteBrowserConfig($user@$host:$port, auth=$authMethod, hostKey=${hostKey.fingerprint})"

    companion object {
        const val AUTH_KEY = "key"
        const val AUTH_PASSWORD = "password"

        /** Null until host, user, the chosen login's secret and a verified host key are all set. */
        fun from(s: SettingsStore.Settings): RemoteBrowserConfig? {
            val config = RemoteBrowserConfig(
                s.browserHost.trim(), s.browserPort, s.browserUser.trim(),
                s.browserAuthMethod, s.browserKeyEncrypted, s.browserPasswordEncrypted,
                PinnedKey.parse(s.browserHostKey) ?: return null,
                s.browserVncPort, s.browserVncPasswordEncrypted
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
 *   ├─ SshTunnelManager ×2    (127.0.0.1:<port> → VPS 127.0.0.1:9222 CDP / :5900 VNC)
 *   ├─ BrowserService         (CDP browser control)
 *   └─ VncConnection          (whole-desktop preview + manual input)
 */
class RemoteBrowserManager(
    val config: RemoteBrowserConfig
) : RemoteBrowser {
    private val lock = Mutex()
    @Volatile private var ssh: SshClient? = null
    private val runtime = BrowserRuntimeManager()
    private val tunnel = SshTunnelManager()
    private val vncTunnel = SshTunnelManager(config.vncPort)
    private val vncLock = Mutex()
    private val desktopState = Any()
    @Volatile private var desktop: VncConnection? = null
    private var previewActive = false
    private var previewGeneration = 0L
    @Volatile private var disposed = false
    /** VNC "down" is re-probed at most this often from the preview path (never rebuilt there). */
    @Volatile private var vncDownProbeAt = 0L
    @Volatile private var cdp: CdpConnection? = null
    private val browser = BrowserService()
    private val geometryLock = Mutex()
    private val controlInputLock = Mutex()
    @Volatile private var requestedViewport: BrowserViewport? = RemoteBrowsers.previewViewport
    @Volatile private var fittedViewport: BrowserViewport? = null
    @Volatile private var previewWindow: BrowserWindow? = null
    @Volatile private var fitProblem: String? = null

    override val supportsPreview = true
    override val supportsInput = true

    override fun setPreviewViewport(width: Int, height: Int) {
        if (width > 0 && height > 0) requestedViewport = BrowserViewport(width, height)
    }

    /** Caller holds the AI/manual ownership lock; frame/input never resize on their own. */
    private suspend fun fitPreviewWindow() {
        val viewport = requestedViewport ?: return
        if (fittedViewport == viewport && previewWindow != null) return
        geometryLock.withLock {
            if (fittedViewport == viewport && previewWindow != null) return@withLock
            try {
                previewWindow = browser.fitPreviewWindow(viewport)
                fittedViewport = viewport
                fitProblem = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: BrowserException) {
                // Automation remains available, but never pretend an unfitted desktop
                // is the requested Chrome window or send cropped input to wrong pixels.
                previewWindow = null
                fitProblem = error.message ?: "Chrome 창 크기를 맞추지 못했습니다"
            }
        }
    }

    override fun startPreview() { synchronized(desktopState) { previewActive = true; previewGeneration++ } }
    override fun stopPreview() {
        synchronized(desktopState) { previewActive = false }
        closeDesktop()
    }

    private fun closeDesktop() {
        val old = synchronized(desktopState) { previewGeneration++; desktop.also { desktop = null } }
        old?.close()
    }

    private suspend fun desktopConnection(): VncConnection? = vncLock.withLock {
        val generation = synchronized(desktopState) { if (!previewActive || disposed) return@withLock null; previewGeneration }
        desktop?.takeIf { it.isOpen }?.let { return@withLock it }
        val client = ssh?.takeIf { it.isConnected } ?: return@withLock null
        if (cdp?.isOpen != true) return@withLock null
        if (runtime.last?.vnc == "down") {
            // A healed VNC must not stay "down" until some unrelated AI action runs:
            // re-probe (and repair) at most once per 30 s from the preview path.
            if (System.currentTimeMillis() - vncDownProbeAt < 30_000)
                throw BrowserException("VNC가 내려가 전체 Chrome 화면을 볼 수 없습니다. VPS의 VNC 서비스를 확인하세요")
            vncDownProbeAt = System.currentTimeMillis()
            runtime.ensure(client)
            if (runtime.last?.vnc == "down")
                throw BrowserException("VNC가 내려가 전체 Chrome 화면을 볼 수 없습니다. VPS의 VNC 서비스를 확인하세요")
        }
        val connection = VncConnection.open(vncTunnel.ensure(client), config.vncPassword())
        val accepted = synchronized(desktopState) {
            if (previewActive && !disposed && previewGeneration == generation && ssh === client) {
                desktop = connection; true
            } else false
        }
        if (accepted) connection else { connection.close(); null }
    }

    override suspend fun previewFrame(quality: Int): ByteArray? = withContext(Dispatchers.IO) {
        // Deliberately outside the action lock: long navigation/settling must not
        // freeze the view. Never reconnect SSH, restart Chrome or create a tab here.
        try { geometryLock.withLock {
            fitProblem?.let { throw BrowserException(it) }
            desktopConnection()?.frame(quality, previewWindow)
        } }
        catch (e: Exception) { closeDesktop(); throw e }
    }

    override suspend fun acquireControl(): BrowserControl {
        val owner = Any()
        lock.lock(owner) // cancellable while an existing AI action finishes
        if (disposed) { lock.unlock(owner); throw BrowserException("브라우저 설정이 바뀌어 연결이 닫혔습니다") }
        try { withContext(Dispatchers.IO) { if (cdp?.isOpen == true) fitPreviewWindow() } }
        catch (error: Throwable) { lock.unlock(owner); throw error }
        return object : BrowserControl {
            private val released = AtomicBoolean()
            override suspend fun resizeViewport(width: Int, height: Int) = controlInputLock.withLock {
                if (released.get() || disposed) return@withLock
                setPreviewViewport(width, height)
                withContext(Dispatchers.IO) {
                    desktop?.takeIf { it.isOpen }?.releaseInputs()
                    fitPreviewWindow()
                }
            }
            override suspend fun input(event: DesktopInput) = controlInputLock.withLock {
                if (released.get() || disposed) throw BrowserException("직접 조작이 끝났습니다")
                fitProblem?.let { throw BrowserException(it) }
                val connection = desktop?.takeIf { it.isOpen } ?: throw BrowserException("VNC 화면 연결을 기다리는 중입니다")
                val window = previewWindow
                val translated = when (event) {
                    is DesktopInput.Pointer -> event.copy(x = event.x + (window?.left ?: 0), y = event.y + (window?.top ?: 0))
                    is DesktopInput.Scroll -> event.copy(x = event.x + (window?.left ?: 0), y = event.y + (window?.top ?: 0))
                    else -> event
                }
                connection.input(translated)
            }
            override suspend fun release() {
                if (!released.compareAndSet(false, true)) return
                withContext(NonCancellable + Dispatchers.IO) {
                    try { desktop?.takeIf { it.isOpen }?.releaseInputs() }
                    catch (_: Exception) { closeDesktop() }
                    finally {
                        // A person may have navigated or switched Chrome tabs. Do
                        // not apply the AI's pre-handoff element ids to a new page.
                        browser.afterManualControl()
                        fittedViewport = null
                        lock.unlock(owner)
                    }
                }
            }
        }
    }

    override suspend fun execute(action: BrowserAction): BrowserResult = lock.withLock {
        if (disposed) throw BrowserException("브라우저 설정이 바뀌어 연결이 닫혔습니다")
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
                fitPreviewWindow()
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
        if (disposed) throw BrowserException("브라우저 설정이 바뀌어 연결이 닫혔습니다")
        val client = ssh?.takeIf { it.isConnected } ?: run {
            teardown()
            val connected = SshClient(config.target(), config.hostKey).also { it.connect() }
            // close() doesn't hold [lock]: it may have run while connect() was in
            // flight. Never leave its freshly made session behind.
            if (disposed) {
                runCatching { connected.disconnect() }
                throw BrowserException("브라우저 설정이 바뀌어 연결이 닫혔습니다")
            }
            ssh = connected
            connected
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
        fittedViewport = null
        previewWindow = null
        fitProblem = null
        cdp?.close()
        cdp = null
    }

    private fun teardown() {
        closeDesktop()
        vncTunnel.reset(ssh)
        closeCdp()
        tunnel.reset(ssh)
        ssh?.disconnect()
        ssh = null
    }

    fun close() {
        disposed = true
        stopPreview()
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
    @Volatile var previewViewport: BrowserViewport? = null
        private set

    /** Chat geometry arrives before a browser tool creates the process-wide connection. */
    @Synchronized fun setPreviewViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        previewViewport = BrowserViewport(width, height)
        current?.setPreviewViewport(width, height)
    }

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

    /** Settings "연결 확인": pinned SSH, runtime, then actual VNC authentication/frame. */
    suspend fun check(config: RemoteBrowserConfig): RuntimeStatus = withContext(Dispatchers.IO) {
        reset()
        val client = SshClient(config.target(), config.hostKey)
        try {
            client.connect()
            val status = BrowserRuntimeManager().ensure(client)
            val port = client.forwardLocal(config.vncPort)
            try { VncConnection.open(port, config.vncPassword()).use { it.frame(20) } }
            finally { client.removeForward(port) }
            status
        } finally {
            client.disconnect()
        }
    }
}
