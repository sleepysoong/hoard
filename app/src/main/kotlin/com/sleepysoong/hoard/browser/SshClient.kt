package com.sleepysoong.hoard.browser

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/** How the VPS login proves itself. Secrets exist in memory only, never persisted as plain text. */
sealed interface SshAuth {
    /** An OpenSSH/PEM private key without a passphrase. */
    data class Key(val privateKey: String) : SshAuth { override fun toString() = "Key(...)" }

    /** Password login (the VPS's sshd must allow PasswordAuthentication). */
    data class Password(val password: String) : SshAuth { override fun toString() = "Password(...)" }
}

/** Where and as whom to SSH, and how to authenticate. */
data class SshTarget(val host: String, val port: Int, val user: String, val auth: SshAuth) {
    override fun toString() = "SshTarget($user@$host:$port, auth=${if (auth is SshAuth.Password) "password" else "key"})"
}

/** Something on the SSH / runtime / CDP path failed; the message is shown to the model and the user. */
open class BrowserException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A failure reconnecting can't fix (setup, host key, runtime): reported, never retried. */
open class FatalBrowserException(message: String) : BrowserException(message)

/** The VPS answered but can't run the browser (ensure-browser-runtime said ready=false). */
class RuntimeNotReadyException(message: String) : FatalBrowserException(message)

/** The pinned host key doesn't match: never retried, never auto-replaced. */
class HostKeyMismatchException(message: String) : FatalBrowserException(message)

/** A server host key as pinned in Settings: "type base64" (the known_hosts key part). */
data class PinnedKey(val type: String, val base64: String) {
    /** OpenSSH-style fingerprint (same as `ssh-keygen -lf`), e.g. "SHA256:abc…". */
    val fingerprint: String
        get() = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(base64)))

    override fun toString() = "$type $base64"

    companion object {
        fun parse(s: String?): PinnedKey? =
            s?.trim()?.split(" ", limit = 2)?.takeIf { it.size == 2 && it[1].isNotBlank() }?.let { PinnedKey(it[0], it[1].trim()) }
    }
}

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * One SSH connection to the VPS (JSch, key or password auth): exec commands and
 * local port forwards. Host key verification is strict: the server must present
 * exactly the [pinned] key (pinned by the user in Settings after comparing the
 * fingerprint, see [probeHostKey]); anything else aborts before authentication —
 * with a password login that is what keeps the password from a spoofed server.
 */
class SshClient(private val target: SshTarget, private val pinned: PinnedKey) {
    private var session: Session? = null

    val isConnected: Boolean get() = session?.isConnected == true

    fun connect(timeoutMs: Int = CONNECT_TIMEOUT_MS) {
        if (isConnected) return
        val jsch = JSch()
        jsch.setHostKeyRepository(PinnedRepository(pinned))
        when (val auth = target.auth) {
            is SshAuth.Key -> try {
                jsch.addIdentity("hoard", auth.privateKey.trim().toByteArray(Charsets.UTF_8), null, null)
            } catch (e: Exception) {
                throw BrowserException("SSH 개인 키를 읽을 수 없습니다 (암호 없는 OpenSSH/PEM 키만 지원): ${e.message}", e)
            }
            is SshAuth.Password -> Unit // handed to the session below
        }
        val s = jsch.getSession(target.user, target.host, target.port).apply {
            setConfig("StrictHostKeyChecking", "yes")
            when (val auth = target.auth) {
                is SshAuth.Key -> setConfig("PreferredAuthentications", "publickey")
                is SshAuth.Password -> {
                    setPassword(auth.password)
                    setConfig("PreferredAuthentications", "password")
                }
            }
            userInfo = NoPrompts
            setServerAliveInterval(KEEPALIVE_MS)
            setServerAliveCountMax(3)
        }
        try {
            s.connect(timeoutMs)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("HostKey has been changed", true) || msg.contains("reject HostKey", true)) {
                throw HostKeyMismatchException(
                    "VPS 호스트 키가 등록된 키와 다릅니다 (등록: ${pinned.fingerprint}). 중간자 공격이거나 서버가 재설치된 경우입니다. " +
                        "서버를 확인한 뒤 설정 → 원격 브라우저에서 다시 확인하세요."
                )
            }
            val authHint = when (target.auth) {
                is SshAuth.Key -> "${target.user}의 ~/.ssh/authorized_keys에 이 앱의 공개 키가 있는지 확인하세요"
                is SshAuth.Password -> "비밀번호가 맞는지, 서버의 sshd가 비밀번호 로그인을 허용하는지(PasswordAuthentication yes) 확인하세요"
            }
            throw BrowserException(
                if (msg.contains("Auth fail", true) || msg.contains("USERAUTH fail", true)) "SSH 인증 실패: $authHint"
                else "SSH 연결 실패 (${target.host}:${target.port}): $msg",
                e
            )
        }
        session = s
    }

    /** Runs [command] and waits for it (up to [timeoutMs]). A non-zero exit is a normal result. */
    fun exec(command: String, timeoutMs: Long = 60_000): ExecResult {
        val s = session?.takeIf { it.isConnected } ?: throw BrowserException("SSH가 연결되어 있지 않습니다")
        val ch = try {
            (s.openChannel("exec") as ChannelExec).apply { setCommand(command) }
        } catch (e: Exception) {
            throw BrowserException("SSH 채널을 열 수 없습니다: ${e.message}", e)
        }
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        try {
            val stdout = ch.inputStream
            val stderr = ch.errStream
            ch.connect(CONNECT_TIMEOUT_MS)
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(8192)
            while (true) {
                while (stdout.available() > 0) { val n = stdout.read(buf); if (n < 0) break; if (out.size() < MAX_OUTPUT) out.write(buf, 0, n) }
                while (stderr.available() > 0) { val n = stderr.read(buf); if (n < 0) break; if (err.size() < MAX_OUTPUT) err.write(buf, 0, n) }
                if (ch.isClosed && stdout.available() == 0 && stderr.available() == 0) break
                if (System.currentTimeMillis() > deadline) throw BrowserException("SSH 명령 시간 초과 (${timeoutMs / 1000}초): $command")
                Thread.sleep(30)
            }
            return ExecResult(ch.exitStatus, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
        } catch (e: BrowserException) {
            throw e
        } catch (e: Exception) {
            throw BrowserException("SSH 명령 실패: ${e.message}", e)
        } finally {
            ch.disconnect()
        }
    }

    /** Local forward 127.0.0.1:<free port> → (VPS) 127.0.0.1:[remotePort]. Returns the local port. */
    fun forwardLocal(remotePort: Int): Int {
        val s = session?.takeIf { it.isConnected } ?: throw BrowserException("SSH가 연결되어 있지 않습니다")
        return try {
            s.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", remotePort)
        } catch (e: Exception) {
            throw BrowserException("SSH 터널을 만들 수 없습니다: ${e.message}", e)
        }
    }

    /** Whether the forward on [localPort] is still registered on this (connected) session. */
    fun hasForward(localPort: Int): Boolean =
        isConnected && runCatching { session?.portForwardingL.orEmpty().any { it.substringBefore(':') == localPort.toString() } }.getOrDefault(false)

    fun removeForward(localPort: Int) { runCatching { session?.delPortForwardingL("127.0.0.1", localPort) } }

    fun disconnect() {
        runCatching { session?.disconnect() }
        session = null
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val KEEPALIVE_MS = 30_000
        private const val MAX_OUTPUT = 256 * 1024

        /**
         * Reads the host key the server presents, without authenticating: the key
         * exchange runs, the key is recorded, and the connection is refused on purpose.
         * The user compares its fingerprint before it is pinned.
         */
        fun probeHostKey(host: String, port: Int, user: String, timeoutMs: Int = CONNECT_TIMEOUT_MS): PinnedKey {
            val recorder = RecordingRepository()
            val jsch = JSch().apply { setHostKeyRepository(recorder) }
            val s = jsch.getSession(user.ifBlank { "probe" }, host, port).apply {
                setConfig("StrictHostKeyChecking", "yes")
                userInfo = NoPrompts
            }
            try {
                s.connect(timeoutMs)
            } catch (e: Exception) {
                recorder.seen?.let { return it }
                throw BrowserException("SSH 서버에 연결할 수 없습니다 ($host:$port): ${e.message}", e)
            } finally {
                runCatching { s.disconnect() }
            }
            return recorder.seen ?: throw BrowserException("서버가 호스트 키를 보내지 않았습니다")
        }
    }
}

/** JSch host key store holding exactly one trusted (pinned) key. */
private class PinnedRepository(private val pinned: PinnedKey) : BaseRepository() {
    override fun check(host: String?, key: ByteArray?): Int {
        val presented = HostKey(host, key)
        return if (presented.type == pinned.type && presented.key == pinned.base64) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }
}

/** Records the presented key and rejects it (used by [SshClient.probeHostKey]). */
private class RecordingRepository : BaseRepository() {
    @Volatile var seen: PinnedKey? = null
    override fun check(host: String?, key: ByteArray?): Int {
        val presented = HostKey(host, key)
        seen = PinnedKey(presented.type, presented.key)
        return HostKeyRepository.NOT_INCLUDED
    }
}

private abstract class BaseRepository : HostKeyRepository {
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID() = "hoard-pinned"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

private object NoPrompts : UserInfo {
    override fun getPassphrase(): String? = null
    override fun getPassword(): String? = null
    override fun promptPassword(message: String?) = false
    override fun promptPassphrase(message: String?) = false
    override fun promptYesNo(message: String?) = false
    override fun showMessage(message: String?) = Unit
}
