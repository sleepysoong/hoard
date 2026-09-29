package com.sleepysoong.hoard.tools.files

import com.sleepysoong.hoard.tools.Tool
import com.sleepysoong.hoard.tools.ToolException
import com.sleepysoong.hoard.tools.bool
import com.sleepysoong.hoard.tools.toolParameters
import com.sleepysoong.hoard.tools.number
import com.sleepysoong.hoard.tools.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/*
 * File tools over a [Workspace] (the app folder, optionally shared storage too):
 * read_file, write_file, edit_file, glob, grep. Text files (UTF-8) only.
 */

/** Largest file read/edited/searched (bytes). */
internal const val MAX_FILE_BYTES = 5L * 1024 * 1024


private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
    try {
        block()
    } catch (e: FileToolException) {
        throw ToolException(e.message ?: "file error")
    } catch (e: java.io.IOException) {
        throw ToolException("I/O error: ${e.message}")
    }
}

/** Strict UTF-8 decode; binary files are refused instead of returned as garbage. */
internal fun readText(file: File, ws: Workspace): String {
    if (!file.exists()) throw FileToolException("file not found: ${ws.relative(file)}")
    if (file.isDirectory) throw FileToolException("is a directory: ${ws.relative(file)} (use glob to list it)")
    if (file.length() > MAX_FILE_BYTES) throw FileToolException("file too large: ${ws.relative(file)} (${file.length()} bytes, max $MAX_FILE_BYTES)")
    val bytes = file.readBytes()
    if (bytes.indexOf(0) >= 0) throw FileToolException("binary file: ${ws.relative(file)}")
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        throw FileToolException("not a UTF-8 text file: ${ws.relative(file)}")
    }
}

/** Write via a temp file + rename so a crash never leaves a half-written file. */
internal fun writeAtomically(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, ".${file.name}.hoard-tmp")
    tmp.writeText(text, Charsets.UTF_8)
    try {
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

// ---------------------------------------------------------------- read_file

class ReadFileTool(private val ws: Workspace) : Tool {
    override val parallelSafe = true
    override val name = "read_file"
    override val title = "파일 읽기"
    override val description = "Read a UTF-8 text file. Returns lines prefixed with their line numbers (\"<n>\\t<line>\"). " +
        "Use offset (1-based first line) and limit (number of lines, default $DEFAULT_LIMIT) to page through long files. " + ws.note
    override val parameters = toolParameters {
        string("path", "File path relative to the workspace root.", required = true)
        integer("offset", "1-based line number to start from (default 1).")
        integer("limit", "Maximum number of lines to return (default $DEFAULT_LIMIT).")
    }

    override fun subject(args: JsonObject) = args.string("path").orEmpty()
    override fun summarize(output: JsonObject): String {
        val s = output.string("startLine") ?: (output["startLine"] as? JsonPrimitive)?.content
        val e = (output["endLine"] as? JsonPrimitive)?.content
        val t = (output["totalLines"] as? JsonPrimitive)?.content
        return "$s–$e / ${t}줄"
    }

    override suspend fun execute(args: JsonObject): JsonObject = io {
        val file = ws.resolve(args.string("path") ?: throw ToolException("path is required"))
        val offset = (args.number("offset") ?: 1).also { if (it < 1) throw ToolException("offset must be >= 1") }
        val limit = (args.number("limit") ?: DEFAULT_LIMIT).also { if (it < 1) throw ToolException("limit must be >= 1") }.coerceAtMost(MAX_LIMIT)
        val lines = readText(file, ws).let { if (it.isEmpty()) emptyList() else it.removeSuffix("\n").split("\n") }
        val start = offset - 1
        if (start > 0 && start >= lines.size) throw ToolException("offset $offset is past the end of the file (${lines.size} lines)")
        val sb = StringBuilder()
        var end = start
        var charsCut = false
        for (i in start until minOf(lines.size, start + limit)) {
            val line = lines[i].removeSuffix("\r").let { if (it.length > MAX_LINE_CHARS) it.take(MAX_LINE_CHARS) + "…[line truncated]" else it }
            if (sb.length + line.length > MAX_OUTPUT_CHARS) { charsCut = true; break }
            sb.append(i + 1).append('\t').append(line).append('\n')
            end = i + 1
        }
        buildJsonObject {
            put("path", ws.relative(file))
            put("content", sb.toString())
            put("startLine", if (end > start) start + 1 else 0)
            put("endLine", end)
            put("totalLines", lines.size)
            put("truncated", end < lines.size || charsCut)
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 2000
        const val MAX_LIMIT = 5000
        const val MAX_LINE_CHARS = 2000
        const val MAX_OUTPUT_CHARS = 100_000
    }
}

// ---------------------------------------------------------------- write_file

class WriteFileTool(private val ws: Workspace) : Tool {
    override val name = "write_file"
    override val title = "파일 쓰기"
    override val description = "Create or overwrite a UTF-8 text file with the given content (parent folders are created). " +
        "Overwrites without asking: read_file first if the file may already exist. " + ws.note
    override val parameters = toolParameters {
        string("path", "File path relative to the workspace root.", required = true)
        string("content", "Full new content of the file.", required = true)
    }

    override fun subject(args: JsonObject) = args.string("path").orEmpty()
    override fun summarize(output: JsonObject): String {
        val created = (output["created"] as? JsonPrimitive)?.content == "true"
        return (if (created) "새 파일 · " else "덮어씀 · ") + (output["bytes"] as? JsonPrimitive)?.content + "바이트"
    }

    override suspend fun execute(args: JsonObject): JsonObject = io {
        val path = args.string("path")?.takeIf { it.isNotBlank() } ?: throw ToolException("path is required")
        val content = args.string("content") ?: throw ToolException("content is required")
        val file = ws.resolve(path)
        if (ws.isRoot(file) || file.isDirectory) throw ToolException("is a directory: ${ws.relative(file)}")
        val bytes = content.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_FILE_BYTES) throw ToolException("content too large ($bytes bytes, max $MAX_FILE_BYTES)")
        val created = !file.exists()
        writeAtomically(file, content)
        buildJsonObject {
            put("path", ws.relative(file))
            put("bytes", bytes)
            put("created", created)
        }
    }
}

// ---------------------------------------------------------------- edit_file

class EditFileTool(private val ws: Workspace) : Tool {
    override val name = "edit_file"
    override val title = "파일 수정"
    override val description = "Replace an exact string in a text file. old_string must match exactly (including whitespace) " +
        "and, unless replace_all is true, occur exactly once — add surrounding context to make it unique. " + ws.note
    override val parameters = toolParameters {
        string("path", "File path relative to the workspace root.", required = true)
        string("old_string", "Exact text to replace.", required = true)
        string("new_string", "Replacement text (must differ from old_string).", required = true)
        boolean("replace_all", "Replace every occurrence instead of requiring a unique match (default false).")
    }

    override fun subject(args: JsonObject) = args.string("path").orEmpty()
    override fun summarize(output: JsonObject) = "${(output["replacements"] as? JsonPrimitive)?.content}곳 바꿈"

    override suspend fun execute(args: JsonObject): JsonObject = io {
        val file = ws.resolve(args.string("path") ?: throw ToolException("path is required"))
        val old = args.string("old_string") ?: throw ToolException("old_string is required")
        val new = args.string("new_string") ?: throw ToolException("new_string is required")
        val all = args.bool("replace_all") ?: false
        if (old.isEmpty()) throw ToolException("old_string must not be empty")
        if (old == new) throw ToolException("old_string and new_string are identical")
        val text = readText(file, ws)
        val count = countOccurrences(text, old)
        if (count == 0) throw ToolException("old_string not found in ${ws.relative(file)}")
        if (count > 1 && !all) throw ToolException("old_string occurs $count times in ${ws.relative(file)}; add context to make it unique or set replace_all")
        val updated = if (all) text.replace(old, new) else text.replaceFirst(old, new)
        writeAtomically(file, updated)
        buildJsonObject {
            put("path", ws.relative(file))
            put("replacements", if (all) count else 1)
        }
    }

    private fun countOccurrences(text: String, s: String): Int {
        var n = 0
        var i = text.indexOf(s)
        while (i >= 0) { n++; i = text.indexOf(s, i + s.length) }
        return n
    }
}

// ---------------------------------------------------------------- glob

class GlobTool(private val ws: Workspace) : Tool {
    override val parallelSafe = true
    override val name = "glob"
    override val title = "파일 찾기"
    override val description = "Find files by glob pattern (e.g. \"**/*.md\", \"notes/*.txt\", \"*.{kt,kts}\"), matched against paths " +
        "relative to the search folder. Returns matching file paths, newest first. " + ws.note
    override val parameters = toolParameters {
        string("pattern", "Glob pattern: * matches within a folder, ** across folders, {a,b} alternatives.", required = true)
        string("path", "Folder to search in, relative to the workspace root (default: the root).")
    }

    override fun subject(args: JsonObject) = args.string("pattern").orEmpty() + (args.string("path")?.let { " · $it" } ?: "")
    override fun summarize(output: JsonObject) = "${(output["count"] as? JsonPrimitive)?.content}개" +
        (if ((output["truncated"] as? JsonPrimitive)?.content == "true") " (일부)" else "")

    override suspend fun execute(args: JsonObject): JsonObject = io {
        val pattern = args.string("pattern")?.trim()?.takeIf { it.isNotEmpty() } ?: throw ToolException("pattern is required")
        val dir = ws.resolve(args.string("path"))
        if (!dir.isDirectory) throw FileToolException("not a folder: ${ws.relative(dir)}")
        val matchers = globMatchers(pattern)
        val hits = mutableListOf<File>()
        val complete = walkFiles(dir, ws) { f ->
            val rel = f.relativeTo(dir).path.replace(File.separatorChar, '/')
            if (matchers.any { it.matches(java.nio.file.Paths.get(rel)) }) hits += f
        }
        hits.sortByDescending { it.lastModified() }
        val shown = hits.take(MAX_RESULTS)
        buildJsonObject {
            put("path", ws.relative(dir))
            put("files", buildJsonArray { shown.forEach { add(JsonPrimitive(ws.relative(it))) } })
            put("count", hits.size)
            put("truncated", hits.size > shown.size || !complete)
            if (!complete) put("note", "folder too large to scan fully; narrow path")
        }
    }

    companion object {
        const val MAX_RESULTS = 500

        // A leading "**" + "/" should also match files at the top level (Java's glob needs a folder there).
        internal fun globMatchers(pattern: String) = buildList {
            val fs = FileSystems.getDefault()
            try {
                add(fs.getPathMatcher("glob:$pattern"))
                if (pattern.startsWith("**/")) add(fs.getPathMatcher("glob:" + pattern.removePrefix("**/")))
            } catch (e: IllegalArgumentException) {
                throw FileToolException("invalid glob pattern: ${e.message}")
            }
        }
    }
}

/**
 * Every regular file under [dir] (not following symlinked folders, skipping our temp
 * files and unreadable folders such as Android/data). Returns false when the walk
 * stopped early at the workspace's entry/time limits (a whole phone storage is big).
 */
internal suspend fun walkFiles(dir: File, ws: Workspace, onFile: suspend (File) -> Unit): Boolean {
    val stack = ArrayDeque<File>().apply { add(dir) }
    val deadline = System.currentTimeMillis() + ws.maxWalkMillis
    var visited = 0
    while (stack.isNotEmpty()) {
        val d = stack.removeLast()
        val children = d.listFiles()?.sortedBy { it.name } ?: continue
        for (f in children) {
            if (++visited % 256 == 0) {
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() > deadline) return false
            }
            if (visited > ws.maxWalkEntries) return false
            if (Files.isSymbolicLink(f.toPath())) continue
            when {
                f.isDirectory -> stack.add(f)
                f.isFile && !f.name.endsWith(".hoard-tmp") -> onFile(f)
            }
        }
    }
    return true
}

// ---------------------------------------------------------------- grep

class GrepTool(private val ws: Workspace) : Tool {
    override val parallelSafe = true
    override val name = "grep"
    override val title = "내용 검색"
    override val description = "Search file contents with a regular expression (Java regex syntax). Returns matching lines as " +
        "{file, line, text}. Optional path (file or folder), glob filter on file paths (e.g. \"**/*.md\"), " +
        "case_sensitive (default true) and max_results (default $DEFAULT_MAX). Binary and very large files are skipped. " + ws.note
    override val parameters = toolParameters {
        string("pattern", "Regular expression to search for.", required = true)
        string("path", "File or folder to search, relative to the workspace root (default: the root).")
        string("glob", "Only search files whose path (relative to path) matches this glob, e.g. \"**/*.md\".")
        boolean("case_sensitive", "Case-sensitive match (default true).")
        integer("max_results", "Maximum matching lines to return (default $DEFAULT_MAX, max $MAX_MAX).")
    }

    override fun subject(args: JsonObject) = args.string("pattern").orEmpty() + (args.string("glob")?.let { " · $it" } ?: "")
    override fun summarize(output: JsonObject) = "${(output["count"] as? JsonPrimitive)?.content}건 · 파일 ${(output["filesSearched"] as? JsonPrimitive)?.content}개 검색" +
        (if ((output["truncated"] as? JsonPrimitive)?.content == "true") " (일부)" else "")

    override suspend fun execute(args: JsonObject): JsonObject = io {
        val pattern = args.string("pattern")?.takeIf { it.isNotEmpty() } ?: throw ToolException("pattern is required")
        val caseSensitive = args.bool("case_sensitive") ?: true
        val max = (args.number("max_results") ?: DEFAULT_MAX).coerceIn(1, MAX_MAX)
        val regex = try {
            Regex(pattern, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
        } catch (e: java.util.regex.PatternSyntaxException) {
            throw ToolException("invalid regex: ${e.description} near index ${e.index}")
        }
        val target = ws.resolve(args.string("path"))
        if (!target.exists()) throw FileToolException("not found: ${ws.relative(target)}")
        val matchers = args.string("glob")?.trim()?.takeIf { it.isNotEmpty() }?.let { GlobTool.globMatchers(it) }
        val base = if (target.isDirectory) target else target.parentFile
        val deadline = System.nanoTime() + TIME_BUDGET_MS * 1_000_000
        val matches = mutableListOf<JsonObject>()
        var searched = 0
        suspend fun search(f: File) {
            if (matches.size >= max) return
            if (matchers != null) {
                val rel = java.nio.file.Paths.get(f.relativeTo(base).path.replace(File.separatorChar, '/'))
                if (matchers.none { it.matches(rel) }) return
            }
            val text = runCatching { readText(f, ws) }.getOrNull() ?: return // binary/large/non-UTF-8: skip
            searched++
            for ((i, line) in text.lineSequence().withIndex()) {
                // Pathological patterns (catastrophic backtracking) hit the time budget, not the phone.
                if (regex.containsMatchIn(DeadlineCharSequence(line.take(MAX_LINE_CHARS), deadline))) {
                    matches += buildJsonObject {
                        put("file", ws.relative(f))
                        put("line", i + 1)
                        put("text", line.take(300))
                    }
                    if (matches.size >= max) return
                }
            }
        }
        var complete = true
        try {
            if (target.isDirectory) complete = walkFiles(target, ws) { search(it) } else search(target)
        } catch (_: DeadlineCharSequence.Expired) {
            throw ToolException("search took too long (over ${TIME_BUDGET_MS / 1000}s); use a simpler pattern or narrow path/glob")
        }
        buildJsonObject {
            put("matches", kotlinx.serialization.json.JsonArray(matches))
            put("count", matches.size)
            put("filesSearched", searched)
            put("truncated", matches.size >= max || !complete)
            if (!complete) put("note", "folder too large to search fully; narrow path or glob")
        }
    }

    companion object {
        const val DEFAULT_MAX = 100
        const val MAX_MAX = 1000
        const val MAX_LINE_CHARS = 10_000
        const val TIME_BUDGET_MS = 3_000L
    }
}

/** Regex input that aborts the match once [deadline] (System.nanoTime) passes. */
internal class DeadlineCharSequence(private val s: CharSequence, private val deadline: Long) : CharSequence {
    class Expired : RuntimeException()
    private var reads = 0
    override val length get() = s.length
    override fun get(index: Int): Char {
        if (++reads and 0x3FF == 0 && System.nanoTime() > deadline) throw Expired()
        return s[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = DeadlineCharSequence(s.subSequence(startIndex, endIndex), deadline)
    override fun toString() = s.toString()
}

object FileTools {
    fun all(ws: Workspace): List<Tool> = listOf(ReadFileTool(ws), WriteFileTool(ws), EditFileTool(ws), GlobTool(ws), GrepTool(ws))

    /**
     * The app's workspace folder (private app storage), plus the phone's shared storage
     * when [fullStorage] is on. Shared storage needs Android's "All files access"
     * (MANAGE_EXTERNAL_STORAGE), checked on every access.
     */
    fun workspace(context: android.content.Context, fullStorage: Boolean = false) = Workspace(
        File(context.filesDir, "workspace"),
        extraRoots = if (fullStorage) listOf(sharedStorage()) else emptyList()
    )

    fun sharedStorage() = StorageRoot(
        label = "shared storage",
        dir = android.os.Environment.getExternalStorageDirectory(),
        available = { hasAllFilesAccess() },
        unavailableReason = "Hoard has no \"All files access\" permission. Ask the user to grant it in Hoard 도구 → 파일 → 기기 전체 저장소."
    )

    /** "All files access" granted? Unknown (the check threw) counts as no. */
    fun hasAllFilesAccess(): Boolean = runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)
}
