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
    val routing: RoutingInfo? = null
)

/** Produces a reply for a [ReplyRequest]. Throws on failure (see [RouterException]). */
interface AiEngine {
    suspend fun streamReply(request: ReplyRequest, onEvent: suspend (StreamEvent) -> Unit)
}

/**
 * Engine selection. With a router URL set in Settings, replies come from
 * sleepyrouter; without one the app stays usable offline with the mock engine.
 * Tests can pin an engine via [override].
 */
object Engines {
    @Volatile var override: AiEngine? = null

    fun forRouter(routerUrl: String): AiEngine =
        override ?: if (routerUrl.isBlank()) MockAiEngine else RouterAiEngine(routerUrl)
}
