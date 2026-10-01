package com.sleepysoong.hoard.tools

import com.sleepysoong.hoard.browser.RemoteBrowser
import com.sleepysoong.hoard.data.PermissionProfile
import com.sleepysoong.hoard.data.todo.TodoService
import com.sleepysoong.hoard.goal.GoalService
import com.sleepysoong.hoard.schedule.ScheduleService
import com.sleepysoong.hoard.schedule.WakeupService
import com.sleepysoong.hoard.termux.TermuxExecutor
import com.sleepysoong.hoard.skills.SkillRuntime
import com.sleepysoong.hoard.tools.fetch.PageFetcher
import com.sleepysoong.hoard.tools.files.Workspace
import com.sleepysoong.hoard.tools.search.SearchProvider

/*
 * Tool kit: the one place tools are assembled.
 *
 *   ToolKit.registry(context)
 *     └─ for each ToolModule in ToolKit.modules (fixed order)
 *          └─ module.tools(context)  ← reads context.permissions / context.services
 *     = ToolRegistry  (schemas + guidance → request, execute(name, args) ← tool loop)
 *
 * A module groups related tools and decides, from the context, whether they are
 * offered this turn. Dependencies come from ToolServices (Android implementation in
 * the app, fakes in tests), so no tool reaches for singletons itself.
 */

/** One turn's facts every module may use. */
class ToolContext(
    val sessionId: String,
    /** The session's model (snapshotted into schedules created this turn). */
    val modelId: String,
    val services: ToolServices,
    /** Tool families allowed this turn (everything, or a scheduled run's snapshot). */
    val permissions: PermissionProfile = PermissionProfile.ALL,
    /** An unattended scheduled run: no tools that create more future work. */
    val scheduledRun: Boolean = false
)

/**
 * Dependencies modules draw on, created lazily. A null/absent service means the
 * module that needs it offers nothing (e.g. no Brave key → no web_search).
 */
interface ToolServices {
    /** Web search backend, or null when none is configured (no Brave key). */
    val searchProvider: SearchProvider?
    val pageFetcher: PageFetcher get() = PageFetcher()
    /** The file tools' sandbox; [fullStorage] adds shared storage (when the turn allows it). */
    fun workspace(fullStorage: Boolean): Workspace?
    val termux: TermuxExecutor?
    val todos: TodoService?
    val goals: GoalService?
    val schedules: ScheduleService?
    val wakeups: WakeupService?
    /** The VPS Chrome (browser_use), or null until Settings → 원격 브라우저 is set up and verified. */
    val browser: RemoteBrowser? get() = null
    /** Per-turn skill activation and session context. Null in clients without skills. */
    val skills: SkillRuntime? get() = null
    val skillTasks: SkillTaskService? get() = null
}

/** A group of related tools, offered together when the context allows it. */
interface ToolModule {
    /** Stable id (logs, tests, docs), e.g. "web". */
    val id: String
    fun tools(context: ToolContext): List<Tool>
}

object ToolKit {
    /** Built-in modules in the order their tools are offered to the model. */
    val modules: List<ToolModule> = listOf(SkillModule, TodoModule, WebModule, BrowserModule, TermuxModule, FileModule, GoalModule, ScheduleModule)

    /** Tests: replaces the whole registry the app would build. */
    @Volatile var override: ToolRegistry? = null

    fun registry(context: ToolContext, modules: List<ToolModule> = this.modules): ToolRegistry {
        override?.let { return it }
        val registry = ToolRegistry()
        registry.skillRuntime = context.services.skills
        for (module in modules) module.tools(context).forEach(registry::register)
        return registry
    }
}

// ---------------------------------------------------------------- built-in modules

/**
 * `skill` (activate) + `skill_tasks` (background forks). Never inside a scheduled run:
 * an unattended run must not create more future work (same rule as the schedule module).
 */
object SkillModule : ToolModule {
    override val id = "skills"
    override fun tools(context: ToolContext): List<Tool> {
        if (context.scheduledRun) return emptyList()
        return listOfNotNull(
            context.services.skills?.let(::SkillTool),
            context.services.skillTasks?.let { SkillTaskTool(it, context.sessionId) }
        )
    }
}

/** `todo`: the session's task list (always offered). */
object TodoModule : ToolModule {
    override val id = "todo"
    override fun tools(context: ToolContext) =
        listOfNotNull(context.services.todos?.let { TodoTool(it, context.sessionId) })
}

/** `web_search` (needs a search provider) + `web_fetch`. */
object WebModule : ToolModule {
    override val id = "web"
    override fun tools(context: ToolContext): List<Tool> {
        if (!context.permissions.web) return emptyList()
        return listOfNotNull(context.services.searchProvider?.let(::WebSearchTool), WebFetchTool(context.services.pageFetcher))
    }
}

/** `browser_use`: the user's VPS Chrome over SSH + CDP (needs Settings → 원격 브라우저). */
object BrowserModule : ToolModule {
    override val id = "browser"
    override fun tools(context: ToolContext): List<Tool> {
        if (!context.permissions.browser) return emptyList()
        val browser = context.services.browser ?: return emptyList()
        // Screenshots go to the app workspace (browser/…), shown to the model as a workspace path.
        val ws = context.services.workspace(false)
        return listOf(BrowserUseTool(browser, ws?.let { java.io.File(it.root, "browser") }, displayPath = { f -> ws?.relative(f) ?: f.path }))
    }
}

/** `termux_exec`: one-shot shell commands in Termux. */
object TermuxModule : ToolModule {
    override val id = "termux"
    override fun tools(context: ToolContext) =
        if (!context.permissions.termux) emptyList() else listOfNotNull(context.services.termux?.let(::TermuxExecTool))
}

/** `read_file`, `write_file`, `edit_file`, `glob`, `grep` over the workspace (+ shared storage). */
object FileModule : ToolModule {
    override val id = "files"
    override fun tools(context: ToolContext): List<Tool> {
        if (!context.permissions.files) return emptyList()
        return context.services.workspace(context.permissions.fullStorage)?.let(com.sleepysoong.hoard.tools.files.FileTools::all).orEmpty()
    }
}

/** `goal`: the session's persistent goal. */
object GoalModule : ToolModule {
    override val id = "goal"
    override fun tools(context: ToolContext) =
        listOfNotNull(context.services.goals?.let { GoalTool(context.sessionId, it) })
}

/** `schedule` + `schedule_wakeup` — never inside a scheduled run. */
object ScheduleModule : ToolModule {
    override val id = "schedule"
    override fun tools(context: ToolContext): List<Tool> {
        if (context.scheduledRun) return emptyList()
        return listOfNotNull(
            context.services.schedules?.let { ScheduleTool(it, context.sessionId, context.modelId, context.permissions) },
            context.services.wakeups?.let { WakeupTool(context.sessionId, it) }
        )
    }
}

/** Plain [ToolServices] with everything optional (tests, or partial setups). */
class SimpleToolServices(
    override val searchProvider: SearchProvider? = null,
    val workspace: Workspace? = null,
    override val termux: TermuxExecutor? = null,
    override val todos: TodoService? = null,
    override val goals: GoalService? = null,
    override val schedules: ScheduleService? = null,
    override val wakeups: WakeupService? = null,
    override val pageFetcher: PageFetcher = PageFetcher(),
    override val browser: RemoteBrowser? = null,
    override val skills: SkillRuntime? = null,
    override val skillTasks: SkillTaskService? = null
) : ToolServices {
    override fun workspace(fullStorage: Boolean) = workspace
}
