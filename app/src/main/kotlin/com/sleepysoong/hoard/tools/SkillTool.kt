package com.sleepysoong.hoard.tools

import com.sleepysoong.hoard.skills.SkillRuntime
import kotlinx.serialization.json.JsonObject

/** Claude-compatible native skill invocation. User slash commands call the runtime directly. */
class SkillTool(private val runtime: SkillRuntime) : Tool {
    override val name = "skill"
    override val title = "스킬 실행"
    override val description = "Load an installed skill's instructions and supporting-file locations. Use a listed skill when its description matches the task."
    override val parameters = toolParameters {
        string("skill", "Installed skill name, optionally namespaced (for example plugin:review).", required = true)
        string("args", "Optional arguments, preserving shell-style quotes for multi-word positional values.")
    }
    override val guidance: String get() = runtime.guidance()
    override fun subject(args: JsonObject): String = "/" + args.string("skill").orEmpty().removePrefix("/").take(192)
    override suspend fun execute(args: JsonObject): JsonObject {
        if (args["args"] != null && args.string("args") == null) throw ToolException("args must be a string")
        return runtime.activate(args.requireString("skill"), args.string("args").orEmpty(), userInvoked = false)
    }
    override fun summarize(output: JsonObject): String = when (output.string("context")) {
        "fork" -> "별도 대화에서 스킬 실행"
        else -> if (output["runtime_dir"] != null) "스킬 지침 로드 · Termux 실행 파일 준비" else "스킬 지침 로드"
    }
}
