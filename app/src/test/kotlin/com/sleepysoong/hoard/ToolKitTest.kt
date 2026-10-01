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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.file.Files

/** The tool kit: modules → registry per turn context, the schema DSL, guidance, and adding a new tool. */
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

    @Test fun modulesBuildTheRegistryInOrderAndFollowTheContext() {
        assertEquals(listOf("skills", "todo", "web", "browser", "termux", "files", "goal", "schedule"), ToolKit.modules.map { it.id })
        assertEquals(
            listOf("todo", "web_search", "web_fetch", "termux_exec", "read_file", "write_file", "edit_file", "glob", "grep", "goal"),
            names(ToolContext("s1", "coding", services))
        )
        // A missing service = its tools aren't offered (no search provider → no web_search).
        assertFalse("web_search" in names(ToolContext("s1", "coding", SimpleToolServices(todos = repo.todos))))
        // Permission snapshot (scheduled run): only what it allows.
        val narrow = names(ToolContext("s1", "coding", services, PermissionProfile(web = true, files = false, fullStorage = false, termux = false), scheduledRun = true))
        assertEquals(listOf("todo", "web_search", "web_fetch", "goal"), narrow)
    }

    @Test fun guidanceComesFromTheOfferedTools() {
        val g = ToolKit.registry(ToolContext("s1", "coding", services)).guidance()
        assertTrue(g.any { it.startsWith("Task tracking:") })
        assertTrue(g.any { it.startsWith("Use web_search") })
        assertTrue(g.any { it.startsWith("Read before answering") })
        assertTrue(g.any { it.startsWith("Goals:") })
        assertEquals("no duplicates", g.size, g.distinct().size)
        assertTrue("no web tools → no web guidance",
            ToolKit.registry(ToolContext("s1", "coding", SimpleToolServices(todos = repo.todos))).guidance().none { it.contains("web_search") })
    }

    @Test fun schemaDsl() {
        val p = toolParameters {
            string("q", "Query.", required = true, maxLength = 10)
            integer("n", "Count.", minimum = 1, maximum = 5)
            string("mode", enum = listOf("a", "b"))
            boolean("flag", "A flag.")
        }
        assertEquals(Json.parseToJsonElement("""
            {"type":"object","properties":{
              "q":{"type":"string","description":"Query.","maxLength":10},
              "n":{"type":"integer","description":"Count.","minimum":1,"maximum":5},
              "mode":{"type":"string","enum":["a","b"]},
              "flag":{"type":"boolean","description":"A flag."}},
             "required":["q"],"additionalProperties":false}
        """), p)
        assertFalse(toolParameters(additionalProperties = null) {}.containsKey("additionalProperties"))
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
        assertEquals("echo", reg.tools.last().name)
        assertEquals("""{"text":"hi"}""", reg.execute("echo", """{"text":"hi"}""").output)
        assertTrue(reg.execute("echo", "{}").output.contains("text is required"))
        assertEquals("echo", Json.parseToJsonElement(reg.schemas().last().toString()).jsonObject["name"].toString().trim('"'))
    }
}
