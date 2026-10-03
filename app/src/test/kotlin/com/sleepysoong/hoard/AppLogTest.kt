package com.sleepysoong.hoard

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.diagnostics.AppLog
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** In-app log: written, capped, rotated, redacted, and clearable — without adb. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppLogTest {
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun writesToTailAndFileAndRedactsSecrets() = kotlinx.coroutines.runBlocking {
        AppLog.init(app)
        AppLog.clear()
        AppLog.i("Test", "router check ok token=abc123 secret")
        AppLog.w("Test2", "POST /hoard/v1/responses Authorization: Bearer sk-live-key-here", null)
        AppLog.i("Test3", """settings probe {"routerToken": "json-secret-x"}""")
        val lines = AppLog.tail.first()
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("router check ok") && lines[0].contains("token=[redacted]"))
        assertFalse("bearer token never stored", lines.joinToString().contains("sk-live-key"))
        assertFalse("JSON-form secret never stored: $lines", lines.joinToString().contains("json-secret-x"))
        val file = File(app.filesDir, "logs/hoard.log")
        assertTrue(file.exists() && file.readText().contains("router check ok"))
        AppLog.clear()
        assertTrue(AppLog.tail.first().isEmpty())
    }

    @Test fun tailIsCapped() {
        AppLog.init(app)
        AppLog.clear()
        repeat(450) { AppLog.d("Flood", "line-$it") }
        val lines = runCatching { kotlinx.coroutines.runBlocking { AppLog.tail.first { it.isNotEmpty() } } }.getOrThrow()
        assertTrue("tail capped at 400", lines.size <= 400)
        assertTrue("newest survives", lines.last().contains("line-449"))
        AppLog.clear()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LogViewerUiTest {
    @get:org.junit.Rule(order = 0) val reset = object : org.junit.rules.ExternalResource() {
        override fun before() {
            HoardRepository.resetForTests()
            com.sleepysoong.hoard.testing.TestData.seed()
            com.sleepysoong.hoard.testing.TestData.useMockEngine()
            AppLog.init(ApplicationProvider.getApplicationContext())
        }
    }
    @get:org.junit.Rule(order = 1) val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<MainActivity>()

    @Test fun settingsShowsCopiesAndClearsTheLog() {
        AppLog.clear()
        AppLog.i("UiProbe", "로그 뷰어 확인용 줄")
        compose.onNodeWithTag("tab-설정").performClick()
        compose.onNodeWithTag("open-logs").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithTag("open-logs").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("log-text").fetchSemanticsNodes().isEmpty()) {
            println("TREE:\n" + compose.onRoot().printToString())
        }
        compose.onNodeWithTag("log-text").assertTextContains("로그 뷰어 확인용 줄", substring = true)
        compose.onNodeWithTag("clear-logs").performClick()
        compose.onNodeWithTag("log-text").assertTextContains("(로그 없음)")
    }
}
