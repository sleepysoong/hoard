package com.sleepysoong.hoard.testing

import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.ChatSession
import com.sleepysoong.hoard.data.Defaults
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.engine.Engines
import com.sleepysoong.hoard.engine.MockAiEngine

/**
 * Test fixtures. The app ships no sample data, so tests create the conversation
 * they need: one session ("Hoard에 오신 것을 환영합니다") and, for UI tests, a first
 * assistant bubble. Replies without a router come from [MockAiEngine].
 */
object TestData {
    const val SESSION_ID = "session-fixture"
    const val SESSION_NAME = "Hoard에 오신 것을 환영합니다"
    const val MODEL = "test-model"
    const val GREETING = "안녕하세요, Hoard입니다. 이 대화는 테스트용으로 만든 세션입니다."

    fun session(modelId: String = MODEL) = ChatSession(
        id = SESSION_ID, name = SESSION_NAME, systemPrompt = Defaults.SYSTEM_PROMPT,
        modelId = modelId, contextLimit = Defaults.CONTEXT_LIMIT
    )

    /** Seeds the fixture session into the live repository (optionally with a greeting bubble). */
    fun seed(repo: HoardRepository = HoardRepository.get(), greeting: Boolean = false, modelId: String = MODEL) {
        repo.insertSessionForTests(session(modelId), if (greeting) listOf(ChatMessage("msg-greeting", MessageRole.Assistant, GREETING)) else emptyList())
    }

    /** Offline replies come from the test double instead of "router not connected". */
    fun useMockEngine() { Engines.offline = MockAiEngine }

    fun reset() { Engines.offline = null; Engines.override = null }
}
