package com.sleepysoong.hoard

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.diagnostics.AppLog
import com.sleepysoong.hoard.engine.RouterConnection
import com.sleepysoong.hoard.engine.RouterStatus
import com.sleepysoong.hoard.skills.SkillRuntime
import com.sleepysoong.hoard.skills.SkillStore
import com.sleepysoong.hoard.termux.TermuxBridge
import com.sleepysoong.hoard.work.ChatResponseWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** On Android (not Robolectric): connected router → real WorkManager → empty
 * SkillRuntime → tools → HTTP/SSE → finished bubble, then another send. Also
 * exercise braced/indexed skill substitution and persisted context with ICU.
 * Failure artifacts include the conversation, requests, work state and app log.
 * The HTTP fixture exists only in the instrumentation APK, never in the app.
 */
@RunWith(AndroidJUnit4::class)
class NativeChatFlowTest {
    @get:Rule val name = TestName()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo get() = HoardRepository.get()
    private val notes = StringBuilder()
    private var sessionId: String? = null
    private var router: LocalRouter? = null
    private var original: SettingsStore.Settings? = null
    private var bundleRoot: File? = null

    @After fun finish() {
        try {
            sessionId?.let { sid ->
                notes.appendLine("messages: ${repo.messagesOf(sid)}")
                notes.appendLine("work: ${WorkManager.getInstance(context).getWorkInfosForUniqueWork("hoard-reply-$sid").get()}")
            }
            notes.appendLine("HTTP: ${router?.requests}")
            notes.appendLine("request: ${router?.requestBody}")
            notes.appendLine(AppLog.tail.value.joinToString("\n"))
            // AGP uninstalls the APK after testing, deleting externalFilesDir too.
            // A dedicated logcat stream survives cleanup and is collected by CI.
            android.util.Log.i("HoardNativeArtifact", "BEGIN ${name.methodName}")
            notes.toString().lineSequence().forEach { line ->
                line.chunked(3_000).ifEmpty { listOf("") }.forEach {
                    android.util.Log.i("HoardNativeArtifact", it)
                }
            }
            android.util.Log.i("HoardNativeArtifact", "END ${name.methodName}")
        } finally {
            sessionId?.let {
                ChatResponseWorker.cancel(context, it)
                repo.deleteSession(it)
            }
            router?.close()
            original?.let { settings -> runBlocking {
                SettingsStore.setRouterUrl(context, settings.routerUrl)
                SettingsStore.setRouterToken(context, settings.routerToken)
            } }
            bundleRoot?.deleteRecursively() // Only this test's unique cache tree.
        }
    }

    @Test fun connectedRouterActuallyAnswersTwoPlainTurnsWithNoInstalledSkills() = runBlocking {
        assertTrue("fresh emulator has no installed skills", SkillStore.get(context).skills.value.isEmpty())
        val server = LocalRouter().also { router = it }
        original = SettingsStore.current(context)
        SettingsStore.setRouterUrl(context, server.url)
        SettingsStore.setRouterToken(context, "")
        assertTrue(RouterConnection.refresh(server.url) is RouterStatus.Connected)
        val session = repo.createSession("Native chat regression", modelId = "coding")
        sessionId = session.id
        repeat(2) { turn ->
            val prompt = "native-user-$turn"
            val reply = "native-reply-$turn"
            repo.appendMessage(session.id, ChatMessage(prompt, MessageRole.User, "Android에서 보내는 질문 $turn"))
            ChatResponseWorker.enqueue(context, session.id, "coding", reply, parentId = prompt, needsNetwork = true)
            val deadline = SystemClock.elapsedRealtime() + 30_000
            var work: List<WorkInfo>
            do {
                Thread.sleep(50)
                work = WorkManager.getInstance(context).getWorkInfosForUniqueWork("hoard-reply-${session.id}").get()
                check(SystemClock.elapsedRealtime() < deadline) { "native worker timeout: $work" }
            } while (work.size < turn + 1 || work.any { !it.state.isFinished })
            notes.appendLine("turn=$turn work=$work")
            val answer = repo.messagesOf(session.id).single { it.id == reply }
            assertTrue("every turn succeeds: $work", work.all { it.state == WorkInfo.State.SUCCEEDED })
            assertFalse(answer.isStreaming)
            assertNull(answer.errorText)
            assertEquals("Android native response", answer.text)
            assertTrue(answer.completionTokens > 0)
            assertEquals(turn + 1, server.requests.count { it == "POST /hoard/v1/responses" })
            assertTrue("request carries the actual prompt: ${server.requestBody}", server.requestBody.contains("질문 $turn"))
        }
    }

    @Test fun bracedAndIndexedSkillArgumentsRenderAndPersistOnAndroid() = runBlocking {
        val root = File(context.cacheDir, "native-skills-${UUID.randomUUID()}").also { bundleRoot = it }
        val store = SkillStore(File(root, "workspace"), File(root, "state"))
        val skill = File(store.workspaceRoot, ".claude/skills/native").apply { mkdirs() }
        File(skill, "SKILL.md").writeText("---\nname: native\ndescription: Native ICU regression\n---\n" +
            "session=\${CLAUDE_SESSION_ID}; indexed=\$ARGUMENTS[1]; first=\$0; all=\$ARGUMENTS")
        store.refresh()
        val runtime = SkillRuntime(store, TermuxBridge(context), "native-session")
        runtime.activate("native", "alpha beta", userInvoked = true)
        val body = runtime.context()
        notes.appendLine(body)
        assertTrue(body.contains("session=native-session; indexed=beta; first=alpha; all=alpha beta"))
        assertFalse(body.contains("\${CLAUDE_SESSION_ID}"))
        assertFalse(body.contains("\$ARGUMENTS[1]"))
        assertEquals(body, SkillRuntime(store, TermuxBridge(context), "native-session").context())
    }

    /** Minimal wire-compatible sleepyrouter fixture; Android has no com.sun HTTP server. */
    private class LocalRouter : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}"
        val requests = CopyOnWriteArrayList<String>()
        @Volatile var requestBody = ""
        private val thread = Thread({
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = DataInputStream(socket.getInputStream().buffered())
                    fun line(): String {
                        val bytes = ByteArrayOutputStream()
                        while (true) {
                            val b = input.read()
                            if (b < 0 || b == 10) break
                            if (b != 13) bytes.write(b)
                            check(bytes.size() < 16_384)
                        }
                        return bytes.toString("UTF-8")
                    }
                    val request = line().substringBeforeLast(" HTTP/")
                    requests += request
                    var size = 0
                    var chunked = false
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) size = header.substringAfter(':').trim().toInt()
                        if (header.startsWith("Transfer-Encoding:", true)) chunked = header.substringAfter(':').trim().equals("chunked", true)
                    }
                    check(size in 0..1_048_576)
                    // RouterAiEngine's streaming RequestBody has no known length;
                    // OkHttp uses HTTP/1.1 chunked encoding on the actual device.
                    val body = if (chunked) {
                        val bytes = ByteArrayOutputStream()
                        while (true) {
                            val count = line().substringBefore(';').trim().toInt(16)
                            check(count >= 0 && count <= 1_048_576 - bytes.size())
                            if (count == 0) {
                                while (line().isNotEmpty()) { /* trailers */ }
                                break
                            }
                            bytes.write(ByteArray(count).also(input::readFully))
                            check(line().isEmpty()) { "chunk must end with CRLF" }
                        }
                        bytes.toByteArray().decodeToString()
                    } else ByteArray(size).also(input::readFully).decodeToString()
                    val (type, response) = when (request) {
                        "GET /v1/models" -> "application/json" to """{"object":"list","data":[{"id":"coding","object":"model","owned_by":"test"}]}"""
                        "POST /hoard/v1/responses" -> {
                            requestBody = body
                            "text/event-stream" to "event: response.completed\ndata: " +
                                """{"type":"response.completed","sequence_number":1,"response":{"id":"resp_native","object":"response","model":"coding","status":"completed","output":[{"type":"message","id":"msg_native","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Android native response","annotations":[]}]}],"usage":{"input_tokens":11,"output_tokens":22,"total_tokens":33}}}""" + "\n\n"
                        }
                        else -> error("unexpected request: $request")
                    }
                    val payload = response.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(payload)
                        flush()
                    }
                }
            } catch (e: Exception) {
                if (!server.isClosed) AppLog.e("NativeFixture", "HTTP fixture failed", e)
            }
        }, "native-router-fixture").apply { isDaemon = true; start() }

        override fun close() { server.close(); thread.join(2_000) }
    }
}
