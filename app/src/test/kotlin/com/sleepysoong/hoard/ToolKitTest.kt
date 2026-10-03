package com.sleepysoong.hoard

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.data.ChatSession
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.PermissionProfile
import com.sleepysoong.hoard.goal.GoalService
import com.sleepysoong.hoard.termux.TermuxExecutor
import com.sleepysoong.hoard.termux.TermuxResult
import com.sleepysoong.hoard.tools.SimpleToolServices
import com.sleepysoong.hoard.tools.Tool
import com.sleepysoong.hoard.tools.ToolContext
import com.sleepysoong.hoard.tools.ToolKit
import com.sleepysoong.hoard.tools.ToolModule
import com.sleepysoong.hoard.tools.files.Workspace
import com.sleepysoong.hoard.tools.requireString
import com.sleepysoong.hoard.tools.search.SearchProvider
import com.sleepysoong.hoard.tools.search.SearchRequest
import com.sleepysoong.hoard.tools.search.SearchResponse
import com.sleepysoong.hoard.tools.toolParameters
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.file.Files

/** The tool kit: the registry must follow what is actually available, and adding a
 *  tool takes only a module (README "Tool API"). The exact offer list/version-visible
 *  wording is pinned once at the wire in ToolLoopTest, so it is not mirrored here. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ToolKitTest {
    private val repo = HoardRepository(null).apply { addSession(ChatSession("s1", "t", "sys", "coding", 32_000), emptyList()) }
    private val search = object : SearchProvider {
        override val id = "fake"
        override suspend fun search(request: SearchRequest) = SearchResponse(request.query, null, emptyList())
    }
    private val termux = object : TermuxExecutor {
        override suspend fun executeTermux(command: String, cwd: String?, timeoutMs: Long) = TermuxResult("", "", 0)
    }
    private val services = SimpleToolServices(
        searchProvider = search, workspace = Workspace(Files.createTempDirectory("kit").toFile()), termux = termux,
        todos = repo.todos, goals = GoalService(repo)
    )
    private fun names(ctx: ToolContext) = ToolKit.registry(ctx).tools.map { it.name }

    @Test fun registryFollowsServicesAndPermissions() {
        val full = names(ToolContext("s1", "coding", services))
        assertTrue("web_search" in full && "termux_exec" in full && "read_file" in full && "goal" in full)
        // A missing service = its tools aren't offered (no search provider → no web_search).
        assertFalse("web_search" in names(ToolContext("s1", "coding", SimpleToolServices(todos = repo.todos))))
        // Permission snapshot (scheduled run): only what it allows.
        val narrow = names(ToolContext("s1", "coding", services,
            PermissionProfile(web = true, files = false, fullStorage = false, termux = false), scheduledRun = true))
        assertTrue("todo" in narrow && "web_search" in narrow && "web_fetch" in narrow && "goal" in narrow)
        assertFalse("termux_exec" in narrow)
        assertFalse("read_file" in narrow)
        assertFalse("an unattended run cannot create more future work", "schedule" in narrow || "schedule_wakeup" in narrow)
    }

    @Test fun guidanceMatchesTheOfferedTools() {
        val g = ToolKit.registry(ToolContext("s1", "coding", services)).guidance()
        assertTrue("guidance exists for the offered tools", g.isNotEmpty())
        assertTrue("no duplicates", g.size == g.distinct().size)
        assertTrue("no web tools → no web guidance",
            ToolKit.registry(ToolContext("s1", "coding", SimpleToolServices(todos = repo.todos))).guidance().none { it.contains("web_search") })
    }

    /** What README "Tool API → adding a tool" shows: a module + a tool, nothing else to touch. */
    @Test fun aNewModuleIsOneObject() = runBlocking {
        val echo = object : Tool {
            override val name = "echo"
            override val description = "Echo text back."
            override val parameters = toolParameters { string("text", "What to echo.", required = true) }
            override val parallelSafe = true
            override suspend fun execute(args: JsonObject) = buildJsonObject { put("text", args.requireString("text")) }
        }
        val module = object : ToolModule {
            override val id = "echo"
            override fun tools(context: ToolContext) = listOf(echo)
        }
        val reg = ToolKit.registry(ToolContext("s1", "coding", services), ToolKit.modules + module)
        assertTrue(reg.tools.last().name == "echo")
        assertTrue(reg.execute("echo", """{"text":"hi"}""").output.contains("hi"))
        assertTrue(reg.execute("echo", "{}").output.contains("text is required"))
        assertTrue("the new tool's schema reaches the request", reg.schemas().last().toString().contains("\"echo\""))
    }
}
