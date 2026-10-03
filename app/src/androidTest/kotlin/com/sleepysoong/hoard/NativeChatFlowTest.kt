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
import com.sleepysoong.hoard.goal.Actor
import com.sleepysoong.hoard.goal.GoalService
import com.sleepysoong.hoard.data.GoalStatus
import kotlinx.serialization.json.JsonPrimitive
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

    @Test fun legacyIdleGoalRecoversAndContinuesToAnActualFileThenUserStopWins(): Unit = runBlocking {
        val file = File(context.filesDir, "workspace/native-goal-${UUID.randomUUID()}.md")
        val content = "# Native goal\nVerified on Android"
        val server = LocalRouter { body, index ->
            when (index) {
                0, 1 -> responseText("다음 작업을 준비합니다.")
                2 -> responseTool("write_file", """{"path":${JsonPrimitive(file.name)},"content":${JsonPrimitive(content)}}""")
                3 -> {
                    check(file.isFile && file.readText() == content) { "write_file did not create the actual result" }
                    responseTool("read_file", """{"path":${JsonPrimitive(file.name)}}""")
                }
                4 -> {
                    check(body.contains("Verified on Android")) { "read_file output did not reach the next model request" }
                    responseTool("goal", """{"action":"complete","evidence":"Read ${file.name}: Native goal heading and Verified on Android content match."}""")
                }
                5 -> responseText("결과 파일을 검증했습니다.")
                else -> error("Goal must stop after completion, request=$index")
            }
        }.also { router = it }
        original = SettingsStore.current(context)
        SettingsStore.setRouterUrl(context, server.url)
        SettingsStore.setRouterToken(context, "")
        val session = repo.createSession("Native goal", modelId = "coding")
        sessionId = session.id
        val goals = GoalService(repo)
        goals.create(session.id, "검증 가능한 파일 작성", Actor.User)
        repo.updateGoals { list -> list.map { if (it.sessionId == session.id) it.copy(continuationSuppressed = true) else it } }
        repo.appendMessage(session.id, ChatMessage("legacy-idle", MessageRole.Assistant, "생각 중입니다."))
        repo.flush()
        val saved = com.sleepysoong.hoard.data.HoardStore(File(context.filesDir, "hoard-store.json")).load()!!
        assertTrue("legacy marker really round-trips through persisted storage", saved.goals.single { it.sessionId == session.id }.continuationSuppressed)
        try {
            ChatResponseWorker.recoverLegacyContinuations(context)
            awaitWork { goals.current(session.id)?.status == GoalStatus.Completed }
            assertEquals(content, file.readText())
            assertFalse(goals.current(session.id)!!.continuationSuppressed)
            assertEquals(6, server.responseBodies.size)
            assertTrue("hidden prompts never become user messages", repo.messagesOf(session.id).none { it.role == MessageRole.User })

            // Hold an actual in-flight HTTP response while the user's real stop path runs.
            val entered = java.util.concurrent.CountDownLatch(1)
            val released = java.util.concurrent.CountDownLatch(1)
            server.reply = { _, _ ->
                entered.countDown()
                check(released.await(10, java.util.concurrent.TimeUnit.SECONDS))
                responseText("계속 생각 중입니다.")
            }
            goals.create(session.id, "사용자가 중단할 작업", Actor.User)
            ChatResponseWorker.enqueue(context, session.id, "coding", "stop-reply", "legacy-idle", needsNetwork = true, mode = ChatResponseWorker.MODE_CONTINUE)
            check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "worker never reached HTTP" }
            val store = androidx.lifecycle.ViewModelStore()
            lateinit var vm: com.sleepysoong.hoard.ui.chat.ChatViewModel
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                vm = androidx.lifecycle.ViewModelProvider(store,
                    androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as android.app.Application)
                )[com.sleepysoong.hoard.ui.chat.ChatViewModel::class.java]
                vm.selectSession(session.id)
            }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (vm.uiState.value.session?.id != session.id) {
                check(SystemClock.elapsedRealtime() < deadline); kotlinx.coroutines.delay(20)
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.stopReply() }
            released.countDown()
            awaitWork { goals.current(session.id)?.status == GoalStatus.Paused }
            val stoppedAt = server.responseBodies.size
            kotlinx.coroutines.delay(800)
            assertEquals("stop cannot enqueue another automatic turn", stoppedAt, server.responseBodies.size)
            assertEquals(GoalStatus.Paused, goals.current(session.id)!!.status)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        } finally { file.delete() }
    }

    private fun awaitWork(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (true) {
            val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork("hoard-reply-$sessionId").get()
            if (condition() && work.isNotEmpty() && work.all { it.state.isFinished }) {
                notes.appendLine("goal work=$work")
                return
            }
            check(SystemClock.elapsedRealtime() < deadline) { "native goal did not settle: $work; fixture=${router?.failure}" }
            Thread.sleep(50)
        }
    }

    private fun responseText(text: String) = responseOutput("""{"type":"message","id":"native_message","role":"assistant","status":"completed","content":[{"type":"output_text","text":${JsonPrimitive(text)},"annotations":[]}]}""")
    private fun responseTool(tool: String, arguments: String) = responseOutput("""{"type":"function_call","id":"fc_$tool","call_id":"call_$tool","name":"$tool","arguments":${JsonPrimitive(arguments)},"status":"completed"}""")
    private fun responseOutput(item: String) = "event: response.completed\ndata: " +
        """{"type":"response.completed","sequence_number":1,"response":{"id":"resp_native","object":"response","model":"coding","status":"completed","output":[$item],"usage":{"input_tokens":11,"output_tokens":22,"total_tokens":33}}}""" + "\n\n"

    /** Minimal wire-compatible sleepyrouter fixture; Android has no com.sun HTTP server. */
    private class LocalRouter(@Volatile var reply: ((String, Int) -> String)? = null) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}"
        val requests = CopyOnWriteArrayList<String>()
        @Volatile var requestBody = ""
        @Volatile var failure: Throwable? = null
        val responseBodies = CopyOnWriteArrayList<String>()
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
                            val index = responseBodies.size
                            responseBodies += body
                            "text/event-stream" to (reply?.invoke(body, index) ?: ("event: response.completed\ndata: " +
                                """{"type":"response.completed","sequence_number":1,"response":{"id":"resp_native","object":"response","model":"coding","status":"completed","output":[{"type":"message","id":"msg_native","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Android native response","annotations":[]}]}],"usage":{"input_tokens":11,"output_tokens":22,"total_tokens":33}}}""" + "\n\n"
                            ))
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
                failure = e
                if (!server.isClosed) AppLog.e("NativeFixture", "HTTP fixture failed", e)
            }
        }, "native-router-fixture").apply { isDaemon = true; start() }

        override fun close() { server.close(); thread.join(2_000) }
    }
}
