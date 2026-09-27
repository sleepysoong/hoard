package com.sleepysoong.hoard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.engine.RouterAiEngine
import com.sleepysoong.hoard.testing.ChatHarness
import com.sleepysoong.hoard.testing.FakeRouter
import com.sleepysoong.hoard.testing.FakeRouter.Companion.completed
import com.sleepysoong.hoard.testing.FakeRouter.Companion.created
import com.sleepysoong.hoard.testing.FakeRouter.Companion.delta
import com.sleepysoong.hoard.testing.FakeRouter.Companion.routingFrame
import com.sleepysoong.hoard.testing.FakeRouter.Companion.trace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Full chat stack against a router speaking sleepyrouter's /hoard/v1/responses wire
 * format: ViewModel → WorkManager → worker → RouterAiEngine → HTTP → repository.
 *
 * Ways the integration can go wrong, each covered below:
 *  - request: wrong path/body (model, instructions, history roles/order, stream flag)
 *  - success after failover: the trace (tried/failed/selected) or the real model lost
 *  - text: deltas not accumulated, final text/usage from response.completed ignored
 *  - router says "all candidates failed" (502 + trace): must retry, then show why
 *  - router rejects the request (400): must NOT retry, show the reason
 *  - stream broken after commit (error event + trace): selected model marked failed, retried
 *  - router unreachable: clear message, retried, no spinner left behind
 *  - truncated reply (response.incomplete): keep the text, say it was cut
 *  - no router configured: offline mock keeps working
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RouterIntegrationTest {
    @get:Rule val name = TestName()
    private lateinit var h: ChatHarness
    private lateinit var router: FakeRouter

    private fun start() {
        h = ChatHarness()
        router = FakeRouter()
        runBlocking { SettingsStore.setRouterUrl(h.app, router.url) }
    }

    @After fun tearDown() {
        if (::router.isInitialized) router.close()
        if (::h.isInitialized) {
            h.snapshot("final")
            router.requests.forEachIndexed { i, r -> h.note("router request #$i ${r.method} ${r.path} ${r.body.take(400)}") }
            h.writeTranscript("RouterIntegrationTest.${name.methodName}")
        }
    }

    private fun sendAndSettle(text: String, model: String = "coding") {
        h.vm.send(text, emptyList(), model)
        h.awaitReplies()
    }

    private fun reply() = h.messages().last().also { assertEquals(MessageRole.Assistant, it.role) }

    @Test
    fun successAfterFailoverKeepsTraceAndRealModel() {
        start()
        router.enqueue(FakeRouter.Reply.Sse(listOf(
            routingFrame(), created(), delta("안녕", 1), delta("하세요", 2), completed("안녕하세요")
        )))
        sendAndSettle("인사해줘")

        val r = reply()
        assertEquals("안녕하세요", r.text)
        assertFalse(r.isStreaming)
        assertNull(r.errorText)
        assertEquals("answered by the selected model, not the requested group", "openrouter/c", r.modelId)
        val route = assertNotNull(r.routing).let { r.routing!! }
        assertEquals("coding", route.requestedModel)
        assertEquals(listOf("zen/a", "gemini/d", "openrouter/c"), route.attempts.map { it.model })
        assertEquals("finished stream → selected marked succeeded", listOf("failed", "skipped", "succeeded"), route.attempts.map { it.outcome })
        assertEquals(429, route.attempts[0].statusCode)
        assertTrue(route.attempts[0].reason!!.contains("slow down"))
        assertTrue(route.attempts[1].reason!!.startsWith("missing_api_key"))
        assertEquals(2, route.failures.size)
        assertEquals("usage from response.completed", 11, r.promptTokens)
        assertEquals(22, r.completionTokens)
        assertEquals(WorkInfo.State.SUCCEEDED, h.allWork().single().state)
    }

    @Test
    fun requestIsOpenAIResponsesWithHistorySystemPromptAndStream() {
        start()
        h.vm.setSystemPrompt("너는 간결하다.")
        router.enqueue(
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("첫 답"))),
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("둘째 답")))
        )
        sendAndSettle("첫 질문")
        sendAndSettle("둘째 질문")

        val req = router.requests.last()
        assertEquals("POST", req.method)
        assertEquals("/hoard/v1/responses", req.path)
        val body = Json.parseToJsonElement(req.body).jsonObject
        assertEquals("the model picked for the send (a router group)", "coding", body["model"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("너는 간결하다.", body["instructions"]!!.jsonPrimitive.content)
        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("assistant", "user", "assistant", "user"), input.map { it["role"]!!.jsonPrimitive.content })
        val texts = input.map { it["content"]!!.jsonArray[0].jsonObject }
        assertEquals("output_text", texts[2]["type"]!!.jsonPrimitive.content)
        assertEquals("첫 답", texts[2]["text"]!!.jsonPrimitive.content)
        assertEquals("input_text", texts[3]["type"]!!.jsonPrimitive.content)
        assertEquals("둘째 질문", texts[3]["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun allCandidatesFailedRetriesThenExplainsWhy() {
        start()
        router.enqueue(FakeRouter.allFailed(), FakeRouter.allFailed(), FakeRouter.allFailed())
        h.vm.send("아무도 못 답하는 질문", emptyList(), "coding")
        repeat(5) { h.fireBackoff() }
        h.awaitReplies()

        val r = reply()
        assertEquals("bounded retries", 3, router.requests.count { it.path.endsWith("/responses") })
        assertFalse(r.isStreaming)
        assertTrue(r.errorText!!, r.errorText!!.contains("모든 모델이 실패"))
        assertTrue(r.errorText!!.contains("중단"))
        val route = r.routing!!
        assertNull("nothing answered", route.selectedModel)
        assertEquals(listOf("zen/a", "gemini/d"), route.failures.map { it.model })
        assertEquals(WorkInfo.State.FAILED, h.allWork().single().state)
    }

    @Test
    fun rejectedRequestIsNotRetried() {
        start()
        router.enqueue(FakeRouter.Reply.Json(400, """{"error":{"message":"input must not be empty","type":"invalid_request_error","param":null,"code":"invalid_request"},"sleepyrouter":{"routing":${trace(selected = null)}}}"""))
        sendAndSettle("잘못된 요청")
        val r = reply()
        assertEquals(1, router.requests.size)
        assertEquals("input must not be empty", r.errorText)
        assertNotNull(r.routing)
        assertEquals(WorkInfo.State.FAILED, h.allWork().single().state)
    }

    @Test
    fun streamBrokenAfterCommitMarksSelectedFailedAndRetries() {
        start()
        val errorEvent = "error" to """{"type":"error","code":"upstream_error","message":"upstream stream ended before completion","param":null,"sequence_number":3,"sleepyrouter":{"routing":${trace(selectedOutcome = "failed")}}}"""
        router.enqueue(
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), delta("반쯤", 1), errorEvent)),
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), delta("다시 완전한 답", 1), completed("다시 완전한 답")))
        )
        h.vm.send("끊기는 답변", emptyList(), "coding")
        h.awaitNoRunningWork()
        val broken = reply()
        h.snapshot("after broken stream")
        assertTrue(broken.errorText!!.contains("ended before completion"))
        assertEquals("failed", broken.routing!!.attempts.last().outcome)

        h.fireBackoff()
        h.awaitReplies()
        val r = reply()
        assertEquals("retry streams into the same bubble", broken.id, r.id)
        assertEquals("다시 완전한 답", r.text)
        assertNull(r.errorText)
        assertEquals("succeeded", r.routing!!.attempts.last().outcome)
    }

    @Test
    fun connectionCutMidStreamIsRetried() {
        start()
        router.enqueue(
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), delta("잘리는", 1)), cut = true),
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("복구된 답")))
        )
        h.vm.send("연결이 끊기는 질문", emptyList(), "coding")
        h.awaitNoRunningWork()
        assertTrue(reply().errorText!!.contains("다시 시도"))
        h.fireBackoff()
        h.awaitReplies()
        assertEquals("복구된 답", reply().text)
    }

    @Test
    fun unreachableRouterSaysSoAndLeavesNoSpinner() {
        h = ChatHarness()
        router = FakeRouter()
        val deadUrl = router.url
        router.close() // nothing listens there now
        runBlocking { SettingsStore.setRouterUrl(h.app, deadUrl) }
        h.vm.send("연결 안 되는 라우터", emptyList(), "coding")
        repeat(5) { h.fireBackoff() }
        h.awaitReplies()
        val r = reply()
        assertFalse(r.isStreaming)
        assertTrue(r.errorText!!, r.errorText!!.contains("연결할 수 없습니다"))
        assertNull(r.routing)
    }

    @Test
    fun truncatedReplyKeepsTextAndSaysItWasCut() {
        start()
        router.enqueue(FakeRouter.Reply.Sse(listOf(
            routingFrame(), created(), delta("긴 답의 앞부분", 1),
            completed("긴 답의 앞부분", type = "response.incomplete", status = "incomplete", extra = ""","incomplete_details":{"reason":"max_output_tokens"}""")
        )))
        sendAndSettle("아주 긴 답")
        val r = reply()
        assertTrue(r.text.startsWith("긴 답의 앞부분"))
        assertTrue(r.text.contains("max_output_tokens"))
        assertEquals("incomplete", r.routing!!.attempts.last().outcome)
        assertFalse(r.isStreaming)
    }

    @Test
    fun noRouterConfiguredUsesOfflineMock() {
        h = ChatHarness()
        router = FakeRouter()
        sendAndSettle("오프라인 질문")
        assertTrue(reply().text.contains("오프라인 질문"))
        assertNull(reply().routing)
        assertTrue(router.requests.isEmpty())
    }

    @Test
    fun endpointNormalisationAndModelList() {
        assertEquals("http://h:4567/hoard/v1/responses", RouterAiEngine.endpoint("http://h:4567", "responses"))
        assertEquals("http://h:4567/hoard/v1/responses", RouterAiEngine.endpoint("http://h:4567/ ", "responses"))
        assertEquals("http://h:4567/hoard/v1/responses", RouterAiEngine.endpoint("http://h:4567/hoard/v1", "responses"))
        assertEquals("http://h:4567/v1/models", RouterAiEngine.endpoint("http://h:4567/v1", "models", hoard = false))
        router = FakeRouter()
        h = ChatHarness()
        val models = runBlocking { RouterAiEngine(router.url).listModels() }
        assertEquals(listOf("coding", "zen/a"), models)
    }
}
