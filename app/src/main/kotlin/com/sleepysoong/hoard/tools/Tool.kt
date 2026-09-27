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

    /** Runs the call. Throw [ToolException] for a failure the model should see. */
    suspend fun execute(args: JsonObject): JsonObject

    /** One-line label for the reply's thinking steps, e.g. "웹 검색 · 서울 날씨". */
    fun label(args: JsonObject): String = name

    /** Short human summary of a result for the thinking steps. */
    fun summarize(output: JsonObject): String = ""
}

/** A failure reported back to the model as `{"error": message}` (it can adapt or answer anyway). */
class ToolException(message: String) : Exception(message)

/** What running one call produced: the JSON string sent back as function_call_output, plus UI text. */
data class ToolOutcome(val output: String, val label: String, val summary: String, val isError: Boolean)

class ToolRegistry(val tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }

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
        val tool = byName[name] ?: return error(name, "unknown tool \"$name\"; available: ${byName.keys.joinToString()}")
        val args = runCatching { json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject }.getOrNull()
            ?: return error(tool.name, "arguments must be a JSON object")
        val label = runCatching { tool.label(args) }.getOrDefault(tool.name)
        return try {
            val out = tool.execute(args)
            ToolOutcome(out.toString(), label, runCatching { tool.summarize(out) }.getOrDefault(""), isError = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolException) {
            error(label, e.message ?: "failed")
        } catch (e: Exception) {
            error(label, "${e::class.simpleName}: ${e.message}")
        }
    }

    private fun error(label: String, message: String) = ToolOutcome(
        output = buildJsonObject { put("error", message) }.toString(),
        label = label, summary = "실패: $message", isError = true
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
