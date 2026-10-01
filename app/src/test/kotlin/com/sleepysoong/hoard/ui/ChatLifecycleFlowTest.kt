package com.sleepysoong.hoard.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.sleepysoong.hoard.MainActivity
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.engine.RouterConnection
import com.sleepysoong.hoard.engine.RouterStatus
import com.sleepysoong.hoard.testing.FakeRouter
import com.sleepysoong.hoard.testing.TestData
import com.sleepysoong.hoard.ui.chat.ChatViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real menu/composer → ViewModel → WorkManager → HTTP → store.
 * Covers target loss after menu dismissal, inactive-session deletion/rename,
 * overlapping regeneration, stop before first delta/while network queued,
 * and disconnected sends via both the composer and direct VM entry points.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ChatLifecycleFlowTest {
    private val router = FakeRouter()
    private lateinit var app: Application
    private var releaseNetwork = true
    private val repo get() = HoardRepository.get()
    private val vm get() = ViewModelProvider(compose.activity)[ChatViewModel::class.java]
    @get:Rule(order = 0) val reset = object : ExternalResource() {
        override fun before() {
            app = ApplicationProvider.getApplicationContext()
            HoardRepository.resetForTests()
            RouterConnection.resetForTests()
            TestData.reset()
            TestData.seed(modelId = "coding")
            repo.appendMessage(TestData.SESSION_ID, ChatMessage("old-user", MessageRole.User, "원래 질문"))
            repo.appendMessage(TestData.SESSION_ID, ChatMessage("old-reply", MessageRole.Assistant, "교체될 기존 답변"))
            repo.createSession("다른 세션", modelId = "coding")
            runBlocking { SettingsStore.reset(app); SettingsStore.setRouterUrl(app, router.url) }
            WorkManagerTestInitHelper.initializeTestWorkManager(app,
                Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build())
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val name = TestName()
    private val out = File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts", "chat-lifecycle").apply { mkdirs() }
    @After fun finish() {
        File(out, "${name.methodName}.txt").writeText(repo.sessions.value.joinToString("\n") { s ->
            "${s.id}: ${s.name}\n" + repo.messagesOf(s.id).joinToString("\n") { "${it.id} ${it.role} streaming=${it.isStreaming} ${it.text} error=${it.errorText}" }
        } + "\nHTTP paths: " + router.requests.joinToString { it.path })
        WorkManager.getInstance(app).cancelAllWork().result.get()
        router.close()
    }
    private fun waitFor(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!cond()) {
            if (releaseNetwork) WorkManager.getInstance(app).getWorkInfos(WorkQuery.fromStates(WorkInfo.State.ENQUEUED)).get()
                .forEach { WorkManagerTestInitHelper.getTestDriver(app)!!.setAllConstraintsMet(it.id) }
            compose.waitForIdle()
            Thread.sleep(10)
            check(System.currentTimeMillis() < deadline) { "flow timeout: ${name.methodName}" }
        }
    }
    private fun openChat() {
        waitFor { RouterConnection.status.value is RouterStatus.Connected }
        compose.onNodeWithText(TestData.SESSION_NAME).performClick()
        compose.waitForIdle()
    }
    private fun shot(suffix: String) {
        val b = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "${name.methodName}-$suffix.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun deletingAnInactiveSessionFromItsMenuActuallyDeletesThatSession() {
        compose.onNodeWithText(TestData.SESSION_NAME).performTouchInput { longClick() }
        compose.onNodeWithText("삭제").performClick()
        compose.onNodeWithText("세션을 삭제할까요?").assertExists()
        compose.onNodeWithText("삭제").performClick()
        waitFor { repo.sessionOf(TestData.SESSION_ID) == null }
        compose.onNodeWithText(TestData.SESSION_NAME).assertDoesNotExist()
        compose.onNodeWithText("다른 세션").assertExists()
        assertTrue(repo.messagesOf(TestData.SESSION_ID).isEmpty())
        shot("deleted")
    }

    @Test fun renamingAnInactiveSessionDoesNotRenameTheOpenSession() {
        compose.onNodeWithText(TestData.SESSION_NAME).performTouchInput { longClick() }
        compose.onNodeWithText("이름 바꾸기").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("이름 수정됨")
        compose.onNodeWithText("변경").performClick()
        waitFor { repo.sessionOf(TestData.SESSION_ID)?.name == "이름 수정됨" }
        compose.onNodeWithText("다른 세션").assertExists()
        shot("renamed")
    }

    @Test fun regenerationFinishesExitBeforeTheNewBubbleIsInserted() {
        router.enqueue(FakeRouter.Reply.Sse(listOf(FakeRouter.created(), FakeRouter.delta("새로운 답변", 1), FakeRouter.completed("새로운 답변"))))
        openChat()
        compose.onNodeWithText("교체될 기존 답변").performTouchInput { longClick() }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("다시 생성").performClick()
        // Close the action popup, then sample the actual old bubble's exit.
        compose.mainClock.advanceTimeBy(180)
        assertTrue("old reply stays only until its short exit finishes", repo.messagesOf(TestData.SESSION_ID).any { it.id == "old-reply" })
        assertEquals("no new request while old reply exits", 0, router.requests.count { it.path == "/hoard/v1/responses" })
        shot("exiting")
        compose.mainClock.advanceTimeBy(200)
        compose.mainClock.autoAdvance = true
        waitFor { repo.messagesOf(TestData.SESSION_ID).any { it.text == "새로운 답변" } }
        compose.onNodeWithText("교체될 기존 답변").assertDoesNotExist()
        assertEquals(1, repo.messagesOf(TestData.SESSION_ID).count { it.role == MessageRole.Assistant })
        shot("new-only")
    }

    @Test fun stopWorksBeforeAnyContentAndAllowsANewSend() {
        router.enqueue(FakeRouter.Reply.Sse(emptyList(), stallMs = 20_000),
            FakeRouter.Reply.Sse(listOf(FakeRouter.created(), FakeRouter.delta("중단 뒤 새 답변", 1), FakeRouter.completed("중단 뒤 새 답변"))))
        openChat()
        compose.onNode(hasSetTextAction()).performTextInput("아직 응답 없는 질문")
        compose.onNodeWithContentDescription("보내기").performClick()
        waitFor { router.requests.any { it.path == "/hoard/v1/responses" } }
        compose.onNodeWithContentDescription("답변 중지").performClick()
        waitFor { repo.messagesOf(TestData.SESSION_ID).any { it.errorText == "사용자가 중지함" } }
        assertFalse(repo.messagesOf(TestData.SESSION_ID).any { it.isStreaming })
        compose.onNode(hasSetTextAction()).performTextInput("중단 직후 다음 질문")
        compose.onNodeWithContentDescription("보내기").performClick()
        waitFor { repo.messagesOf(TestData.SESSION_ID).any { it.text == "중단 뒤 새 답변" } }
        assertEquals(2, router.requests.count { it.path == "/hoard/v1/responses" })
        shot("resent")
    }

    @Test fun networkQueuedReplyCanBeStoppedBeforeWorkerCreatesABubble() {
        openChat()
        releaseNetwork = false
        compose.onNode(hasSetTextAction()).performTextInput("네트워크 대기 중 질문")
        compose.onNodeWithContentDescription("보내기").performClick()
        waitFor { WorkManager.getInstance(app).getWorkInfos(WorkQuery.fromStates(WorkInfo.State.ENQUEUED)).get().isNotEmpty() }
        compose.onNodeWithContentDescription("답변 중지").performClick()
        waitFor { WorkManager.getInstance(app).getWorkInfos(WorkQuery.fromStates(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING)).get().isEmpty() }
        releaseNetwork = true
        assertEquals(0, router.requests.count { it.path == "/hoard/v1/responses" })
        shot("queue-cancelled")
    }

    @Test fun noRouterBlocksSendAndRegenerateWithoutLosingDraftOrExistingReply() {
        openChat()
        runBlocking { SettingsStore.setRouterUrl(app, "") }
        waitFor { RouterConnection.status.value == RouterStatus.Offline && vm.settings.value.routerUrl.isEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("보존할 초안")
        compose.onNodeWithContentDescription("보내기").assertIsNotEnabled()
        compose.runOnUiThread {
            vm.send("보존할 초안", emptyList(), "coding")
            vm.retryFrom("old-reply")
            vm.editUserMessage("old-user", "수정 금지")
        }
        assertEquals("보존할 초안", vm.input)
        assertEquals(listOf("원래 질문", "교체될 기존 답변"), repo.messagesOf(TestData.SESSION_ID).map { it.text })
        assertEquals(0, router.requests.count { it.path == "/hoard/v1/responses" })
        shot("offline-draft-kept")
    }
}
