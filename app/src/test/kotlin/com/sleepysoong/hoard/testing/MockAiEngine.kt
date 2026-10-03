package com.sleepysoong.hoard.engine

import com.sleepysoong.hoard.data.StepKind
import com.sleepysoong.hoard.data.ThinkingStep
import com.sleepysoong.hoard.data.estimateTokens

/**
 * Test double only (not in the app): fake thinking and word-by-word streaming,
 * installed via [Engines.offline] / [Engines.override]. The app's backend is [RouterAiEngine].
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
        val thinking = mockThinking(question, request.history.size, request.droppedCount)
        val shown = mutableListOf<ThinkingStep>()
        for (step in thinking) {
            delay(step.durationMs / 3)
            shown += step
            emit(StreamEvent(thinking = shown.toList(), elapsedMs = System.currentTimeMillis() - started))
        }
        val full = mockAnswer(question, request.modelId, request.hasAttachments)
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

    fun mockThinking(question: String, contextMessages: Int, droppedMessages: Int): List<ThinkingStep> = listOf(
        ThinkingStep("요청 파악", "분석: ${question.take(64)}…", 320),
        ThinkingStep(
            "컨텍스트 조회",
            "이전 메시지 ${contextMessages - 1}개 참고" +
                (if (droppedMessages > 0) ", 컨텍스트 한도로 오래된 메시지 ${droppedMessages}개 생략" else "") + ".",
            480
            // Not StepKind.Tool: the mock never executed a tool.
        ),
        ThinkingStep("답변 작성", "간결한 답변 구성.", 610)
    )

    fun mockAnswer(question: String, modelId: String, hasAttachments: Boolean): String {
        val attachNote = if (hasAttachments) "\n\n첨부파일도 확인했습니다." else ""
        val base = if (question.length < 24) "짧은 답변 ($modelId): \"$question\" — 알겠습니다. 핵심만 먼저 정리했습니다."
        else "**$modelId**의 답변: \"${question.take(120)}${if (question.length > 120) "…" else ""}\" 내용을 파악했습니다. " +
            "여러 단어로 나뉘어 스트리밍되도록 충분히 긴 문장을 이어서 씁니다. 하나 둘 셋 넷 다섯 여섯 일곱 여덟."
        return base + attachNote
    }
}
