package com.sleepysoong.hoard

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.testing.ChatHarness
import com.sleepysoong.hoard.testing.FakeRouter
import com.sleepysoong.hoard.testing.FakeRouter.Companion.completed
import com.sleepysoong.hoard.testing.FakeRouter.Companion.created
import com.sleepysoong.hoard.testing.FakeRouter.Companion.delta
import com.sleepysoong.hoard.testing.FakeRouter.Companion.reasoningDelta
import com.sleepysoong.hoard.testing.FakeRouter.Companion.routingFrame
import com.sleepysoong.hoard.testing.FakeRouter.Companion.toolCallCompleted
import com.sleepysoong.hoard.tools.ToolRegistry
import com.sleepysoong.hoard.tools.WebFetchTool
import com.sleepysoong.hoard.tools.WebSearchTool
import com.sleepysoong.hoard.tools.WebTools
import com.sleepysoong.hoard.tools.search.SearchHit
import com.sleepysoong.hoard.tools.search.SearchProvider
import com.sleepysoong.hoard.tools.search.SearchRequest
import com.sleepysoong.hoard.tools.search.SearchResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The tool loop through the whole app: worker → RouterAiEngine → (fake) router →
 * function_call → tool runs on the device → function_call_output sent back →
 * final answer. Tool steps show up in the reply's thinking list.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ToolLoopTest {
    @get:Rule val name = TestName()
    private lateinit var h: ChatHarness
    private lateinit var router: FakeRouter
    private val searched = mutableListOf<SearchRequest>()

    private val fakeSearch = object : SearchProvider {
        override val id = "fake"
        override suspend fun search(request: SearchRequest): SearchResponse {
            searched += request
            return SearchResponse(request.query, null, listOf(SearchHit("서울 날씨", "https://weather.example/seoul", "맑음, 최고 24도")), moreResultsAvailable = false)
        }
    }

    private fun start(tools: ToolRegistry? = ToolRegistry(listOf(WebSearchTool(fakeSearch), WebFetchTool()))) {
        h = ChatHarness()
        router = FakeRouter()
        WebTools.override = tools
        runBlocking { SettingsStore.setRouterUrl(h.app, router.url) }
        val deadline = System.currentTimeMillis() + 5_000
        while (h.vm.settings.value.routerUrl != router.url) {
            h.idle(); Thread.sleep(5)
            check(System.currentTimeMillis() < deadline)
        }
    }

    @After fun tearDown() {
        WebTools.override = null
        if (::router.isInitialized) router.close()
        if (::h.isInitialized) {
            h.snapshot("final")
            router.requests.forEachIndexed { i, r -> h.note("router request #$i ${r.body.take(1500)}") }
            h.writeTranscript("ToolLoopTest.${name.methodName}")
        }
    }

    private fun body(i: Int): JsonObject = Json.parseToJsonElement(router.requests.filter { it.path.endsWith("/responses") }[i].body).jsonObject

    @Test fun searchCallRunsOnDeviceAndResultGoesBackToTheModel() {
        start()
        router.enqueue(
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), reasoningDelta("검색이 필요하다.", 1),
                toolCallCompleted(Triple("web_search:0", "web_search", """{"query":"서울 날씨","freshness":"day"}""")))),
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), delta("오늘 서울은 맑습니다.", 1), completed("오늘 서울은 맑습니다. (출처: https://weather.example/seoul)", outTok = 12)))
        )
        h.vm.send("서울 날씨 알려줘", emptyList(), "coding")
        h.awaitReplies()

        val r = h.messages().last()
        assertEquals(MessageRole.Assistant, r.role)
        assertNull(r.errorText)
        assertEquals("오늘 서울은 맑습니다. (출처: https://weather.example/seoul)", r.text)
        assertFalse(r.isStreaming)
        assertEquals(listOf("모델 추론", "웹 검색 · 서울 날씨"), r.thinking.map { it.title })
        assertTrue(r.thinking[1].detail, r.thinking[1].detail.startsWith("결과 1개"))
        assertEquals("tokens of both rounds", 5 + 12, r.completionTokens)

        assertEquals(listOf("서울 날씨"), searched.map { it.query })
        assertEquals("day", searched[0].freshness?.name?.lowercase())

        // Round 1: tools offered with the usage note.
        val first = body(0)
        assertEquals(listOf("web_search", "web_fetch"), first["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertTrue(first["instructions"]!!.jsonPrimitive.content.contains("Today's date"))
        assertNull(first["tool_choice"])
        // Round 2: history + the call + its normalized output.
        val input = body(1)["input"]!!.jsonArray.map { it.jsonObject }
        val call = input[input.size - 2]
        val output = input.last()
        assertEquals("function_call", call["type"]!!.jsonPrimitive.content)
        assertEquals("web_search:0", call["call_id"]!!.jsonPrimitive.content)
        assertEquals("function_call_output", output["type"]!!.jsonPrimitive.content)
        assertEquals("web_search:0", output["call_id"]!!.jsonPrimitive.content)
        val result = Json.parseToJsonElement(output["output"]!!.jsonPrimitive.content).jsonObject
        assertEquals("search_1", result["results"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("user question still first", "서울 날씨 알려줘",
            input[0]["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun toolFailureIsReportedToTheModelNotTheUser() {
        start()
        router.enqueue(
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), toolCallCompleted(Triple("c1", "web_fetch", """{"url":"http://192.168.0.1/"}""")))),
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("그 주소는 읽을 수 없어요.")))
        )
        h.vm.send("공유기 페이지 읽어줘", emptyList(), "coding")
        h.awaitReplies()
        val r = h.messages().last()
        assertNull(r.errorText)
        assertEquals("그 주소는 읽을 수 없어요.", r.text)
        assertTrue(r.thinking.last().detail, r.thinking.last().detail.startsWith("실패"))
        val out = body(1)["input"]!!.jsonArray.last().jsonObject["output"]!!.jsonPrimitive.content
        assertTrue(out, out.contains("\"error\"") && out.contains("blocked"))
    }

    @Test fun loopIsBoundedAndTheLastRoundForbidsTools() {
        start()
        val rounds = com.sleepysoong.hoard.engine.RouterAiEngine.MAX_TOOL_ROUNDS
        repeat(rounds) { i ->
            router.enqueue(FakeRouter.Reply.Sse(listOf(routingFrame(), created(), toolCallCompleted(Triple("c$i", "web_search", """{"query":"q$i"}""")))))
        }
        router.enqueue(FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("충분히 찾았으니 답합니다."))))
        h.vm.send("끝없이 검색해", emptyList(), "coding")
        h.awaitReplies(timeoutMs = 30_000)
        val sent = router.requests.count { it.path.endsWith("/responses") }
        assertEquals(rounds + 1, sent)
        assertNull(body(rounds - 1)["tool_choice"])
        assertEquals("none", body(rounds)["tool_choice"]!!.jsonPrimitive.content)
        assertEquals("충분히 찾았으니 답합니다.", h.messages().last().text)
        assertEquals(rounds, searched.size)
    }

    @Test fun disabledWebToolsSendNoTools() {
        start(tools = null)
        runBlocking { SettingsStore.setWebToolsEnabled(h.app, false) }
        router.enqueue(FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("도구 없이 답"))))
        h.vm.send("안녕", emptyList(), "coding")
        h.awaitReplies()
        assertNull(body(0)["tools"])
        assertEquals("도구 없이 답", h.messages().last().text)
    }

    @Test fun withoutBraveKeyOnlyWebFetchIsOffered() {
        start(tools = null) // real WebTools.registry from settings: enabled, no key
        router.enqueue(FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("ok"))))
        h.vm.send("안녕", emptyList(), "coding")
        h.awaitReplies()
        assertEquals(listOf("web_fetch"), body(0)["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }
}
