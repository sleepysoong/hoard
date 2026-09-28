package com.sleepysoong.hoard

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.engine.RouterAiEngine
import com.sleepysoong.hoard.testing.ChatHarness
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Hoard against the REAL sleepyrouter binary (not a fake of its wire format):
 * Hoard worker → RouterAiEngine → sleepyrouter /hoard/v1/responses → fake
 * OpenAI-compatible upstreams. Proves the two projects agree on the wire.
 *
 * Opt-in: set SLEEPYROUTER_BIN to a built binary (`go build -o … ./cmd/sleepyrouter`).
 * Skipped otherwise (CI has no Go toolchain). Output: build/test-artifacts/.
 *
 * Upstream behaviour by upstream model name:
 *   a → 429 rate limit (JSON error)   b → 503 HTML (no error body)   c → streams "real answer"
 *   x → provider has no API key (skipped by the router)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RealSleepyrouterTest {
    @get:Rule val name = TestName()
    private lateinit var upstream: HttpServer
    private var router: Process? = null
    private lateinit var home: File
    private var port = 0
    private val log = StringBuilder()
    private val upstreamBodies = mutableListOf<String>()
    private val upstreamAuth = mutableListOf<String?>()
    @Volatile private var todoScenario = false

    @Before fun start() {
        val bin = System.getenv("SLEEPYROUTER_BIN")
        assumeTrue("SLEEPYROUTER_BIN not set — skipping real-binary test", !bin.isNullOrBlank() && File(bin).canExecute())

        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/responses") { ex ->
                val body = ex.requestBody.readBytes().decodeToString()
                val model = Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                synchronized(log) {
                    log.append("upstream got model=$model stream=${body.contains("\"stream\":true")}\n")
                    upstreamBodies += body
                    upstreamAuth += ex.requestHeaders.getFirst("Authorization")
                }
                when (model) {
                    "a" -> reply(ex, 429, "application/json", """{"error":{"type":"rate_limit_error","code":"rate_limited","message":"Rate limit reached for a","param":null}}""")
                    "b" -> reply(ex, 503, "text/html", "<html>down</html>")
                    else -> {
                        ex.responseHeaders.add("Content-Type", "text/event-stream")
                        ex.sendResponseHeaders(200, 0)
                        val frames = if (todoScenario && !body.contains("\"function_call_output\"")) listOf(
                            com.sleepysoong.hoard.testing.FakeRouter.created(),
                            com.sleepysoong.hoard.testing.FakeRouter.toolCallCompleted(
                                Triple("todo-real-1", "todo", """{"op":"create","content":"실제 라우터 도구 연결 확인"}""")
                            )
                        ) else listOf(
                            "response.created" to """{"type":"response.created","sequence_number":0,"response":{"id":"resp_up","object":"response","model":"$model","status":"in_progress","output":[]}}""",
                            "response.output_text.delta" to """{"type":"response.output_text.delta","sequence_number":1,"item_id":"m1","output_index":0,"content_index":0,"delta":"real "}""",
                            "response.output_text.delta" to """{"type":"response.output_text.delta","sequence_number":2,"item_id":"m1","output_index":0,"content_index":0,"delta":"answer"}""",
                            "response.completed" to """{"type":"response.completed","sequence_number":3,"response":{"id":"resp_up","object":"response","model":"$model","status":"completed","output":[{"type":"message","id":"m1","role":"assistant","status":"completed","content":[{"type":"output_text","text":"real answer","annotations":[]}]}],"usage":{"input_tokens":7,"output_tokens":2,"total_tokens":9}}}"""
                        )
                        ex.responseBody.use { out ->
                            for ((n, d) in frames) { out.write("event: $n\ndata: $d\n\n".toByteArray()); out.flush() }
                        }
                    }
                }
            }
            // Chat Completions upstream (like NVIDIA NIM): first asks for web_search, then answers.
            createContext("/v1/chat/completions") { ex ->
                val body = ex.requestBody.readBytes().decodeToString()
                synchronized(log) { log.append("chat upstream got ${body.take(300)}\n"); upstreamBodies += body }
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, 0)
                fun chunk(delta: String, finish: String? = null) =
                    """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"t","choices":[{"index":0,"delta":$delta,"finish_reason":${finish?.let { "\"$it\"" } ?: "null"}}]}"""
                val frames = if (!body.contains("\"role\":\"tool\"")) listOf(
                    chunk("""{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"web_search","arguments":""}}]}"""),
                    chunk("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\":\"seoul weather\"}"}}]}"""),
                    chunk("{}", "tool_calls")
                ) else listOf(chunk("""{"role":"assistant","content":"sunny in "}"""), chunk("""{"content":"Seoul"}"""), chunk("{}", "stop"))
                ex.responseBody.use { out ->
                    for (f in frames) { out.write("data: $f\n\n".toByteArray()); out.flush() }
                    out.write("data: [DONE]\n\n".toByteArray())
                }
            }
            start()
        }
        val up = "http://127.0.0.1:${upstream.address.port}/v1"
        port = ServerSocket(0).use { it.localPort }
        home = Files.createTempDirectory("sleepyrouter-home").toFile()
        File(home, "config.toml").writeText("""
            version = 1
            [server]
            host = "127.0.0.1"
            port = $port
            auth_token_env = "SR_TOKEN"
            [routing]
            default_group = "coding"
            [timeouts]
            request = "20s"
            first_event = "10s"
            stream_idle = "10s"
            [logging]
            level = "info"
            format = "text"
            [usage]
            enabled = false
            [providers.p1]
            base_url = "$up"
            api_key_env = "P1_KEY"
            [providers.p2]
            base_url = "$up"
            api_key_env = "P2_KEY"
            [providers.p3]
            base_url = "$up"
            api_key_env = "P3_KEY_UNSET"
            [providers.p4]
            base_url = "$up"
            api_key_env = "P2_KEY"
            wire_api = "chat_completions"
            [models."p4/t"]
            provider = "p4"
            upstream_model = "t"
            [models."p1/a"]
            provider = "p1"
            upstream_model = "a"
            [models."p3/x"]
            provider = "p3"
            upstream_model = "x"
            [models."p2/b"]
            provider = "p2"
            upstream_model = "b"
            [models."p2/c"]
            provider = "p2"
            upstream_model = "c"
            [groups]
            coding = ["p1/a", "p3/x", "p2/b", "p2/c"]
            broken = ["p1/a", "p2/b"]
        """.trimIndent())
        File(home, ".env").apply {
            writeText("P1_KEY=k1-secret-value\nP2_KEY=k2-secret-value\nSR_TOKEN=$ROUTER_TOKEN\n")
            Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("rw-------"))
        }
        router = ProcessBuilder(bin, "serve").apply {
            environment()["SLEEPYROUTER_HOME"] = home.path
            redirectErrorStream(true)
            redirectOutput(File(home, "router.log"))
        }.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val up2 = runCatching { (URL("http://127.0.0.1:$port/health").openConnection() as java.net.HttpURLConnection).responseCode == 200 }.getOrDefault(false)
            if (up2) break
            check(router!!.isAlive) { "sleepyrouter exited: " + File(home, "router.log").readText() }
            check(System.currentTimeMillis() < deadline) { "sleepyrouter did not come up: " + File(home, "router.log").readText() }
            Thread.sleep(50)
        }
    }

    @After fun stop() {
        router?.destroy()
        router?.waitFor()
        if (::upstream.isInitialized) upstream.stop(0)
        if (::home.isInitialized) {
            val out = File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts").apply { mkdirs() }
            File(out, "RealSleepyrouterTest.${name.methodName}.txt").writeText(
                log.toString() + "\n--- sleepyrouter log ---\n" + File(home, "router.log").readText()
            )
            home.deleteRecursively()
        }
    }

    private fun reply(ex: com.sun.net.httpserver.HttpExchange, status: Int, type: String, body: String) {
        val b = body.toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(status, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    @Test fun chatThroughRealRouterShowsFailoverTrace() {
        val h = ChatHarness()
        runBlocking { SettingsStore.setRouterUrl(h.app, "http://127.0.0.1:$port"); SettingsStore.setRouterToken(h.app, ROUTER_TOKEN) }
        h.vm.send("real router, please", emptyList(), "coding")
        h.awaitReplies(timeoutMs = 30_000)
        h.snapshot("after reply")
        log.append(h.let { val sb = StringBuilder(); it.messages().forEach { m -> sb.append("${m.role}: ${m.text} | model=${m.modelId} | error=${m.errorText}\n") }; sb })

        val r = h.messages().last()
        assertEquals(MessageRole.Assistant, r.role)
        assertEquals("real answer", r.text)
        // What the answering model actually received — not just what Hoard sent.
        val sent = synchronized(log) { upstreamBodies.last() }
        assertTrue("the question reaches the upstream model: $sent", sent.contains("real router, please"))
        assertTrue("system prompt too", sent.contains("\"instructions\""))
        assertNull(r.errorText)
        assertEquals("answered by the model sleepyrouter selected", "p2/c", r.modelId)
        val route = r.routing!!
        log.append("trace: " + route + "\n")
        assertEquals("coding", route.requestedModel)
        assertEquals(listOf("p1/a", "p3/x", "p2/b", "p2/c"), route.candidates)
        assertEquals(listOf("failed", "skipped", "failed", "succeeded"), route.attempts.map { it.outcome })
        assertEquals(429, route.attempts[0].statusCode)
        assertTrue(route.attempts[0].reason!!, route.attempts[0].reason!!.contains("Rate limit reached for a"))
        assertTrue(route.attempts[1].reason!!.startsWith("missing_api_key"))
        assertEquals(503, route.attempts[2].statusCode)
        assertTrue(route.attempts[2].reason!!, route.attempts[2].reason!!.contains("503"))
        assertEquals(7, r.promptTokens)
        assertEquals(2, r.completionTokens)
        val all = route.toString()
        assertTrue("no provider key reaches the app", !all.contains("k1-secret-value") && !all.contains("k2-secret-value"))
    }

    @Test fun allCandidatesFailShowsReasonsFromRealRouter() {
        val h = ChatHarness()
        runBlocking { SettingsStore.setRouterUrl(h.app, "http://127.0.0.1:$port"); SettingsStore.setRouterToken(h.app, ROUTER_TOKEN) }
        h.vm.send("nobody can answer", emptyList(), "broken")
        repeat(5) { h.fireBackoff() }
        h.awaitReplies(timeoutMs = 30_000)
        h.snapshot("after failure")
        val r = h.messages().last()
        log.append("error=${r.errorText}\ntrace=${r.routing}\n")
        assertTrue(r.errorText!!, r.errorText!!.contains("모든 모델이 실패"))
        val route = r.routing!!
        assertNull(route.selectedModel)
        assertEquals(listOf("p1/a", "p2/b"), route.attempts.map { it.model })
        assertEquals(listOf(429, 503), route.attempts.map { it.statusCode })
    }

    /** Tool loop over the real router's Chat Completions bridge: tool_calls ↔ function_call(_output). */
    @Test fun webSearchToolLoopThroughRealRouterChatBridge() {
        val h = ChatHarness()
        val queries = mutableListOf<String>()
        com.sleepysoong.hoard.tools.WebTools.override = com.sleepysoong.hoard.tools.ToolRegistry(listOf(
            com.sleepysoong.hoard.tools.WebSearchTool(object : com.sleepysoong.hoard.tools.search.SearchProvider {
                override val id = "fake"
                override suspend fun search(request: com.sleepysoong.hoard.tools.search.SearchRequest): com.sleepysoong.hoard.tools.search.SearchResponse {
                    queries += request.query
                    return com.sleepysoong.hoard.tools.search.SearchResponse(request.query, null,
                        listOf(com.sleepysoong.hoard.tools.search.SearchHit("Seoul weather", "https://weather.example/seoul", "sunny, 24C")))
                }
            })
        ))
        try {
            runBlocking { SettingsStore.setRouterUrl(h.app, "http://127.0.0.1:$port"); SettingsStore.setRouterToken(h.app, ROUTER_TOKEN) }
            h.vm.send("서울 날씨?", emptyList(), "p4/t")
            h.awaitReplies(timeoutMs = 30_000)
            val r = h.messages().last()
            log.append("reply=${r.text} error=${r.errorText} thinking=${r.thinking}\n")
            assertNull(r.errorText)
            assertEquals("sunny in Seoul", r.text)
            assertEquals(listOf("seoul weather"), queries)
            assertTrue(r.thinking.any { it.title.startsWith("웹 검색") })
            val second = synchronized(log) { upstreamBodies.last() }
            assertTrue("tool result reached the upstream as a tool message: $second",
                second.contains("\"role\":\"tool\"") && second.contains("call_1") && second.contains("search_1"))
            assertTrue("tool definitions forwarded: $second", second.contains("\"web_search\""))
        } finally {
            com.sleepysoong.hoard.tools.WebTools.override = null
        }
    }

    @Test fun todoStateAndReminderThroughRealRouter() {
        todoScenario = true
        val h = ChatHarness()
        val sid = h.vm.uiState.value.session!!.id
        h.repo.todos.execute(sid, com.sleepysoong.hoard.data.todo.TodoRequest.Create("이전 작업 이어가기"))
        runBlocking { SettingsStore.setRouterUrl(h.app, "http://127.0.0.1:$port"); SettingsStore.setRouterToken(h.app, ROUTER_TOKEN) }
        h.vm.send("계속하고 연결도 확인해줘", emptyList(), "p2/c")
        h.awaitReplies(timeoutMs = 30_000)
        assertNull(h.messages().last().errorText)
        assertEquals(listOf("이전 작업 이어가기", "실제 라우터 도구 연결 확인"), h.repo.todos.list(sid).map { it.content })
        synchronized(log) {
            assertEquals(2, upstreamBodies.size)
            assertTrue(upstreamBodies.first().contains("Current session tasks"))
            assertTrue(upstreamBodies.first().contains("\"developer\""))
            assertTrue(upstreamBodies.last().contains("function_call_output"))
            assertTrue(upstreamBodies.last().contains("todo-real-1"))
            assertTrue(!upstreamBodies.last().contains("Current session tasks"))
            log.append("todo state=${h.repo.todos.list(sid)}\n")
        }
    }

    @Test fun modelListFromRealRouter() {
        val models = runBlocking { RouterAiEngine("http://127.0.0.1:$port", token = ROUTER_TOKEN).listModels() }
        log.append("models=$models\n")
        assertEquals(listOf("coding", "broken"), models.filter { it.isGroup }.map { it.id })
        assertTrue(models.any { it.id == "p2/c" && !it.isGroup })
    }

    @Test fun imageAttachmentReachesTheUpstreamModelIntact() {
        val h = ChatHarness()
        runBlocking { SettingsStore.setRouterUrl(h.app, "http://127.0.0.1:$port"); SettingsStore.setRouterToken(h.app, ROUTER_TOKEN) }
        val f = File.createTempFile("photo", ".png").apply { deleteOnExit() }
        val bmp = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFFF0000.toInt()) }
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val att = com.sleepysoong.hoard.data.UiAttachment("att-1", "red.png", "image/*", f.length(), android.net.Uri.fromFile(f))
        h.vm.send("무슨 색이야?", listOf(att), "coding")
        h.awaitReplies(timeoutMs = 30_000)

        val sentToModel = synchronized(log) { upstreamBodies.last() } // the candidate that answered (p2/c)
        log.append("upstream body (truncated): ${sentToModel.take(600)}\n")
        val expected = "data:image/png;base64," + android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
        assertTrue("router forwards input_image unchanged", sentToModel.contains("\"input_image\"") && sentToModel.contains(expected))
        assertTrue(sentToModel.contains("무슨 색이야?"))
        assertEquals("real answer", h.messages().last().text)
    }

    /** Other OpenAI clients may omit item "type"; the router must still forward the conversation. */
    @Test fun untypedInputItemsStillReachTheModel() {
        val conn = URL("http://127.0.0.1:$port/hoard/v1/responses").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $ROUTER_TOKEN")
        conn.outputStream.use { it.write("""{"model":"p2/c","stream":true,"input":[{"role":"user","content":"untyped hello"}]}""".toByteArray()) }
        assertEquals(200, conn.responseCode)
        conn.inputStream.use { it.readBytes() } // drain the SSE stream
        val sent = synchronized(log) { upstreamBodies.last() }
        assertTrue("conversation forwarded: $sent", sent.contains("untyped hello"))
    }

    /** Real auth: a wrong token is refused by sleepyrouter, the right one works, and it never goes upstream. */
    @Test fun routerTokenIsEnforcedAndNeverForwarded() {
        val h = ChatHarness()
        runBlocking { SettingsStore.setRouterUrl(h.app, "http://127.0.0.1:$port"); SettingsStore.setRouterToken(h.app, "wrong-token") }
        h.vm.send("잘못된 토큰으로", emptyList(), "coding")
        h.awaitReplies(timeoutMs = 30_000)
        assertTrue(h.messages().last().errorText!!, h.messages().last().errorText!!.contains("라우터 인증 실패"))
        assertTrue("refused before any upstream call", synchronized(log) { upstreamBodies.isEmpty() })

        runBlocking { SettingsStore.setRouterToken(h.app, ROUTER_TOKEN) }
        h.vm.send("올바른 토큰으로", emptyList(), "coding")
        h.awaitReplies(timeoutMs = 30_000)
        assertEquals("real answer", h.messages().last().text)
        synchronized(log) {
            assertTrue("upstream gets provider keys only: $upstreamAuth", upstreamAuth.none { it.orEmpty().contains(ROUTER_TOKEN) })
        }
    }

    companion object {
        const val ROUTER_TOKEN = "sr-real-token-0123456789"
    }
}
