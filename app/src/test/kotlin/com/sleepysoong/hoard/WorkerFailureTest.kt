package com.sleepysoong.hoard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.engine.MockAiEngine
import com.sleepysoong.hoard.testing.ChatHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * How a background reply can fail, and what the user must see each time:
 *  - transient error mid-stream → retried, still exactly one bubble, completed
 *  - permanent error            → gives up after bounded attempts, one bubble, not spinning
 *  - cancellation               → not retried, bubble stops streaming
 *  - bubble removed during backoff (delete / regenerate) → retry must not resurrect it
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WorkerFailureTest {
    @get:Rule val name = TestName()
    private lateinit var h: ChatHarness

    @After fun dump() {
        MockAiEngine.testHook = null
        if (::h.isInitialized) h.writeTranscript("WorkerFailureTest.${name.methodName}")
    }

    private fun replies() = h.messages().filter { it.role == MessageRole.Assistant && !it.id.startsWith("msg-welcome") }

    @Test
    fun transientFailureMidStreamRetriesIntoTheSameSingleBubble() {
        h = ChatHarness()
        var failures = 0
        MockAiEngine.testHook = { _, i -> if (i == 4 && failures++ == 0) throw IOException("network down") }

        h.vm.send("끊겼다가 다시 이어지는 답변", emptyList(), "hoard-1-pro")
        h.awaitNoRunningWork()
        h.snapshot("after first failed attempt")
        assertEquals(1, replies().size)
        assertFalse("failed attempt must not keep spinning", replies().single().isStreaming)

        h.fireBackoff()
        h.awaitReplies()
        h.snapshot("after retry")

        val r = replies()
        assertEquals("retry reuses the bubble instead of appending a duplicate: ${r.map { it.id }}", 1, r.size)
        assertEquals(r.map { it.id }.distinct().size, r.size)
        assertTrue(r.single().text.contains("끊겼다가"))
        assertFalse(r.single().isStreaming)
        assertEquals(WorkInfo.State.SUCCEEDED, h.allWork().single().state)
    }

    @Test
    fun permanentFailureGivesUpAfterBoundedAttempts() {
        h = ChatHarness()
        var attempts = 0
        MockAiEngine.testHook = { _, i -> if (i == 0) { attempts++; throw IOException("always down") } }

        h.vm.send("항상 실패하는 답변", emptyList(), "hoard-1-pro")
        repeat(10) { h.fireBackoff() }
        h.awaitReplies()
        h.snapshot("gave up")

        assertTrue("bounded attempts, was $attempts", attempts in 2..5)
        assertEquals(WorkInfo.State.FAILED, h.allWork().single().state)
        val r = replies().single()
        assertFalse(r.isStreaming)
        assertTrue("user sees the reply was interrupted: ${r.text}", r.text.contains("중단"))
    }

    @Test
    fun cancelledReplyIsNotRetriedAndStopsStreaming() {
        h = ChatHarness(pace = 0.5f)
        h.vm.send("취소될 긴 답변을 부탁해", emptyList(), "hoard-1-pro")
        val deadline = System.currentTimeMillis() + 5_000
        while (replies().firstOrNull()?.thinking.isNullOrEmpty()) {
            h.idle(); Thread.sleep(5)
            check(System.currentTimeMillis() < deadline) { "never started streaming" }
        }
        h.workManager.cancelAllWork().result.get()
        h.awaitReplies()
        h.snapshot("after cancel")

        assertEquals(WorkInfo.State.CANCELLED, h.allWork().single().state)
        val r = replies().single()
        assertFalse("cancelled reply must not keep spinning", r.isStreaming)
        h.fireBackoff()
        h.idle()
        assertEquals("no retry after cancel", 1, replies().size)
    }

    @Test
    fun bubbleDeletedDuringBackoffIsNotResurrected() {
        h = ChatHarness()
        var failures = 0
        MockAiEngine.testHook = { _, i -> if (i == 1 && failures++ == 0) throw IOException("blip") }

        h.vm.send("삭제될 답변", emptyList(), "hoard-1-pro")
        h.awaitNoRunningWork()
        val failed = replies().single()
        h.vm.deleteMessage(failed.id)
        h.snapshot("deleted during backoff")

        h.fireBackoff()
        h.awaitReplies()
        h.snapshot("after backoff fired")

        assertTrue("deleted reply stays deleted: ${replies().map { it.text }}", replies().isEmpty())
    }
}
