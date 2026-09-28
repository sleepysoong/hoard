package com.sleepysoong.hoard.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/**
 * A function tool the model can call through sleepyrouter (Responses `tools`).
 * Tools run on the device; the router only relays the call and its output.
 *
 * Output is a JSON object in the tool's own *normalized* shape — never a
 * provider's raw response — so providers can be swapped without the model or
 * the tool schema noticing.
 */
interface Tool {
    val name: String
    /** Shown to the model: when to use it and what it does NOT do. */
    val description: String
    /** JSON Schema of the arguments object. */
    val parameters: JsonObject

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
    fun schemas(): List<JsonObject> = tools.map { t ->
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
        val args = runCatching { json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject }.getOrNull()
            ?: return error(tool.title, "", "arguments must be a JSON object")
        val subject = runCatching { tool.subject(args) }.getOrDefault("")
        return try {
            val out = tool.execute(args)
            ToolOutcome(out.toString(), tool.title, body(subject, runCatching { tool.summarize(out) }.getOrDefault("")), isError = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolException) {
            error(tool.title, subject, e.message ?: "failed")
        } catch (e: Exception) {
            error(tool.title, subject, "${e::class.simpleName}: ${e.message}")
        }
    }

    fun isParallelSafe(name: String): Boolean = synchronized(byName) { byName[name] }?.parallelSafe ?: true

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

internal fun JsonObject.string(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.number(key: String): Int? {
    val p = this[key] as? kotlinx.serialization.json.JsonPrimitive ?: return null
    return p.content.toDoubleOrNull()?.toInt()
}
