package com.sleepysoong.hoard

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.CompactionInfo
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.data.TRIGGER_COMPACT
import com.sleepysoong.hoard.data.isCompaction
import com.sleepysoong.hoard.data.isCompactionSummary
import com.sleepysoong.hoard.engine.ReplyRequest
import com.sleepysoong.hoard.testing.ChatHarness
import com.sleepysoong.hoard.testing.FakeRouter
import com.sleepysoong.hoard.testing.FakeRouter.Companion.completed
import com.sleepysoong.hoard.testing.FakeRouter.Companion.created
import com.sleepysoong.hoard.testing.FakeRouter.Companion.routingFrame
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Worker → summary stream → persisted checkpoint → next actual router request.
 * Failure paths: incomplete/malformed summaries, invalidated history, lost checkpoint
 * when skills consume the context budget, and automatic threshold compaction.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CompactionFlowTest {
    @get:Rule val name = TestName()
    private lateinit var h: ChatHarness
    private lateinit var router: FakeRouter

    private val summary = """
        ## Overview
        배포 작업 진행 중.
        ## User Instructions
        기존 인증을 유지한다.
        ## Open Items
        원격 연결 확인.
        ## Done
        설정 저장 완료.
        ## Key Facts
        서비스 포트는 9222.
    """.trimIndent()

    private fun start() {
        h = ChatHarness()
        router = FakeRouter()
        runBlocking { SettingsStore.setRouterUrl(h.app, router.url) }
        val deadline = System.currentTimeMillis() + 5_000
        while (h.vm.settings.value.routerUrl != router.url) {
            h.idle(); Thread.sleep(5)
            check(System.currentTimeMillis() < deadline)
        }
        h.repo.updateSession(sid()) { it.copy(systemPrompt = "사용자 요청을 따르세요", contextLimit = 8_000) }
        h.repo.appendMessage(sid(), ChatMessage("old-user", MessageRole.User, "OLD_PRIVATE_DETAIL " + "배포 작업 기록 ".repeat(500)))
        h.repo.appendMessage(sid(), ChatMessage("old-reply", MessageRole.Assistant, "설정 확인 결과 ".repeat(500)))
        h.repo.appendMessage(sid(), ChatMessage("recent-user", MessageRole.User, "최근 질문은 원격 연결 확인"))
        h.repo.appendMessage(sid(), ChatMessage("recent-reply", MessageRole.Assistant, "최근 답변은 연결 대기"))
        h.idle()
    }

    private fun sid() = h.vm.uiState.value.session!!.id
    private fun response(text: String) = FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed(text)))
    private fun bodies() = router.requests.filter { it.path.endsWith("/responses") }.map { Json.parseToJsonElement(it.body).jsonObject }

    @After fun finish() {
        if (::h.isInitialized) {
            h.snapshot("final")
            if (::router.isInitialized) router.requests.filter { it.path.endsWith("/responses") }.forEach {
                h.note("request: ${it.body}")
            }
            h.writeTranscript("CompactionFlowTest.${name.methodName}")
        }
        if (::router.isInitialized) router.close()
    }

    @Test fun manualSummaryReplacesOnlyOldContextAndSurvivesNextReply() {
        start()
        router.enqueue(response(summary), response("새 질문의 답변"))
        h.vm.send("/compact 인증 정보 보존", emptyList(), "coding")
        h.awaitReplies()
        val checkpoint = h.repo.messagesOf(sid()).single { it.isCompaction }
        assertTrue(checkpoint.errorText.orEmpty(), checkpoint.isCompactionSummary)
        assertEquals("인증 정보 보존", checkpoint.compaction!!.focus)
        assertTrue("full history remains available in app", h.repo.messagesOf(sid()).any { it.id == "old-user" })
        assertFalse("summarizer must have no tools", bodies().first().containsKey("tools"))

        h.vm.send("새 질문", emptyList(), "coding")
        h.awaitReplies()
        val next = bodies().last()["input"].toString()
        assertTrue(next, next.contains("서비스 포트는 9222"))
        assertTrue(next, next.contains("최근 질문"))
        assertTrue(next, next.contains("새 질문"))
        assertFalse(next, next.contains("OLD_PRIVATE_DETAIL"))
        assertNull(h.repo.messagesOf(sid()).last().errorText)
    }

    @Test fun invalidSummaryNeverHidesTheOriginalConversation() {
        start()
        router.enqueue(response("요약 형식이 없는 짧은 문장"), response("원본을 이용한 답변"))
        h.vm.send("/compact", emptyList(), "coding")
        h.awaitReplies()
        val failed = h.repo.messagesOf(sid()).single { it.isCompaction }
        assertFalse(failed.isStreaming)
        assertFalse(failed.isCompactionSummary)
        assertNotNull(failed.errorText)
        h.vm.send("계속", emptyList(), "coding")
        h.awaitReplies()
        val next = bodies().last()["input"].toString()
        assertTrue(next, next.contains("OLD_PRIVATE_DETAIL"))
        assertFalse(next, next.contains("요약 형식이 없는 짧은 문장"))
    }

    @Test fun automaticCompactionFinishesBeforeAnsweringTheNewPrompt() {
        start()
        h.repo.updateSession(sid()) { it.copy(contextLimit = 4_000) }
        router.enqueue(response(summary), response("자동 압축 이후 답변"))
        h.vm.send("지금 연결해 주세요", emptyList(), "coding")
        h.awaitReplies()
        val checkpoint = h.repo.messagesOf(sid()).single { it.isCompaction }
        assertTrue(checkpoint.errorText.orEmpty(), checkpoint.isCompactionSummary)
        assertTrue(checkpoint.compaction!!.auto)
        assertEquals(2, bodies().size)
        assertTrue(bodies().last()["input"].toString().contains("지금 연결해 주세요"))
        assertEquals("자동 압축 이후 답변", h.repo.messagesOf(sid()).last().text)
    }

    @Test fun incompleteSummaryIsNotAcceptedEvenWithAllRequiredHeadings() {
        start()
        router.enqueue(FakeRouter.Reply.Sse(listOf(routingFrame(), created(),
            completed(summary, type = "response.incomplete", status = "incomplete",
                extra = ""","incomplete_details":{"reason":"max_output_tokens"}"""))),
            response("원본으로 계속"))
        h.vm.send("/compact", emptyList(), "coding")
        h.awaitReplies()
        val failed = h.repo.messagesOf(sid()).single { it.isCompaction }
        assertFalse(failed.isStreaming)
        assertFalse(failed.isCompactionSummary)
        assertTrue(failed.errorText.orEmpty(), failed.errorText.orEmpty().contains("완료되지"))
        h.vm.send("계속", emptyList(), "coding")
        h.awaitReplies()
        assertTrue(bodies().last()["input"].toString().contains("OLD_PRIVATE_DETAIL"))
    }

    @Test fun deletingAnEarlierMessageInvalidatesItsCheckpoint() {
        start()
        router.enqueue(response(summary), response("삭제 이후 답변"))
        h.vm.send("/compact", emptyList(), "coding")
        h.awaitReplies()
        h.repo.deleteMessage(sid(), "old-user")
        assertFalse(h.repo.messagesOf(sid()).single { it.isCompaction }.isCompactionSummary)
        h.vm.send("계속", emptyList(), "coding")
        h.awaitReplies()
        val next = bodies().last()["input"].toString()
        assertFalse(next, next.contains("서비스 포트는 9222"))
        assertTrue(next, next.contains("설정 확인 결과"))
    }

    @Test fun skillContextKeepsCheckpointAndAccurateDroppedCount() {
        start()
        val checkpoint = ChatMessage("checkpoint", MessageRole.User, summary,
            trigger = TRIGGER_COMPACT, compaction = CompactionInfo(auto = false))
        val question = ChatMessage("question", MessageRole.User, "현재 질문")
        val request = ReplyRequest.build(h.repo.sessionOf(sid())!!, listOf(checkpoint, question))
        val combined = request.withSkillContext("보조 지침")
        assertEquals(listOf("checkpoint", "question"), combined.history.map { it.id })
        assertEquals("nothing was dropped", 0, combined.droppedCount)
        val tighter = request.copy(contextLimit = 800, history = listOf(checkpoint,
            ChatMessage("large", MessageRole.Assistant, "오래된 답변 ".repeat(1_000)), question))
            .withSkillContext("보조 지침")
        assertEquals(listOf("checkpoint", "question"), tighter.history.map { it.id })
        assertEquals(1, tighter.droppedCount)
    }
}
