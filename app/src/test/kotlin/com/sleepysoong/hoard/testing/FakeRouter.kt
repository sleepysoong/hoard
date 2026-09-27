package com.sleepysoong.hoard.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Local stand-in for sleepyrouter's `/hoard/v1/responses`, speaking its exact wire
 * format (OpenAI Responses SSE + `sleepyrouter.routing` event, JSON error envelope
 * + `sleepyrouter.routing`). Each request pops the next scripted [Reply].
 */
class FakeRouter : AutoCloseable {
    sealed interface Reply {
        /** SSE frames (event name, JSON data). [cut] = drop the connection after them. */
        data class Sse(val frames: List<Pair<String, String>>, val cut: Boolean = false, val stallMs: Long = 0) : Reply
        data class Json(val status: Int, val body: String) : Reply
    }

    data class Recorded(val method: String, val path: String, val body: String, val authorization: String? = null)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val script = Collections.synchronizedList(mutableListOf<Reply>())
    val requests: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())
    val url: String get() = "http://127.0.0.1:${server.address.port}"
    /** When set, every request must carry `Authorization: Bearer <requiredToken>` (like auth_token_env). */
    @Volatile var requiredToken: String? = null
    /** Reply for GET /v1/models. */
    var modelsBody = """{"object":"list","data":[{"id":"coding","object":"model"},{"id":"zen/a","object":"model"}]}"""

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    fun enqueue(vararg replies: Reply) { script += replies }

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().decodeToString()
        val auth = ex.requestHeaders.getFirst("Authorization")
        requests += Recorded(ex.requestMethod, ex.requestURI.path, body, auth)
        requiredToken?.let { want ->
            if (auth != "Bearer $want") {
                return send(ex, Reply.Json(401, """{"error":{"message":"missing or invalid sleepyrouter token","type":"upstream_error","param":null,"code":"invalid_api_key"}}"""))
            }
        }
        if (ex.requestMethod == "GET" && ex.requestURI.path == "/v1/models") {
            return send(ex, Reply.Json(200, modelsBody))
        }
        val next = synchronized(script) { if (script.isEmpty()) null else script.removeAt(0) }
            ?: Reply.Json(500, """{"error":{"message":"fake router: no scripted reply","type":"upstream_error","code":"x"}}""")
        send(ex, next)
    }

    private fun send(ex: HttpExchange, r: Reply) {
        when (r) {
            is Reply.Json -> {
                val b = r.body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(r.status, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            is Reply.Sse -> {
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, 0)
                val out = ex.responseBody
                for ((name, data) in r.frames) {
                    out.write("event: $name\ndata: $data\n\n".toByteArray())
                    out.flush()
                }
                if (r.stallMs > 0) {
                    // A thinking model: keep-alives only, until the client leaves.
                    val until = System.currentTimeMillis() + r.stallMs
                    try {
                        while (System.currentTimeMillis() < until) { Thread.sleep(100); out.write(": ping\n\n".toByteArray()); out.flush() }
                    } catch (_: Exception) { return }
                }
                if (r.cut) {
                    // Abort without the terminating chunk: the client sees a broken stream.
                    ex.close()
                    return
                }
                out.close()
            }
        }
    }

    override fun close() = server.stop(0)

    companion object {
        /** The trace sleepyrouter sends: zen/a 429 → gemini/d skipped → openrouter/c selected. */
        fun trace(selectedOutcome: String = "streaming", selected: String? = "openrouter/c") = """
            {"requested_model":"coding","route_reason":"model-group",
             "candidates":["zen/a","gemini/d","openrouter/c"],
             ${if (selected != null) "\"selected_model\":\"$selected\",\"selected_provider\":\"openrouter\"," else ""}
             "attempts":[
              {"index":1,"model":"zen/a","provider":"zen","upstream_model":"a","outcome":"failed","error_class":"rate_limit","status_code":429,"reason":"rate_limit_error: rate_limited: slow down","failed_over":true,"duration_ms":12},
              {"index":2,"model":"gemini/d","provider":"gemini","upstream_model":"d","outcome":"skipped","error_class":"unknown","reason":"missing_api_key: API key missing for provider gemini","duration_ms":0}
              ${if (selected != null) ",{\"index\":3,\"model\":\"$selected\",\"provider\":\"openrouter\",\"upstream_model\":\"c\",\"outcome\":\"$selectedOutcome\",\"duration_ms\":0}" else ""}
             ]}""".replace("\n", "").replace(Regex(" {2,}"), "")

        fun routingFrame(t: String = trace()) = "sleepyrouter.routing" to """{"routing":$t,"type":"sleepyrouter.routing"}"""

        fun created(model: String = "coding") =
            "response.created" to """{"type":"response.created","sequence_number":0,"response":{"id":"resp_1","object":"response","model":"$model","status":"in_progress","output":[]}}"""

        fun delta(text: String, seq: Int) =
            "response.output_text.delta" to """{"type":"response.output_text.delta","sequence_number":$seq,"item_id":"msg_1","output_index":0,"content_index":0,"delta":${quote(text)}}"""

        fun completed(text: String, inTok: Int = 11, outTok: Int = 22, status: String = "completed", type: String = "response.completed", extra: String = "") =
            type to """{"type":"$type","sequence_number":99,"response":{"id":"resp_1","object":"response","model":"coding","status":"$status"$extra,"output":[{"type":"message","id":"msg_1","role":"assistant","status":"completed","content":[{"type":"output_text","text":${quote(text)},"annotations":[]}]}],"usage":{"input_tokens":$inTok,"output_tokens":$outTok,"total_tokens":${inTok + outTok}}}}"""

        fun reasoningDelta(text: String, seq: Int) =
            "response.reasoning_text.delta" to """{"type":"response.reasoning_text.delta","sequence_number":$seq,"item_id":"rs_1","output_index":0,"content_index":0,"delta":${quote(text)}}"""

        /** Gateway error after commit (e.g. a reasoning-only reply), carrying the trace. */
        fun postCommitError(message: String) =
            "error" to """{"type":"error","sequence_number":98,"message":${quote(message)},"sleepyrouter":{"routing":${trace(selectedOutcome = "failed")}}}"""

        fun quote(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()

        /** All-candidates-failed JSON error, as sleepyrouter sends it before any stream commit. */
        fun allFailed() = Reply.Json(
            502,
            """{"error":{"message":"All configured candidates failed","type":"upstream_error","param":null,"code":"all_candidates_failed"},"sleepyrouter":{"routing":${trace(selected = null)}}}"""
        )
    }
}
