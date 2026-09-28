package com.sleepysoong.hoard.goal

import com.sleepysoong.hoard.data.Goal

/**
 * Pieces the agent loop uses around a goal, kept out of the loop itself:
 *  - [context]: the ephemeral developer text injected into a request while a goal is
 *    active (never stored in the conversation; the repository is the source of truth)
 *  - [ContinuationEvaluator]: after a turn settles, decides whether to start a hidden
 *    continuation turn — or stop, suppress (spin), or hit the budget.
 */
object GoalRuntime {
    const val CONTINUE_MESSAGE = "[Automatic continuation] Continue working toward the active goal. " +
        "Take the next useful action. If it is verified complete, call goal with action \"complete\" and the evidence; " +
        "if it is impossible under the constraints, call goal with action \"block\"."

    const val BUDGET_SUMMARY_MESSAGE = "[Goal budget exhausted] Stop working on the goal now. Do not call tools. Summarize: " +
        "current progress, evidence gathered, unresolved blockers, and the best next action."

    const val SYSTEM_RULES = "Goals: a goal is a persistent objective that may take several turns. Create one (goal tool, " +
        "action \"create\") only when the user wants work to continue toward a clear, verifiable outcome — not for simple " +
        "questions or one-step changes. While a goal is active: make concrete progress instead of restating it; never mark it " +
        "complete from confidence alone — compare it against concrete evidence (tests, command output, files) and pass that " +
        "evidence to goal \"complete\"; if progress is impossible, use goal \"block\" with the reason. You cannot pause, resume " +
        "or clear goals."

    fun context(g: Goal): String = buildString {
        append("<active_goal>\n<objective>\n").append(g.objective).append("\n</objective>\n")
        g.successCriteria?.let { append("<success_criteria>\n").append(it).append("\n</success_criteria>\n") }
        g.verification?.let { append("<verification>\n").append(it).append("\n</verification>\n") }
        g.constraints?.let { append("<constraints>\n").append(it).append("\n</constraints>\n") }
        g.boundaries?.let { append("<boundaries>\n").append(it).append("\n</boundaries>\n") }
        append("<runtime>\nturns_used: ").append(g.usedTurns).append("\nturns_remaining: ").append((g.maxTurns - g.usedTurns).coerceAtLeast(0))
        append("\ntokens_used: ").append(g.usedTokens).append(" of ").append(g.maxTokens).append("\n</runtime>\n</active_goal>\n")
        append("Audit the objective against concrete evidence. If incomplete, take the next useful action. ")
        append("If verified complete, use the goal tool to complete it with the evidence. ")
        append("If progress is impossible under the current constraints, report the blocker with goal \"block\".")
    }
}

/** What happened in the turn that just finished. */
data class TurnOutcome(
    val succeeded: Boolean,
    val automatic: Boolean,
    val toolCalls: Int,
    val goalChanged: Boolean
)

/** Runtime facts the evaluator can't see from the goal alone. */
data class SessionActivity(
    /** Another reply / user message is already queued for this session. */
    val workQueued: Boolean,
    /** A schedule_wakeup is pending: the model chose to wait, don't busy-continue. */
    val wakeupPending: Boolean
)

sealed class ContinuationDecision {
    data class Stop(val why: String) : ContinuationDecision()
    object Continue : ContinuationDecision()
    /** Spin prevention: the continuation did nothing observable. */
    object Suppress : ContinuationDecision()
    data class BudgetLimited(val which: String) : ContinuationDecision()
}

class ContinuationEvaluator(private val goals: GoalService) {
    fun decide(sessionId: String, turn: TurnOutcome, activity: SessionActivity): ContinuationDecision {
        val g = goals.current(sessionId) ?: return ContinuationDecision.Stop("no goal")
        if (g.status != com.sleepysoong.hoard.data.GoalStatus.Active) return ContinuationDecision.Stop("goal ${g.status.wire}")
        if (!g.autoContinue) return ContinuationDecision.Stop("auto-continue off")
        if (!turn.succeeded) return ContinuationDecision.Stop("turn failed")
        if (turn.automatic && turn.toolCalls == 0 && !turn.goalChanged) return ContinuationDecision.Suppress
        if (g.continuationSuppressed) return ContinuationDecision.Stop("suppressed")
        if (activity.workQueued) return ContinuationDecision.Stop("user input queued")
        if (activity.wakeupPending) return ContinuationDecision.Stop("waiting for wakeup")
        goals.budgetExceeded(g)?.let { return ContinuationDecision.BudgetLimited(it) }
        return ContinuationDecision.Continue
    }
}
