package com.sleepysoong.hoard

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.UiAttachment
import com.sleepysoong.hoard.engine.MockAiEngine
import com.sleepysoong.hoard.engine.ReplyRequest
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
import java.util.Collections

/**
 * What the engine seam receives is what a real backend would send. Ways it goes wrong:
 *  - system prompt edits / model changes are ignored
 *  - history is missing, out of order, or includes turns after the reply being regenerated
 *  - the session context limit is not applied (or drops the question itself)
 *  - attachments on the answered message are lost on regenerate
 *  - disabled plugins / MCP servers / skills are still offered as tools
 *  - the top-bar context usage ignores user messages or double-counts history
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class EngineRequestTest {
    @get:Rule val name = TestName()
    private lateinit var h: ChatHarness
    private val requests: MutableList<ReplyRequest> = Collections.synchronizedList(mutableListOf())

    @After fun dump() {
        MockAiEngine.testHook = null
        if (::h.isInitialized) {
            requests.forEachIndexed { i, r ->
                h.note("request #$i model=${r.modelId} limit=${r.contextLimit} dropped=${r.droppedCount} " +
                    "promptTokens=${r.promptTokens} tools=${r.tools} attach=${r.hasAttachments}\n" +
                    "    system: ${r.systemPrompt}\n" +
                    r.history.joinToString("\n") { "    ${it.role}: ${it.text.take(60)}" })
            }
            h.writeTranscript("EngineRequestTest.${name.methodName}")
        }
    }

    private fun start(pace: Float = 0f) {
        h = ChatHarness(pace)
        MockAiEngine.testHook = { req, i -> if (i == 0) requests += req }
    }

    private fun turn(text: String, attachments: List<UiAttachment> = emptyList(), model: String = "hoard-1-pro") {
        h.vm.send(text, attachments, model)
        h.awaitReplies()
    }

    @Test
    fun requestCarriesEditedSystemPromptModelAndFullOrderedHistory() {
        start()
        h.vm.setSystemPrompt("너는 해적처럼 말한다.")
        h.vm.setModel("hoard-1-ultra")
        turn("첫 질문", model = "hoard-1-ultra")
        turn("둘째 질문", model = "hoard-1-ultra")
        h.snapshot("two turns")

        val r = requests.last()
        assertEquals("너는 해적처럼 말한다.", r.systemPrompt)
        assertEquals("hoard-1-ultra", r.modelId)
        assertEquals("둘째 질문", r.question)
        assertEquals(
            listOf(MessageRole.Assistant, MessageRole.User, MessageRole.Assistant, MessageRole.User),
            r.history.map { it.role }
        )
        assertEquals("첫 질문", r.history[1].text)
        assertTrue("prior reply is context", r.history[2].text.contains("첫 질문"))
        assertEquals(0, r.droppedCount)
        assertTrue("streaming placeholder never leaks into history", r.history.none { it.isStreaming || it.text.isBlank() })
    }

    @Test
    fun regenerateMiddleReplySendsOnlyTurnsBeforeIt() {
        start()
        turn("사과"); turn("바나나"); turn("체리")
        val a2 = h.messages()[4]
        requests.clear()

        h.vm.retryFrom(a2.id)
        h.awaitReplies()
        h.snapshot("after regenerate A2")

        val r = requests.single()
        assertEquals("바나나", r.question)
        assertEquals(4, r.history.size) // welcome, 사과, A1, 바나나
        assertFalse(r.history.any { it.text.contains("체리") })
    }

    @Test
    fun smallContextLimitDropsOldestTurnsButKeepsSystemPromptAndQuestion() {
        start()
        val long = "긴 문장 ".repeat(60) // ≈ 90 tokens each
        turn("$long 하나"); turn("$long 둘"); turn("$long 셋")
        h.vm.setContextLimit(300)
        requests.clear()
        turn("짧은 마지막 질문")
        h.snapshot("after small-limit turn")

        val r = requests.single()
        assertEquals("짧은 마지막 질문", r.question)
        assertTrue("oldest turns dropped", r.droppedCount > 0)
        assertTrue("fits the limit: ${r.promptTokens}", r.promptTokens <= 300)
        assertEquals("newest turn kept, oldest dropped", r.history.last().text, "짧은 마지막 질문")
        assertFalse(r.history.any { it.text.endsWith("하나") })
        assertTrue(r.systemPrompt.isNotBlank())
        val reply = h.messages().last()
        assertTrue("user can see trimming in thinking", reply.thinking.any { it.detail.contains("${r.droppedCount}") })
    }

    @Test
    fun questionLargerThanLimitIsStillSent() {
        start()
        h.vm.setContextLimit(10)
        turn("아주 ".repeat(100) + "긴 질문")
        val r = requests.single()
        assertTrue(r.question.endsWith("긴 질문"))
        assertEquals(1, r.history.size)
    }

    @Test
    fun attachmentsSurviveRegenerate() {
        start()
        val photo = UiAttachment("att-1", "receipt.jpg", "image/jpeg", 12_345)
        turn("이 영수증 정리해줘", listOf(photo))
        val reply = h.messages().last()
        assertTrue(requests.single().hasAttachments)
        assertEquals("receipt.jpg", requests.single().history.last().attachments.single().name)

        requests.clear()
        h.vm.retryFrom(reply.id)
        h.awaitReplies()
        h.snapshot("after regenerate with attachment")
        assertTrue("regenerate still sees the photo", requests.single().hasAttachments)
        assertTrue(h.messages().last().text.contains("첨부"))
    }

    @Test
    fun onlyEnabledToolsAreOffered() {
        start()
        val plugins = h.repo.plugins.value
        val enabled = plugins.first { it.enabled }
        h.repo.setPluginEnabled(enabled.id, false)
        val mcpOff = h.repo.mcpServers.value.first { !it.enabled }
        h.repo.setMcpEnabled(mcpOff.id, true)
        turn("도구 목록 확인")

        val tools = requests.single().tools
        assertFalse(enabled.name in tools)
        assertTrue(mcpOff.name in tools)
        h.repo.skills.value.filter { it.enabled }.forEach { assertTrue(it.name in tools) }
        h.repo.skills.value.filterNot { it.enabled }.forEach { assertFalse(it.name in tools) }
    }

    @Test
    fun contextUsageCountsUserMessagesAndMatchesWhatTheNextRequestSends() {
        start(pace = 0.5f)
        val before = h.vm.uiState.value.usedTokens
        val question = "사용자 메시지도 컨텍스트를 차지한다 ".repeat(10)
        h.vm.send(question, emptyList(), "hoard-1-pro")
        h.idle()
        val afterSend = h.vm.uiState.value.usedTokens
        assertTrue("user message counted before reply: $before → $afterSend", afterSend > before)
        h.awaitReplies()

        turn("다음 질문")
        h.snapshot("usage check")
        val r = requests.last()
        val prev = h.messages()[h.messages().lastIndex - 2]
        assertEquals(MessageRole.Assistant, prev.role)
        // Usage shown after the previous reply == what the next request carried, minus the new question.
        val usage = h.vm.uiState.value.usedTokens
        val last = h.messages().last()
        assertEquals("top bar = next request's prompt + this reply", r.promptTokens + last.completionTokens, usage)
    }
}
