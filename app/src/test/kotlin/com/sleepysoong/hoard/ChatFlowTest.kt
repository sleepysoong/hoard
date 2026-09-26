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
}
