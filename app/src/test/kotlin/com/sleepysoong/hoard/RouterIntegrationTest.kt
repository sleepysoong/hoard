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
import com.sleepysoong.hoard.testing.FakeRouter.Companion.postCommitError
import com.sleepysoong.hoard.testing.FakeRouter.Companion.reasoningDelta
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
        // Like the real app: the ViewModel has loaded the setting before the user sends.
        val deadline = System.currentTimeMillis() + 5_000
        while (h.vm.settings.value.routerUrl != router.url) {
            h.idle(); Thread.sleep(5)
            check(System.currentTimeMillis() < deadline) { "settings never reached the ViewModel" }
        }
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
    fun modelReasoningIsShownAsThinkingStep() {
        start()
        router.enqueue(FakeRouter.Reply.Sse(listOf(
            routingFrame(), created(), reasoningDelta("인사다. ", 1), reasoningDelta("짧게 답하자.", 2),
            delta("안녕하세요", 3), completed("안녕하세요")
        )))
        sendAndSettle("안녕")

        val r = reply()
        assertEquals("안녕하세요", r.text)
        assertEquals(1, r.thinking.size)
        assertEquals("모델 추론", r.thinking[0].title)
        assertEquals("인사다. 짧게 답하자.", r.thinking[0].detail)
        assertNull(r.errorText)
    }

    @Test
    fun reasoningOnlyReplyIsRetriedInsteadOfEmptySuccess() {
        start()
        router.enqueue(
            // Router already committed (reasoning streamed), then saw no answer.
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), reasoningDelta("!!!!", 1), postCommitError("upstream returned empty response"))),
            // Older router: an empty completed. Also not a success.
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed(""))),
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), delta("이번엔 답", 1), completed("이번엔 답")))
        )
        h.vm.send("안녕", emptyList(), "coding")
        repeat(5) { h.fireBackoff() }
        h.awaitReplies()

        val r = reply()
        assertEquals("retried until a real answer", 3, router.requests.count { it.path.endsWith("/responses") })
        assertEquals("이번엔 답", r.text)
        assertNull(r.errorText)
    }

    @Test
    fun reasoningOnlyEveryTimeExplainsWhy() {
        start()
        repeat(3) { router.enqueue(FakeRouter.Reply.Sse(listOf(routingFrame(), created(), reasoningDelta("…", 1), postCommitError("upstream returned empty response")))) }
        h.vm.send("안녕", emptyList(), "coding")
        repeat(5) { h.fireBackoff() }
        h.awaitReplies()

        val r = reply()
        assertEquals(3, router.requests.count { it.path.endsWith("/responses") })
        assertTrue(r.errorText!!, r.errorText!!.contains("빈 응답"))
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
        // The system prompt comes first; the web-tool note (tools on by default) follows it.
        assertTrue(body["instructions"]!!.jsonPrimitive.content.startsWith("너는 간결하다.\n\n"))
        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals("welcome bubble excluded", listOf("user", "assistant", "user"), input.map { it["role"]!!.jsonPrimitive.content })
        assertTrue("every item is a typed message (untyped items make typed decoders drop all input)",
            input.all { it["type"]?.jsonPrimitive?.content == "message" })
        val texts = input.map { it["content"]!!.jsonArray[0].jsonObject }
        assertEquals("output_text", texts[1]["type"]!!.jsonPrimitive.content)
        assertEquals("첫 답", texts[1]["text"]!!.jsonPrimitive.content)
        assertEquals("input_text", texts[2]["type"]!!.jsonPrimitive.content)
        assertEquals("둘째 질문", texts[2]["text"]!!.jsonPrimitive.content)
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
    fun noRouterConfiguredBlocksSendingWithoutInventingABubble() {
        h = ChatHarness()
        com.sleepysoong.hoard.engine.Engines.offline = null // the app's real no-router behaviour
        router = FakeRouter()
        h.vm.input = "오프라인 질문"
        sendAndSettle("오프라인 질문")
        assertTrue("no user or error bubble created", h.messages().isEmpty())
        assertEquals("draft retained", "오프라인 질문", h.vm.input)
        assertTrue(h.vm.goalNotice.value!!.contains("라우터가 연결되지 않았습니다"))
        assertTrue(h.allWork().isEmpty())
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
        router.modelsBody = """{"object":"list","data":[{"id":"coding","owned_by":"sleepyrouter"},{"id":"zen/a","owned_by":"zen"}]}"""
        val models = runBlocking { RouterAiEngine(router.url).listModels() }
        assertEquals(listOf("coding", "zen/a"), models.map { it.id })
        assertEquals(listOf(true, false), models.map { it.isGroup })
    }

    /** Sessions carrying a model this router doesn't know (older router, none yet): connecting moves them. */
    @Test
    fun connectingMigratesStaleSessionModelsToTheRoutersFirstGroup() {
        start()
        val welcome = h.vm.uiState.value.session!!
        assertEquals(com.sleepysoong.hoard.testing.TestData.MODEL, welcome.modelId)
        router.modelsBody = """{"object":"list","data":[{"id":"coding","owned_by":"sleepyrouter"},{"id":"zen/a","owned_by":"zen"}]}"""
        val routerSession = h.repo.createSession("라우터 모델 세션", modelId = "zen/a")
        runBlocking { com.sleepysoong.hoard.engine.RouterConnection.refresh(router.url, h.repo) }
        h.idle()
        assertEquals("unknown ID → router's first group", "coding", h.repo.sessionOf(welcome.id)!!.modelId)
        assertEquals("a model the router knows is kept", "zen/a", h.repo.sessionOf(routerSession.id)!!.modelId)

        router.enqueue(FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("ok"))))
        h.vm.send("질문", emptyList(), h.vm.uiState.value.session!!.modelId)
        h.awaitReplies()
        val body = Json.parseToJsonElement(router.requests.last().body).jsonObject
        assertEquals("coding", body["model"]!!.jsonPrimitive.content)
    }

    /** Offline in router mode: the reply waits for a network instead of burning its retries. */
    @Test
    fun offlineReplyWaitsForNetworkWithoutUsingAttempts() {
        start()
        h.networkUp = false
        router.enqueue(FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("네트워크 복구 후 답"))))
        h.vm.send("지하철에서 보낸 질문", emptyList(), "coding")
        repeat(20) { h.idle(); Thread.sleep(10) }
        val waiting = h.allWork().single()
        assertEquals(WorkInfo.State.ENQUEUED, waiting.state)
        assertEquals("no attempt spent while offline", 0, waiting.runAttemptCount)
        assertTrue("nothing sent", router.requests.none { it.path.endsWith("/responses") })

        h.networkUp = true
        h.awaitReplies()
        assertEquals("네트워크 복구 후 답", reply().text)
    }

    /** Every candidate failed on keys (auth / missing): retrying only replays the same failures. */
    @Test
    fun allFailedOnKeysIsNotRetried() {
        start()
        val keysTrace = """{"requested_model":"coding","route_reason":"model-group","candidates":["zen/a","gemini/d"],"attempts":[
            {"index":1,"model":"zen/a","provider":"zen","outcome":"failed","error_class":"auth","status_code":401,"reason":"invalid api key","failed_over":true,"duration_ms":3},
            {"index":2,"model":"gemini/d","provider":"gemini","outcome":"skipped","error_class":"unknown","reason":"missing_api_key: API key missing for provider gemini","duration_ms":0}]}""".replace("\n", "")
        router.enqueue(FakeRouter.Reply.Json(502, """{"error":{"message":"All configured candidates failed","type":"upstream_error","param":null,"code":"all_candidates_failed"},"sleepyrouter":{"routing":$keysTrace}}"""))
        h.vm.send("키가 전부 틀린 라우터", emptyList(), "coding")
        repeat(3) { h.fireBackoff() }
        h.awaitReplies()
        assertEquals("one request, no retries", 1, router.requests.count { it.path.endsWith("/responses") })
        assertTrue(reply().errorText!!, reply().errorText!!.contains("API 키 확인"))
        assertEquals(WorkInfo.State.FAILED, h.allWork().single().state)
    }

    /** Router with inbound auth: the token goes on every call; a wrong one fails clearly, once. */
    @Test
    fun tokenIsSentAndAuthFailureIsNotRetried() {
        start()
        router.requiredToken = "sr-token-1"
        runBlocking { SettingsStore.setRouterToken(h.app, "wrong") }
        h.vm.send("잘못된 토큰", emptyList(), "coding")
        repeat(3) { h.fireBackoff() }
        h.awaitReplies()
        assertEquals("401 is not retried", 1, router.requests.count { it.path.endsWith("/responses") })
        assertTrue(reply().errorText!!, reply().errorText!!.contains("라우터 인증 실패"))

        runBlocking { SettingsStore.setRouterToken(h.app, "sr-token-1") }
        router.enqueue(FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("인증 통과"))))
        sendAndSettle("올바른 토큰")
        assertEquals("인증 통과", reply().text)
        assertEquals("Bearer sr-token-1", router.requests.last().authorization)

        val status = runBlocking { com.sleepysoong.hoard.engine.RouterConnection.refresh(router.url, h.repo, token = "sr-token-1") }
        assertTrue("model list also authenticated: $status", status is com.sleepysoong.hoard.engine.RouterStatus.Connected)
        val bad = runBlocking { com.sleepysoong.hoard.engine.RouterConnection.refresh(router.url, h.repo, token = "") }
        assertTrue("missing token reported: $bad", bad is com.sleepysoong.hoard.engine.RouterStatus.Failed && bad.reason.contains("인증"))
    }

    @Test
    fun noTokenMeansNoAuthorizationHeader() {
        start()
        router.enqueue(FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("ok"))))
        sendAndSettle("토큰 없음")
        assertNull(router.requests.last().authorization)
    }
}
