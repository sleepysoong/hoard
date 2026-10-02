package com.sleepysoong.hoard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import com.sleepysoong.hoard.diagnostics.AppLog
import com.sleepysoong.hoard.engine.AiEngine
import com.sleepysoong.hoard.engine.Engines
import com.sleepysoong.hoard.engine.ReplyRequest
import com.sleepysoong.hoard.engine.StreamEvent
import com.sleepysoong.hoard.testing.ChatHarness
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.regex.PatternSyntaxException

/** Worker boundary gaps: class initialization Error (not Exception), subsequent
 * NoClassDefFoundError, causal diagnostics, and a finished job with a spinning bubble.
 * Existing network/cancellation tests already cover retries and stop semantics.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WorkerFailureFlowTest {
    private lateinit var h: ChatHarness

    @After fun finish() {
        Engines.override = null
        if (::h.isInitialized) {
            h.snapshot("final")
            h.note(AppLog.tail.value.joinToString("\n"))
            h.writeTranscript("WorkerFailureFlowTest.classInitializationFailure")
        }
    }

    @Test fun initializationAndSubsequentLinkageErrorsNeverLeaveAnOrphanSpinner() {
        h = ChatHarness()
        AppLog.init(h.app)
        AppLog.clear()
        val initial = ExceptionInInitializerError(PatternSyntaxException("ICU syntax failure", "bad-pattern", 8))
        val subsequent = NoClassDefFoundError("Could not initialize class SkillRuntime").apply { initCause(initial) }
        var calls = 0
        val failures = ArrayDeque<Throwable>().apply { add(initial); add(subsequent) }
        Engines.override = object : AiEngine {
            override suspend fun streamReply(request: ReplyRequest, onEvent: suspend (StreamEvent) -> Unit) {
                calls++
                throw failures.removeFirst()
            }
        }

        repeat(2) { attempt ->
            val previousWork = h.allWork().mapTo(hashSetOf()) { it.id }
            h.vm.send("클래스 초기화 실패 $attempt", emptyList(), "coding")
            val deadline = System.currentTimeMillis() + 5_000
            var work: WorkInfo? = null
            while (work?.state?.isFinished != true) {
                h.idle(); Thread.sleep(5)
                // APPEND_OR_REPLACE may remove the previous FAILED chain entirely.
                work = h.allWork().firstOrNull { it.id !in previousWork }
                check(System.currentTimeMillis() < deadline) { "worker did not finish" }
            }
            h.idle()
            h.snapshot("failed attempt $attempt")
            val reply = h.repo.messagesOf(h.vm.uiState.value.session!!.id).last()
            assertFalse("failed job must not leave a spinner", reply.isStreaming)
            assertTrue("visible error explains initialization failure", reply.errorText.orEmpty().contains("ICU syntax failure"))
            assertEquals("class initialization is not a transient network failure", WorkInfo.State.FAILED, work!!.state)
        }
        val log = AppLog.tail.value.joinToString("\n")
        assertTrue(log.contains("ExceptionInInitializerError"))
        assertTrue(log.contains("NoClassDefFoundError"))
        assertTrue("causal stack must survive in-app log copying", log.contains("PatternSyntaxException") && log.contains("ICU syntax failure"))
        assertEquals("no automatic replay of side effects", 2, calls)
    }
}
