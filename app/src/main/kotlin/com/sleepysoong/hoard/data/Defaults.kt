package com.sleepysoong.hoard.data

/** App defaults (not sample data). */
object Defaults {
    const val SYSTEM_PROMPT = "너는 Hoard라는 친절한 AI 어시스턴트다. 사용자의 언어로 간결하게 답하라."
    const val CONTEXT_LIMIT = 32_000

    /**
     * Older versions seeded a canned welcome session/bubble with this id prefix. It is
     * dropped when such a store is loaded and never sent to a model.
     */
    const val LEGACY_WELCOME_PREFIX = "msg-welcome"
    const val LEGACY_WELCOME_SESSION = "session-welcome"
}
