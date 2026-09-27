package com.sleepysoong.hoard.tools

import com.sleepysoong.hoard.termux.TermuxException
import com.sleepysoong.hoard.termux.TermuxExecutor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `termux_exec`: run a one-shot shell command in Termux and return
 * `{"stdout","stderr","exitCode"}`. A non-zero exit code is an ordinary result
 * the model reads; only "could not run / no result" is a tool error.
 * Knows nothing about Android: it talks to a [TermuxExecutor].
 */
class TermuxExecTool(private val termux: TermuxExecutor) : Tool {
    override val name = NAME
    override val title = "Termux 실행"
    override val description = "Execute a one-shot shell command in Termux and return its result."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("command") {
                put("type", "string")
                put("description", "Shell command to execute.")
            }
            putJsonObject("cwd") {
                put("type", "string")
                put("description", "Optional working directory.")
            }
            putJsonObject("timeout") {
                put("type", "integer")
                put("description", "Optional timeout in milliseconds.")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("command")) })
    }

    override fun subject(args: JsonObject): String =
        args.string("command").orEmpty().lineSequence().firstOrNull().orEmpty().take(120)

    override fun summarize(output: JsonObject): String {
        val code = output.string("exitCode") ?: (output["exitCode"] as? JsonPrimitive)?.content
        val lines = output.string("stdout").orEmpty().lines().count { it.isNotBlank() }
        val err = output.string("stderr").orEmpty()
        return buildString {
            append("exit $code")
            append(" · stdout ${lines}줄")
            if (err.isNotBlank()) append(" · stderr: ").append(err.lineSequence().first().take(80))
        }
    }

    override suspend fun execute(args: JsonObject): JsonObject {
        val command = args.string("command").orEmpty()
        if (command.isBlank()) throw ToolException("command is required")
        val cwd = args.string("cwd")?.trim()?.takeIf { it.isNotEmpty() }
        val timeout = (args["timeout"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
            ?.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS) ?: TermuxExecutor.DEFAULT_TIMEOUT_MS
        val r = try {
            termux.executeTermux(command, cwd, timeout)
        } catch (e: TermuxException) {
            throw ToolException("${e.code}: ${e.message}")
        }
        return buildJsonObject {
            put("stdout", cap(r.stdout))
            put("stderr", cap(r.stderr))
            put("exitCode", r.exitCode)
            if (r.stdoutTruncated || r.stdout.length > MAX_CHARS) put("stdoutTruncated", true)
            if (r.stderrTruncated || r.stderr.length > MAX_CHARS) put("stderrTruncated", true)
        }
    }

    private fun cap(s: String) = if (s.length <= MAX_CHARS) s else s.take(MAX_CHARS) + "\n[… truncated]"

    companion object {
        const val NAME = "termux_exec"
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 10 * 60_000L
        /** Per stream, for the model's context. Termux itself caps output to fit Binder. */
        const val MAX_CHARS = 20_000
    }
}
