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
        // Every tool step is title + body: the tool's name, then subject and result.
        assertEquals(listOf("모델 추론", "웹 검색"), r.thinking.map { it.title })
        assertEquals("서울 날씨\n결과 1개: 서울 날씨", r.thinking[1].detail)
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

    /** Several web_fetch calls in one round run at the same time; outputs go back in call order. */
    @Test fun callsOfOneRoundRunInParallelAndKeepTheirOrder() {
        val running = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val slowFetch = object : com.sleepysoong.hoard.tools.Tool {
            override val name = "web_fetch"
            override val description = "fake"
            override val parallelSafe = true
            override val parameters = kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("object")) }
            override suspend fun execute(args: JsonObject): JsonObject {
                val now = running.incrementAndGet(); peak.accumulateAndGet(now, ::maxOf)
                kotlinx.coroutines.delay(300)
                running.decrementAndGet()
                return kotlinx.serialization.json.buildJsonObject { put("content", kotlinx.serialization.json.JsonPrimitive("body of " + args["url"]!!.jsonPrimitive.content)) }
            }
        }
        start(tools = ToolRegistry(listOf(slowFetch)))
        val urls = (1..3).map { "https://s$it.example/" }
        router.enqueue(
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), toolCallCompleted(*urls.mapIndexed { i, u -> Triple("f$i", "web_fetch", """{"url":"$u"}""") }.toTypedArray()))),
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("세 페이지를 읽었어요.")))
        )
        val t0 = System.currentTimeMillis()
        h.vm.send("세 곳 읽어줘", emptyList(), "coding")
        h.awaitReplies()
        assertEquals("all three fetched at once", 3, peak.get())
        assertTrue("parallel, not 3 x 300ms", System.currentTimeMillis() - t0 < 5_000)
        val outputs = body(1)["input"]!!.jsonArray.map { it.jsonObject }.filter { it["type"]!!.jsonPrimitive.content == "function_call_output" }
        assertEquals(listOf("f0", "f1", "f2"), outputs.map { it["call_id"]!!.jsonPrimitive.content })
        assertEquals(urls.map { "body of $it" }, outputs.map { Json.parseToJsonElement(it["output"]!!.jsonPrimitive.content).jsonObject["content"]!!.jsonPrimitive.content })
        assertTrue(body(0)["instructions"]!!.jsonPrimitive.content.contains("in parallel"))
        assertEquals("세 페이지를 읽었어요.", h.messages().last().text)
        assertEquals(3, h.messages().last().thinking.count { it.kind == com.sleepysoong.hoard.data.StepKind.Tool })
    }

    /** Side-effect tools are never run concurrently: write then read in one turn stays ordered. */
    @Test fun writeThenReadInOneTurnStaysOrdered() {
        val dir = java.nio.file.Files.createTempDirectory("ws2").toFile()
        start(tools = WebTools.registry(enabled = false, braveApiKey = "", files = com.sleepysoong.hoard.tools.files.Workspace(dir)))
        router.enqueue(
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), toolCallCompleted(
                Triple("a", "read_file", """{"path":"x.txt"}"""),
                Triple("b", "write_file", """{"path":"x.txt","content":"v1"}"""),
                Triple("c", "read_file", """{"path":"x.txt"}"""),
                Triple("d", "grep", """{"pattern":"v1"}""")
            ))),
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("ok")))
        )
        h.vm.send("x", emptyList(), "coding")
        h.awaitReplies()
        val out = body(1)["input"]!!.jsonArray.map { it.jsonObject }.filter { it["type"]!!.jsonPrimitive.content == "function_call_output" }
            .associate { it["call_id"]!!.jsonPrimitive.content to it["output"]!!.jsonPrimitive.content }
        assertTrue("read before the write: not found", out["a"]!!.contains("not found"))
        assertTrue("read after the write sees it", out["c"]!!.contains("1\\tv1"))
        assertTrue(out["d"]!!.contains("\"count\":1"))
        dir.deleteRecursively()
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
        assertEquals("페이지 읽기", r.thinking.last().title)
        assertTrue(r.thinking.last().detail, r.thinking.last().detail.startsWith("192.168.0.1/\n실패"))
        assertTrue(r.thinking.last().failed)
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

    @Test fun termuxExecResultGoesBackToTheModel() {
        val commands = mutableListOf<String>()
        val termux = object : com.sleepysoong.hoard.termux.TermuxExecutor {
            override suspend fun executeTermux(command: String, cwd: String?, timeoutMs: Long): com.sleepysoong.hoard.termux.TermuxResult {
                commands += command
                return com.sleepysoong.hoard.termux.TermuxResult("On branch main\nnothing to commit\n", "", 0)
            }
        }
        // As the worker builds it: web tools off, Termux registered like any other tool.
        start(tools = WebTools.registry(enabled = false, braveApiKey = "", termux = termux))
        router.enqueue(
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), toolCallCompleted(Triple("t1", "termux_exec", """{"command":"git status"}""")))),
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("작업 트리가 깨끗해요.")))
        )
        h.vm.send("git status 해줘", emptyList(), "coding")
        h.awaitReplies()
        assertEquals(listOf("git status"), commands)
        assertEquals(listOf("termux_exec"), body(0)["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        val out = Json.parseToJsonElement(body(1)["input"]!!.jsonArray.last().jsonObject["output"]!!.jsonPrimitive.content).jsonObject
        assertEquals("On branch main\nnothing to commit\n", out["stdout"]!!.jsonPrimitive.content)
        assertEquals(0, out["exitCode"]!!.jsonPrimitive.content.toInt())
        val r = h.messages().last()
        assertEquals("작업 트리가 깨끗해요.", r.text)
        assertEquals("Termux 실행", r.thinking.last().title)
    }

    @Test fun fileToolsWriteThenReadThroughTheLoop() {
        val dir = java.nio.file.Files.createTempDirectory("ws").toFile()
        start(tools = WebTools.registry(enabled = false, braveApiKey = "", files = com.sleepysoong.hoard.tools.files.Workspace(dir)))
        router.enqueue(
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), toolCallCompleted(
                Triple("w1", "write_file", """{"path":"memo/today.md","content":"우유 사기\n"}"""),
                Triple("r1", "read_file", """{"path":"memo/today.md"}""")
            ))),
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("메모에 저장했어요.")))
        )
        h.vm.send("오늘 할 일 메모해 줘", emptyList(), "coding")
        h.awaitReplies()
        assertEquals("우유 사기\n", java.io.File(dir, "memo/today.md").readText())
        assertEquals(listOf("read_file", "write_file", "edit_file", "glob", "grep"),
            body(0)["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        val out = Json.parseToJsonElement(body(1)["input"]!!.jsonArray.last().jsonObject["output"]!!.jsonPrimitive.content).jsonObject
        assertEquals("1\t우유 사기\n", out["content"]!!.jsonPrimitive.content)
        assertEquals(listOf("파일 쓰기", "파일 읽기"), h.messages().last().thinking.map { it.title })
        dir.deleteRecursively()
    }

    /** Device tools off: only the runtime capabilities (goal / schedule / wakeup) remain. */
    @Test fun allToolsOffSendNoTools() {
        start(tools = null)
        runBlocking { SettingsStore.setWebToolsEnabled(h.app, false); SettingsStore.setFileToolsEnabled(h.app, false) }
        router.enqueue(FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("도구 없이 답"))))
        h.vm.send("안녕", emptyList(), "coding")
        h.awaitReplies()
        assertEquals(listOf("goal", "schedule", "schedule_wakeup"), body(0)["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals("도구 없이 답", h.messages().last().text)
    }

    @Test fun defaultsOfferWebFetchAndFileToolsButNoSearchWithoutKey() {
        start(tools = null) // real WebTools.registry from settings: web + file tools on, no Brave key, Termux off
        router.enqueue(FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("ok"))))
        h.vm.send("안녕", emptyList(), "coding")
        h.awaitReplies()
        assertEquals(listOf("web_fetch", "read_file", "write_file", "edit_file", "glob", "grep", "goal", "schedule", "schedule_wakeup"), body(0)["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }
}
