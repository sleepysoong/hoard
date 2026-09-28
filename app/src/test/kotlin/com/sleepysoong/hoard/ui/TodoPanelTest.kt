package com.sleepysoong.hoard.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.testing.ChatHarness
import com.sleepysoong.hoard.tools.TodoTool
import com.sleepysoong.hoard.ui.chat.ChatScreen
import com.sleepysoong.hoard.ui.theme.HoardTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Tool commit → ViewModel → actual chat panel, session switching, and clear. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TodoPanelTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var h: ChatHarness
    @After fun finish() { if (::h.isInitialized) h.destroyViewModel() }

    private fun scenario(dark: Boolean) {
        h = ChatHarness()
        val sid = h.vm.uiState.value.session!!.id
        val tool = TodoTool(h.repo.todos, sid)
        fun call(s: String) = runBlocking { tool.execute(Json.parseToJsonElement(s).jsonObject) }
        call("""{"op":"create","content":"현재 구조 확인"}""")
        call("""{"op":"create","content":"세션별 Todo 영속 상태 구현"}""")
        val id = h.repo.todos.list(sid)[0].id
        call("""{"op":"update","id":"$id","status":"in_progress"}""")
        compose.setContent { HoardTheme(darkTheme = dark) { ChatScreen(h.vm) } }
        compose.onNodeWithTag("todo-toggle").performClick()
        compose.onNodeWithText("세션별 Todo 영속 상태 구현").assertExists()
        call("""{"op":"update","id":"$id","status":"completed"}""")
        compose.waitForIdle()
        compose.onNodeWithText("완료", useUnmergedTree = true).assertExists()
        val out = File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts", "todo").apply { mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bmp ->
            File(out, if (dark) "dark.png" else "light.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.runOnIdle { h.vm.newSession("독립 세션") }
        compose.onNodeWithTag("todo-panel").assertDoesNotExist()
        compose.runOnIdle { h.vm.selectSession(sid) }
        compose.onNodeWithTag("todo-panel").assertExists()
        call("""{"op":"clear"}""")
        compose.onNodeWithTag("todo-panel").assertDoesNotExist()
    }
    @Test fun light() = scenario(false)
    @Test fun dark() = scenario(true)
}
