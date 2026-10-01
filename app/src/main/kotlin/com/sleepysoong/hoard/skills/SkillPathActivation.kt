package com.sleepysoong.hoard.skills

import java.io.File

/** Path-scoped skills activate when the agent actually works on a matching workspace file. */
class SkillPathActivation(private val store: SkillStore, private val runtime: SkillRuntime) {
    private val activated = mutableSetOf<String>()

    suspend fun onFile(path: String) {
        val target = if (File(path).isAbsolute) File(path) else File(store.workspaceRoot, path)
        val root = store.workspaceRoot.canonicalFile.toPath()
        val canonical = target.canonicalFile.toPath()
        if (!canonical.startsWith(root)) return
        for (skill in store.skills.value) {
            if (!skill.enabled || skill.document.disableModelInvocation || skill.id in activated || skill.document.context == "fork") continue
            val patterns = when (val paths = skill.document.frontmatter["paths"]) {
                is String -> splitPatterns(paths)
                is List<*> -> paths.filterIsInstance<String>()
                else -> emptyList()
            }
            val scope = generateSequence(skill.directory) { it.parentFile }
                .firstOrNull { it.name == ".claude" }?.parentFile?.canonicalFile?.toPath() ?: root
            if (!canonical.startsWith(scope)) continue
            val relative = scope.relativize(canonical).toString().replace(File.separatorChar, '/')
            if (patterns.any { it.length <= 512 && alternatives(it).any { pattern -> match(pattern.removePrefix("./"), relative) } }) {
                activated += skill.id
                runtime.activate(skill.command)
            }
        }
    }

    private fun splitPatterns(value: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        value.forEachIndexed { index, char ->
            if (char == '{') depth++
            if (char == '}') depth--
            if (char == ',' && depth == 0) {
                result += value.substring(start, index).trim()
                start = index + 1
            }
        }
        result += value.substring(start).trim()
        return result.filter(String::isNotEmpty)
    }

    private fun alternatives(pattern: String): List<String> {
        var results = listOf(pattern)
        repeat(4) {
            results = results.flatMap { current ->
                val group = Regex("\\{([^{}]+)\\}").find(current)
                if (group == null) listOf(current)
                else group.groupValues[1].split(',').take(16).map { current.replaceRange(group.range, it) }
            }.take(64)
        }
        return results
    }

    private fun match(pattern: String, path: String): Boolean {
        val regex = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            when {
                pattern.startsWith("**/", i) -> { regex.append("(?:.*/)?"); i += 3 }
                pattern.startsWith("**", i) -> { regex.append(".*"); i += 2 }
                pattern[i] == '*' -> { regex.append("[^/]*"); i++ }
                pattern[i] == '?' -> { regex.append("[^/]"); i++ }
                else -> { regex.append(Regex.escape(pattern[i].toString())); i++ }
            }
        }
        return Regex(regex.append('$').toString()).matches(path)
    }
}
