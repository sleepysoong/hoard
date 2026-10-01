package com.sleepysoong.hoard.skills

import com.sleepysoong.hoard.termux.TermuxBridge
import com.sleepysoong.hoard.termux.TermuxException
import com.sleepysoong.hoard.termux.TermuxExecutor
import com.sleepysoong.hoard.termux.TermuxResult
import com.sleepysoong.hoard.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Copies bytes only. No installer, bundled script, or hook runs during preparation. */
internal class SkillShell(private val executor: TermuxExecutor?) {
    fun digest(directory: File): String = hash(archive(directory))
    suspend fun sync(directory: File, key: String): String = withContext(Dispatchers.IO) {
        val archive = archive(directory)
        val digest = hash(archive)
        val root = "${TermuxBridge.HOME_PATH}/.hoard/skills/${hash(key.toByteArray()).take(32)}"
        // Never reuse a Termux mirror: model/user shell tools can edit its helpers.
        // A fresh unpredictable destination is published by one atomic rename.
        val target = "$root/$digest-${UUID.randomUUID()}"
        withTimeout(180_000L) {
            val stage = "$root/.stage-${UUID.randomUUID()}"
            checked("set -e; umask 077; mkdir -p ${quote(root)}; mkdir ${quote(stage)}; : > ${quote("$stage/bundle.tar")}")
            try {
                val encoded = Base64.getEncoder().encodeToString(archive)
                encoded.chunked(16_384).forEach { chunk ->
                    checked("set -o pipefail; printf '%s' ${quote(chunk)} | base64 -d >> ${quote("$stage/bundle.tar")}")
                }
                checked("set -e; test \"\$(sha256sum ${quote("$stage/bundle.tar")} | cut -d ' ' -f 1)\" = ${quote(digest)}; " +
                    "mkdir ${quote("$stage/tree")}; tar -xf ${quote("$stage/bundle.tar")} -C ${quote("$stage/tree")}; " +
                    "test ! -e ${quote(target)}; mv -T ${quote("$stage/tree")} ${quote(target)}; " +
                    "rm -f ${quote("$stage/bundle.tar")}; rmdir ${quote(stage)}")
                target
            } catch (e: Exception) {
                // This is one uniquely named staging tree, never a user/workspace path.
                // Leave a failed tree for diagnosis: cancellation must not start more commands.
                throw e
            }
        }
    }

    suspend fun ensureDataDirectory(key: String): String {
        val path = "${TermuxBridge.HOME_PATH}/.hoard/plugin-data/${hash(key.toByteArray()).take(32)}"
        checked("umask 077; mkdir -p ${quote(path)}")
        return path
    }

    /** Bound output on the Termux side, before Binder transports it. timeout kills the process. */
    suspend fun run(command: String, cwd: String, timeoutMs: Long = 30_000L): TermuxResult {
        val seconds = (timeoutMs.coerceIn(1_000L, 120_000L) + 999) / 1000
        // Drain overflow through FIFOs instead of letting temp files fill the disk.
        // Do not set a process-wide file-size limit: trusted commands may create real artifacts.
        val wrapped = "d=\$(mktemp -d) || exit 1; trap 'rm -f \"\$d/out\" \"\$d/err\" \"\$d/op\" \"\$d/ep\"; rmdir \"\$d\"' EXIT; " +
            "mkfifo \"\$d/op\" \"\$d/ep\" || exit 1; " +
            "timeout -k 1s ${seconds + 3}s bash -c 'head -c ${OUTPUT_LIMIT + 1}; cat >/dev/null' <\"\$d/op\" >\"\$d/out\" & op=\$!; " +
            "timeout -k 1s ${seconds + 3}s bash -c 'head -c ${OUTPUT_LIMIT + 1}; cat >/dev/null' <\"\$d/ep\" >\"\$d/err\" & ep=\$!; " +
            "timeout -k 2s ${seconds}s bash -c ${quote(command)} >\"\$d/op\" 2>\"\$d/ep\"; rc=\$?; wait \"\$op\" \"\$ep\"; " +
            "head -c $OUTPUT_LIMIT \"\$d/out\"; head -c $OUTPUT_LIMIT \"\$d/err\" >&2; " +
            "if [ \"\$(wc -c <\"\$d/out\")\" -gt $OUTPUT_LIMIT ] || [ \"\$(wc -c <\"\$d/err\")\" -gt $OUTPUT_LIMIT ]; " +
            "then printf '\\nSkill shell output exceeds $OUTPUT_LIMIT bytes; narrow the command.\\n' >&2; exit 125; fi; exit \$rc"
        return checked(wrapped, cwd, seconds * 1000 + 5_000, allowFailure = true)
    }

    private suspend fun checked(
        command: String,
        cwd: String? = null,
        timeoutMs: Long = 30_000L,
        allowFailure: Boolean = false
    ): TermuxResult {
        if (command.toByteArray().size > 32_768) throw ToolException("Skill Termux command exceeds 32 KB; shorten the command or arguments.")
        val bridge = executor ?: throw ToolException("Skill scripts require Termux. Install Termux, grant Hoard RUN_COMMAND, and enable allow-external-apps in Termux.")
        val result = try {
            bridge.executeTermux(command, cwd, timeoutMs)
        } catch (e: TermuxException) {
            throw ToolException("${e.code}: ${e.message}")
        }
        if (result.stdoutTruncated || result.stderrTruncated) throw ToolException("Termux truncated skill output; narrow the command and retry.")
        if (!allowFailure && result.exitCode != 0) throw ToolException(
            "Skill bundle preparation failed (exit ${result.exitCode}): ${result.stderr.take(2_000)} ${result.stdout.take(1_000)}. " +
                "Check Termux storage and install coreutils, tar and bash; then invoke the skill again."
        )
        return result
    }

    /** Deterministic, bounded ustar archive. Reject links/special files, traversal and reserved names. */
    private fun archive(directory: File): ByteArray {
        val root = directory.canonicalFile
        if (!root.isDirectory) throw ToolException("Skill bundle directory no longer exists: $root")
        val out = ByteArrayOutputStream()
        var count = 0
        var bytes = 0L
        fun entry(file: File, relative: String) {
            if (++count > 512) throw ToolException("Skill bundle has more than 512 entries; reduce the bundle.")
            if (Files.isSymbolicLink(file.toPath()) || !file.canonicalFile.toPath().startsWith(root.toPath())) {
                throw ToolException("Skill bundle links are not copied: $relative. Replace the link with a regular file.")
            }
            if (!file.isDirectory && !file.isFile) throw ToolException("Unsupported skill bundle entry: $relative")
            if (relative.split('/').any { it == ".." || it == ".hoard-ready" } || relative.any { it.code < 32 }) {
                throw ToolException("Unsafe skill bundle path: $relative")
            }
            val path = relative + if (file.isDirectory) "/" else ""
            val encoded = path.toByteArray(Charsets.UTF_8)
            val header = ByteArray(512)
            fun text(offset: Int, size: Int, value: String) {
                val data = value.toByteArray(Charsets.UTF_8)
                if (data.size > size) throw ToolException("Skill bundle path is too long: $relative")
                data.copyInto(header, offset)
            }
            if (encoded.size <= 100) text(0, 100, path) else {
                val split = path.dropLastWhile { it == '/' }.lastIndexOf('/')
                if (split < 0) throw ToolException("Skill bundle path is too long: $relative")
                text(0, 100, path.substring(split + 1))
                text(345, 155, path.substring(0, split))
            }
            val data = if (file.isFile) {
                if (file.length() + bytes > 16L * 1024 * 1024) throw ToolException("Skill bundle exceeds 16 MB; reduce the bundle.")
                file.inputStream().use { stream ->
                    val data = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (bytes + data.size() + read > 16L * 1024 * 1024) throw ToolException("Skill bundle exceeds 16 MB.")
                        data.write(buffer, 0, read)
                    }
                    data.toByteArray()
                }.also {
                    bytes += it.size
                    if (bytes > 16L * 1024 * 1024) throw ToolException("Skill bundle exceeds 16 MB.")
                }
            } else ByteArray(0)
            text(100, 8, (if (file.isDirectory || file.canExecute()) "0000700" else "0000600") + "\u0000")
            text(108, 8, "0000000\u0000")
            text(116, 8, "0000000\u0000")
            text(124, 12, data.size.toString(8).padStart(11, '0') + "\u0000")
            text(136, 12, "00000000000\u0000")
            for (i in 148..155) header[i] = 32
            header[156] = (if (file.isDirectory) '5' else '0').code.toByte()
            text(257, 6, "ustar\u0000")
            text(263, 2, "00")
            val sum = header.sumOf { it.toInt() and 255 }
            text(148, 8, sum.toString(8).padStart(6, '0') + "\u0000 ")
            out.write(header)
            out.write(data)
            if (data.size % 512 != 0) out.write(ByteArray(512 - data.size % 512))
            if (file.isDirectory) {
                val children = file.listFiles() ?: throw ToolException("Cannot read skill bundle directory: $relative")
                children.sortedBy { it.name }.forEach { entry(it, "$relative/${it.name}") }
            }
        }
        (root.listFiles() ?: throw ToolException("Cannot read skill bundle directory: $root"))
            .sortedBy { it.name }.forEach { entry(it, it.name) }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    companion object {
        const val OUTPUT_LIMIT = 16_384
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
        fun hash(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
