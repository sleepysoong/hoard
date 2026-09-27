package com.sleepysoong.hoard.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.sleepysoong.hoard.MainActivity
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.engine.RouterConnection
import com.sleepysoong.hoard.testing.FakeRouter
import com.sleepysoong.hoard.testing.FakeRouter.Companion.completed
import com.sleepysoong.hoard.testing.FakeRouter.Companion.created
import com.sleepysoong.hoard.testing.FakeRouter.Companion.delta
import com.sleepysoong.hoard.testing.FakeRouter.Companion.routingFrame
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The user's path through the real app against a sleepyrouter-shaped router:
 * Settings → enter URL → 연결 (catalog becomes the router's groups/models) →
 * chat → reply bubble shows the answering model, failed candidates and reasons.
 * Screenshots: build/test-artifacts/router/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class RouterUiTest {
    private val router = FakeRouter().apply {
        modelsBody = """{"object":"list","data":[{"id":"coding","owned_by":"sleepyrouter"},{"id":"fast","owned_by":"sleepyrouter"},{"id":"openrouter/c","owned_by":"openrouter"}]}"""
    }

    @get:Rule(order = 0) val reset = object : org.junit.rules.ExternalResource() {
        override fun before() {
            HoardRepository.resetForTests()
            RouterConnection.resetForTests()
            val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
            runBlocking { SettingsStore.reset(app) }
            WorkManagerTestInitHelper.initializeTestWorkManager(
                app, Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build()
            )
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @After fun stop() = router.close()

    private val outDir = File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts", "router").apply { mkdirs() }
    private fun shot(name: String) {
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            // Network is "up": let router replies (network-constrained) run.
            val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
            val wm = androidx.work.WorkManager.getInstance(app)
            wm.getWorkInfos(androidx.work.WorkQuery.fromStates(androidx.work.WorkInfo.State.ENQUEUED)).get()
                .forEach { WorkManagerTestInitHelper.getTestDriver(app)!!.setAllConstraintsMet(it.id) }
            compose.waitForIdle(); Thread.sleep(20)
            check(System.currentTimeMillis() < deadline) { "timed out: $what" }
        }
    }

    private fun connect() {
        compose.onNodeWithTag("tab-설정").performClick()
        compose.onNode(hasSetTextAction() and hasText("라우터 주소")).performTextReplacement(router.url)
        compose.onNodeWithText("연결").performClick()
        waitFor("connected") { compose.onAllNodesWithText("연결됨", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun connectShowsRouterCatalog() {
        connect()
        compose.onNodeWithTag("router-status").assertTextContains("그룹 2 · 모델 1", substring = true)
        compose.onNodeWithText("기본 모델 (라우터)").assertExists()
        compose.onNodeWithText("coding").assertExists()
        compose.onNodeWithText("openrouter/c").assertExists()
        shot("settings-connected")
        assertEquals("/v1/models", router.requests.last().path)
    }

    @Test fun badUrlShowsWhyAndKeepsMockCatalog() {
        compose.onNodeWithTag("tab-설정").performClick()
        val dead = FakeRouter().also { it.close() }.url
        compose.onNode(hasSetTextAction() and hasText("라우터 주소")).performTextReplacement(dead)
        compose.onNodeWithText("연결").performClick()
        waitFor("failed") { compose.onAllNodesWithText("연결 실패", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("기본 모델 (목업)").assertExists()
        shot("settings-failed")
    }

    @Test fun replyShowsAnsweringModelAndFailedCandidates() {
        router.enqueue(FakeRouter.Reply.Sse(listOf(routingFrame(), created(), delta("라우터가 ", 1), delta("답했어요", 2), completed("라우터가 답했어요"))))
        connect()
        compose.onNodeWithTag("tab-세션").performClick()
        compose.onNodeWithContentDescription("새 세션").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("라우팅 보여줘")
        compose.onNodeWithContentDescription("보내기").performClick()
        waitFor("reply") { compose.onAllNodesWithText("라우터가 답했어요").fetchSemanticsNodes().isNotEmpty() }
        waitFor("routing summary") { compose.onAllNodesWithTag("routing-summary", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

        val body = router.requests.first { it.path == "/hoard/v1/responses" }.body
        assertTrue("new session starts on the router's first group: $body", body.contains("\"model\":\"coding\""))
        compose.onNodeWithTag("routing-summary", useUnmergedTree = true).assertContentDescriptionContains("응답 모델 openrouter/c · 2개 실패", substring = true)
        // The # pill names the requested group; the footer no longer repeats the model.
        compose.onNodeWithText("#", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("coding", useUnmergedTree = true).assertExists()
        assertEquals(0, compose.onAllNodesWithText("openrouter/c · ").fetchSemanticsNodes().size)
        shot("reply-collapsed")

        compose.onNodeWithTag("routing-summary", useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("1. zen/a").assertExists()
        compose.onNodeWithText("rate_limit_error: rate_limited: slow down", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("missing_api_key: API key missing for provider gemini", substring = true, useUnmergedTree = true).assertExists()
        assertEquals(3, compose.onAllNodesWithTag("routing-attempt", useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onAllNodesWithText("성공").fetchSemanticsNodes().let { assertEquals(1, it.size) }
        shot("reply-expanded")
    }

    @Test fun allFailedShowsErrorAndTrace() {
        router.enqueue(FakeRouter.allFailed(), FakeRouter.allFailed(), FakeRouter.allFailed())
        connect()
        compose.onNodeWithTag("tab-세션").performClick()
        compose.onNodeWithContentDescription("새 세션").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("아무도 못 답해")
        compose.onNodeWithContentDescription("보내기").performClick()
        waitFor("error") { compose.onAllNodesWithTag("reply-error", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reply-error", useUnmergedTree = true).assertTextContains("모든 모델이 실패했습니다", substring = true)
        compose.onNodeWithTag("routing-summary", useUnmergedTree = true).assertContentDescriptionContains("응답한 모델 없음 · 2개 실패", substring = true)
        shot("reply-all-failed")
    }

    @Test fun stopButtonAppearsWhileReplyingAndStops() {
        // Router that sends one delta and then stalls (keep-alives only).
        router.enqueue(FakeRouter.Reply.Sse(listOf(routingFrame(), created(), delta("답하는 중", 1)), stallMs = 20_000))
        connect()
        compose.onNodeWithTag("tab-세션").performClick()
        compose.onNodeWithContentDescription("새 세션").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("길게 답해줘")
        compose.onNodeWithContentDescription("보내기").performClick()
        waitFor("streaming") { compose.onAllNodesWithText("답하는 중").fetchSemanticsNodes().isNotEmpty() }
        waitFor("stop button") { compose.onAllNodesWithContentDescription("답변 중지").fetchSemanticsNodes().isNotEmpty() }
        shot("replying-stop-button")
        compose.onNodeWithContentDescription("답변 중지").performClick()
        waitFor("stopped") { compose.onAllNodesWithTag("reply-error", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reply-error", useUnmergedTree = true).assertTextContains("사용자가 중지함", substring = true)
        compose.onNodeWithText("답하는 중").assertExists()
        waitFor("send button back") { compose.onAllNodesWithContentDescription("보내기").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun tokenFieldIsMaskedAndUsedForTheCheck() {
        router.requiredToken = "ui-secret-token"
        compose.onNodeWithTag("tab-설정").performClick()
        compose.onNode(hasSetTextAction() and hasText("라우터 주소")).performTextReplacement(router.url)
        compose.onNodeWithText("연결").performClick()
        waitFor("auth failure") { compose.onAllNodesWithText("인증", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("router-status").assertTextContains("연결 실패", substring = true)

        compose.onNode(hasSetTextAction() and hasText("토큰 (선택)")).performTextReplacement("ui-secret-token")
        compose.waitForIdle()
        // Semantics always carry the raw EditableText; what is *drawn* must be masked.
        val field = compose.onNode(hasSetTextAction() and hasText("토큰 (선택)")).fetchSemanticsNode()
        assertTrue("token field is a password field (drawn as dots)",
            field.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Password))
        compose.onNodeWithText("연결").performClick()
        waitFor("connected") { compose.onAllNodesWithText("연결됨", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Bearer ui-secret-token", router.requests.last().authorization)
        shot("settings-token")
    }

    @Test fun webToolsSettingsSaveMaskedBraveKeyAndSwitch() {
        compose.onNodeWithTag("tab-설정").performClick()
        compose.onNodeWithTag("web-tools-status").performScrollTo()
        compose.onNodeWithTag("web-tools-status").assertTextContains("web_fetch만", substring = true)
        compose.onNode(hasSetTextAction() and hasText("Brave Search API 키")).performTextReplacement("BSA-test-key")
        compose.waitForIdle()
        val field = compose.onNode(hasSetTextAction() and hasText("Brave Search API 키")).fetchSemanticsNode()
        assertTrue("Brave key is a password field", field.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Password))
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        waitFor("key saved") { runBlocking { SettingsStore.current(app).braveApiKey } == "BSA-test-key" }
        compose.onNodeWithTag("web-tools-status").assertTextContains("web_search + web_fetch", substring = true)
        shot("settings-web-tools")
        compose.onNodeWithTag("web-tools-switch").performClick()
        waitFor("switch saved") { !runBlocking { SettingsStore.current(app).webToolsEnabled } }
        compose.onNodeWithTag("web-tools-status").assertTextContains("꺼짐", substring = true)
        assertTrue("settings never print the key", !runBlocking { SettingsStore.current(app) }.toString().contains("BSA-test-key"))
    }
}
