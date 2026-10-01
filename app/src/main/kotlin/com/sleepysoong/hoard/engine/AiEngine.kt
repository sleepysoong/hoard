package com.sleepysoong.hoard.engine

import com.sleepysoong.hoard.data.RoutingInfo
import com.sleepysoong.hoard.data.ThinkingStep

/** One streamed update of a reply. Text/thinking are cumulative (full so far). */
data class StreamEvent(
    val thinking: List<ThinkingStep> = emptyList(),
    val deltaText: String = "",
    val done: Boolean = false,
    val elapsedMs: Long = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** Router trace: tried models, why they failed, the model that answered. */
    val routing: RoutingInfo? = null,
    /** Terminal response.incomplete: partial text is not safe as a history replacement. */
    val incomplete: Boolean = false
)

/** Produces a reply for a [ReplyRequest]. Throws on failure (see [RouterException]). */
interface AiEngine {
    suspend fun streamReply(request: ReplyRequest, onEvent: suspend (StreamEvent) -> Unit)
}

/**
 * Engine selection: replies come from sleepyrouter. Without a router URL there is
 * nothing to answer with — [NoRouterEngine] says so (no fake replies).
 * Tests can pin an engine via [override], or only the no-router case via [offline].
 */
object Engines {
    @Volatile var override: AiEngine? = null
    @Volatile var offline: AiEngine? = null

    fun forRouter(
        routerUrl: String,
        attachments: AttachmentEncoder? = null,
        token: String = "",
        tools: com.sleepysoong.hoard.tools.ToolRegistry? = null
    ): AiEngine =
        override ?: if (routerUrl.isBlank()) (offline ?: NoRouterEngine) else RouterAiEngine(routerUrl, attachments = attachments, token = token, tools = tools)

    /** Whether [forRouter] can answer at all (a router is set, or a test engine stands in). */
    fun canAnswer(routerUrl: String): Boolean = override != null || routerUrl.isNotBlank() || offline != null
}

/** No router configured: fail with what to do, never invent an answer. */
object NoRouterEngine : AiEngine {
    override suspend fun streamReply(request: ReplyRequest, onEvent: suspend (StreamEvent) -> Unit) {
        throw RouterException.Permanent("라우터가 연결되지 않았습니다 · 설정 → 라우터에서 sleepyrouter 주소를 입력하세요", null, null)
    }
}
