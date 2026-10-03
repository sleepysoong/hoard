package com.sleepysoong.hoard.goal

import com.sleepysoong.hoard.data.Goal
import com.sleepysoong.hoard.data.GoalStatus
import com.sleepysoong.hoard.data.HoardRepository
import java.util.UUID

/** Who asks for a lifecycle change. The model can create/get/complete/block, never pause/resume/clear. */
enum class Actor { User, Model, System }

class GoalException(message: String) : Exception(message)

/** Legacy persisted limits: retained for compatibility, no longer stop goal execution. */
data class GoalBudget(
    val maxAutoTurns: Int = Int.MAX_VALUE,
    val maxTokens: Int = Int.MAX_VALUE,
    val maxWallTimeMs: Long = Long.MAX_VALUE
)

/**
 * Goal lifecycle and its authority rules. The repository is the source of truth;
 * UIs observe [HoardRepository.goals] (a StateFlow: they react to changes, no polling).
 *
 *            create                    complete(evidence)
 *   (none) ─────────▶ active ──────────────────────────────▶ completed
 *                      │  ▲  ╲ block(reason)        ▲
 *            pause     │  │   ╲─────▶ blocked ──────┘ (resume → active)
 *                      ▼  │ resume
 *                     paused          budget exhausted (system) ─▶ budget_limited
 *   clear: any non-terminal state ─▶ cleared (kept as history)
 */
class GoalService(
    private val repo: HoardRepository = HoardRepository.get(),
    private val clock: () -> Long = System::currentTimeMillis,
    val budget: GoalBudget = defaultBudget
) {
    companion object {
        /** Budget for new goals (config; tests lower it). */
        @Volatile var defaultBudget = GoalBudget()
    }

    /** The session's current goal: the newest one that isn't cleared (list order = creation order, ties included). */
    fun current(sessionId: String): Goal? =
        repo.goals.value.lastOrNull { it.sessionId == sessionId && it.status != GoalStatus.Cleared }

    fun active(sessionId: String): Goal? = current(sessionId)?.takeIf { it.status == GoalStatus.Active }

    fun history(sessionId: String): List<Goal> = repo.goals.value.filter { it.sessionId == sessionId }

    fun create(
        sessionId: String,
        objective: String,
        by: Actor,
        successCriteria: String? = null,
        verification: String? = null,
        constraints: String? = null,
        boundaries: String? = null
    ): Goal {
        val obj = objective.trim()
        if (obj.isEmpty()) throw GoalException("objective is required")
        if (obj.length > 2_000) throw GoalException("objective is too long (max 2000 characters)")
        repo.sessionOf(sessionId) ?: throw GoalException("session not found")
        current(sessionId)?.takeIf { it.isOpen }?.let {
            throw GoalException(
                "this session already has a ${it.status.wire} goal (\"${it.objective.take(80)}\"). " +
                    if (by == Actor.Model) "Complete or block it first; only the user can clear or replace it." else "Clear it first."
            )
        }
        val now = clock()
        val goal = Goal(
            id = "goal-" + UUID.randomUUID().toString().take(8),
            sessionId = sessionId,
            objective = obj,
            successCriteria = successCriteria.clean(),
            verification = verification.clean(),
            constraints = constraints.clean(),
            boundaries = boundaries.clean(),
            status = GoalStatus.Active,
            createdBy = by.name.lowercase(),
            createdAt = now, activatedAt = now, updatedAt = now,
            maxTurns = budget.maxAutoTurns, maxTokens = budget.maxTokens, maxDurationMs = budget.maxWallTimeMs
        )
        repo.updateGoals { it + goal }
        return goal
    }

    /** Needs concrete evidence (tests, command output, files…), not confidence. */
    fun complete(sessionId: String, evidence: String, by: Actor): Goal {
        val ev = evidence.trim()
        if (ev.length < 10) throw GoalException("evidence is required: describe the concrete result that proves the goal (test output, command result, file, …)")
        return transition(sessionId, by, "complete", allowedFrom = setOf(GoalStatus.Active)) {
            it.copy(status = GoalStatus.Completed, evidence = ev.take(4_000), completedAt = clock())
        }
    }

    fun block(sessionId: String, reason: String, by: Actor): Goal {
        val r = reason.trim()
        if (r.isEmpty()) throw GoalException("reason is required")
        return transition(sessionId, by, "block", allowedFrom = setOf(GoalStatus.Active)) {
            it.copy(status = GoalStatus.Blocked, blockedReason = r.take(2_000))
        }
    }

    fun pause(sessionId: String, by: Actor): Goal = userOnly(by, "pause").let {
        transition(sessionId, by, "pause", allowedFrom = setOf(GoalStatus.Active)) { it.copy(status = GoalStatus.Paused) }
    }

    /** Back to active; clear any legacy idle-turn marker. */
    fun resume(sessionId: String, by: Actor): Goal = userOnly(by, "resume").let {
        transition(sessionId, by, "resume", allowedFrom = setOf(GoalStatus.Paused, GoalStatus.Blocked, GoalStatus.BudgetLimited)) {
            it.copy(
                status = GoalStatus.Active, blockedReason = null, activatedAt = clock(), continuationSuppressed = false,
                // Resuming a budget-limited goal grants a fresh budget.
                usedTurns = if (it.status == GoalStatus.BudgetLimited) 0 else it.usedTurns,
                usedTokens = if (it.status == GoalStatus.BudgetLimited) 0 else it.usedTokens
            )
        }
    }

    fun clear(sessionId: String, by: Actor): Goal = userOnly(by, "clear").let {
        transition(sessionId, by, "clear", allowedFrom = GoalStatus.entries.toSet() - GoalStatus.Cleared) { it.copy(status = GoalStatus.Cleared) }
    }

    // ---- runtime (system) ------------------------------------------------------

    /**
     * Accounts one finished turn (tokens always, a turn only for automatic continuations)
     * to [goalId] — the goal that was active when the turn started, so the turn that
     * completes or blocks it still counts — or else to the currently active goal.
     */
    fun recordTurn(sessionId: String, tokens: Int, automatic: Boolean, goalId: String? = null) {
        val targetId = goalId ?: active(sessionId)?.id ?: return
        repo.updateGoals { list ->
            list.map { g ->
                if (g.id != targetId || g.sessionId != sessionId) g
                else g.copy(usedTokens = g.usedTokens + tokens.coerceAtLeast(0), usedTurns = g.usedTurns + if (automatic) 1 else 0, updatedAt = clock())
            }
        }
    }

    fun suppressContinuation(sessionId: String, suppressed: Boolean) {
        update(sessionId) { if (it.isOpen) it.copy(continuationSuppressed = suppressed) else it }
    }

    /** Session fork: an independent snapshot of the open goal for the new session. */
    fun forkInto(fromSessionId: String, toSessionId: String): Goal? {
        val src = current(fromSessionId)?.takeIf { it.isOpen } ?: return null
        val now = clock()
        val copy = src.copy(
            id = "goal-" + UUID.randomUUID().toString().take(8),
            sessionId = toSessionId, parentGoalId = src.id,
            createdAt = now, updatedAt = now, createdBy = "system"
        )
        repo.updateGoals { it + copy }
        return copy
    }

    // ---- internals -------------------------------------------------------------

    private fun userOnly(by: Actor, action: String) {
        if (by == Actor.Model) throw GoalException("the model may not $action a goal; only the user can")
    }

    private fun transition(sessionId: String, by: Actor, action: String, allowedFrom: Set<GoalStatus>, change: (Goal) -> Goal): Goal {
        val g = current(sessionId) ?: throw GoalException("this session has no goal")
        if (g.status !in allowedFrom) throw GoalException("cannot $action a ${g.status.wire} goal")
        return update(sessionId) { if (it.id == g.id) change(it) else it } ?: g
    }

    private fun update(sessionId: String, change: (Goal) -> Goal): Goal? {
        val target = current(sessionId) ?: return null
        var out: Goal? = null
        repo.updateGoals { list ->
            list.map { if (it.id == target.id) change(it).let { n -> if (n != it) n.copy(updatedAt = clock()) else n }.also { r -> out = r } else it }
        }
        return out
    }

    private fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() }?.take(2_000)
}
