package com.sleepysoong.hoard

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sleepysoong.hoard.browser.*
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.SettingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.abs

/** Production chat/viewer on Android, actual pinned SSH/CDP/VNC, no browser double.
 * Failure oracles: screenshot pixels distinguish tabs; the real page receives UI input;
 * Chrome/preview aspect ratios agree in both orientations; controls animate and quality
 * persists. Evidence: native-smoke/ui-*.jpg plus fixture/WorkManager logs.
 */
@RunWith(AndroidJUnit4::class)
class NativeBrowserPreviewTest {
    private lateinit var sessionId: String
    private val sessionName = "Native browser preview"
    @get:Rule(order = 0) val seed = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val packageName = instrumentation.targetContext.packageName
            // This flow verifies the browser, not the first-launch permission UI.
            // Grant real Android permissions before MainActivity's PermissionGate runs.
            instrumentation.uiAutomation.grantRuntimePermission(packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            val command = instrumentation.uiAutomation.executeShellCommand("appops set --uid $packageName MANAGE_EXTERNAL_STORAGE allow")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(command).use { it.readBytes() }
            sessionId = HoardRepository.get().createSession(sessionName, modelId = "coding").id
        }
        override fun after() { HoardRepository.get().deleteSession(sessionId) }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private var sequence = 0

    private fun image(name: String, bytes: ByteArray) {
        val chunks = Base64.getEncoder().encodeToString(bytes).chunked(2800)
        val unique = "ui-$name-${sequence++}"
        chunks.forEachIndexed { i, chunk -> android.util.Log.i("HoardNativeImage", "IMAGE $unique $i ${chunks.size} $chunk") }
    }
    private fun screenshot(tag: String, name: String): Bitmap {
        val bitmap = captureNode(tag)
        image(name, ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray())
        return bitmap
    }

    /** Software-rendered emulators occasionally time out PixelCopy under load;
     *  retry briefly so the gate fails only on a real rendering/content problem. */
    private fun captureNode(tag: String): Bitmap {
        val deadline = System.currentTimeMillis() + 15_000
        var last: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            val attempt = runCatching {
                compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().asAndroidBitmap()
            }
            attempt.onSuccess { return it }
            last = attempt.exceptionOrNull()
            Thread.sleep(250)
        }
        throw AssertionError("capture never succeeded for $tag", last)
    }
    private fun await(condition: () -> Boolean) = compose.waitUntil(20_000, condition)

    @Test fun liquidViewerFitsChromeInputsTheVisiblePageAndSurvivesRotationAndBackground(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        fun arg(name: String) = requireNotNull(args.getString(name)) { "Missing real fixture: $name" }
        val manager = RemoteBrowsers.get(RemoteBrowserConfig(
            arg("browserHost"), arg("browserPort").toInt(), arg("browserUser"), "key",
            keyEncrypted = SecretStore.encrypt(Base64.getDecoder().decode(arg("browserKey")).decodeToString()),
            hostKey = requireNotNull(PinnedKey.parse(arg("browserHostKey"))),
            vncPasswordEncrypted = SecretStore.encrypt(arg("browserVncPassword"))
        ))
        val originalTheme = SettingsStore.current(compose.activity).theme
        try {
            SettingsStore.setTheme(compose.activity, "light")
            compose.onNodeWithText(sessionName).performClick()
            compose.waitForIdle() // chat supplies the viewport before the first browser action
            BrowserPreviews.show(sessionId, manager)
            manager.execute(BrowserAction.Open("http://127.0.0.1:18080/first.html"))
            await { compose.onAllNodesWithTag("browser-preview-image", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            val initial = manager.previewFrame(90)!!
            image("initial-chrome", initial)
            val initialBitmap = BitmapFactory.decodeByteArray(initial, 0, initial.size)
            val requested = requireNotNull(RemoteBrowsers.previewViewport)
            val initialError = abs((initialBitmap.width.toDouble() / initialBitmap.height) / (requested.width.toDouble() / requested.height) - 1)
            Assert.assertTrue("first navigation already matches the preview aspect: requested=$requested actual=${initialBitmap.width}x${initialBitmap.height}", initialError < 0.05)
            initialBitmap.recycle()
            compose.onNodeWithText("누르면 전체화면").performClick()
            await { compose.onAllNodesWithText("직접 조작 · AI 브라우저 대기").fetchSemanticsNodes().isNotEmpty() }

            fun assertFitAndTab(blue: Boolean) {
                await {
                    val bitmap = runCatching { captureNode("browser-desktop-input") }.getOrNull() ?: return@await false
                    try {
                        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height * 2 / 3)
                        if (blue) Color.blue(pixel) > Color.red(pixel) + 60 else Color.red(pixel) > Color.blue(pixel) + 60
                    } finally { bitmap.recycle() }
                }
                screenshot("browser-desktop-input", if (blue) "blue-tab" else "red-tab").recycle()
                val desktop = compose.onNodeWithTag("browser-desktop-input", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                // Read only VNC here: CDP actions must remain blocked by manual ownership.
                val bytes = runBlocking { manager.previewFrame(90)!! }
                image("fitted-chrome", bytes)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val ratioError = abs((bounds.outWidth.toDouble() / bounds.outHeight) / (desktop.width / desktop.height) - 1)
                android.util.Log.i("HoardNativeArtifact", "VIEWPORT Android=$desktop Chrome=${bounds.outWidth}x${bounds.outHeight} error=$ratioError")
                Assert.assertTrue("Chrome fits the available preview, not the server desktop", ratioError < 0.05)
            }
            assertFitAndTab(blue = false)

            // VNC keystrokes go to the X-focused window; like a person, tap the
            // desktop first so Chrome (not the host's window manager) holds focus.
            compose.onNodeWithTag("browser-desktop-input", useUnmergedTree = true).performTouchInput { click(center) }
            compose.waitForIdle()

            // Actual press physics, not a testTag/component-name assertion.
            val address = compose.onNodeWithText("주소창")
            val rest = address.fetchSemanticsNode().boundsInRoot.width
            compose.mainClock.autoAdvance = false
            address.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(200)
            val held = address.fetchSemanticsNode().boundsInRoot.width
            screenshot("browser-preview-fullscreen", "liquid-button-held").recycle()
            Assert.assertTrue("button visibly sinks while pressed: rest=$rest held=$held", held < rest * 0.98)
            address.performTouchInput { up() }
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag("browser-control-text").performTextInput("http://127.0.0.1:18080/second.html")
            compose.onNodeWithText("입력", substring = false).performClick()
            compose.onNodeWithText("Enter", substring = false).performClick()
            assertFitAndTab(blue = true)

            val slider = compose.onNode(hasTestTag("browser-preview-quality") and hasAnyAncestor(hasTestTag("browser-preview-fullscreen")), useUnmergedTree = true)
            slider.performTouchInput { click(Offset(width - 1f, centerY)) }
            await { runBlocking { SettingsStore.current(compose.activity).browserPreviewQuality == 5 } }
            compose.onNodeWithTag("browser-preview-fullscreen").assertExists()
            screenshot("browser-preview-fullscreen", "portrait-quality").recycle()
            compose.onNodeWithText("AI 계속").performClick()
            val actual = manager.execute(BrowserAction.State)
            Assert.assertTrue("UI address/Enter buttons navigated the real Chrome", actual.json["url"]!!.jsonPrimitive.content.endsWith("/second.html"))
            val secondId = actual.json["tab"]!!.jsonObject["id"]!!.jsonPrimitive.int
            val first = manager.execute(BrowserAction.Open("http://127.0.0.1:18080/first.html", newTab = true))
            val firstShot = manager.execute(BrowserAction.Screenshot)
            val desktopBytes = manager.previewFrame(90)!!
            val desktopBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(desktopBytes, 0, desktopBytes.size, desktopBounds)
            val pageInset = desktopBounds.outHeight - firstShot.json["height"]!!.jsonPrimitive.int
            Assert.assertTrue("actual preview includes tabs and address bar", pageInset > 60)
            manager.execute(BrowserAction.SwitchTab(secondId))
            compose.onNodeWithText("누르면 전체화면").performClick()
            assertFitAndTab(blue = true) // independently proves the VNC view follows the AI's selected tab
            compose.onNodeWithText("AI 계속").performClick()
            manager.execute(BrowserAction.SwitchTab(first.json["tab"]!!.jsonObject["id"]!!.jsonPrimitive.int))
            compose.onNodeWithText("누르면 전체화면").performClick()
            assertFitAndTab(blue = false)
            compose.onNodeWithTag("browser-desktop-input", useUnmergedTree = true).performTouchInput {
                val scale = minOf(width.toFloat() / desktopBounds.outWidth, height.toFloat() / desktopBounds.outHeight)
                click(Offset((width - desktopBounds.outWidth * scale) / 2 + 140 * scale,
                    (height - desktopBounds.outHeight * scale) / 2 + (pageInset + 160) * scale))
            }
            compose.onNodeWithTag("browser-control-text").performTextInput("manual UI 한국")
            compose.onNodeWithText("입력", substring = false).performClick()
            compose.onNodeWithText("AI 계속").performClick()
            val typed = manager.execute(BrowserAction.State)
            Assert.assertTrue("scaled touch + liquid input button edited the actual page field", typed.json.toString().contains("manual UI 한국"))
            compose.onNodeWithText("누르면 전체화면").performClick()
            assertFitAndTab(blue = false)

            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            await { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            await { compose.onAllNodesWithTag("browser-preview-fullscreen").fetchSemanticsNodes().isNotEmpty() }
            assertFitAndTab(blue = false)
            screenshot("browser-preview-fullscreen", "landscape").recycle()
            SettingsStore.setTheme(compose.activity, "dark")
            await {
                val bitmap = compose.onNodeWithTag("browser-preview-fullscreen").captureToImage().asAndroidBitmap()
                try {
                    // The leftmost desktop pixel may be Chrome; use the controls' far-right gutter.
                    val gutter = bitmap.getPixel(bitmap.width - 5, bitmap.height / 2)
                    Color.red(gutter) < 80 && Color.green(gutter) < 80 && Color.blue(gutter) < 80
                } finally { bitmap.recycle() }
            }
            screenshot("browser-preview-fullscreen", "landscape-dark").recycle()
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            compose.waitForIdle()
            Assert.assertNull("backgrounded viewer has no VNC reception", manager.previewFrame(55))
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            assertFitAndTab(blue = false)
            compose.onNodeWithContentDescription("전체화면 닫기").performClick()
            val page = manager.execute(BrowserAction.State)
            Assert.assertTrue(page.json["url"]!!.jsonPrimitive.content.endsWith("/first.html"))
            compose.onNodeWithTag("browser-preview-close").performClick()
            compose.waitForIdle()
            Assert.assertNull("dismissed viewer has no VNC reception", manager.previewFrame(55))
            Assert.assertEquals(5, SettingsStore.current(compose.activity).browserPreviewQuality)
        } finally {
            compose.mainClock.autoAdvance = true
            BrowserPreviews.target.value?.let(BrowserPreviews::dismiss)
            RemoteBrowsers.reset()
            SettingsStore.setTheme(compose.activity, originalTheme)
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
