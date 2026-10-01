package com.sleepysoong.hoard

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.skills.SkillRuntime
import com.sleepysoong.hoard.skills.SkillStore
import com.sleepysoong.hoard.testing.ChatHarness
import com.sleepysoong.hoard.testing.FakeRouter
import com.sleepysoong.hoard.testing.FakeRouter.Companion.completed
import com.sleepysoong.hoard.testing.FakeRouter.Companion.created
import com.sleepysoong.hoard.testing.FakeRouter.Companion.routingFrame
import com.sleepysoong.hoard.testing.FakeRouter.Companion.toolCallCompleted
import com.sleepysoong.hoard.tools.SkillTool
import com.sleepysoong.hoard.tools.ToolException
import com.sleepysoong.hoard.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** Real SKILL.md files → store → activation/tool loop → persisted turn context.
 * Failure paths: automatic code without trust, helpers edited after trust, model
 * invocation of user-only skills, unsafe YAML, and corrupt private registry.
 * No Termux/network install is simulated as a successful production operation.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SkillFlowTest {
    @get:Rule val temp = TemporaryFolder()
    @get:Rule val name = TestName()
    private lateinit var h: ChatHarness
    private lateinit var router: FakeRouter
    private var liveBundle: File? = null
    private val notes = StringBuilder()

    private fun store(): SkillStore = SkillStore(temp.newFolder("workspace"), temp.newFolder("state"))
    private fun bundle(store: SkillStore, command: String, body: String, fields: String = ""): File {
        val root = File(store.workspaceRoot, ".claude/skills/$command").apply { mkdirs() }
        File(root, "SKILL.md").writeText("---\nname: $command\ndescription: Integration test bundle\n$fields\n---\n$body\n")
        store.refresh()
        return root
    }

    @After fun finish() {
        if (::h.isInitialized) {
            h.snapshot("final")
            if (::router.isInitialized) router.requests.filter { it.path.endsWith("/responses") }.forEach { h.note("request: ${it.body}") }
            h.writeTranscript("SkillFlowTest.${name.methodName}")
        } else {
            val artifacts = File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts").apply { mkdirs() }
            File(artifacts, "SkillFlowTest.${name.methodName}.txt").writeText(notes.toString())
        }
        if (::router.isInitialized) router.close()
        liveBundle?.deleteRecursively() // only this test's uniquely named bundle
    }

    @Test fun modelLoadsLocalSkillAndInstructionsPersistIntoNextTurn() {
        h = ChatHarness()
        router = FakeRouter()
        runBlocking { SettingsStore.setRouterUrl(h.app, router.url) }
        val deadline = System.currentTimeMillis() + 5_000
        while (h.vm.settings.value.routerUrl != router.url) {
            h.idle(); Thread.sleep(5); check(System.currentTimeMillis() < deadline)
        }
        val command = "review-${UUID.randomUUID().toString().take(8)}"
        liveBundle = bundle(SkillStore.get(h.app), command, "REVIEW_REFERENCE: \$ARGUMENTS")
        val args = "literal !`printf SHOULD_NOT_EXECUTE`"
        router.enqueue(
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), toolCallCompleted(Triple("skill:0", "skill",
                buildJsonObject { put("skill", command); put("args", args) }.toString())))),
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("스킬 검토 완료"))),
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("같은 지침으로 후속 검토")))
        )
        h.vm.send("설치한 스킬로 검토", emptyList(), "coding")
        h.awaitReplies()
        assertNull(h.repo.messagesOf(h.vm.uiState.value.session!!.id).last().errorText)
        val firstRound = router.requests.filter { it.path.endsWith("/responses") }
        assertEquals(2, firstRound.size)
        assertTrue(firstRound.last().body.contains("REVIEW_REFERENCE"))
        assertTrue("argument syntax must stay literal data", firstRound.last().body.contains("SHOULD_NOT_EXECUTE"))
        h.vm.send("다음 파일도 검토", emptyList(), "coding")
        h.awaitReplies()
        assertTrue(router.requests.filter { it.path.endsWith("/responses") }.last().body.contains("REVIEW_REFERENCE"))
    }

    /** Stopping the fork's child session must cancel the parent turn that owns the fork. */
    @Test fun stoppingAForkedChildSessionCancelsTheInlineForkPromptly() {
        h = ChatHarness()
        router = FakeRouter()
        runBlocking { SettingsStore.setRouterUrl(h.app, router.url) }
        val deadline = System.currentTimeMillis() + 5_000
        while (h.vm.settings.value.routerUrl != router.url) {
            h.idle(); Thread.sleep(5); check(System.currentTimeMillis() < deadline)
        }
        liveBundle = bundle(SkillStore.get(h.app), "forkx", "포크 본문 \$ARGUMENTS", "context: fork\nbackground: false")
        router.enqueue(
            FakeRouter.Reply.Sse(listOf(created(), FakeRouter.delta("자식의 부분 답변", 1)), stallMs = 120_000))
        val sid = h.vm.uiState.value.session!!.id
        h.vm.send("/forkx 지금 작업", emptyList(), "coding")
        // The fork spawns an isolated child session whose reply then stalls.
        // Wait until its partial text arrived (the request is definitely in flight,
        // and the child→parent stop mapping is registered by then).
        val waitStart = System.currentTimeMillis()
        var child: com.sleepysoong.hoard.data.ChatSession? = null
        while (child == null) {
            h.idle(); Thread.sleep(20)
            child = h.repo.sessions.value.firstOrNull { it.id != sid }
                ?.takeIf { c -> h.repo.messagesOf(c.id).any { it.isStreaming && it.text == "자식의 부분 답변" } }
            check(System.currentTimeMillis() - waitStart < 10_000) { "fork child never streamed" }
        }
        val childId = child!!.id
        val stopAt = System.currentTimeMillis()
        com.sleepysoong.hoard.work.ChatResponseWorker.cancel(h.app, childId)
        h.awaitReplies(timeoutMs = 12_000)
        h.snapshot("parent", sid); h.snapshot("child", childId)
        val childReply = h.repo.messagesOf(childId).last()
        assertFalse("child bubble stopped", childReply.isStreaming)
        assertEquals("사용자가 중지함", childReply.errorText)
        assertTrue("partial child text kept", childReply.text.startsWith("자식의 부분 답변"))
        assertTrue(
            "parent turn cancelled quickly, not after the 120 s stall: ${System.currentTimeMillis() - stopAt}ms",
            System.currentTimeMillis() - stopAt < 8_000
        )
        assertTrue("parent reply is not left streaming",
            h.repo.messagesOf(sid).none { it.isStreaming })
    }

    @Test fun userOnlySkillRejectsModelButAcceptsExplicitInvocation() = runBlocking {
        val store = store()
        bundle(store, "manual", "사용자가 직접 요청한 작업", "disable-model-invocation: true")
        val runtime = SkillRuntime(store, null, "session-user")
        val result = ToolRegistry(listOf(SkillTool(runtime))).execute("skill", """{"skill":"manual"}""")
        notes.appendLine(result.output)
        assertTrue(result.isError)
        assertTrue(runtime.context().isBlank())
        val explicit = runtime.activate("manual", userInvoked = true)
        notes.appendLine(explicit)
        assertTrue(explicit.toString().contains("사용자가 직접 요청한 작업"))
        assertTrue(runtime.context().contains("사용자가 직접 요청한 작업"))
    }

    @Test fun editingHelperRevokesTrustBeforeDynamicExecution() = runBlocking {
        val store = store()
        val root = bundle(store, "dynamic", "현재 상태: !`bash scripts/helper.sh`")
        val helper = File(root, "scripts/helper.sh").apply { parentFile!!.mkdirs(); writeText("echo safe\n") }
        val runtime = SkillRuntime(store, null, "session-code")
        try {
            runtime.activate("dynamic")
            fail("untrusted dynamic code must be rejected before Termux")
        } catch (e: ToolException) {
            notes.appendLine(e.message)
            assertTrue(e.message.orEmpty().contains("code trust"))
        }
        val id = store.find("dynamic")!!.id
        store.setTrustedCode(id, true)
        assertTrue(store.find("dynamic")!!.trustedCode)
        helper.appendText("echo changed\n")
        try {
            runtime.activate("dynamic")
            fail("modified helper must revoke its bundle's trust")
        } catch (e: ToolException) {
            notes.appendLine(e.message)
            assertTrue(e.message.orEmpty().contains("code trust"))
        }
        assertFalse(store.find("dynamic")!!.trustedCode)
        val restarted = SkillStore(store.workspaceRoot, store.stateRoot)
        assertFalse("revocation must persist", restarted.find("dynamic")!!.trustedCode)
    }

    @Test fun unsafeYamlIsIsolatedAndHealthySkillStillLoads() = runBlocking {
        val store = store()
        bundle(store, "healthy", "읽을 수 있는 지침")
        for ((command, yaml) in listOf(
            "duplicate" to "name: duplicate\nname: replaced",
            "recursive" to "metadata: &cycle [*cycle]",
            "object" to "metadata: !!java.net.URL ['https://example.com']"
        )) {
            val root = File(store.workspaceRoot, ".claude/skills/$command").apply { mkdirs() }
            File(root, "SKILL.md").writeText("---\n$yaml\n---\nunsafe\n")
        }
        store.refresh()
        notes.appendLine(store.refreshErrors.value.joinToString("\n"))
        assertEquals(listOf("healthy"), store.skills.value.map { it.command })
        assertEquals(3, store.refreshErrors.value.size)
        assertTrue(SkillRuntime(store, null, "session-healthy").activate("healthy").toString().contains("읽을 수 있는 지침"))
    }

    @Test fun corruptRegistryIsPreservedAndCannotRetainPartialTrust() = runBlocking {
        val original = store()
        bundle(original, "trusted", "안전한 지침")
        original.setTrustedCode(original.find("trusted")!!.id, true)
        val registry = File(original.stateRoot, "registry.json")
        // A syntactically valid file with a malformed later entry can fail after
        // earlier trust flags have already been read. Recovery must discard all of them.
        val json = org.json.JSONObject(registry.readText())
        json.getJSONObject("flags").put("malformed", "not an object")
        val broken = json.toString()
        registry.writeText(broken)
        val recovered = SkillStore(original.workspaceRoot, original.stateRoot)
        notes.appendLine(recovered.refreshErrors.value.joinToString("\n"))
        assertTrue(recovered.refreshErrors.value.isNotEmpty())
        val backup = original.stateRoot.listFiles()!!.single { it.name.startsWith("registry.corrupt-") }
        assertEquals(broken, backup.readText())
        assertFalse("partial trust must never survive a corrupt registry", recovered.find("trusted")!!.trustedCode)
        assertTrue(SkillRuntime(recovered, null, "recovered").activate("trusted").toString().contains("안전한 지침"))
    }
}
