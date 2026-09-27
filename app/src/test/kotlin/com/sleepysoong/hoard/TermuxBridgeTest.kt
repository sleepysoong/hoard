package com.sleepysoong.hoard

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.termux.TermuxBridge
import com.sleepysoong.hoard.termux.TermuxException
import com.sleepysoong.hoard.termux.TermuxPlatform
import com.sleepysoong.hoard.termux.TermuxResult
import com.sleepysoong.hoard.termux.TermuxResultReceiver
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * TermuxBridge against a fake Termux: the RUN_COMMAND intent it sends (literal wire
 * values, so a TermuxConstants change is caught), results coming back through the
 * real PendingIntent → manifest receiver path, concurrency, timeout, and each
 * setup failure mapped to its own error.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TermuxBridgeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private class FakeTermux(var installed: Boolean = true, var granted: Boolean = true, var startError: RuntimeException? = null) : TermuxPlatform {
        val started = mutableListOf<Intent>()
        override fun isTermuxInstalled() = installed
        override fun hasRunCommandPermission() = granted
        override fun startService(intent: Intent) {
            startError?.let { throw it }
            started += intent
        }
    }

    /** What Termux does when a command finishes: send our PendingIntent with the result bundle filled in. */
    private fun termuxReplies(request: Intent, result: Bundle) {
        @Suppress("DEPRECATION")
        val pi = request.getParcelableExtra<PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT")!!
        pi.send(ctx, 0, Intent().putExtra("result", result))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun bundle(stdout: String? = "", stderr: String? = "", exitCode: Int? = 0, err: Int = -1, errmsg: String? = null) = Bundle().apply {
        stdout?.let { putString("stdout", it) }
        stderr?.let { putString("stderr", it) }
        exitCode?.let { putInt("exitCode", it) }
        putInt("err", err)
        errmsg?.let { putString("errmsg", it) }
    }

    @Test fun runCommandIntentMatchesTermuxApi() = runBlocking {
        val termux = FakeTermux()
        val bridge = TermuxBridge(ctx, termux)
        val call = async(start = CoroutineStart.UNDISPATCHED) { bridge.executeTermux("git status", cwd = "/sdcard/repo") }
        val i = termux.started.single()
        assertEquals("com.termux.RUN_COMMAND", i.action)
        assertEquals("com.termux", i.component!!.packageName)
        assertEquals("com.termux.app.RunCommandService", i.component!!.className)
        assertEquals("/data/data/com.termux/files/usr/bin/bash", i.getStringExtra("com.termux.RUN_COMMAND_PATH"))
        assertArrayEquals(arrayOf("-lc", "git status"), i.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS"))
        assertEquals("/sdcard/repo", i.getStringExtra("com.termux.RUN_COMMAND_WORKDIR"))
        assertTrue(i.getBooleanExtra("com.termux.RUN_COMMAND_BACKGROUND", false))
        @Suppress("DEPRECATION")
        val pi = i.getParcelableExtra<PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT")!!
        val target = shadowOf(pi).savedIntent
        assertEquals("explicit, to our receiver", TermuxResultReceiver::class.java.name, target.component!!.className)
        assertTrue("mutable so Termux can fill in the result", shadowOf(pi).flags and PendingIntent.FLAG_MUTABLE != 0)

        termuxReplies(i, bundle(stdout = "On branch main\n", exitCode = 0))
        assertEquals(TermuxResult("On branch main\n", "", 0), call.await())
    }

    @Test fun defaultWorkdirIsTermuxHome() = runBlocking {
        val termux = FakeTermux()
        val call = async(start = CoroutineStart.UNDISPATCHED) { TermuxBridge(ctx, termux).executeTermux("pwd") }
        assertEquals("/data/data/com.termux/files/home", termux.started.single().getStringExtra("com.termux.RUN_COMMAND_WORKDIR"))
        termuxReplies(termux.started.single(), bundle(stdout = "/data/data/com.termux/files/home\n"))
        call.await(); Unit
    }

    @Test fun nonZeroExitAndEmptyOutputAreNormalResults() = runBlocking {
        val termux = FakeTermux()
        val bridge = TermuxBridge(ctx, termux)
        val failing = async(start = CoroutineStart.UNDISPATCHED) { bridge.executeTermux("false") }
        termuxReplies(termux.started.last(), bundle(stdout = "", stderr = "", exitCode = 1))
        assertEquals(TermuxResult("", "", 1), failing.await())

        val missingStreams = async(start = CoroutineStart.UNDISPATCHED) { bridge.executeTermux("true") }
        termuxReplies(termux.started.last(), bundle(stdout = null, stderr = null, exitCode = 0))
        assertEquals("absent stdout/stderr = empty", TermuxResult("", "", 0), missingStreams.await())

        val stderr = async(start = CoroutineStart.UNDISPATCHED) { bridge.executeTermux("ls /nope") }
        termuxReplies(termux.started.last(), bundle(stderr = "ls: /nope: No such file or directory\n", exitCode = 2))
        assertEquals(2, stderr.await().exitCode)
    }

    @Test fun concurrentCallsGetTheirOwnResults() = runBlocking {
        val termux = FakeTermux()
        val bridge = TermuxBridge(ctx, termux)
        val a = async(start = CoroutineStart.UNDISPATCHED) { bridge.executeTermux("echo a") }
        val b = async(start = CoroutineStart.UNDISPATCHED) { bridge.executeTermux("echo b") }
        val (ia, ib) = termux.started
        @Suppress("DEPRECATION")
        assertNotEquals("distinct PendingIntents",
            ia.getParcelableExtra<PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT"),
            ib.getParcelableExtra<PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT"))
        // Finish in the opposite order.
        termuxReplies(ib, bundle(stdout = "b\n"))
        termuxReplies(ia, bundle(stdout = "a\n"))
        assertEquals("a\n", a.await().stdout)
        assertEquals("b\n", b.await().stdout)
    }

    @Test fun timeoutEndsTheWaitWithAClearError() = runBlocking {
        val termux = FakeTermux()
        try {
            TermuxBridge(ctx, termux).executeTermux("sleep 100", timeoutMs = 100)
            fail("expected timeout")
        } catch (e: TermuxException.Timeout) {
            assertEquals("termux_timeout", e.code)
            assertTrue(e.message!!, e.message!!.contains("100 ms"))
        }
        // A late result for the abandoned request is dropped, not delivered anywhere.
        termuxReplies(termux.started.single(), bundle(stdout = "late"))
    }

    @Test fun eachSetupProblemHasItsOwnError() = runBlocking {
        // supervisorScope: a failed call must surface through await(), not cancel the test.
        suspend fun codeOf(termux: FakeTermux, reply: Bundle? = null): String = supervisorScope {
            try {
                val call = async(start = CoroutineStart.UNDISPATCHED) { TermuxBridge(ctx, termux).executeTermux("id", timeoutMs = 2_000) }
                if (reply != null && termux.started.isNotEmpty()) termuxReplies(termux.started.last(), reply)
                yield()
                call.await(); "ok"
            } catch (e: TermuxException) { e.code }
        }

        assertEquals("termux_not_installed", codeOf(FakeTermux(installed = false)))
        assertEquals("termux_permission_denied", codeOf(FakeTermux(granted = false)))
        assertEquals("termux_permission_denied", codeOf(FakeTermux(startError = SecurityException("Permission Denial"))))
        assertEquals("termux_start_failed", codeOf(FakeTermux(startError = IllegalStateException("not allowed to start service"))))
        // What Termux really sends when ~/.termux/termux.properties lacks allow-external-apps=true.
        assertEquals("termux_external_apps_disabled", codeOf(FakeTermux(), bundle(stdout = null, stderr = null, exitCode = null, err = 1,
            errmsg = "RunCommandService requires `allow-external-apps` property to be set to `true` in `~/.termux/termux.properties` file.")))
        assertEquals("termux_execution_failed", codeOf(FakeTermux(), bundle(stdout = null, stderr = null, exitCode = null, err = 1, errmsg = "Mandatory extra missing")))
        assertEquals("no exit code at all", "termux_execution_failed", codeOf(FakeTermux(), bundle(exitCode = null)))
    }

    @Test fun truncationByTermuxIsReported() {
        val r = TermuxBridge.parseResult(bundle(stdout = "x".repeat(10)).apply { putString("stdout_original_length", "200000") })
        assertTrue(r.stdoutTruncated)
        assertFalse(r.stderrTruncated)
    }
}
