package com.sleepysoong.hoard

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.data.todo.TodoStatus
import com.sleepysoong.hoard.testing.ChatHarness
import com.sleepysoong.hoard.testing.FakeRouter
import com.sleepysoong.hoard.tools.ToolRegistry
import com.sleepysoong.hoard.tools.TodoTool
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Failure inventory: cross-session IDs, invalid transitions/arguments, concurrent starts,
 * failed commits publishing phantom state, process death, fork aliasing, deleted-session
 * resurrection, context trimming and repeated reminder injection. Artifacts: TodoFlowTest.*.txt. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TodoFlowTest {
    @get:Rule val name = TestName()
    private lateinit var h: ChatHarness
    private lateinit var root: File
    private var router: FakeRouter? = null

    @Before fun start() {
        root = Files.createTempDirectory("todo-flow").toFile()
        h = ChatHarness(storeFile = File(root, "chat.json"))
    }

    private val sid get() = h.vm.uiState.value.session!!.id
    private fun registry(session: String = sid) = ToolRegistry(listOf(TodoTool(h.repo.todos, session)))
    private fun call(json: String, session: String = sid) = runBlocking { registry(session).execute("todo", json) }
    private fun create(content: String, session: String = sid): String {
        val out = call("""{"op":"create","content":"$content"}""", session)
        assertFalse(out.output, out.isError)
        return h.repo.todos.list(session).last().id
    }
    private fun update(id: String, status: String, session: String = sid) =
        call("""{"op":"update","id":"$id","status":"$status"}""", session)

    @After fun finish() {
        if (::h.isInitialized) {
            h.note("todos: ${h.repo.todos.state.value}")
            h.snapshot("final")
            router?.requests?.forEach { h.note("wire: ${it.body}") }
            h.writeTranscript("TodoFlowTest.${name.methodName}")
            h.destroyViewModel()
        }
        router?.close()
        com.sleepysoong.hoard.data.HoardRepository.resetForTests()
        root.deleteRecursively()
    }

    @Test fun routerToolLoopPersistsAndResumesWithOneEphemeralReminder() {
        val r = FakeRouter().also { router = it }
        runBlocking {
            SettingsStore.setRouterUrl(h.app, r.url)
        }
        r.enqueue(
            FakeRouter.Reply.Sse(listOf(FakeRouter.toolCallCompleted(
                Triple("t1", "todo", """{"op":"create","content":"설계 확인"}"""),
                Triple("t2", "todo", """{"op":"create","content":"영속 상태 구현"}""")
            ))),
            FakeRouter.Reply.Sse(listOf(FakeRouter.completed("두 단계로 진행합니다.")))
        )
        h.vm.send("설계 확인하고 영속 상태 구현해줘", emptyList(), "coding")
        h.awaitReplies()
        assertNull(h.messages().last().errorText)
        val tasks = h.repo.todos.list(sid)
        assertEquals(listOf("설계 확인", "영속 상태 구현"), tasks.map { it.content })
        assertEquals(2, h.vm.uiState.value.todos.size)
        h.repo.flush()
        h.destroyViewModel()
        h = ChatHarness(storeFile = File(root, "chat.json"))
        runBlocking { SettingsStore.setRouterUrl(h.app, r.url) }
        assertEquals(tasks, h.repo.todos.list(sid))
        // Force old conversation out of the request. Execution state survives independently.
        h.vm.setContextLimit(1)
        r.enqueue(
            FakeRouter.Reply.Sse(listOf(FakeRouter.toolCallCompleted(
                Triple("u1", "todo", """{"op":"update","id":"${tasks[0].id}","status":"in_progress"}"""),
                Triple("u2", "todo", """{"op":"update","id":"${tasks[0].id}","status":"completed"}"""),
                Triple("u3", "todo", """{"op":"update","id":"${tasks[1].id}","status":"in_progress"}""")
            ))),
            FakeRouter.Reply.Sse(listOf(FakeRouter.completed("설계를 확인했고 구현 중입니다.")))
        )
        h.vm.send("계속해", emptyList(), "coding")
        h.awaitReplies()
        assertNull(h.messages().last().errorText)
        val bodies = r.requests.filter { it.path.endsWith("/responses") }.map { Json.parseToJsonElement(it.body).jsonObject }
        fun reminders(i: Int) = bodies[i]["input"]!!.jsonArray.filter { it.jsonObject["role"]?.jsonPrimitive?.content == "developer" }
        assertTrue(reminders(0).isEmpty())
        assertEquals(1, reminders(2).size)
        assertTrue(reminders(2).single().toString().contains(tasks[0].id))
        assertTrue(reminders(3).isEmpty())
        assertFalse(h.messages().any { it.text.contains("Current session tasks") })
        assertEquals(listOf(TodoStatus.Completed, TodoStatus.InProgress), h.repo.todos.list(sid).map { it.status })
        assertFalse(bodies[0]["tools"].toString().contains("priority"))
    }

    @Test fun validationIsolationAndConcurrentStartsAreAtomic() {
        val a = create("현재 구조 확인")
        val b = create("기능 구현")
        val other = h.repo.createSession("다른 세션").id
        assertTrue(update(a, "completed").isError)
        assertTrue(update(a, "in_progress", other).isError)
        for (bad in listOf(
            """{"op":"create","content":"","status":"completed"}""",
            """{"op":"create","content":"작업","priority":"high"}""",
            """{"op":"update","id":"$a"}""",
            """{"op":"update","id":"$a","status":"unknown"}""",
            """{"op":"clear","sessionId":"$other"}"""
        )) assertTrue(bad, call(bad).isError)
        val reg = registry()
        val results = runBlocking {
            listOf(a, b).map { id -> async(Dispatchers.IO) { reg.execute("todo", """{"op":"update","id":"$id","status":"in_progress"}""") } }.awaitAll()
        }
        assertEquals(1, results.count { !it.isError })
        assertEquals(1, h.repo.todos.list(sid).count { it.status == TodoStatus.InProgress })
        val active = h.repo.todos.list(sid).single { it.status == TodoStatus.InProgress }.id
        assertFalse(update(active, "completed").isError)
        assertNull(h.repo.todos.reminder(other))
        assertFalse(update(active, "in_progress").isError) // reopen
        assertFalse(update(active, "cancelled").isError)
        assertTrue(update(active, "completed").isError)
        assertFalse(update(active, "pending").isError) // restore
        assertFalse(call("""{"op":"remove","id":"$a"}""").isError)
        assertEquals(listOf(b), h.repo.todos.list(sid).map { it.id })
        assertFalse(call("""{"op":"clear"}""").isError)
        assertTrue(h.repo.todos.list(sid).isEmpty())
        assertNull(h.repo.todos.reminder(sid))
    }

    @Test fun failedCommitDoesNotPublishAndForkGetsIndependentActiveSnapshot() {
        val done = create("이미 완료한 조사")
        update(done, "in_progress"); update(done, "completed")
        val active = create("실행 중인 구현")
        update(active, "in_progress")
        create("결과 확인")
        h.repo.appendMessage(sid, com.sleepysoong.hoard.data.ChatMessage("fork-point", com.sleepysoong.hoard.data.MessageRole.User, "진행해"))
        val branch = h.repo.branchFrom(sid, "fork-point", "별도 접근")!!
        val forked = h.repo.todos.list(branch.id)
        assertEquals(listOf("실행 중인 구현", "결과 확인"), forked.map { it.content })
        assertTrue(forked.none { it.id in h.repo.todos.list(sid).map { t -> t.id } })
        assertFalse(update(forked[0].id, "completed", branch.id).isError)
        assertEquals(TodoStatus.InProgress, h.repo.todos.list(sid).first { it.id == active }.status)
        val before = h.repo.todos.state.value
        SQLiteDatabase.openDatabase(File(root, "chat.todos.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER fail_todo_insert BEFORE INSERT ON todos BEGIN SELECT RAISE(ABORT, 'disk write refused'); END")
            assertTrue(call("""{"op":"create","content":"저장 실패"}""").isError)
            assertEquals(before, h.repo.todos.state.value)
            assertEquals(before[sid], h.repo.todos.list(sid))
            db.execSQL("DROP TRIGGER fail_todo_insert")
        }
        val stale = registry(branch.id)
        h.repo.deleteSession(branch.id)
        assertTrue(runBlocking { stale.execute("todo", """{"op":"create","content":"늦은 호출"}""") }.isError)
        assertNull(h.repo.todos.state.value[branch.id])
        h.repo.flush()
        val original = sid
        h.destroyViewModel()
        h = ChatHarness(storeFile = File(root, "chat.json"))
        assertEquals(before[original], h.repo.todos.list(original))
        assertNull(h.repo.todos.state.value[branch.id])
    }
}
