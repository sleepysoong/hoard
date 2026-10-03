package com.sleepysoong.hoard

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sleepysoong.hoard.browser.*
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/** Actual Android → pinned SSH → real headful Chrome/CDP + password-protected
 * x11vnc. The CI fixture is mandatory, not skipped when missing. Failure paths:
 * cropped toolbar, wrong input coordinates/tab, stale AI ids, concurrent ownership,
 * cancellation while AI waits, and stopping/reopening the preview. Screenshots and
 * result JSON survive APK cleanup in the native-smoke artifact.
 */
@RunWith(AndroidJUnit4::class)
class NativeBrowserControlTest {
    @Test fun wholeChromeMouseKeyboardTabHandoffAndAutomationPauseActuallyWork(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        fun arg(name: String) = requireNotNull(args.getString(name)) { "Missing native browser fixture argument: $name" }
        val config = RemoteBrowserConfig(
            host = arg("browserHost"), port = arg("browserPort").toInt(), user = arg("browserUser"),
            authMethod = "key", keyEncrypted = SecretStore.encrypt(Base64.getDecoder().decode(arg("browserKey")).decodeToString()),
            hostKey = requireNotNull(PinnedKey.parse(arg("browserHostKey"))),
            vncPasswordEncrypted = SecretStore.encrypt(arg("browserVncPassword"))
        )
        val manager = RemoteBrowserManager(config)
        var frameNumber = 0
        fun note(text: String) { android.util.Log.i("HoardNativeArtifact", "BROWSER $text") }
        fun image(name: String, bytes: ByteArray) {
            val unique = "$name-${frameNumber++}"
            val chunks = Base64.getEncoder().encodeToString(bytes).chunked(2800)
            chunks.forEachIndexed { i, data -> android.util.Log.i("HoardNativeImage", "IMAGE $unique $i ${chunks.size} $data") }
        }
        suspend fun frame(name: String): ByteArray {
            val deadline = System.currentTimeMillis() + 10_000
            while (true) {
                manager.previewFrame(75)?.let { image(name, it); return it }
                check(System.currentTimeMillis() < deadline) { "No native VNC desktop frame" }; delay(100)
            }
        }
        fun inputId(result: BrowserResult) = result.json["elements"]!!.jsonArray.map { it.jsonObject }
            .first { it["type"]?.jsonPrimitive?.content == "input" }["id"]!!.jsonPrimitive.int
        fun tabId(result: BrowserResult) = result.json["tab"]!!.jsonObject["id"]!!.jsonPrimitive.int
        suspend fun key(control: BrowserControl, vararg keys: Int) = control.input(DesktopInput.Chord(keys.toList()))
        try {
            val first = manager.execute(BrowserAction.Open("http://127.0.0.1:18080/first.html"))
            note("first=${first.json}")
            manager.startPreview()
            val desktop = frame("whole-chrome")
            val screenshot = manager.execute(BrowserAction.Screenshot)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(desktop, 0, desktop.size, bounds)
            val pageHeight = screenshot.json["height"]!!.jsonPrimitive.int
            assertTrue("VNC includes actual Chrome tabs and address bar", bounds.outHeight > pageHeight + 60)
            val topInset = bounds.outHeight - pageHeight
            val control = manager.acquireControl()
            try {
                val queued = async { runCatching { manager.execute(BrowserAction.Click(inputId(first))) } }
                delay(200); assertFalse("AI cannot change the browser during manual control", queued.isCompleted)
                // A desktop may initially focus its window manager rather than
                // Chrome. Use the real viewer's mouse path before keyboard input.
                control.input(DesktopInput.Pointer(140, topInset + 160, 1))
                control.input(DesktopInput.Pointer(140, topInset + 160, 0))
                key(control, 0xffe3, 'l'.code)
                control.input(DesktopInput.Text("http://127.0.0.1:18080/second.html")); key(control, 0xff0d)
                val deadline = System.currentTimeMillis() + 10_000
                while (true) {
                    val bytes = frame("manual-address-bar")
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val blue = Color.blue(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                    val red = Color.red(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)); bitmap.recycle()
                    if (blue > red + 60) break
                    check(System.currentTimeMillis() < deadline) { "Address bar keyboard input did not navigate Chrome" }
                    delay(100)
                }
                assertFalse(queued.isCompleted)
                control.release()
                val stale = queued.await().exceptionOrNull()
                assertTrue("old element ids cannot act on the user's new page: $stale", stale is BrowserException)
            } finally { control.release() }
            val second = manager.execute(BrowserAction.State)
            note("after-manual=${second.json}")
            assertTrue(second.json["url"]!!.jsonPrimitive.content.endsWith("/second.html"))
            val newTab = manager.execute(BrowserAction.Open("http://127.0.0.1:18080/first.html", newTab = true))
            val manual = manager.acquireControl()
            try {
                manual.input(DesktopInput.Pointer(140, topInset + 160, 1))
                manual.input(DesktopInput.Pointer(140, topInset + 160, 0))
                manual.input(DesktopInput.Text("manual mouse 한국"))
                key(manual, 0xffe3, 0xffe1, 0xff09) // Chrome's previous-tab shortcut
            } finally { manual.release() }
            val visible = manager.execute(BrowserAction.State)
            assertEquals("CDP follows the same tab a person selected in Chrome", tabId(second), tabId(visible))
            val typed = manager.execute(BrowserAction.SwitchTab(tabId(newTab)))
            note("mouse-input=${typed.json}")
            assertTrue("desktop-pixel click focused the real page field", typed.json.toString().contains("manual mouse 한국"))
            frame("ai-visible-tab")
            val paused = manager.acquireControl()
            try {
                val waiting = async { manager.execute(BrowserAction.State) }
                delay(100); waiting.cancelAndJoin()
            } finally { paused.release() }
            assertTrue("cancelled waiter never strands the control lock", manager.execute(BrowserAction.State).json.containsKey("elements"))
            manager.stopPreview()
            assertNull("hidden preview receives nothing", manager.previewFrame(55))
            manager.startPreview(); frame("reopened-desktop")
        } finally { manager.stopPreview(); manager.close() }
    }
}
