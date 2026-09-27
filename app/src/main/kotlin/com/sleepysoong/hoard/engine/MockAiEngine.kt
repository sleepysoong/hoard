package com.sleepysoong.hoard.engine

import com.sleepysoong.hoard.data.MockData
import com.sleepysoong.hoard.data.ThinkingStep
import com.sleepysoong.hoard.data.estimateTokens

/**
 * Offline engine (no router configured) and test double: fake thinking and
 * word-by-word streaming. The real backend is [RouterAiEngine].
 */
object MockAiEngine : AiEngine {
    /** Multiplier for the fake streaming delays. Tests set 0 to run instantly. */
    @Volatile var pace: Float = 1f

    /** Test hook: called before every streamed event; throw to simulate a network failure. */
    @Volatile var testHook: ((request: ReplyRequest, eventIndex: Int) -> Unit)? = null

    private suspend fun delay(ms: Long) = kotlinx.coroutines.delay((ms * pace).toLong())

    override suspend fun streamReply(
        request: ReplyRequest,
        onEvent: suspend (StreamEvent) -> Unit
    ) {
        val question = request.question
        var eventIndex = 0
        val emit: suspend (StreamEvent) -> Unit = { ev ->
            testHook?.invoke(request, eventIndex++)
            onEvent(ev)
        }
        val started = System.currentTimeMillis()
        val thinking = MockData.mockThinking(question, request.history.size, request.droppedCount, request.tools)
        val shown = mutableListOf<ThinkingStep>()
        for (step in thinking) {
            delay(step.durationMs / 3)
            shown += step
            emit(StreamEvent(thinking = shown.toList(), elapsedMs = System.currentTimeMillis() - started))
        }
        val full = MockData.mockAnswer(question, request.modelId, request.hasAttachments)
        val promptTokens = request.promptTokens
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
                    StreamEvent(
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
            StreamEvent(
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
