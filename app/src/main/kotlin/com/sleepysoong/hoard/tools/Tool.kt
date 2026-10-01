package com.sleepysoong.hoard.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.sleepysoong.hoard.skills.SkillRuntime
import kotlin.coroutines.cancellation.CancellationException

/**
 * A function tool the model can call through sleepyrouter (Responses `tools`).
 * Tools run on the device; the router only relays the call and its output.
 * Tools are grouped into [ToolModule]s and built per turn by [ToolKit]; see README "Tool API".
 *
 * Output is a JSON object in the tool's own *normalized* shape — never a
 * provider's raw response — so providers can be swapped without the model or
 * the tool schema noticing.
 */
interface Tool {
    /** Function name the model calls (snake_case, unique in a registry). */
    val name: String
    /** Shown to the model: when to use it and what it does NOT do. */
    val description: String
    /** JSON Schema of the arguments object — build it with [toolParameters]. */
    val parameters: JsonObject

    /**
     * Optional system guidance added to the request's instructions while this tool is
     * offered (how to use it well across turns). Keep it short; the description covers the call itself.
     */
    val guidance: String? get() = null

    /**
     * Read-only (no side effects): may run at the same time as other such calls of the
     * same round. Tools that change anything (files, shell) run alone, in call order.
     */
    val parallelSafe: Boolean get() = false

    /** Runs the call. Throw [ToolException] for a failure the model should see. */
    suspend fun execute(args: JsonObject): JsonObject

    /** Card title in the reply's 작업 list, e.g. "웹 검색". */
    val title: String get() = name

    /** What this call is about (card body, first line), e.g. the query or the URL. */
    fun subject(args: JsonObject): String = ""

    /** Short human summary of a result (card body, after the subject). */
    fun summarize(output: JsonObject): String = ""

    /**
     * What an earlier result of this tool becomes once a later call of it returned in the
     * same turn (null = keep it). For snapshots that go stale — a browser page state whose
     * element ids are no longer valid — so the turn's context doesn't grow with every step.
     */
    fun supersede(output: JsonObject): JsonObject? = null
}

/** A failure reported back to the model as `{"error": message}` (it can adapt or answer anyway). */
class ToolException(message: String) : Exception(message)

/**
 * What running one call produced: the JSON string sent back as function_call_output,
 * plus the card text ([title] / [body]) every tool shows the same way.
 */
data class ToolOutcome(val output: String, val title: String, val body: String, val isError: Boolean)

class ToolRegistry(initial: List<Tool> = emptyList()) {
    private val byName = LinkedHashMap<String, Tool>()
    /** Per-turn runtime; populated only by ToolKit, never by individual tools. */
    var skillRuntime: SkillRuntime? = null
    var readOnly: Boolean = false
    var onWorkspaceFile: (suspend (String) -> Unit)? = null
    var stopRequested: String? = null
        private set

    private fun denied(name: String, args: JsonObject? = null): Boolean =
        (readOnly && name !in SkillToolPolicy.readOnlyTools) ||
            skillRuntime?.disallowedTools().orEmpty().any { SkillToolPolicy.matches(it, name, args) }

    init { initial.forEach(::register) }

    /** Adds a tool (e.g. `register(TermuxExecTool(termuxBridge))`). Names must be unique. */
    fun register(tool: Tool): ToolRegistry = apply {
        synchronized(byName) {
            require(tool.name !in byName) { "tool ${tool.name} is already registered" }
            byName[tool.name] = tool
        }
    }

    val tools: List<Tool> get() = synchronized(byName) { byName.values.toList() }

    val isEmpty: Boolean get() = tools.isEmpty()

    /** Responses API function tool definitions. */
    fun schemas(): List<JsonObject> = tools.filterNot { denied(it.name) }.map { t ->
        buildJsonObject {
            put("type", "function")
            put("name", t.name)
            put("description", t.description)
            put("parameters", t.parameters)
        }
    }

    /**
     * Executes a model's call. Never throws except for cancellation: bad arguments,
     * unknown tools and tool failures become an `{"error": …}` output the model reads.
     */
    suspend fun execute(name: String, argumentsJson: String): ToolOutcome {
        val tool = synchronized(byName) { byName[name] } ?: return error(name, "", "unknown tool \"$name\"; available: ${byName.keys.joinToString()}")
        var args = runCatching { json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject }.getOrNull()
            ?: return error(tool.title, "", "arguments must be a JSON object")
        val subject = runCatching { tool.subject(args) }.getOrDefault("")
        var invoked = false
        var pathSkillContext: String? = null
        return try {
            stopRequested?.let { throw ToolException("skill hook stopped this turn: $it") }
            if (denied(name, args)) throw ToolException("tool '$name' is disallowed for this skill turn")
            if (name in setOf("read_file", "write_file", "edit_file", "glob", "grep")) {
                val previous = skillRuntime?.context()
                // Skill activation is best effort here: a skill that can't be prepared must
                // never turn an ordinary read_file / glob into a tool error.
                args.string("path")?.let { path ->
                    try { onWorkspaceFile?.invoke(path) } catch (e: CancellationException) { throw e } catch (_: Exception) { Unit }
                }
                skillRuntime?.context()?.takeIf { it != previous && it.isNotBlank() }?.let { pathSkillContext = it }
            }
            val pre = skillRuntime?.hook("PreToolUse", hookInput(name, args))
            val specific = pre?.get("hookSpecificOutput") as? JsonObject
            if ((specific?.get("permissionDecision") as? JsonPrimitive)?.contentOrNull == "deny") {
                throw ToolException((specific["permissionDecisionReason"] as? JsonPrimitive)?.contentOrNull ?: "blocked by skill hook")
            }
            if ((pre?.get("continue") as? JsonPrimitive)?.contentOrNull == "false") {
                stopRequested = (pre["stopReason"] as? JsonPrimitive)?.contentOrNull ?: "blocked by skill hook"
                throw ToolException(stopRequested!!)
            }
            (specific?.get("updatedInput") as? JsonObject)?.let { args = it }
            // Hook rewrites never bypass a disallowed-tools pattern.
            if (denied(name, args)) throw ToolException("rewritten tool call is disallowed")
            invoked = true
            val result = tool.execute(args)
            val activated = pathSkillContext
            val out = if (activated == null) result else buildJsonObject {
                result.forEach { (key, value) -> put(key, value) }
                put("activated_skill_context", activated)
            }
            // Post hooks must not turn a completed side effect into a retry of that effect.
            val post = try {
                skillRuntime?.hook("PostToolUse", hookInput(name, args, out))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                buildJsonObject { put("warning", "PostToolUse hook: ${e.message}") }
            }
            if ((post?.get("continue") as? JsonPrimitive)?.contentOrNull == "false") {
                stopRequested = (post["stopReason"] as? JsonPrimitive)?.contentOrNull ?: "stopped by skill hook"
            }
            val replacement = (post?.get("hookSpecificOutput") as? JsonObject)?.get("updatedToolOutput")
            val output = replacement ?: if (post == null) out else buildJsonObject {
                out.forEach { (key, value) -> put(key, value) }
                put("skill_hook", post)
            }
            ToolOutcome(output.toString(), tool.title, body(subject, runCatching { tool.summarize(out) }.getOrDefault("")), isError = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolException) {
            if (invoked) failureHook(name, args, e.message.orEmpty())
            error(tool.title, subject, e.message ?: "failed")
        } catch (e: Exception) {
            if (invoked) failureHook(name, args, e.message.orEmpty())
            error(tool.title, subject, "${e::class.simpleName}: ${e.message}")
        }
    }

    private fun hookInput(name: String, args: JsonObject, output: JsonObject? = null) = buildJsonObject {
        put("tool_name", name)
        put("tool_input", args)
        output?.let { put("tool_response", it) }
    }

    private suspend fun failureHook(name: String, args: JsonObject, reason: String) {
        try {
            skillRuntime?.hook("PostToolUseFailure", buildJsonObject {
                put("tool_name", name); put("tool_input", args); put("error", reason)
            })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Preserve the original tool failure, not a secondary notification hook failure.
        }
    }

    /** System guidance of every registered tool, in registration order, without duplicates. */
    fun guidance(): List<String> = tools.mapNotNull { it.guidance?.trim()?.takeIf { g -> g.isNotEmpty() } }.distinct()

    /** [Tool.supersede] of an earlier output (JSON string), or null to keep it unchanged. */
    fun supersede(name: String, outputJson: String): String? {
        val tool = synchronized(byName) { byName[name] } ?: return null
        val out = runCatching { json.parseToJsonElement(outputJson).jsonObject }.getOrNull() ?: return null
        return runCatching { tool.supersede(out) }.getOrNull()?.toString()
    }

    /**
     * Skill hooks run in call order and path activation is a state change, so while
     * either is live the file tools stay serial too.
     */
    fun isParallelSafe(name: String): Boolean =
        !hasHooks() &&
            !(onWorkspaceFile != null && name in setOf("read_file", "write_file", "edit_file", "glob", "grep")) &&
            (synchronized(byName) { byName[name] }?.parallelSafe ?: true)

    /** Whether any skill hook is live this turn. */
    fun hasHooks(): Boolean = skillRuntime?.hasHooks() == true
    /** Card title/body for a call before it runs. */
    fun preview(name: String, argumentsJson: String): Pair<String, String> {
        val tool = synchronized(byName) { byName[name] } ?: return name to ""
        val args = runCatching { json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject }.getOrNull() ?: return tool.title to ""
        return tool.title to runCatching { tool.subject(args) }.getOrDefault("")
    }

    private fun body(subject: String, detail: String) = listOf(subject, detail).filter { it.isNotBlank() }.joinToString("\n")

    private fun error(title: String, subject: String, message: String) = ToolOutcome(
        output = buildJsonObject { put("error", message) }.toString(),
        title = title, body = body(subject, "실패: $message"), isError = true
    )

    companion object {
        internal val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
