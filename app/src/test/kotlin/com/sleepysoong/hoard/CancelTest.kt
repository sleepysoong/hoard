package com.sleepysoong.hoard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.testing.ChatHarness
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Cancelling a reply must drop the router connection at once, so sleepyrouter
 * cancels the upstream generation (no tokens burnt for nobody). The router here
 * stalls exactly like a thinking model: one delta, then only SSE keep-alives
 * (which never reach the client as events) or complete silence.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CancelTest {
    private lateinit var srv: HttpServer
    private val disconnectedAt = AtomicLong(0)

    private fun stallingRouter(keepAlive: Boolean) {
        srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/") { ex ->
                ex.requestBody.readBytes()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, 0)
                val out = ex.responseBody
                out.write("event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"생각 중인 답의 앞부분\"}\n\n".toByteArray()); out.flush()
                try {
                    repeat(150) {
                        Thread.sleep(100)
                        // A write to a closed socket is how a server notices the client left.
                        out.write(if (keepAlive) ": ping\n\n".toByteArray() else ByteArray(0)); out.flush()
                        if (!keepAlive) { out.write(": x\n".toByteArray()); out.flush() }
                    }
                } catch (e: Exception) {
                    disconnectedAt.compareAndSet(0, System.currentTimeMillis())
                }
                runCatching { out.close() }
            }
            start()
        }
    }

    @After fun stop() { if (::srv.isInitialized) srv.stop(0) }

    private fun cancelMidStream(h: ChatHarness, cancel: (sessionId: String) -> Unit): Long {
        runBlocking { SettingsStore.setRouterUrl(h.app, "http://127.0.0.1:${srv.address.port}") }
        h.vm.send("오래 생각하는 질문", emptyList(), "coding")
        val t0 = System.currentTimeMillis()
        while (h.messages().lastOrNull()?.text?.startsWith("생각 중") != true) {
            h.idle(); Thread.sleep(10)
            check(System.currentTimeMillis() - t0 < 5_000) { "stream never started" }
        }
        val at = System.currentTimeMillis()
        cancel(h.vm.uiState.value.session!!.id)
        while (disconnectedAt.get() == 0L && System.currentTimeMillis() - at < 8_000) { h.idle(); Thread.sleep(10) }
        check(disconnectedAt.get() != 0L) { "router connection still open 8 s after cancel" }
        return disconnectedAt.get() - at
    }

    @Test fun deletingSessionDropsTheRouterConnectionPromptly() {
        stallingRouter(keepAlive = true)
        val h = ChatHarness()
        val ms = cancelMidStream(h) { h.vm.deleteSession(it) }
        assertTrue("disconnected ${ms}ms after cancel", ms < 1_500)
    }

    @Test fun silentRouterIsAlsoDroppedPromptly() {
        stallingRouter(keepAlive = false)
        val h = ChatHarness()
        val ms = cancelMidStream(h) { h.vm.deleteSession(it) }
        assertTrue("disconnected ${ms}ms after cancel", ms < 1_500)
    }

    @Test fun stopButtonKeepsPartialTextAndDisconnects() {
        stallingRouter(keepAlive = true)
        val h = ChatHarness()
        val ms = cancelMidStream(h) { h.vm.stopReply() }
        h.awaitReplies()
        val r = h.messages().last()
        assertEquals(MessageRole.Assistant, r.role)
        assertTrue("partial text kept: ${r.text}", r.text.startsWith("생각 중인 답의 앞부분"))
        assertFalse(r.isStreaming)
        assertEquals("사용자가 중지함", r.errorText)
        assertTrue("disconnected ${ms}ms after stop", ms < 1_500)
        assertEquals(WorkInfo.State.CANCELLED, h.allWork().single().state)
        // Stopping is final: no retry, no second request.
        h.fireBackoff()
        assertEquals(WorkInfo.State.CANCELLED, h.allWork().single().state)
    }
}
