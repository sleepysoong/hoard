package com.sleepysoong.hoard.testing

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.engine.MockAiEngine
import com.sleepysoong.hoard.ui.chat.ChatViewModel
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Drives the real chat stack (ChatViewModel → WorkManager → ChatResponseWorker →
 * MockAiEngine → HoardRepository) on Robolectric. Only the clock is faked (engine pace).
 */
class ChatHarness(pace: Float = 0f) {
    val app: Application = ApplicationProvider.getApplicationContext()
    val workManager: WorkManager
    /** Always the live store; after [simulateProcessDeath] this is the fresh instance. */
    val repo: HoardRepository get() = HoardRepository.get()
    private val store = ViewModelStore()
    val vm: ChatViewModel
    private val log = StringBuilder()

    init {
        HoardRepository.resetForTests()
        MockAiEngine.pace = pace
        MockAiEngine.faultInjector = null
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .build()
        )
        workManager = WorkManager.getInstance(app)
        vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[ChatViewModel::class.java]
        idle()
    }

    /** Process killed and restarted: in-memory chats are gone, WorkManager's queue survives. */
    fun simulateProcessDeath() {
        HoardRepository.resetForTests()
        idle()
    }

    /** User leaves the app and the activity is finished: the ViewModel is cleared. */
    fun destroyViewModel() {
        store.clear()
        idle()
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    fun unfinishedWork(): List<WorkInfo> = workManager.getWorkInfos(
        WorkQuery.fromStates(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
    ).get()

    /** Waits until every reply worker has finished and no bubble is still streaming. */
    fun awaitReplies(timeoutMs: Long = 15_000, onTick: () -> Unit = {}) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            idle()
            onTick()
            val streaming = repo.messages.value.values.flatten().any { it.isStreaming }
            if (unfinishedWork().isEmpty() && !streaming) break
            check(System.currentTimeMillis() < deadline) {
                "replies did not settle: work=${unfinishedWork().map { it.state }} streaming=$streaming\n$log"
            }
            Thread.sleep(5)
        }
        idle()
    }

    fun messages(): List<ChatMessage> = vm.uiState.value.messages

    /** Fast-forwards WorkManager backoff so every retried reply runs its next attempt now. */
    fun fireBackoff() {
        val driver = WorkManagerTestInitHelper.getTestDriver(app)!!
        workManager.getWorkInfos(WorkQuery.fromStates(WorkInfo.State.ENQUEUED)).get()
            .forEach { driver.setInitialDelayMet(it.id) }
        idle()
    }

    fun allWork(): List<WorkInfo> = workManager.getWorkInfos(
        WorkQuery.fromStates(*WorkInfo.State.entries.toTypedArray())
    ).get()

    /** Records a labelled snapshot of a session (default: active) into the transcript artifact. */
    fun snapshot(label: String, sessionId: String? = vm.uiState.value.session?.id) {
        val s = sessionId?.let { repo.sessionOf(it) }
        log.append("── $label  [session=${s?.id} model=${s?.modelId} limit=${s?.contextLimit}]\n")
        sessionId?.let { repo.messagesOf(it) }.orEmpty().forEachIndexed { i, m ->
            val flags = buildList {
                if (m.isStreaming) add("streaming")
                if (m.thinking.isNotEmpty()) add("thinking=${m.thinking.size}")
                if (m.totalTokens > 0) add("tok=${m.promptTokens}+${m.completionTokens}")
            }.joinToString(" ")
            log.append("  %2d %-9s %s  %s\n".format(i, m.role, m.text.replace("\n", "⏎").take(110), flags))
        }
    }

    /** Writes build/test-artifacts/<name>.txt so a failing or passing run can be inspected. */
    fun writeTranscript(name: String) {
        val dir = File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts")
        dir.mkdirs()
        File(dir, "$name.txt").writeText(log.toString())
    }
}
