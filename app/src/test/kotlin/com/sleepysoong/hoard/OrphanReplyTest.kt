package com.sleepysoong.hoard

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.engine.MockAiEngine
import com.sleepysoong.hoard.testing.ChatHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * A background reply can outlive its target:
 *  - the process dies while the reply is queued/backing off (in-memory store wiped)
 *  - the user deletes the session mid-stream
 *  - the user deletes the streaming bubble itself
 * In every case the store must not grow an orphan message list, the work must
 * finish (not retry forever), and no "reply ready" notification may be posted.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OrphanReplyTest {
    @get:Rule val name = TestName()
    private lateinit var h: ChatHarness

    @After fun dump() {
        MockAiEngine.faultInjector = null
        if (::h.isInitialized) h.writeTranscript("OrphanReplyTest.${name.methodName}")
    }

    private fun assertNoOrphans() {
        val sessionIds = h.repo.sessions.value.map { it.id }.toSet()
        assertEquals("message lists only for existing sessions", sessionIds, h.repo.messages.value.keys)
    }

    private fun doneNotifications() =
        shadowOf(h.app.getSystemService(NotificationManager::class.java)).allNotifications
            .filter { it.extras.getString("android.text")?.contains("준비") == true }

    private fun waitUntilStreaming(sessionId: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (h.repo.messagesOf(sessionId).none { it.isStreaming && it.thinking.isNotEmpty() }) {
            h.idle(); Thread.sleep(5)
            check(System.currentTimeMillis() < deadline) { "never started streaming" }
        }
    }

    @Test
    fun replyQueuedAcrossProcessDeathDoesNotCreateGhostSession() {
        h = ChatHarness()
        var failures = 0
        MockAiEngine.faultInjector = { _, i -> if (i == 0 && failures++ == 0) throw IOException("blip") }
        val session = h.vm.newSession("곧 사라질 세션")
        h.vm.send("프로세스가 죽기 전에 보낸 질문", emptyList(), "hoard-1-pro")
        h.awaitNoRunningWork()
        h.snapshot("before death", session)

        h.simulateProcessDeath()
        h.fireBackoff()
        h.awaitReplies()
        h.snapshot("after restart", session)

        assertTrue(h.repo.sessionOf(session) == null)
        assertTrue("no messages for the lost session", h.repo.messagesOf(session).isEmpty())
        assertNoOrphans()
        assertTrue(doneNotifications().isEmpty())
        assertTrue("work finished instead of retrying", h.unfinishedWork().isEmpty())
    }

    @Test
    fun jobRestoredAfterRestartForUnknownSessionIsDropped() {
        h = ChatHarness()
        // WorkManager persists the queue; after a restart the job's session is gone.
        com.sleepysoong.hoard.work.ChatResponseWorker.enqueue(
            h.app, "session-lost-in-restart", "재시작 전 질문", "hoard-1-pro", "msg-lost", false
        )
        h.awaitReplies()
        h.snapshot("after restored job", "session-lost-in-restart")

        assertTrue(h.repo.messagesOf("session-lost-in-restart").isEmpty())
        assertNoOrphans()
        assertTrue(doneNotifications().isEmpty())
        assertTrue(h.unfinishedWork().isEmpty())
    }

    @Test
    fun deletingSessionMidStreamStopsReplyWithoutOrphans() {
        h = ChatHarness(pace = 0.5f)
        val keep = h.vm.uiState.value.session!!.id
        val doomed = h.vm.newSession("지울 세션")
        h.vm.send("지워질 세션에서 긴 답변 부탁", emptyList(), "hoard-1-pro")
        waitUntilStreaming(doomed)
        h.snapshot("streaming", doomed)

        h.vm.deleteSession(doomed)
        h.awaitReplies()
        h.snapshot("after delete (remaining)", keep)

        assertNoOrphans()
        assertTrue(doneNotifications().isEmpty())
        assertEquals(1, h.repo.messagesOf(keep).size) // welcome only, untouched
    }

    @Test
    fun deletingStreamingBubbleStopsReplyAndKeepsItDeleted() {
        h = ChatHarness(pace = 0.5f)
        val sid = h.vm.uiState.value.session!!.id
        h.vm.send("스트리밍 중에 지울 답변", emptyList(), "hoard-1-pro")
        waitUntilStreaming(sid)
        val bubble = h.repo.messagesOf(sid).last()
        h.vm.deleteMessage(bubble.id)
        h.awaitReplies()
        h.snapshot("after deleting streaming bubble", sid)

        val msgs = h.repo.messagesOf(sid)
        assertTrue(msgs.none { it.id == bubble.id })
        assertEquals(MessageRole.User, msgs.last().role)
        assertTrue(doneNotifications().isEmpty())
    }
}
