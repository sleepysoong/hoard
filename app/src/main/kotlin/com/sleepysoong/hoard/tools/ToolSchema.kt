package com.sleepysoong.hoard.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject

/**
 * JSON-Schema DSL for a tool's arguments (always an object at the root, which every
 * router bridge accepts):
 *
 * ```
 * override val parameters = toolParameters {
 *     string("query", "Search query.", required = true)
 *     integer("count", "Number of results.", minimum = 1, maximum = 10)
 *     string("freshness", "Only recent pages.", enum = listOf("day", "week"))
 *     boolean("case_sensitive", "Case-sensitive match.")
 * }
 * ```
 * [additionalProperties]: `false` (default) rejects unknown keys in the schema; `null` omits the key.
 */
fun toolParameters(additionalProperties: Boolean? = false, block: ToolParameters.() -> Unit): JsonObject =
    ToolParameters().apply(block).build(additionalProperties)

class ToolParameters internal constructor() {
    private val properties = LinkedHashMap<String, JsonObject>()
    private val required = mutableListOf<String>()

    fun string(
        name: String,
        description: String? = null,
        required: Boolean = false,
        enum: List<String>? = null,
        minLength: Int? = null,
        maxLength: Int? = null
    ) = add(name, required, buildMap {
        put("type", JsonPrimitive("string"))
        description?.let { put("description", JsonPrimitive(it)) }
        enum?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
        minLength?.let { put("minLength", JsonPrimitive(it)) }
        maxLength?.let { put("maxLength", JsonPrimitive(it)) }
    })

    fun integer(name: String, description: String? = null, required: Boolean = false, minimum: Long? = null, maximum: Long? = null) =
        add(name, required, buildMap {
            put("type", JsonPrimitive("integer"))
            description?.let { put("description", JsonPrimitive(it)) }
            minimum?.let { put("minimum", JsonPrimitive(it)) }
            maximum?.let { put("maximum", JsonPrimitive(it)) }
        })

    fun boolean(name: String, description: String? = null, required: Boolean = false) =
        add(name, required, buildMap {
            put("type", JsonPrimitive("boolean"))
            description?.let { put("description", JsonPrimitive(it)) }
        })

    private fun add(name: String, isRequired: Boolean, schema: Map<String, JsonElement>) {
        require(name !in properties) { "duplicate parameter $name" }
        properties[name] = JsonObject(schema)
        if (isRequired) required += name
    }

    internal fun build(additionalProperties: Boolean?): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", JsonObject(properties))
        put("required", JsonArray(required.map(::JsonPrimitive)))
        additionalProperties?.let { put("additionalProperties", JsonPrimitive(it)) }
    }
}

// ---- argument accessors (the model's JSON arguments) ----------------------------------

/** A string argument (JSON string only), or null. */
fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A string argument that must be present and non-blank ([ToolException] otherwise). */
fun JsonObject.requireString(key: String): String =
    string(key)?.takeIf { it.isNotBlank() } ?: throw ToolException("$key is required")

/** A numeric argument as Int (accepts 5, 5.0 and "5"), or null. */
fun JsonObject.number(key: String): Int? = long(key)?.toInt()

/** A numeric argument as Long (accepts 5, 5.0 and "5"), or null. */
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()

/** A boolean argument, or null. */
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
