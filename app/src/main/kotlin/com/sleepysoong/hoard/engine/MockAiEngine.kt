package com.sleepysoong.hoard.engine

import com.sleepysoong.hoard.data.MockData
import com.sleepysoong.hoard.data.ThinkingStep
import com.sleepysoong.hoard.data.estimateTokens

data class MockStreamEvent(
    val thinking: List<ThinkingStep> = emptyList(),
    val deltaText: String = "",
    val done: Boolean = false,
    val elapsedMs: Long = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0
)

/**
 * Single seam for the future real backend: UI + Worker consume this mock stream.
 * Replace [streamReply] with a real network call; everything else stays.
 */
object MockAiEngine {
    /** Multiplier for the fake streaming delays. Tests set 0 to run instantly. */
    @Volatile var pace: Float = 1f

    /** Test hook: called before every streamed event; throw to simulate a network failure. */
    @Volatile var faultInjector: ((question: String, eventIndex: Int) -> Unit)? = null

    private suspend fun delay(ms: Long) = kotlinx.coroutines.delay((ms * pace).toLong())

    suspend fun streamReply(
        question: String,
        modelId: String,
        hasAttachments: Boolean,
        onEvent: suspend (MockStreamEvent) -> Unit
    ) {
        var eventIndex = 0
        val emit: suspend (MockStreamEvent) -> Unit = { ev ->
            faultInjector?.invoke(question, eventIndex++)
            onEvent(ev)
        }
        val started = System.currentTimeMillis()
        val thinking = MockData.mockThinking(question)
        val shown = mutableListOf<ThinkingStep>()
        for (step in thinking) {
            delay(step.durationMs / 3)
            shown += step
            emit(MockStreamEvent(thinking = shown.toList(), elapsedMs = System.currentTimeMillis() - started))
        }
        val full = MockData.mockAnswer(question, modelId, hasAttachments)
        val promptTokens = estimateTokens(question)
        // Word-by-word streaming illusion.
        val words = full.split(" ")
        val chunk = StringBuilder()
        var count = 0
        for (w in words) {
            delay(28)
            chunk.append(w).append(" ")
            count++
            if (count % 4 == 0) {
                emit(
                    MockStreamEvent(
                        thinking = shown.toList(),
                        deltaText = chunk.toString(),
                        elapsedMs = System.currentTimeMillis() - started,
                        promptTokens = promptTokens,
                        completionTokens = estimateTokens(chunk.toString())
                    )
                )
            }
        }
        emit(
            MockStreamEvent(
                thinking = shown.toList(),
                deltaText = full,
                done = true,
                elapsedMs = System.currentTimeMillis() - started,
                promptTokens = promptTokens,
                completionTokens = estimateTokens(full)
            )
        )
    }
}
