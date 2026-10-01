package com.sleepysoong.hoard.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/** What `ensure-browser-runtime` reported (see scripts/vps/ensure-browser-runtime). */
data class RuntimeStatus(
    val ready: Boolean,
    val error: String? = null,
    val display: String? = null,
    val vnc: String? = null,
    val chrome: String? = null,
    val cdp: String? = null,
    val restarted: List<String> = emptyList()
) {
    val chromeRestarted: Boolean get() = "chrome" in restarted
}

/**
 * Runs `/usr/local/bin/ensure-browser-runtime` on the VPS before every browser action.
 * The script checks X display → VNC → Chrome → CDP, repairs only what is broken and
 * prints one JSON object; only `ready: true` lets the action run.
 */
class BrowserRuntimeManager(private val command: String = COMMAND) {
    @Volatile var last: RuntimeStatus? = null
        private set

    fun ensure(ssh: SshClient): RuntimeStatus {
        val r = ssh.exec(command, timeoutMs = TIMEOUT_MS)
        if (r.exitCode == 127 || (r.stdout.isBlank() && r.stderr.contains("No such file", ignoreCase = true))) {
            throw RuntimeNotReadyException("VPS에 $command 이 없습니다. Hoard 저장소의 scripts/vps/ensure-browser-runtime을 설치하세요")
        }
        val status = parse(r.stdout) ?: throw BrowserException(
            "ensure-browser-runtime이 JSON을 돌려주지 않았습니다 (exit ${r.exitCode}): " + (r.stderr.ifBlank { r.stdout }).trim().take(300)
        )
        last = status
        if (!status.ready) throw RuntimeNotReadyException("VPS 브라우저를 준비하지 못했습니다: ${status.error ?: "unknown"}")
        return status
    }

    companion object {
        const val COMMAND = "/usr/local/bin/ensure-browser-runtime"
        /** Display + Chrome restarts wait up to ~50 s on the VPS side. */
        const val TIMEOUT_MS = 90_000L
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** The last JSON object line of stdout (the script may be wrapped by a login banner). */
        fun parse(stdout: String): RuntimeStatus? {
            val line = stdout.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") && it.endsWith("}") } ?: return null
            val o = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return RuntimeStatus(
                ready = (o["ready"] as? JsonPrimitive)?.booleanOrNull ?: return null,
                error = str("error"),
                display = str("display"),
                vnc = str("vnc"),
                chrome = str("chrome"),
                cdp = str("cdp"),
                restarted = (o["restarted"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            )
        }
    }
}

/**
 * The CDP tunnel: SSH local port forwarding 127.0.0.1:<dynamic port> on the phone →
 * 127.0.0.1:9222 on the VPS. Chrome's debugging port is never exposed to the network.
 */
class SshTunnelManager(private val remotePort: Int = CDP_PORT) {
    @Volatile var localPort: Int? = null
        private set

    /** The live forward's local port; (re)creates it when missing. */
    fun ensure(ssh: SshClient): Int {
        localPort?.let { if (ssh.hasForward(it)) return it }
        localPort?.let(ssh::removeForward)
        return ssh.forwardLocal(remotePort).also { localPort = it }
    }

    fun reset(ssh: SshClient?) {
        localPort?.let { port -> ssh?.removeForward(port) }
        localPort = null
    }

    companion object {
        const val CDP_PORT = 9222
    }
}
