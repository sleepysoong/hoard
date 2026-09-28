package com.sleepysoong.hoard

import com.sleepysoong.hoard.tools.ToolRegistry
import com.sleepysoong.hoard.tools.files.FileTools
import com.sleepysoong.hoard.tools.files.Workspace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** read_file / write_file / edit_file / glob / grep over a temp workspace (plain JVM). */
class FileToolsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var reg: ToolRegistry

    @Before fun setUp() {
        root = tmp.newFolder("workspace")
        reg = ToolRegistry(FileTools.all(Workspace(root)))
    }

    private fun call(name: String, vararg args: Pair<String, Any>): Pair<Boolean, JsonObject> = runBlocking {
        val json = buildJsonObject {
            args.forEach { (k, v) -> when (v) { is String -> put(k, v); is Int -> put(k, v); is Boolean -> put(k, v); else -> error(v) } }
        }
        val o = reg.execute(name, json.toString())
        o.isError to Json.parseToJsonElement(o.output).jsonObject
    }
    private fun ok(name: String, vararg args: Pair<String, Any>): JsonObject = call(name, *args).also { assertFalse("$name failed: ${it.second}", it.first) }.second
    private fun err(name: String, vararg args: Pair<String, Any>): String = call(name, *args).also { assertTrue("$name should fail: ${it.second}", it.first) }.second["error"]!!.jsonPrimitive.content
    private fun JsonObject.s(k: String) = (this[k] as JsonPrimitive).content

    @Test fun writeThenReadWithLineNumbersAndPaging() {
        val w = ok("write_file", "path" to "notes/todo.md", "content" to "one\ntwo\nthree\nfour\n")
        assertEquals("notes/todo.md", w.s("path"))
        assertEquals("true", w.s("created"))
        assertEquals("one\ntwo\nthree\nfour\n", File(root, "notes/todo.md").readText())

        val all = ok("read_file", "path" to "notes/todo.md")
        assertEquals("1\tone\n2\ttwo\n3\tthree\n4\tfour\n", all.s("content"))
        assertEquals("4", all.s("totalLines"))
        assertEquals("false", all.s("truncated"))

        val page = ok("read_file", "path" to "notes/todo.md", "offset" to 2, "limit" to 2)
        assertEquals("2\ttwo\n3\tthree\n", page.s("content"))
        assertEquals("2", page.s("startLine")); assertEquals("3", page.s("endLine")); assertEquals("true", page.s("truncated"))

        assertEquals("false", ok("write_file", "path" to "notes/todo.md", "content" to "x").s("created"))
        assertTrue(err("read_file", "path" to "notes/todo.md", "offset" to 9).contains("past the end"))
        assertTrue(err("read_file", "path" to "nope.txt").contains("not found"))
        assertTrue(err("read_file", "path" to "notes").contains("is a directory"))
        assertEquals("empty file", "", ok("read_file", "path" to "empty.txt".also { File(root, it).writeText("") }).s("content"))
    }

    @Test fun binaryAndNonUtf8AreRefused() {
        File(root, "img.bin").writeBytes(byteArrayOf(1, 0, 2))
        File(root, "latin.txt").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        assertTrue(err("read_file", "path" to "img.bin").contains("binary"))
        assertTrue(err("read_file", "path" to "latin.txt").contains("UTF-8"))
    }

    @Test fun editRequiresAUniqueExactMatchUnlessReplaceAll() {
        File(root, "a.kt").writeText("val x = 1\nval y = 1\nval z = 2\n")
        assertTrue(err("edit_file", "path" to "a.kt", "old_string" to "= 1", "new_string" to "= 9").contains("occurs 2 times"))
        assertTrue(err("edit_file", "path" to "a.kt", "old_string" to "missing", "new_string" to "x").contains("not found"))
        assertTrue(err("edit_file", "path" to "a.kt", "old_string" to "z", "new_string" to "z").contains("identical"))
        assertEquals("1", ok("edit_file", "path" to "a.kt", "old_string" to "val x = 1", "new_string" to "val x = 5").s("replacements"))
        assertEquals("val x = 5\nval y = 1\nval z = 2\n", File(root, "a.kt").readText())
        // "val" occurs 3 times; replace_all changes every one.
        assertEquals("3", ok("edit_file", "path" to "a.kt", "old_string" to "val", "new_string" to "var", "replace_all" to true).s("replacements"))
        assertEquals("var x = 5\nvar y = 1\nvar z = 2\n", File(root, "a.kt").readText())
    }

    @Test fun globMatchesAcrossFoldersNewestFirst() {
        File(root, "top.md").writeText("t")
        File(root, "docs/a.md").apply { parentFile.mkdirs(); writeText("a") }
        File(root, "docs/deep/b.md").apply { parentFile.mkdirs(); writeText("b") }
        File(root, "docs/c.txt").writeText("c")
        File(root, "docs/deep/b.md").setLastModified(System.currentTimeMillis() + 10_000)
        val all = ok("glob", "pattern" to "**/*.md")
        assertEquals(listOf("docs/deep/b.md", "docs/a.md", "top.md").toSet(), all["files"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals("newest first", "docs/deep/b.md", all["files"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(listOf("docs/a.md"), ok("glob", "pattern" to "*.md", "path" to "docs")["files"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, ok("glob", "pattern" to "docs/*.{md,txt}")["files"]!!.jsonArray.size)
        assertTrue(err("glob", "pattern" to "*", "path" to "top.md").contains("not a folder"))
    }

    @Test fun grepFindsLinesWithOptions() {
        File(root, "src/Main.kt").apply { parentFile.mkdirs(); writeText("fun main() {\n    println(\"Hello\")\n}\n") }
        File(root, "src/notes.md").writeText("hello world\nHELLO again\n")
        File(root, "src/blob.bin").writeBytes(byteArrayOf(104, 101, 108, 108, 111, 0))

        val cs = ok("grep", "pattern" to "[Hh]ello")
        assertEquals(setOf("src/Main.kt:2", "src/notes.md:1"), cs["matches"]!!.jsonArray.map { it.jsonObject.let { m -> m.s("file") + ":" + m.s("line") } }.toSet())
        val ci = ok("grep", "pattern" to "hello", "case_sensitive" to false, "glob" to "**/*.md")
        assertEquals(listOf(1, 2), ci["matches"]!!.jsonArray.map { it.jsonObject.s("line").toInt() })
        assertEquals("binary file skipped", "2", ok("grep", "pattern" to "x").s("filesSearched"))
        val capped = ok("grep", "pattern" to "l", "max_results" to 1)
        assertEquals(1, capped["matches"]!!.jsonArray.size); assertEquals("true", capped.s("truncated"))
        assertEquals(1, ok("grep", "pattern" to "println", "path" to "src/Main.kt")["matches"]!!.jsonArray.size)
        assertTrue(err("grep", "pattern" to "(unclosed").contains("invalid regex"))
    }

    @Test fun catastrophicRegexHitsTheTimeBudgetNotThePhone() {
        // Measured on JDK 21: ((a+)+)+b on 30 a's runs for well over 3 s unguarded.
        File(root, "evil.txt").writeText("a".repeat(30) + "\n")
        val started = System.currentTimeMillis()
        val e = err("grep", "pattern" to "((a+)+)+b")
        assertTrue(e, e.contains("too long"))
        assertTrue("bounded", System.currentTimeMillis() - started < 20_000)
    }

    @Test fun nothingOutsideTheWorkspaceIsReachable() {
        val secret = tmp.newFile("secret.txt").apply { writeText("private") }
        val outside = tmp.newFolder("outside")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "secret-link.txt").toPath(), secret.toPath())
        for (p in listOf("../secret.txt", "notes/../../secret.txt", secret.absolutePath, "/etc/passwd", "secret-link.txt", "link/x.txt")) {
            assertTrue("read $p", err("read_file", "path" to p).contains("outside the workspace"))
            assertTrue("write $p", err("write_file", "path" to p, "content" to "pwned").contains("outside the workspace"))
        }
        assertEquals("private", secret.readText())
        assertTrue(outside.listFiles()!!.isEmpty())
        assertTrue(err("glob", "pattern" to "*", "path" to "..").contains("outside the workspace"))
        assertTrue(err("grep", "pattern" to "private", "path" to "../").contains("outside the workspace"))
        // Symlinked folders inside are not walked either.
        File(outside, "leak.txt").writeText("private")
        assertEquals("0", ok("grep", "pattern" to "private").s("count"))
        assertTrue(ok("glob", "pattern" to "**/*.txt")["files"]!!.jsonArray.isEmpty())
        // Absolute paths *inside* the workspace are fine.
        ok("write_file", "path" to File(root, "in.txt").absolutePath, "content" to "ok")
        assertEquals("in.txt", ok("read_file", "path" to "in.txt").s("path"))
    }

    @Test fun cardsShowTitleAndBody() = runBlocking {
        File(root, "a.txt").writeText("1\n2\n")
        val o = reg.execute("read_file", """{"path":"a.txt"}""")
        assertEquals("파일 읽기", o.title)
        assertEquals("a.txt\n1–2 / 2줄", o.body)
        assertEquals(listOf("read_file", "write_file", "edit_file", "glob", "grep"), reg.tools.map { it.name })
    }
}
