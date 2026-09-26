package com.sleepysoong.hoard

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.testing.ChatHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * End-to-end chat flows through the real ViewModel/WorkManager/Worker/Repository stack.
 * Each test writes build/test-artifacts/ChatFlowTest.<test>.txt with every snapshot.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ChatFlowTest {
    @get:Rule val name = TestName()
    private lateinit var h: ChatHarness

    @After fun dump() { if (::h.isInitialized) h.writeTranscript("ChatFlowTest.${name.methodName}") }

    @Test
    fun replyFinishesInItsOwnSessionAfterUserSwitchesAwayAndLeavesApp() {
        h = ChatHarness(pace = 0.3f)
        val origin = h.vm.uiState.value.session!!.id
        h.vm.send("백그라운드에서도 끝까지 답해줘. 리퀴드 글래스 채팅 앱 설계 요약", emptyList(), "hoard-1-ultra")
        h.idle()
        h.snapshot("sent")

        // Mid-stream: user opens another session, then leaves the app entirely.
        val other = h.vm.newSession("다른 세션")
        h.idle()
        h.destroyViewModel()

        h.awaitReplies()
        h.snapshot("settled (origin)", origin)
        h.snapshot("settled (other)", other)

        val msgs = h.repo.messagesOf(origin)
        val reply = msgs.last()
        assertEquals(MessageRole.User, msgs[msgs.lastIndex - 1].role)
        assertEquals(MessageRole.Assistant, reply.role)
        assertFalse("reply must not be left streaming", reply.isStreaming)
        assertTrue("reply streamed real text", reply.text.contains("hoard-1-ultra"))
        assertTrue("thinking steps shown", reply.thinking.size >= 2)
        assertTrue("elapsed time recorded", reply.elapsedMs > 0)
        assertTrue("tokens recorded", reply.completionTokens > 0)
        assertTrue("the other session stays empty", h.repo.messagesOf(other).isEmpty())

        val nm = h.app.getSystemService(NotificationManager::class.java)
        assertTrue(
            "completion notification posted",
            shadowOf(nm).allNotifications.any { it.extras.getString("android.title")?.contains("환영") == true }
        )
    }

    /** Builds welcome, U1, A1, U2, A2, U3, A3 in the active session. */
    private fun threeTurnConversation(): List<String> {
        val prompts = listOf("첫 질문: 사과", "두번째 질문: 바나나", "세번째 질문: 체리")
        for (p in prompts) {
            h.vm.send(p, emptyList(), "hoard-1-pro")
            h.awaitReplies()
        }
        h.snapshot("three turns")
        return prompts
    }

    @Test
    fun retryMiddleReplyRegeneratesForItsOwnPromptInPlace() {
        h = ChatHarness()
        val (q1, q2, _) = threeTurnConversation()
        val a2 = h.messages()[4]
        assertTrue(a2.text.contains(q2))

        h.vm.retryFrom(a2.id)
        h.awaitReplies()
        h.snapshot("after retry of A2")

        val msgs = h.messages()
        assertEquals("tail after A2 is dropped, A2 regenerated in place", 5, msgs.size)
        assertEquals(listOf(q1, q2), msgs.filter { it.role == MessageRole.User }.map { it.text })
        val regenerated = msgs.last()
        assertEquals(MessageRole.Assistant, regenerated.role)
        assertTrue("regenerated for its own prompt, got: ${regenerated.text}", regenerated.text.contains(q2))
        assertTrue(regenerated.id != a2.id)
    }

    @Test
    fun retryFirstReplyUsesFirstPromptAndRetryLastUsesLast() {
        h = ChatHarness()
        val (q1, _, _) = threeTurnConversation()
        h.vm.retryFrom(h.messages()[2].id)
        h.awaitReplies()
        h.snapshot("after retry of A1")
        assertEquals(3, h.messages().size)
        assertTrue(h.messages().last().text.contains(q1))

        val q = "마지막 질문: 두리안"
        h.vm.send(q, emptyList(), "hoard-1-pro")
        h.awaitReplies()
        h.vm.retryFrom(h.messages().last().id)
        h.awaitReplies()
        h.snapshot("after retry of last reply")
        assertEquals(5, h.messages().size)
        assertTrue(h.messages().last().text.contains(q))
    }

    @Test
    fun editMiddleUserMessageDropsLaterTurnsAndAnswersEditedText() {
        h = ChatHarness()
        val (q1, _, _) = threeTurnConversation()
        val u2 = h.messages()[3]
        val edited = "수정된 두번째 질문: 망고"

        h.vm.editUserMessage(u2.id, edited)
        h.awaitReplies()
        h.snapshot("after editing U2")

        val msgs = h.messages()
        assertEquals("welcome, U1, A1, U2', A2'", 5, msgs.size)
        assertEquals(listOf(q1, edited), msgs.filter { it.role == MessageRole.User }.map { it.text })
        assertEquals("edited message keeps its identity", u2.id, msgs[3].id)
        assertEquals(MessageRole.Assistant, msgs[4].role)
        assertTrue("reply answers the edited text: ${msgs[4].text}", msgs[4].text.contains(edited))
        assertEquals("exactly one reply after the edit", 1, msgs.count { it.role == MessageRole.Assistant && it.text.contains(edited) })
    }

    @Test
    fun editLastUserMessageReplacesItsReply() {
        h = ChatHarness()
        threeTurnConversation()
        val u3 = h.messages()[5]
        h.vm.editUserMessage(u3.id, "체리 말고 자두")
        h.awaitReplies()
        h.snapshot("after editing U3")
        val msgs = h.messages()
        assertEquals(7, msgs.size)
        assertEquals("체리 말고 자두", msgs[5].text)
        assertTrue(msgs[6].text.contains("체리 말고 자두"))
    }

    @Test
    fun blankEditOrAssistantTargetChangesNothing() {
        h = ChatHarness()
        threeTurnConversation()
        val before = h.messages()
        h.vm.editUserMessage(before[3].id, "   ")
        h.vm.editUserMessage(before[4].id, "assistant 메시지는 수정 대상이 아님")
        h.awaitReplies()
        h.snapshot("after invalid edits")
        assertEquals(before, h.messages())
    }

    @Test
    fun retryReplyThatHasNoPromptBeforeItKeepsConversation() {
        h = ChatHarness()
        threeTurnConversation()
        val before = h.messages()
        h.vm.retryFrom(before[0].id) // the welcome message: nothing to answer
        h.awaitReplies()
        h.snapshot("after retry of welcome")
        assertEquals(before.map { it.id }, h.messages().map { it.id })
    }
}
