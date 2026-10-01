package com.sleepysoong.hoard.skills

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.IdentityHashMap

/** The body is never evaluated here. Unknown frontmatter is deliberately retained. */
data class SkillDocument(
    val name: String,
    val description: String,
    val body: String,
    val frontmatter: Map<String, Any?>,
) {
    val argumentHint: String get() = string("argument-hint").orEmpty()
    val disableModelInvocation: Boolean get() = boolean("disable-model-invocation", false)
    val userInvocable: Boolean get() = boolean("user-invocable", true)
    val model: String? get() = string("model")
    val context: String? get() = string("context")
    val agent: String? get() = string("agent")
    val shell: String get() = string("shell") ?: "bash"
    val arguments: List<String> get() = list("arguments", tools = false)
    val disallowedTools: List<String> get() = list("disallowed-tools", tools = true)
    val allowedTools: List<String> get() = list("allowed-tools", tools = true)
    val compatibility: String? get() = string("compatibility")
    val effort: String? get() = string("effort")
    val background: Boolean get() = boolean("background", true)
    val license: String? get() = string("license")
    val paths: List<String> get() = when (val value = frontmatter["paths"]) {
        is List<*> -> value.mapNotNull { it as? String }
        is String -> value.split(',').map(String::trim).filter(String::isNotEmpty)
        else -> emptyList()
    }
    val whenToUse: String get() = string("when_to_use").orEmpty()
    val hooks: Map<String, Any?> get() = stringMap(frontmatter["hooks"])
    val metadata: Map<String, Any?> get() = stringMap(frontmatter["metadata"])

    private fun string(key: String): String? = (frontmatter[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun boolean(key: String, default: Boolean): Boolean = when (frontmatter[key]?.toString()?.lowercase()) {
        "true", "yes", "on", "1" -> true
        "false", "no", "off", "0" -> false
        else -> default
    }

    private fun list(key: String, tools: Boolean): List<String> = when (val value = frontmatter[key]) {
        is List<*> -> value.mapNotNull { it as? String }.map(String::trim).filter(String::isNotEmpty)
        is String -> if (tools) splitTools(value) else value.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        else -> emptyList()
    }

    companion object {
        const val MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
        private const val MAX_FRONTMATTER = 128 * 1024

        fun parse(text: String, directoryName: String): SkillDocument {
            require(text.length <= MAX_DOCUMENT_BYTES) { "SKILL.md exceeds the 2 MiB document limit" }
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) { "SKILL.md exceeds the 2 MiB document limit" }
            val content = text.removePrefix("\uFEFF")
            val firstEnd = content.indexOf('\n').let { if (it < 0) content.length else it }
            var body = content
            var fields: Map<String, Any?> = emptyMap()
            if (content.substring(0, firstEnd).trimEnd('\r', ' ', '\t') == "---") {
                val start = (firstEnd + 1).coerceAtMost(content.length)
                var offset = start
                var closingStart = -1
                var closingEnd = -1
                while (offset < content.length) {
                    val end = content.indexOf('\n', offset).let { if (it < 0) content.length else it }
                    if (content.substring(offset, end).trimEnd('\r', ' ', '\t') in listOf("---", "...")) {
                        closingStart = offset
                        closingEnd = (end + 1).coerceAtMost(content.length)
                        break
                    }
                    require(end - start <= MAX_FRONTMATTER) { "Skill frontmatter exceeds 128 KiB" }
                    offset = end + 1
                }
                require(closingStart >= 0) { "Skill frontmatter has no closing --- marker" }
                require(closingStart - start <= MAX_FRONTMATTER) { "Skill frontmatter exceeds 128 KiB" }
                val options = LoaderOptions().apply {
                    maxAliasesForCollections = 20
                    codePointLimit = MAX_FRONTMATTER
                    nestingDepthLimit = 32
                    isAllowDuplicateKeys = false
                    allowRecursiveKeys = false
                }
                val loaded = Yaml(SafeConstructor(options)).load<Any?>(content.substring(start, closingStart))
                require(loaded == null || loaded is Map<*, *>) { "Skill frontmatter must be a YAML mapping" }
                val normalized = normalize(loaded, IdentityHashMap(), intArrayOf(0), 0)
                fields = stringMap(normalized)
                require(fields["hooks"] == null || fields["hooks"] is Map<*, *>) { "Skill hooks must be a YAML mapping" }
                body = content.substring(closingEnd)
            }
            val name = (fields["name"] as? String)?.trim()?.takeIf(String::isNotEmpty) ?: directoryName
            val description = (fields["description"] as? String)?.trim()?.takeIf(String::isNotEmpty)
                ?: body.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
            return SkillDocument(name, description, body, fields)
        }

        /** Reject cyclic aliases and bound expansion even when aliases point to aliases. */
        private fun normalize(value: Any?, active: IdentityHashMap<Any, Boolean>, nodes: IntArray, depth: Int): Any? {
            require(++nodes[0] <= 20_000 && depth <= 32) { "Skill YAML is too complex" }
            if (value !is Map<*, *> && value !is Collection<*>) return value
            require(active.put(value, true) == null) { "Recursive YAML aliases are not supported" }
            try {
                return when (value) {
                    is Map<*, *> -> value.entries.associate { (key, item) ->
                        require(depth != 0 || key is String) { "Skill frontmatter keys must be strings" }
                        require(key !is Map<*, *> && key !is Collection<*>) { "Complex YAML mapping keys are not supported" }
                        key to normalize(item, active, nodes, depth + 1)
                    }
                    is Collection<*> -> value.map { normalize(it, active, nodes, depth + 1) }
                    else -> value
                }
            } finally {
                active.remove(value)
            }
        }

        private fun stringMap(value: Any?): Map<String, Any?> = (value as? Map<*, *>)?.entries
            ?.mapNotNull { (key, item) -> (key as? String)?.let { it to item } }?.toMap().orEmpty()

        /** Spaces/commas inside Bash(...) permission patterns are not separators. */
        private fun splitTools(value: String): List<String> {
            val result = mutableListOf<String>()
            val token = StringBuilder()
            var depth = 0
            var quote: Char? = null
            var escaped = false
            for (char in value) {
                if (escaped) { token.append(char); escaped = false; continue }
                if (char == '\\') { token.append(char); escaped = true; continue }
                if (quote != null) {
                    token.append(char)
                    if (char == quote) quote = null
                } else when {
                    char == '\'' || char == '"' -> { quote = char; token.append(char) }
                    char == '(' -> { depth++; token.append(char) }
                    char == ')' -> { depth = (depth - 1).coerceAtLeast(0); token.append(char) }
                    depth == 0 && (char.isWhitespace() || char == ',') -> {
                        if (token.isNotEmpty()) { result += token.toString(); token.clear() }
                    }
                    else -> token.append(char)
                }
            }
            if (token.isNotEmpty()) result += token.toString()
            return result
        }
    }
}

internal fun validateSkillCommand(command: String) {
    require(command.length in 1..192 && command.split(':').withIndex().all { (index, component) ->
        (index == 0 && ':' in command || '/' !in component) && component.split('/').all {
            it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) && it != "." && it != ".."
        }
    }) { "Unsafe skill command '$command': use letters, digits, dots, underscores, hyphens and namespace colons" }
    require(command.split(':').none { it.equals("goal", true) || it.equals("synced", true) || it.equals("anthropic-skills", true) }) {
        "Skill command '$command' uses a reserved name (goal, synced or anthropic-skills)"
    }
}
