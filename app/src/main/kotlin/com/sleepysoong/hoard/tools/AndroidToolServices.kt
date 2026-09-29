package com.sleepysoong.hoard.tools

import android.content.Context
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.todo.TodoService
import com.sleepysoong.hoard.goal.GoalService
import com.sleepysoong.hoard.schedule.ScheduleService
import com.sleepysoong.hoard.schedule.WakeupService
import com.sleepysoong.hoard.schedule.WorkManagerScheduler
import com.sleepysoong.hoard.termux.TermuxBridge
import com.sleepysoong.hoard.termux.TermuxExecutor
import com.sleepysoong.hoard.tools.files.FileTools
import com.sleepysoong.hoard.tools.files.Workspace
import com.sleepysoong.hoard.tools.search.BraveSearchProvider
import com.sleepysoong.hoard.tools.search.SearchProvider

/**
 * The app's [ToolServices]: real backends, created on first use.
 * [braveApiKey]: the user's own key from Settings (blank = no web_search).
 */
class AndroidToolServices(
    private val context: Context,
    private val braveApiKey: String,
    private val repo: HoardRepository = HoardRepository.get()
) : ToolServices {
    private val schedulerServices by lazy { WorkManagerScheduler.services(context) }

    override val searchProvider: SearchProvider? by lazy { braveApiKey.trim().takeIf { it.isNotEmpty() }?.let(::BraveSearchProvider) }
    override fun workspace(fullStorage: Boolean): Workspace = FileTools.workspace(context, fullStorage)
    override val termux: TermuxExecutor by lazy { TermuxBridge(context) }
    override val todos: TodoService get() = repo.todos
    override val goals: GoalService by lazy { GoalService(repo) }
    override val schedules: ScheduleService get() = schedulerServices.first
    override val wakeups: WakeupService get() = schedulerServices.third
}
