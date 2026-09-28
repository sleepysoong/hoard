package com.sleepysoong.hoard.tools

import com.sleepysoong.hoard.tools.search.BraveSearchProvider
import com.sleepysoong.hoard.tools.search.SearchProvider
import com.sleepysoong.hoard.termux.TermuxExecutor

/**
 * The tools a reply may use, from Settings. web_fetch needs nothing; web_search
 * needs the user's own Brave Search API key (never bundled with the app);
 * termux_exec is opt-in and only registered when [termux] is given.
 * Tests override [override] to inject fakes.
 */
object WebTools {
    @Volatile var override: ToolRegistry? = null

    fun registry(
        enabled: Boolean,
        braveApiKey: String,
        searchProvider: SearchProvider? = null,
        termux: TermuxExecutor? = null,
        /** File tools (read_file, write_file, edit_file, glob, grep) over this sandbox; null = off. */
        files: com.sleepysoong.hoard.tools.files.Workspace? = null,
        /** goal (+ schedule / schedule_wakeup outside scheduled runs) for this session. */
        runtime: RuntimeContext? = null
    ): ToolRegistry {
        override?.let { return it }
        val registry = ToolRegistry()
        if (enabled) {
            val provider = searchProvider ?: braveApiKey.trim().takeIf { it.isNotEmpty() }?.let { BraveSearchProvider(it) }
            if (provider != null) registry.register(WebSearchTool(provider))
            registry.register(WebFetchTool())
        }
        if (termux != null) registry.register(TermuxExecTool(termux))
        if (files != null) com.sleepysoong.hoard.tools.files.FileTools.all(files).forEach(registry::register)
        if (runtime != null) {
            registry.register(GoalTool(runtime.sessionId, runtime.goals))
            runtime.schedules?.let { registry.register(ScheduleTool(it, runtime.sessionId, runtime.modelId, runtime.permissions)) }
            runtime.wakeups?.let { registry.register(WakeupTool(runtime.sessionId, it)) }
        }
        return registry
    }
}

/**
 * Session-bound runtime tools. [schedules]/[wakeups] are null inside a scheduled run:
 * an unattended run must not create more future work.
 */
class RuntimeContext(
    val sessionId: String,
    val modelId: String,
    val goals: com.sleepysoong.hoard.goal.GoalService,
    val schedules: com.sleepysoong.hoard.schedule.ScheduleService?,
    val wakeups: com.sleepysoong.hoard.schedule.WakeupService?,
    /** Snapshotted into schedules created in this turn. */
    val permissions: com.sleepysoong.hoard.data.PermissionProfile
)
