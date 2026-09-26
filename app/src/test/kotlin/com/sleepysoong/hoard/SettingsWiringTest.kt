package com.sleepysoong.hoard

import android.app.NotificationManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.testing.ChatHarness
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings screen values must drive behaviour, not just persist:
 *  - new sessions take the default model / context limit (not a hardcoded value,
 *    not whatever the currently open session happens to use)
 *  - changing defaults later leaves existing sessions alone
 *  - "background reply" off: leaving the app stops the reply (no spinner left, no
 *    "ready" notification); staying in the app still completes it
 *  - "background reply" on: leaving the app still completes the reply
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SettingsWiringTest {
    @get:Rule val name = TestName()
    private lateinit var h: ChatHarness

    @After fun dump() {
        if (::h.isInitialized) {
            runBlocking { SettingsStore.reset(h.app) }
            h.writeTranscript("SettingsWiringTest.${name.methodName}")
        }
    }

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!cond()) {
            h.idle(); Thread.sleep(5)
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
        }
    }

    private fun applySettings(block: suspend () -> Unit, expect: (SettingsStore.Settings) -> Boolean) {
        runBlocking { block() }
        waitUntil("settings to reach the ViewModel") { expect(h.vm.settings.value) }
    }

    private val process get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry

    private fun enterApp() { process.currentState = Lifecycle.State.RESUMED; h.idle() }
    private fun leaveApp() { process.currentState = Lifecycle.State.CREATED; h.idle() }

    private fun readyNotifications() =
        shadowOf(h.app.getSystemService(NotificationManager::class.java)).allNotifications
            .filter { it.extras.getString("android.text")?.contains("준비") == true }

    @Test
    fun newSessionsUseDefaultsAndExistingSessionsKeepTheirs() {
        h = ChatHarness()
        val welcome = h.repo.sessionOf("session-welcome")!!
        applySettings({
            SettingsStore.setDefaultModel(h.app, "hoard-1-ultra")
            SettingsStore.setDefaultContext(h.app, 128_000)
        }) { it.defaultModel == "hoard-1-ultra" && it.defaultContext == 128_000 }

        // The open session uses another model; the new one must not copy it.
        h.vm.setModel("hoard-1-mini")
        val first = h.repo.sessionOf(h.vm.newSession("기본값 세션"))!!
        h.idle()
        assertEquals("hoard-1-ultra", first.modelId)
        assertEquals(128_000, first.contextLimit)

        applySettings({ SettingsStore.setDefaultModel(h.app, "hoard-vision-xl") }) { it.defaultModel == "hoard-vision-xl" }
        val second = h.repo.sessionOf(h.vm.newSession("바뀐 기본값"))!!
        h.idle()
        assertEquals("hoard-vision-xl", second.modelId)
        assertEquals("existing session untouched", "hoard-1-ultra", h.repo.sessionOf(first.id)!!.modelId)
        assertEquals(welcome.contextLimit, h.repo.sessionOf(welcome.id)!!.contextLimit)
    }

    @Test
    fun replacementSessionAfterDeletingTheLastOneUsesDefaults() {
        h = ChatHarness()
        applySettings({
            SettingsStore.setDefaultModel(h.app, "hoard-1-mini")
            SettingsStore.setDefaultContext(h.app, 8_000)
        }) { it.defaultModel == "hoard-1-mini" && it.defaultContext == 8_000 }

        h.vm.deleteSession("session-welcome")
        h.idle()
        val s = h.repo.sessions.value.single()
        assertEquals("hoard-1-mini", s.modelId)
        assertEquals(8_000, s.contextLimit)
        assertEquals(s.id, h.vm.uiState.value.session?.id)
    }

    @Test
    fun backgroundOffStopsReplyWhenLeavingApp() {
        h = ChatHarness(pace = 0.5f)
        enterApp()
        applySettings({ SettingsStore.setBackground(h.app, false) }) { !it.backgroundWork }
        val sid = h.vm.uiState.value.session!!.id

        h.vm.send("백그라운드 꺼짐에서 앱을 나가면?", emptyList(), "hoard-1-pro")
        waitUntil("reply streaming") { h.repo.messagesOf(sid).any { it.isStreaming && it.thinking.isNotEmpty() } }
        leaveApp()
        h.awaitReplies()
        h.snapshot("left app with background off", sid)

        val reply = h.repo.messagesOf(sid).last()
        assertEquals(MessageRole.Assistant, reply.role)
        assertFalse(reply.isStreaming)
        assertTrue("user sees it stopped: ${reply.text}", reply.text.contains("중단"))
        assertEquals(WorkInfo.State.CANCELLED, h.allWork().single().state)
        assertTrue(readyNotifications().isEmpty())
    }

    @Test
    fun backgroundOffStillAnswersWhileAppStaysOpen() {
        h = ChatHarness(pace = 0.1f)
        enterApp()
        applySettings({ SettingsStore.setBackground(h.app, false) }) { !it.backgroundWork }
        val sid = h.vm.uiState.value.session!!.id
        h.vm.send("앱 안에서 기다리는 질문", emptyList(), "hoard-1-pro")
        h.awaitReplies()
        h.snapshot("stayed in app", sid)
        assertTrue(h.repo.messagesOf(sid).last().text.contains("앱 안에서 기다리는 질문"))
        assertEquals(WorkInfo.State.SUCCEEDED, h.allWork().single().state)
    }

    @Test
    fun backgroundOnKeepsAnsweringAfterLeavingApp() {
        h = ChatHarness(pace = 0.3f)
        enterApp()
        val sid = h.vm.uiState.value.session!!.id
        h.vm.send("백그라운드 켜짐에서 앱을 나가도 계속", emptyList(), "hoard-1-pro")
        waitUntil("reply streaming") { h.repo.messagesOf(sid).any { it.isStreaming } }
        leaveApp()
        h.awaitReplies()
        h.snapshot("left app with background on", sid)
        assertTrue(h.repo.messagesOf(sid).last().text.contains("계속"))
        assertEquals(WorkInfo.State.SUCCEEDED, h.allWork().single().state)
        assertEquals(1, readyNotifications().size)
    }
}
