package com.sleepysoong.hoard

import com.sleepysoong.hoard.termux.TermuxException
import com.sleepysoong.hoard.termux.TermuxExecutor
import com.sleepysoong.hoard.termux.TermuxResult
import com.sleepysoong.hoard.tools.TermuxExecTool
import com.sleepysoong.hoard.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** termux_exec's tool layer against a fake executor (no Android, no Termux). */
class TermuxExecToolTest {
    private class FakeExec(val reply: (String) -> TermuxResult) : TermuxExecutor {
        val calls = mutableListOf<Triple<String, String?, Long>>()
        override suspend fun executeTermux(command: String, cwd: String?, timeoutMs: Long): TermuxResult {
            calls += Triple(command, cwd, timeoutMs)
            return reply(command)
        }
    }

    @Test fun resultGoesToTheModelAsIsIncludingNonZeroExit() = runBlocking {
        val exec = FakeExec { cmd -> if (cmd == "git status") TermuxResult("On branch main\n", "", 0) else TermuxResult("", "fatal: not a git repository\n", 128) }
        val reg = ToolRegistry().register(TermuxExecTool(exec))

        val ok = reg.execute("termux_exec", """{"command":"git status"}""")
        assertFalse(ok.isError)
        assertEquals(Json.parseToJsonElement("""{"stdout":"On branch main\n","stderr":"","exitCode":0}"""), Json.parseToJsonElement(ok.output))
        assertEquals(Triple("git status", null, 30_000L), exec.calls.last())
        assertEquals("Termux 실행", ok.title)
        assertTrue(ok.body, ok.body.startsWith("git status\nexit 0"))

        val bad = reg.execute("termux_exec", """{"command":"git log","cwd":"/tmp","timeout":5000}""")
        assertFalse("exitCode != 0 is a normal result, not a tool error", bad.isError)
        val out = Json.parseToJsonElement(bad.output).jsonObject
        assertEquals(128, out["exitCode"]!!.jsonPrimitive.content.toInt())
        assertEquals("fatal: not a git repository\n", out["stderr"]!!.jsonPrimitive.content)
        assertEquals("", out["stdout"]!!.jsonPrimitive.content)
        assertEquals(Triple("git log", "/tmp", 5000L), exec.calls.last())
    }

    @Test fun transportFailuresBecomeDistinctToolErrors() = runBlocking {
        for ((ex, code) in listOf(
            TermuxException.NotInstalled() to "termux_not_installed",
            TermuxException.PermissionDenied() to "termux_permission_denied",
            TermuxException.ExternalAppsDisabled("…") to "termux_external_apps_disabled",
            TermuxException.Timeout(1000) to "termux_timeout"
        )) {
            val o = ToolRegistry().register(TermuxExecTool(FakeExec { throw ex })).execute("termux_exec", """{"command":"ls"}""")
            assertTrue(o.isError)
            val err = Json.parseToJsonElement(o.output).jsonObject["error"]!!.jsonPrimitive.content
            assertTrue(err, err.startsWith("$code:"))
        }
        val blank = ToolRegistry().register(TermuxExecTool(FakeExec { TermuxResult("", "", 0) })).execute("termux_exec", """{"command":"  "}""")
        assertTrue(blank.output.contains("command is required"))
    }

    @Test fun timeoutIsClampedAndHugeOutputCapped() = runBlocking {
        val exec = FakeExec { TermuxResult("y".repeat(50_000), "", 0, stdoutTruncated = false) }
        val reg = ToolRegistry().register(TermuxExecTool(exec))
        val o = Json.parseToJsonElement(reg.execute("termux_exec", """{"command":"yes","timeout":1}""").output).jsonObject
        assertEquals(TermuxExecTool.MIN_TIMEOUT_MS, exec.calls.last().third)
        assertEquals(true, o["stdoutTruncated"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(o["stdout"]!!.jsonPrimitive.content.length < 20_100)
        reg.execute("termux_exec", """{"command":"x","timeout":99999999}""")
        assertEquals(TermuxExecTool.MAX_TIMEOUT_MS, exec.calls.last().third)
    }
}
