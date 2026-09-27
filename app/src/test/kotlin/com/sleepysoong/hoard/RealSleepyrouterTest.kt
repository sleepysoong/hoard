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

    @Before fun start() {
        val bin = System.getenv("SLEEPYROUTER_BIN")
        assumeTrue("SLEEPYROUTER_BIN not set — skipping real-binary test", !bin.isNullOrBlank() && File(bin).canExecute())

        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/responses") { ex ->
                val body = ex.requestBody.readBytes().decodeToString()
                val model = Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                synchronized(log) { log.append("upstream got model=$model stream=${body.contains("\"stream\":true")}\n") }
                when (model) {
                    "a" -> reply(ex, 429, "application/json", """{"error":{"type":"rate_limit_error","code":"rate_limited","message":"Rate limit reached for a","param":null}}""")
                    "b" -> reply(ex, 503, "text/html", "<html>down</html>")
                    else -> {
                        ex.responseHeaders.add("Content-Type", "text/event-stream")
                        ex.sendResponseHeaders(200, 0)
                        val frames = listOf(
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
            writeText("P1_KEY=k1-secret-value\nP2_KEY=k2-secret-value\n")
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
        runBlocking { SettingsStore.setRouterUrl(h.app, "http://127.0.0.1:$port") }
        h.vm.send("real router, please", emptyList(), "coding")
        h.awaitReplies(timeoutMs = 30_000)
        h.snapshot("after reply")
        log.append(h.let { val sb = StringBuilder(); it.messages().forEach { m -> sb.append("${m.role}: ${m.text} | model=${m.modelId} | error=${m.errorText}\n") }; sb })

        val r = h.messages().last()
        assertEquals(MessageRole.Assistant, r.role)
        assertEquals("real answer", r.text)
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
        runBlocking { SettingsStore.setRouterUrl(h.app, "http://127.0.0.1:$port") }
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

    @Test fun modelListFromRealRouter() {
        val models = runBlocking { RouterAiEngine("http://127.0.0.1:$port").listModels() }
        log.append("models=$models\n")
        assertEquals(listOf("coding", "broken"), models.filter { it.isGroup }.map { it.id })
        assertTrue(models.any { it.id == "p2/c" && !it.isGroup })
    }
}
