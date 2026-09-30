package com.sleepysoong.hoard.browser

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/** SSH key helpers for Settings: check an imported key, or make a new one on the phone. */
object SshKeys {
    /** A private key's public half, as a line for ~/.ssh/authorized_keys. */
    data class Info(val type: String, val publicKey: String, val fingerprint: String)

    /** Validates a pasted private key (OpenSSH or PEM, no passphrase). */
    fun inspect(privateKey: String): Info {
        val kp = try {
            KeyPair.load(JSch(), privateKey.trim().toByteArray(Charsets.UTF_8), null)
        } catch (e: Exception) {
            throw BrowserException("개인 키를 읽을 수 없습니다 (OpenSSH/PEM 형식): ${e.message}", e)
        }
        try {
            if (kp.isEncrypted) throw BrowserException("암호가 걸린 키는 쓸 수 없습니다. 암호를 지운 키를 쓰거나(ssh-keygen -p) 앱에서 새 키를 만드세요")
            return info(kp)
        } finally {
            kp.dispose()
        }
    }

    /** A new Ed25519 key pair: (private key in OpenSSH format, its public key). */
    fun generate(): Pair<String, Info> {
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
        try {
            val out = ByteArrayOutputStream()
            kp.writeOpenSSHv1PrivateKey(out, null)
            return out.toString(Charsets.UTF_8.name()) to info(kp)
        } finally {
            kp.dispose()
        }
    }

    private fun info(kp: KeyPair): Info {
        val blob = kp.publicKeyBlob ?: throw BrowserException("이 키에서 공개 키를 만들 수 없습니다")
        val type = kp.keyTypeString
        val publicKey = "$type ${Base64.getEncoder().encodeToString(blob)} hoard"
        val fp = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
        return Info(type, publicKey, fp)
    }
}
