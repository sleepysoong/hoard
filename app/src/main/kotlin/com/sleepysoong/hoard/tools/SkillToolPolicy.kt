package com.sleepysoong.hoard.tools

import kotlinx.serialization.json.JsonObject

/** Claude tool names in skill frontmatter refer to Hoard's equivalent device tools. */
object SkillToolPolicy {
    private val aliases = mapOf(
        "Skill" to "skill", "Read" to "read_file", "Write" to "write_file",
        "Edit" to "edit_file", "Glob" to "glob", "Grep" to "grep",
        "Bash" to "termux_exec", "WebSearch" to "web_search", "WebFetch" to "web_fetch",
        "TodoWrite" to "todo", "Browser" to "browser_use"
    )

    fun matches(rule: String, name: String, args: JsonObject? = null): Boolean {
        val token = rule.trim()
        val tool = token.substringBefore('(')
        val actual = aliases[tool] ?: tool
        if (!glob(actual, name)) return false
        if ('(' !in token) return true
        if (args == null) return false // Keep argument-specific rules in the schema, enforce on execution.
        if (!token.endsWith(')')) return true // Malformed denial must not become an allow.
        val pattern = token.substringAfter('(').dropLast(1)
        val subject = when (name) {
            "termux_exec" -> args.string("command")
            "web_fetch" -> args.string("url")
            "web_search" -> args.string("query")
            "skill" -> args.string("skill")
            else -> args.string("path") ?: args.string("pattern")
        } ?: return false
        if (pattern.length > 512 || pattern.any { it in "[]{}?" }) return true
        if (name == "termux_exec" && (subject.any { it in ";&|`$()\\\n\r\"'<>" } ||
                Regex("^\\s*(?:/|command\\s|env\\s|builtin\\s|exec\\s|nice\\s|nohup\\s)").containsMatchIn(subject))) {
            // Fail closed: composed/dynamic Bash cannot safely be matched by an executable-prefix rule.
            // A harmless composition may also be refused; do not claim this is a shell sandbox.
            return true
        }
        // Claude's legacy Bash(git:*) means the executable and its arguments, not a colon.
        if (name == "termux_exec" && pattern.endsWith(":*")) {
            val prefix = pattern.dropLast(2)
            val command = subject.trimStart().replace(Regex("^(?:[A-Za-z_][A-Za-z0-9_]*=[^\\s]+\\s+)+"), "")
            return command == prefix || command.startsWith("$prefix ") || command.startsWith("$prefix\t")
        }
        return glob(pattern, subject)
    }

    private fun glob(pattern: String, value: String): Boolean = Regex(
        "^" + pattern.split('*').joinToString(".*") { Regex.escape(it) } + "$",
        RegexOption.DOT_MATCHES_ALL
    ).matches(value)

    val readOnlyTools = setOf("read_file", "glob", "grep", "web_search", "web_fetch")
}
