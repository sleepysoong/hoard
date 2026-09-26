package com.sleepysoong.hoard

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.testing.ChatHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * One session = one reply at a time. Ways concurrent replies go wrong:
 *  - two bubbles stream at once and interleave with the user's turns
 *  - a reply queued behind another answers a prompt the user already removed
 *    (regenerate / edit / session delete while work is queued)
 *  - serializing one session must not block a different session
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ReplyOrderingTest {
    @get:Rule val name = TestName()
    private lateinit var h: ChatHarness

    @After fun dump() { if (::h.isInitialized) h.writeTranscript("ReplyOrderingTest.${name.methodName}") }

    private fun streamingIn(sessionId: String) = h.repo.messagesOf(sessionId).count { it.isStreaming }

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!cond()) {
            h.idle(); Thread.sleep(5)
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
        }
    }

    @Test
    fun rapidSendsInOneSessionStreamOneAtATimeInOrder() {
        h = ChatHarness(pace = 0.2f)
        val sid = h.vm.uiState.value.session!!.id
        val prompts = listOf("연속 질문 하나", "연속 질문 둘", "연속 질문 셋")
        prompts.forEach { h.vm.send(it, emptyList(), "hoard-1-pro"); h.idle() }
        h.snapshot("all sent", sid)

        var maxConcurrent = 0
        h.awaitReplies(onTick = { maxConcurrent = maxOf(maxConcurrent, streamingIn(sid)) })
        h.snapshot("settled", sid)

        assertEquals("replies must not stream concurrently", 1, maxConcurrent)
        val turns = h.repo.messagesOf(sid).drop(1) // skip welcome
        val users = turns.filter { it.role == MessageRole.User }.map { it.text }
        val replies = turns.filter { it.role == MessageRole.Assistant }
        assertEquals(prompts, users)
        assertEquals(3, replies.size)
        prompts.zip(replies).forEach { (p, r) -> assertTrue("'${r.text}' answers '$p'", r.text.contains(p)) }
    }

    @Test
    fun regenerateWhileNextReplyIsQueuedDoesNotAnswerRemovedPrompt() {
        h = ChatHarness(pace = 0.5f)
        val sid = h.vm.uiState.value.session!!.id
        h.vm.send("첫 질문 A", emptyList(), "hoard-1-pro")
        h.vm.send("곧 지워질 둘째 질문 B", emptyList(), "hoard-1-pro")
        waitUntil("first reply streaming") { h.repo.messagesOf(sid).any { it.role == MessageRole.Assistant && it.isStreaming } }
        val firstReply = h.repo.messagesOf(sid).first { it.role == MessageRole.Assistant && it.isStreaming }
        h.snapshot("A streaming, B queued", sid)

        h.vm.retryFrom(firstReply.id)
        h.snapshot("right after retryFrom", sid)
        h.awaitReplies()
        h.snapshot("after regenerate", sid)

        val turns = h.repo.messagesOf(sid).drop(1)
        assertEquals(listOf(MessageRole.User, MessageRole.Assistant), turns.map { it.role })
        assertTrue(turns[1].text.contains("첫 질문 A"))
        assertTrue("no reply to removed prompt", h.repo.messagesOf(sid).none { it.text.contains("둘째 질문 B") && it.role == MessageRole.Assistant })
    }

    @Test
    fun editWhileReplyStreamingReplacesItWithSingleReply() {
        h = ChatHarness(pace = 0.5f)
        val sid = h.vm.uiState.value.session!!.id
        h.vm.send("원래 질문", emptyList(), "hoard-1-pro")
        waitUntil("reply streaming") { streamingIn(sid) == 1 }
        val user = h.repo.messagesOf(sid).first { it.role == MessageRole.User }

        h.vm.editUserMessage(user.id, "고친 질문")
        var maxConcurrent = 0
        h.awaitReplies(onTick = { maxConcurrent = maxOf(maxConcurrent, streamingIn(sid)) })
        h.snapshot("after edit mid-stream", sid)

        val turns = h.repo.messagesOf(sid).drop(1)
        assertEquals(2, turns.size)
        assertEquals("고친 질문", turns[0].text)
        assertTrue(turns[1].text.contains("고친 질문"))
        assertTrue(maxConcurrent <= 1)
    }

    @Test
    fun otherSessionsAreNotBlockedByABusySession() {
        h = ChatHarness(pace = 0.5f)
        val busy = h.vm.uiState.value.session!!.id
        h.vm.send("바쁜 세션의 긴 답변", emptyList(), "hoard-1-pro")
        waitUntil("busy session streaming") { streamingIn(busy) == 1 }

        val other = h.vm.newSession("다른 세션")
        h.idle()
        h.vm.send("다른 세션 질문", emptyList(), "hoard-1-pro")
        waitUntil("other session streams while busy one still streams") {
            streamingIn(other) == 1 && streamingIn(busy) == 1
        }
        h.awaitReplies()
        h.snapshot("busy", busy)
        h.snapshot("other", other)
        assertEquals(2, h.repo.messagesOf(other).size)
    }
}
