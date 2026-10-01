package com.sleepysoong.hoard.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import com.sleepysoong.hoard.MainActivity
import com.sleepysoong.hoard.browser.BrowserAction
import com.sleepysoong.hoard.browser.BrowserException
import com.sleepysoong.hoard.browser.BrowserPreviews
import com.sleepysoong.hoard.browser.BrowserResult
import com.sleepysoong.hoard.browser.RemoteBrowser
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.testing.TestData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TestName
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real chat UI + JPEG decoding + persisted preferences, with an injected browser transport.
 * Failure paths: missing/invalid frames, slow captures/backlog, dialog slider interference,
 * background capture, dismissal/reopening, and another session taking the shared browser.
 * Transport is simulated here; this does not claim to verify a phone's SSH connection.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class BrowserPreviewFlowTest {
    private val browser = ObservedBrowser()
    @get:Rule(order = 0) val reset = object : ExternalResource() {
        override fun before() {
            BrowserPreviews.target.value?.let(BrowserPreviews::dismiss)
            HoardRepository.resetForTests()
            TestData.seed(greeting = true)
            TestData.useMockEngine()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val name = TestName()
    private val artifacts = File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts", "browser-preview")
        .apply { mkdirs() }

    @After fun cleanup() {
        BrowserPreviews.target.value?.let(BrowserPreviews::dismiss)
        File(artifacts, "${name.methodName}.txt").writeText(
            "Capture requests (nanoseconds, JPEG quality):\n" + browser.requests.joinToString("\n") +
                "\nMaximum concurrent requests: ${browser.maxConcurrent.get()}\n")
    }

    private fun openPreview() {
        runBlocking { SettingsStore.setBrowserPreviewQuality(compose.activity, 3) }
        compose.onNodeWithText("Hoard에 오신 것을 환영합니다").performClick()
        compose.runOnUiThread { BrowserPreviews.show(TestData.SESSION_ID, browser) }
        await { browser.requests.isNotEmpty() }
        await { compose.onAllNodesWithTag("browser-preview-image", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(10_000, condition)

    private fun screenshot(tag: String, name: String) {
        val bitmap = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().asAndroidBitmap()
        File(artifacts, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun selectQuality(level: Int, fullscreen: Boolean = false) {
        val prefix = if (fullscreen) "browser-preview-fullscreen" else "browser-live-preview"
        val slider = compose.onNode(
            androidx.compose.ui.test.hasTestTag("browser-preview-quality") and
                androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(prefix)),
            useUnmergedTree = true)
        slider.performTouchInput {
            // Both sliders reserve a thumb-width margin at either edge.
            val fraction = (level - 1) / 4f
            down(Offset(20f + (width - 40f) * fraction, centerY))
            up()
        }
        await { runBlocking { SettingsStore.flow(compose.activity).first().browserPreviewQuality == level } }
        val expected = listOf(20, 35, 55, 75, 90)[level - 1]
        await { browser.requests.lastOrNull()?.second == expected }
    }

    @Test fun qualityChangesReachTransportAndPersistAcrossFullscreenAndReopening() {
        openPreview()
        screenshot("browser-live-preview", "compact-default")
        for (level in 1..5) selectQuality(level)
        compose.onNodeWithText("누르면 전체화면").performClick()
        compose.onNodeWithTag("browser-preview-fullscreen").assertExists()
        screenshot("browser-preview-fullscreen", "fullscreen-high")
        selectQuality(2, fullscreen = true)
        compose.onNodeWithTag("browser-preview-fullscreen").assertExists()
        compose.onNodeWithContentDescription("전체화면 닫기").performClick()
        compose.onNodeWithTag("browser-preview-fullscreen").assertDoesNotExist()
        compose.onNodeWithTag("browser-preview-close").performClick()
        compose.onNodeWithTag("browser-live-preview").assertDoesNotExist()
        compose.runOnUiThread { BrowserPreviews.show(TestData.SESSION_ID, browser) }
        compose.onNodeWithText("2/5").assertExists()
        await { browser.requests.lastOrNull()?.second == 35 }
        assertEquals(2, runBlocking { SettingsStore.flow(compose.activity).first().browserPreviewQuality })
        screenshot("browser-live-preview", "compact-restored-low")
    }

    @Test fun backgroundDismissalAndSessionHandoffStopCaptureWithoutASecondLoop() {
        openPreview()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.waitForIdle()
        val paused = browser.requests.size
        Thread.sleep(350)
        assertEquals("no capture while backgrounded", paused, browser.requests.size)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await { browser.requests.size > paused }
        compose.runOnUiThread { BrowserPreviews.show("another-chat", browser) }
        compose.onNodeWithTag("browser-live-preview").assertDoesNotExist()
        val hidden = browser.requests.size
        Thread.sleep(350)
        assertEquals("no capture for hidden chat", hidden, browser.requests.size)
        compose.runOnUiThread { BrowserPreviews.show(TestData.SESSION_ID, browser) }
        await { browser.requests.size > hidden }
        compose.onNodeWithTag("browser-preview-close").performClick()
        compose.waitForIdle()
        val dismissed = browser.requests.size
        Thread.sleep(350)
        assertEquals("no capture after close", dismissed, browser.requests.size)
        assertEquals(1, browser.maxConcurrent.get())
    }

    @Test fun slowCorruptAndDisconnectedFramesRecoverWithoutBacklogOrStaleImage() {
        browser.latencyMs = 180
        openPreview()
        await { browser.requests.size >= 4 }
        assertTrue("ten per second cap", browser.requests.zipWithNext().all { (a, b) -> b.first - a.first >= 100_000_000 })
        assertEquals("single in-flight capture", 1, browser.maxConcurrent.get())
        browser.mode = "invalid"
        compose.onNodeWithText("누르면 전체화면").performClick()
        await { compose.onAllNodesWithTag("browser-preview-image", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        screenshot("browser-preview-fullscreen", "fullscreen-invalid-frame")
        browser.mode = "disconnected"
        val failed = browser.requests.size
        await { browser.requests.size >= failed + 2 }
        browser.mode = "ready"
        await { compose.onAllNodesWithTag("browser-preview-image", useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        screenshot("browser-preview-fullscreen", "fullscreen-recovered")
    }

    private class ObservedBrowser : RemoteBrowser {
        override val supportsPreview = true
        @Volatile var mode = "ready"
        @Volatile var latencyMs = 0L
        val requests = CopyOnWriteArrayList<Pair<Long, Int>>()
        private val concurrent = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        private val jpeg by lazy {
            val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(70, 110, 65)) }
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                .also { bitmap.recycle() }
        }
        override suspend fun execute(action: BrowserAction): BrowserResult = error("preview must never execute browser actions")
        override suspend fun previewFrame(quality: Int): ByteArray? {
            requests += System.nanoTime() to quality
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), ::maxOf)
            try {
                delay(latencyMs)
                return when (mode) {
                    "invalid" -> byteArrayOf(1, 2, 3)
                    "disconnected" -> throw BrowserException("disconnected")
                    else -> jpeg
                }
            } finally {
                concurrent.decrementAndGet()
            }
        }
    }
}
