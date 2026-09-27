package com.sleepysoong.hoard.engine

import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.RouteAttempt
import com.sleepysoong.hoard.data.RoutingInfo
import com.sleepysoong.hoard.data.StepKind
import com.sleepysoong.hoard.data.ThinkingStep
import com.sleepysoong.hoard.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Real backend: sleepyrouter's `POST /hoard/v1/responses` — the OpenAI Responses
 * API plus a routing trace (`sleepyrouter.routing`: tried models, failure
 * reasons, selected model). Streams SSE, maps it onto [StreamEvent].
 *
 * Errors are split so the worker can decide what to retry:
 *  - [RouterException.Transient]: network/5xx/timeouts/stream cut → worth retrying
 *  - [RouterException.Permanent]: 4xx, all candidates failed for a client reason → don't
 * Both carry the routing trace when the router sent one.
 */
@OptIn(InternalCoroutinesApi::class) // Job.invokeOnCompletion(onCancelling = true)
class RouterAiEngine(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 10_000,
    /** Max silence between SSE events (mirrors sleepyrouter's stream_idle). */
    private val readTimeoutMs: Int = 330_000,
    /** Reads attachment bytes (ContentResolver in the app). Null = describe attachments only. */
    private val attachments: AttachmentEncoder? = null,
    /** Inbound token for a router with auth_token_env set (sent as a Bearer token). */
    private val token: String = "",
    /** Function tools run on the device (web_search, web_fetch). Null/empty = plain chat. */
    private val tools: ToolRegistry? = null,
    private val maxToolRounds: Int = MAX_TOOL_ROUNDS
) : AiEngine {

    override suspend fun streamReply(request: ReplyRequest, onEvent: suspend (StreamEvent) -> Unit) =
        withContext(Dispatchers.IO) { stream(request, onEvent) }

    /**
     * The tool loop. Each round is one `/hoard/v1/responses` stream. When the model
     * answers with function calls, they run here, their outputs are appended as
     * `function_call` + `function_call_output` input items and the conversation is
     * sent again — until the model answers in text. The last allowed round sends
     * `tool_choice: "none"` so the loop always ends with an answer.
     * Reasoning, tool steps and any text from earlier rounds stay in the reply.
     */
    private suspend fun stream(request: ReplyRequest, onEvent: suspend (StreamEvent) -> Unit) {
        val started = System.currentTimeMillis()
        val active = tools?.takeUnless { it.isEmpty }
        val prior = Prior()
        val toolItems = mutableListOf<JsonObject>()
        var round = 0
        while (true) {
            val finalRound = active == null || round >= maxToolRounds
            val body = encodeRequest(request, attachments, if (active != null) active.schemas() else null, toolItems, forbidTools = active != null && finalRound)
            val state = streamRound(request, body, prior, started, acceptTools = !finalRound, onEvent)
            if (state.toolCalls.isEmpty()) return
            prior.absorb(state)
            for (call in state.toolCalls) {
                val t0 = System.currentTimeMillis()
                onEvent(prior.event(request, started, running = runCatching { active!!.label(call.name, call.arguments) }.getOrDefault(call.name)))
                val outcome = active!!.execute(call.name, call.arguments)
                prior.thinking += ThinkingStep(outcome.label, outcome.summary, System.currentTimeMillis() - t0, StepKind.Tool, failed = outcome.isError)
                toolItems += buildJsonObject {
                    put("type", "function_call")
                    put("call_id", call.callId)
                    put("name", call.name)
                    put("arguments", call.arguments)
                }
                toolItems += buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", call.callId)
                    put("output", outcome.output)
                }
                onEvent(prior.event(request, started))
            }
            round++
        }
    }

    /** One streamed request. Returns the finished round (text answer or tool calls). */
    private suspend fun streamRound(
        request: ReplyRequest,
        body: String,
        prior: Prior,
        started: Long,
        acceptTools: Boolean,
        onEvent: suspend (StreamEvent) -> Unit
    ): StreamState {
        val conn = openConnection(endpoint(baseUrl, "responses"))
        // Blocking socket reads don't observe coroutine cancellation (and SSE keep-alives
        // never surface as events), so a cancelled reply would keep the router — and the
        // upstream model — generating until the read timeout. Close the socket from outside
        // the moment this coroutine is cancelled; the blocked read then fails immediately.
        val job = currentCoroutineContext()[Job]
        val watcher = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) runCatching { conn.disconnect() }
        }
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            // Connecting happens lazily on the first write, so unreachable routers
            // (refused, DNS, no route) surface here, not at responseCode.
            // Stream the body: without a streaming mode HttpURLConnection buffers the whole
            // request (base64 attachments included) in memory, on top of the String and its
            // byte[] copy — ~5× the payload at once.
            conn.setChunkedStreamingMode(64 * 1024)
            val status = try {
                conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
                conn.responseCode
            } catch (e: SocketTimeoutException) {
                throw RouterException.Transient("라우터 응답 시간 초과", null, e)
            } catch (e: IOException) {
                throw RouterException.Transient(describeConnectError(e), null, e)
            }
            val contentType = conn.contentType.orEmpty()
            if (status !in 200..299 || !contentType.startsWith("text/event-stream")) {
                val errBody = (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw errorFromJsonBody(status, errBody)
            }

            val state = StreamState(request, prior, acceptTools)
            conn.inputStream.bufferedReader().use { reader ->
                readSse(reader) { name, data ->
                    currentCoroutineContext().ensureActive()
                    val ev = state.consume(name, data, System.currentTimeMillis() - started)
                    if (ev != null) onEvent(ev)
                    state.done
                }
            }
            if (!state.done) {
                throw RouterException.Transient("응답 스트림이 완료 전에 끊겼습니다", state.routing, null)
            }
            return state
        } catch (e: SocketTimeoutException) {
            currentCoroutineContext().ensureActive()
            throw RouterException.Transient("응답이 멈췄습니다 (시간 초과)", null, e)
        } catch (e: IOException) {
            // A socket closed by the watcher surfaces as an IOException: report the
            // cancellation, not a network error (which would schedule a retry).
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            watcher?.dispose()
            conn.disconnect()
        }
    }

    /** Health + model list for Settings: `GET /v1/models` (groups first, then models). */
    suspend fun listModels(): List<RouterModel> = withContext(Dispatchers.IO) {
        val conn = openConnection(endpoint(baseUrl, "models", hoard = false))
        try {
            conn.requestMethod = "GET"
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw errorFromJsonBody(status, body)
            parseModelList(body)
        } catch (e: IOException) {
            throw RouterException.Transient("라우터에 연결할 수 없습니다 (${e.message})", null, e)
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = "POST"
            useCaches = false
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }

    /** Stateful mapping of the Responses SSE stream to UI events. */
    private class StreamState(val request: ReplyRequest, val prior: Prior, val acceptTools: Boolean) {
        val text = StringBuilder()
        var reasoning = StringBuilder()
        /** When the answer started; the reasoning step's duration stops there. */
        var reasoningMs: Long? = null
        var routing: RoutingInfo? = null
        var promptTokens = 0
        var completionTokens = 0
        var done = false
        var responseModel: String? = null
        /** Function calls of a finished round (only when [acceptTools]). */
        var toolCalls: List<ToolCall> = emptyList()

        fun consume(name: String?, data: String, elapsed: Long): StreamEvent? {
            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
            val type = name ?: obj.str("type")
            when (type) {
                "sleepyrouter.routing" -> {
                    routing = parseRouting(obj["routing"])
                    return event(elapsed)
                }
                "response.created", "response.in_progress" -> {
                    responseModel = obj.obj("response")?.str("model") ?: responseModel
                    return null
                }
                "response.output_text.delta" -> {
                    if (reasoningMs == null && reasoning.isNotEmpty()) reasoningMs = elapsed
                    text.append(obj.str("delta").orEmpty())
                    return event(elapsed)
                }
                "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                    reasoning.append(obj.str("delta").orEmpty())
                    return event(elapsed)
                }
                "response.completed" -> {
                    val resp = obj.obj("response")
                    readUsage(resp)
                    // Authoritative final text (covers providers that skip deltas).
                    val full = resp?.let(::outputText)
                    if (!full.isNullOrEmpty()) { text.setLength(0); text.append(full) }
                    if (acceptTools) toolCalls = resp?.let(::functionCalls).orEmpty()
                    // A model that only reasoned and never answered: retryable, not an empty success.
                    if (text.isEmpty() && toolCalls.isEmpty() && prior.text.isEmpty()) {
                        throw RouterException.Transient(EMPTY_REPLY, routing?.withSelectedOutcome("failed"), null)
                    }
                    routing = routing?.withSelectedOutcome("succeeded")
                    done = true
                    return event(elapsed, done = toolCalls.isEmpty())
                }
                "response.incomplete" -> {
                    val resp = obj.obj("response")
                    readUsage(resp)
                    val why = resp?.obj("incomplete_details")?.str("reason") ?: "unknown"
                    val full = resp?.let(::outputText)
                    if (!full.isNullOrEmpty()) { text.setLength(0); text.append(full) }
                    if (text.isEmpty()) throw RouterException.Permanent("응답이 완료되지 않았습니다 ($why)", routing, null)
                    text.append("\n\n(응답이 잘렸습니다: $why)")
                    routing = routing?.withSelectedOutcome("incomplete")
                    done = true
                    return event(elapsed, done = true)
                }
                "response.failed" -> {
                    val err = obj.obj("response")?.obj("error")
                    throw RouterException.Transient(
                        "모델이 응답을 실패했습니다" + (err?.str("message")?.let { ": $it" } ?: ""),
                        routing, null
                    )
                }
                "error" -> {
                    // Gateway error after commit: carries the trace with the selected model failed.
                    obj.obj("sleepyrouter")?.let { routing = parseRouting(it["routing"]) ?: routing }
                    val msg = obj.str("message")
                    throw RouterException.Transient(if (msg == "upstream returned empty response") EMPTY_REPLY else msg ?: "라우터 오류", routing, null)
                }
            }
            return null
        }

        private fun readUsage(resp: JsonObject?) {
            val u = resp?.obj("usage") ?: return
            promptTokens = u.int("input_tokens") ?: promptTokens
            completionTokens = u.int("output_tokens") ?: completionTokens
        }

        val reasoningStep: ThinkingStep? get() =
            if (reasoning.isEmpty()) null else ThinkingStep("모델 추론", reasoning.toString(), (reasoningMs ?: lastElapsed) - prior.roundStartMs)

        private var lastElapsed = 0L

        val ownCompletionTokens: Int get() =
            if (completionTokens > 0) completionTokens else com.sleepysoong.hoard.data.estimateTokens(text.toString())

        private fun event(elapsed: Long, done: Boolean = false): StreamEvent {
            lastElapsed = elapsed
            return StreamEvent(
                thinking = prior.thinking + listOfNotNull(reasoningStep),
                deltaText = prior.joinText(text.toString()),
                done = done,
                elapsedMs = elapsed,
                promptTokens = if (promptTokens > 0) promptTokens else prior.promptTokens.takeIf { it > 0 } ?: request.promptTokens,
                completionTokens = prior.completionTokens + ownCompletionTokens,
                routing = routing ?: prior.routing
            )
        }
    }

    /** What earlier tool rounds of this reply produced. */
    private class Prior {
        val thinking = mutableListOf<ThinkingStep>()
        var text = ""
        var promptTokens = 0
        var completionTokens = 0
        var routing: RoutingInfo? = null
        /** Elapsed ms when the current round started (reasoning durations are per round). */
        var roundStartMs = 0L

        fun joinText(current: String) = when {
            text.isEmpty() -> current
            current.isEmpty() -> text
            else -> text + "\n\n" + current
        }

        fun absorb(s: StreamState) {
            s.reasoningStep?.let { thinking += it }
            text = joinText(s.text.toString())
            if (s.promptTokens > 0) promptTokens = s.promptTokens
            completionTokens += s.ownCompletionTokens
            routing = s.routing ?: routing
        }

        fun event(request: ReplyRequest, started: Long, running: String? = null): StreamEvent {
            val elapsed = System.currentTimeMillis() - started
            roundStartMs = elapsed
            return StreamEvent(
                thinking = thinking + listOfNotNull(running?.let { ThinkingStep(it, "실행 중…", 0, StepKind.Tool, running = true) }),
                deltaText = text,
                done = false,
                elapsedMs = elapsed,
                promptTokens = promptTokens.takeIf { it > 0 } ?: request.promptTokens,
                completionTokens = completionTokens,
                routing = routing
            )
        }
    }

    /** A model's function call from a finished round. */
    data class ToolCall(val callId: String, val name: String, val arguments: String)

    companion object {
        internal val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * What the user typed → a base URL. A bare `host:port` (no scheme) means plain
         * HTTP, sleepyrouter's default; explicit http/https is kept.
         */
        fun normalizeBaseUrl(input: String): String {
            val t = input.trim().trimEnd('/')
            if (t.isEmpty()) return ""
            return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) t else "http://$t"
        }

        /**
         * Human explanation of a connection failure. Java's own message
         * ("Failed to connect to host/1.2.3.4:4567") looks like a mangled URL — the
         * part after "/" is just the resolved IP — so say what actually went wrong.
         */
        fun describeConnectError(e: IOException): String {
            val raw = e.message.orEmpty()
            val target = Regex("to ([^/\\s]+)/[0-9a-fA-F.:]+:(\\d+)").find(raw)?.let { "${it.groupValues[1]}:${it.groupValues[2]}" }
            return when (e) {
                is java.net.UnknownHostException -> "주소를 찾을 수 없습니다 (${e.message}) · 호스트 이름을 확인하세요"
                is java.net.ConnectException, is java.net.NoRouteToHostException ->
                    "라우터에 연결할 수 없습니다" + (target?.let { " ($it)" } ?: "") +
                        " · 서버가 켜져 있고 외부 접속을 받는지 확인하세요 (sleepyrouter [server] host = \"0.0.0.0\", 방화벽·포트)"
                is SocketTimeoutException -> "라우터가 응답하지 않습니다" + (target?.let { " ($it)" } ?: "") + " · 방화벽이나 포트를 확인하세요"
                is javax.net.ssl.SSLException -> "HTTPS 연결 실패 (${e.message}) · sleepyrouter는 기본이 http:// 입니다"
                else -> "라우터에 연결할 수 없습니다 ($raw)"
            }
        }

        /** `http://host:4567` (or with trailing `/`, `/hoard/v1`) → `…/hoard/v1/<path>`. */
        fun endpoint(base: String, path: String, hoard: Boolean = true): String {
            var b = normalizeBaseUrl(base)
            b = b.removeSuffix("/hoard/v1").removeSuffix("/v1")
            return if (hoard) "$b/hoard/v1/$path" else "$b/v1/$path"
        }

        /** Tool rounds per reply before the model is made to answer (`tool_choice: none`). */
        const val MAX_TOOL_ROUNDS = 8

        /**
         * OpenAI Responses request body built from the Hoard conversation.
         * With [toolSchemas]: the `tools` array, a short usage note in `instructions`,
         * and [toolItems] (earlier rounds' function_call / function_call_output) after
         * the history. [forbidTools] = final round: `tool_choice: "none"`.
         */
        fun encodeRequest(
            r: ReplyRequest,
            attachments: AttachmentEncoder? = null,
            toolSchemas: List<JsonObject>? = null,
            toolItems: List<JsonObject> = emptyList(),
            forbidTools: Boolean = false,
            today: java.time.LocalDate = java.time.LocalDate.now()
        ): String = buildJsonObject {
            put("model", r.modelId)
            put("stream", true)
            val note = if (toolSchemas.isNullOrEmpty()) "" else toolNote(toolSchemas, today)
            val instructions = listOf(r.systemPrompt.trim(), note).filter { it.isNotEmpty() }.joinToString("\n\n")
            if (instructions.isNotEmpty()) put("instructions", instructions)
            if (!toolSchemas.isNullOrEmpty()) {
                put("tools", JsonArray(toolSchemas))
                if (forbidTools) put("tool_choice", "none")
            }
            // Only the newest user message ships file bytes; earlier turns (already
            // answered) mention their attachments by name to keep requests small.
            val lastUser = r.history.indexOfLast { it.role == MessageRole.User }
            put("input", buildJsonArray {
                r.history.forEachIndexed { i, m -> add(encodeMessage(m, if (i == lastUser) attachments else null)) }
                toolItems.forEach { add(it) }
            })
        }.toString()

        private fun encodeMessage(m: ChatMessage, files: AttachmentEncoder?): JsonObject = buildJsonObject {
            val assistant = m.role == MessageRole.Assistant
            // Explicit item type: typed decoders (the official SDKs, which sleepyrouter uses)
            // silently drop the *entire* input array when a message item has no "type".
            put("type", "message")
            put("role", if (assistant) "assistant" else if (m.role == MessageRole.System) "system" else "user")
            put("content", buildJsonArray {
                var text = m.text
                val attached = !assistant && m.attachments.isNotEmpty()
                if (attached && files == null) {
                    text += "\n\n[첨부: " + m.attachments.joinToString { "${it.name} (${it.mime})" } + "]"
                }
                if (text.isNotEmpty() || !attached) {
                    add(buildJsonObject {
                        put("type", if (assistant) "output_text" else "input_text")
                        put("text", text)
                    })
                }
                if (attached && files != null) m.attachments.forEach { add(files.encode(it)) }
            })
        }

        /** Concatenated output_text of a Responses object. */
        const val EMPTY_REPLY = "모델이 빈 응답을 보냈습니다 (추론만 하고 답을 쓰지 않음)"

        fun hasToolCall(resp: JsonObject): Boolean = functionCalls(resp).isNotEmpty()

        /** `function_call` items of a completed Responses object. */
        fun functionCalls(resp: JsonObject): List<ToolCall> =
            (resp["output"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { it.str("type") == "function_call" }
                .mapNotNull { o ->
                    val name = o.str("name") ?: return@mapNotNull null
                    val callId = o.str("call_id") ?: o.str("id") ?: return@mapNotNull null
                    ToolCall(callId, name, o.str("arguments").orEmpty().ifBlank { "{}" })
                }

        private fun toolNote(schemas: List<JsonObject>, today: java.time.LocalDate): String {
            val names = schemas.mapNotNull { it.str("name") }
            val lines = mutableListOf("Today's date: $today.")
            if ("web_search" in names) lines += "Use web_search for current or unfamiliar facts. Its results are snippets only, not page contents."
            if ("web_fetch" in names) lines += "Use web_fetch to read a page before relying on what it says. Cite the URLs you used."
            return lines.joinToString(" ")
        }

        fun outputText(resp: JsonObject): String =
            (resp["output"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { it.str("type") == "message" }
                .flatMap { (it["content"] as? JsonArray).orEmpty() }
                .mapNotNull { it as? JsonObject }
                .filter { it.str("type") == "output_text" }
                .joinToString("") { it.str("text").orEmpty() }

        fun parseRouting(el: JsonElement?): RoutingInfo? {
            val o = el as? JsonObject ?: return null
            return RoutingInfo(
                requestedModel = o.str("requested_model").orEmpty(),
                routeReason = o.str("route_reason").orEmpty(),
                candidates = (o["candidates"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                selectedModel = o.str("selected_model"),
                selectedProvider = o.str("selected_provider"),
                attempts = (o["attempts"] as? JsonArray).orEmpty().mapNotNull { a ->
                    val x = a as? JsonObject ?: return@mapNotNull null
                    RouteAttempt(
                        index = x.int("index") ?: 0,
                        model = x.str("model").orEmpty(),
                        provider = x.str("provider").orEmpty(),
                        upstreamModel = x.str("upstream_model"),
                        outcome = x.str("outcome").orEmpty(),
                        errorClass = x.str("error_class"),
                        statusCode = x.int("status_code"),
                        reason = x.str("reason"),
                        failedOver = (x["failed_over"] as? JsonPrimitive)?.booleanOrNull,
                        durationMs = (x["duration_ms"] as? JsonPrimitive)?.longOrNull ?: 0
                    )
                }
            )
        }

        /** Non-SSE reply: OpenAI error envelope (+ trace on the Hoard endpoint). */
        fun errorFromJsonBody(status: Int, body: String): RouterException {
            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            val routing = obj?.obj("sleepyrouter")?.let { parseRouting(it["routing"]) }
            val err = obj?.obj("error")
            val msg = err?.str("message") ?: body.take(200).ifBlank { "HTTP $status" }
            val code = err?.str("code")
            if (status == 401) {
                return RouterException.Permanent("라우터 인증 실패 · 설정 → 라우터의 토큰을 확인하세요", routing, status)
            }
            val text = when (code) {
                "all_candidates_failed" -> "모든 모델이 실패했습니다"
                "no_usable_candidates" -> "사용 가능한 모델이 없습니다 (API 키 확인)"
                "model_not_found" -> "라우터에 없는 모델입니다"
                else -> msg
            }
            // 4xx = the request itself is wrong (retrying won't help); 5xx/other = transient —
            // unless every candidate failed for a reason time can't fix (bad/missing keys),
            // where retrying just replays the same failures against every provider.
            val hopeless = routing != null && routing.failures.isNotEmpty() &&
                routing.failures.all { it.outcome == "skipped" || it.errorClass == "auth" }
            return if (status in 400..499 || hopeless) RouterException.Permanent(
                if (hopeless) "$text (API 키 확인 필요)" else text, routing, status
            )
            else RouterException.Transient(text, routing, null, status)
        }

        fun parseModelList(body: String): List<RouterModel> {
            val data = runCatching { json.parseToJsonElement(body).jsonObject["data"]?.jsonArray }.getOrNull().orEmpty()
            return data.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                // sleepyrouter marks groups with owned_by = "sleepyrouter"; models carry their provider.
                RouterModel(id, owner = o.str("owned_by").orEmpty())
            }
        }

        /** Minimal SSE reader: `event:` / `data:` lines, blank line ends an event. */
        suspend fun readSse(reader: BufferedReader, onEvent: suspend (name: String?, data: String) -> Boolean) {
            var name: String? = null
            val data = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) {
                            val stop = onEvent(name, data.toString())
                            if (stop) return
                        }
                        name = null; data.setLength(0)
                    }
                    line.startsWith(":") -> Unit // comment / keep-alive
                    line.startsWith("event:") -> name = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").removePrefix(" "))
                    }
                }
            }
            if (data.isNotEmpty()) onEvent(name, data.toString())
        }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    }
}

/** An entry of the router's `/v1/models`: a routing group or a concrete model. */
data class RouterModel(val id: String, val owner: String) {
    val isGroup: Boolean get() = owner == "sleepyrouter"
}

sealed class RouterException(message: String, val routing: RoutingInfo?, cause: Throwable?) : IOException(message, cause) {
    /** Worth retrying: network, 5xx, timeouts, stream cut, model failure after commit. */
    class Transient(message: String, routing: RoutingInfo?, cause: Throwable?, val status: Int? = null) :
        RouterException(message, routing, cause)

    /** The request itself is rejected (4xx, incomplete with no output): retrying won't help. */
    class Permanent(message: String, routing: RoutingInfo?, val status: Int?) :
        RouterException(message, routing, null)
}
