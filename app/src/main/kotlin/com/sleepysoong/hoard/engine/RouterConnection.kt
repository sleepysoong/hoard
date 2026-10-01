package com.sleepysoong.hoard.engine

import com.sleepysoong.hoard.data.AiModel
import com.sleepysoong.hoard.data.HoardRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Connection state shown in Settings and used to decide the model catalog. */
sealed interface RouterStatus {
    /** No router URL: nothing can answer until one is connected. */
    data object Offline : RouterStatus
    data object Checking : RouterStatus
    data class Connected(val url: String, val groups: Int, val models: Int) : RouterStatus
    data class Failed(val url: String, val reason: String) : RouterStatus
}

/**
 * Talks to the router's `/v1/models`: verifies the URL and replaces the model
 * catalog (picker, Settings default model) with the router's groups and models.
 */
object RouterConnection {
    private val _status = MutableStateFlow<RouterStatus>(RouterStatus.Offline)
    val status: StateFlow<RouterStatus> = _status.asStateFlow()

    /** Only the newest probe may publish; overlapping checks (launch + edits) race otherwise. */
    private val refreshSeq = java.util.concurrent.atomic.AtomicLong(0)

    /** Test hook: build the client for a URL (defaults to the real HTTP client). */
    @Volatile var clientFactory: (url: String, token: String) -> RouterAiEngine =
        { url, token -> RouterAiEngine(url, connectTimeoutMs = 5_000, readTimeoutMs = 10_000, token = token) }

    suspend fun refresh(url: String, repo: HoardRepository = HoardRepository.get(), token: String = ""): RouterStatus {
        val seq = refreshSeq.incrementAndGet()
        com.sleepysoong.hoard.diagnostics.AppLog.i("RouterConnection", "check start url=$url")
        if (url.isBlank()) {
            if (refreshSeq.get() == seq) {
                repo.setRouterModels(emptyList())
                _status.value = RouterStatus.Offline
            }
            return RouterStatus.Offline
        }
        if (refreshSeq.get() == seq) _status.value = RouterStatus.Checking
        val result = try {
            val list = clientFactory(url, token).listModels()
            if (refreshSeq.get() == seq) {
                repo.setRouterModels(list.map(::toAiModel))
                RouterStatus.Connected(url, groups = list.count { it.isGroup }, models = list.count { !it.isGroup })
                    .also { _status.value = it }
            } else RouterStatus.Checking // superseded; state belongs to the newer probe
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Keep the previous catalog: a flaky check shouldn't wipe the picker.
            if (refreshSeq.get() == seq) RouterStatus.Failed(url, e.message ?: "연결 실패").also { _status.value = it }
            else RouterConnection.status.value
        }
        com.sleepysoong.hoard.diagnostics.AppLog.i("RouterConnection",
            "check done url=$url -> $result" + if (refreshSeq.get() != seq) " (superseded)" else "")
        return result
    }

    fun resetForTests() { _status.value = RouterStatus.Offline }

    private fun toAiModel(m: RouterModel) = AiModel(
        id = m.id,
        displayName = m.id,
        vendor = if (m.isGroup) "그룹" else m.owner,
        description = if (m.isGroup) "자동 라우팅 · 실패하면 다음 모델로" else "${m.owner} 모델 직접 호출",
        supportsVision = true,
        supportsThinking = true
    )
}
