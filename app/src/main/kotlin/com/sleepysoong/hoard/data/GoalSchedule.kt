package com.sleepysoong.hoard.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A goal: a thread-scoped persistent completion contract. Belongs to one session,
 * survives restarts (persisted with the conversation), and is only ever completed
 * with concrete evidence. One non-terminal goal per session; old goals stay as history
 * ("cleared" is a status, not a delete).
 */
@Serializable
data class Goal(
    val id: String,
    val sessionId: String,
    val objective: String,
    val successCriteria: String? = null,
    val verification: String? = null,
    val constraints: String? = null,
    val boundaries: String? = null,
    val status: GoalStatus,
    /** "user" | "model" | "system". */
    val createdBy: String,
    val createdAt: Long,
    val activatedAt: Long? = null,
    val completedAt: Long? = null,
    val updatedAt: Long,
    val blockedReason: String? = null,
    /** What proved completion (goal.complete). */
    val evidence: String? = null,
    val maxTurns: Int,
    val maxTokens: Int,
    val maxDurationMs: Long,
    /** Automatic continuation turns used. */
    val usedTurns: Int = 0,
    /** Tokens (prompt + completion) of every turn while active. */
    val usedTokens: Int = 0,
    val autoContinue: Boolean = true,
    /** Set by spin prevention (a continuation that did nothing); cleared by user input / resume. */
    val continuationSuppressed: Boolean = false,
    val parentGoalId: String? = null
) {
    /** Counts against the one-open-goal-per-session rule. */
    val isOpen: Boolean get() = status == GoalStatus.Active || status == GoalStatus.Paused || status == GoalStatus.Blocked
}

@Serializable
enum class GoalStatus {
    @SerialName("active") Active,
    @SerialName("paused") Paused,
    @SerialName("blocked") Blocked,
    @SerialName("completed") Completed,
    @SerialName("budget_limited") BudgetLimited,
    @SerialName("cleared") Cleared;

    val wire: String get() = when (this) {
        Active -> "active"; Paused -> "paused"; Blocked -> "blocked"
        Completed -> "completed"; BudgetLimited -> "budget_limited"; Cleared -> "cleared"
    }
}

/** When a scheduled run fires. Times are UTC epoch millis; cron keeps its IANA zone. */
@Serializable
sealed class ScheduleTrigger {
    @Serializable @SerialName("at")
    data class At(val atMillis: Long) : ScheduleTrigger()

    /** Fixed interval from [anchorMillis] (the first planned run): no drift. */
    @Serializable @SerialName("every")
    data class Every(val intervalMs: Long, val anchorMillis: Long) : ScheduleTrigger()

    /** 5-field cron in [timezone]. */
    @Serializable @SerialName("cron")
    data class Cron(val expression: String, val timezone: String) : ScheduleTrigger()
}

@Serializable enum class ScheduleStatus {
    @SerialName("active") Active, @SerialName("paused") Paused,
    @SerialName("cancelled") Cancelled, @SerialName("finished") Finished
}

/** Missed firings (app/scheduler down): drop them, run the latest once, or all (capped). */
@Serializable enum class CatchUpPolicy { @SerialName("skip") Skip, @SerialName("latest") Latest, @SerialName("all") All }

/** A firing while the previous run is still going: skip it, run it after, or run both. */
@Serializable enum class OverlapPolicy { @SerialName("skip") Skip, @SerialName("queue") Queue, @SerialName("parallel") Parallel }

@Serializable enum class ContextMode { @SerialName("clean") Clean, @SerialName("snapshot") Snapshot }

/**
 * What a scheduled run may use, snapshotted at creation. A run gets the intersection
 * of this and the current settings: never broader than when the user set it up.
 */
@Serializable
data class PermissionProfile(
    val web: Boolean,
    val files: Boolean,
    val fullStorage: Boolean,
    val termux: Boolean
) {
    companion object {
        /** Everything the app offers (tools are always on). */
        val ALL = PermissionProfile(web = true, files = true, fullStorage = true, termux = true)
    }
}

/** A durable future execution: fires isolated runs (own session) on its trigger. */
@Serializable
data class Schedule(
    val id: String,
    val creatorSessionId: String?,
    val name: String,
    val prompt: String,
    val trigger: ScheduleTrigger,
    val modelId: String,
    val status: ScheduleStatus,
    val nextRunAt: Long?,
    val lastRunAt: Long? = null,
    val catchUp: CatchUpPolicy,
    val overlap: OverlapPolicy = OverlapPolicy.Skip,
    val maxRuns: Int? = null,
    val runCount: Int = 0,
    val expiresAt: Long? = null,
    val contextMode: ContextMode = ContextMode.Clean,
    val contextSnapshot: String? = null,
    val permissions: PermissionProfile,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable enum class RunStatus {
    @SerialName("queued") Queued, @SerialName("running") Running, @SerialName("succeeded") Succeeded,
    @SerialName("failed") Failed, @SerialName("blocked") Blocked, @SerialName("timed_out") TimedOut,
    @SerialName("cancelled") Cancelled, @SerialName("skipped") Skipped;

    val finished: Boolean get() = this != Queued && this != Running
}

/** One firing of a schedule. (scheduleId, plannedAt) is unique: a firing never runs twice. */
@Serializable
data class ScheduleRun(
    val id: String,
    val scheduleId: String,
    val plannedAt: Long,
    val queuedAt: Long? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val status: RunStatus,
    val runSessionId: String? = null,
    val attempt: Int = 1,
    val outputSummary: String? = null,
    val error: String? = null,
    val tokensUsed: Int? = null,
    val createdAt: Long
)
