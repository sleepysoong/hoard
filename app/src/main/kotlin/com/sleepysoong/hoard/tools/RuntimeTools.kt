package com.sleepysoong.hoard.tools

import com.sleepysoong.hoard.data.CatchUpPolicy
import com.sleepysoong.hoard.data.ContextMode
import com.sleepysoong.hoard.data.Goal
import com.sleepysoong.hoard.data.OverlapPolicy
import com.sleepysoong.hoard.data.PermissionProfile
import com.sleepysoong.hoard.data.Schedule
import com.sleepysoong.hoard.data.ScheduleTrigger
import com.sleepysoong.hoard.goal.Actor
import com.sleepysoong.hoard.goal.GoalException
import com.sleepysoong.hoard.goal.GoalService
import com.sleepysoong.hoard.schedule.CreateScheduleInput
import com.sleepysoong.hoard.schedule.ScheduleException
import com.sleepysoong.hoard.schedule.ScheduleService
import com.sleepysoong.hoard.schedule.WakeupException
import com.sleepysoong.hoard.schedule.WakeupService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.OffsetDateTime
import java.time.ZoneId

private fun JsonObjectBuilder.str(name: String, description: String) = putJsonObject(name) { put("type", "string"); put("description", description) }
private fun JsonObjectBuilder.int(name: String, description: String) = putJsonObject(name) { put("type", "integer"); put("description", description) }
private fun JsonObjectBuilder.enum(name: String, values: List<String>, description: String) = putJsonObject(name) {
    put("type", "string"); putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }; put("description", description)
}

// ---------------------------------------------------------------- goal

/** `goal`: create / get / complete (with evidence) / block the session's goal. Pause/resume/clear are the user's. */
class GoalTool(private val sessionId: String, private val goals: GoalService) : Tool {
    override val name = "goal"
    override val title = "목표"
    override val description = "Manage this session's persistent goal (a verifiable objective that may take several turns; " +
        "the runtime keeps continuing it automatically while it is active). action=create (objective, optional success_criteria, " +
        "verification, constraints, boundaries), get, complete (evidence: the concrete result that proves it — test output, " +
        "command result, file; never mere confidence), block (reason: why progress is impossible). You cannot pause, resume or clear goals."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            enum("action", listOf("create", "get", "complete", "block"), "What to do.")
            str("objective", "create: the outcome to achieve.")
            str("success_criteria", "create: what counts as done.")
            str("verification", "create: how to verify it (test suite, command, benchmark…).")
            str("constraints", "create: what must stay true.")
            str("boundaries", "create: what not to touch.")
            str("evidence", "complete: concrete evidence the goal is met.")
            str("reason", "block: why progress is impossible under the constraints.")
        }
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
    }

    override fun subject(args: JsonObject) = when (args.string("action")) {
        "create" -> "설정 · " + args.string("objective").orEmpty()
        "complete" -> "달성 · " + args.string("evidence").orEmpty()
        "block" -> "막힘 · " + args.string("reason").orEmpty()
        else -> "확인"
    }.take(160)

    override suspend fun execute(args: JsonObject): JsonObject {
        val g = try {
            when (args.string("action")) {
                "create" -> goals.create(
                    sessionId, args.string("objective").orEmpty(), Actor.Model,
                    args.string("success_criteria"), args.string("verification"), args.string("constraints"), args.string("boundaries")
                )
                "get" -> goals.current(sessionId) ?: return buildJsonObject { put("goal", kotlinx.serialization.json.JsonNull) }
                "complete" -> goals.complete(sessionId, args.string("evidence").orEmpty(), Actor.Model)
                "block" -> goals.block(sessionId, args.string("reason").orEmpty(), Actor.Model)
                else -> throw ToolException("action must be create, get, complete or block")
            }
        } catch (e: GoalException) {
            throw ToolException(e.message ?: "goal error")
        }
        return buildJsonObject { put("goal", goalJson(g)) }
    }

    companion object {
        fun goalJson(g: Goal) = buildJsonObject {
            put("id", g.id); put("objective", g.objective); put("status", g.status.wire)
            g.successCriteria?.let { put("success_criteria", it) }
            g.verification?.let { put("verification", it) }
            g.constraints?.let { put("constraints", it) }
            g.boundaries?.let { put("boundaries", it) }
            g.blockedReason?.let { put("blocked_reason", it) }
            g.evidence?.let { put("evidence", it) }
            put("turns_used", g.usedTurns); put("turns_max", g.maxTurns)
            put("tokens_used", g.usedTokens); put("tokens_max", g.maxTokens)
        }
    }
}

// ---------------------------------------------------------------- schedule

/**
 * `schedule`: durable future runs (at / every / cron), each in its own isolated session.
 * The model must resolve relative times ("내일 9시") to an exact trigger itself; the
 * backend validates it.
 */
class ScheduleTool(
    private val service: ScheduleService,
    private val creatorSessionId: String,
    private val modelId: String,
    private val permissions: PermissionProfile,
    private val zone: ZoneId = ZoneId.systemDefault()
) : Tool {
    override val name = "schedule"
    override val title = "예약"
    override val description = "Create and manage scheduled runs: the prompt runs later in a separate session (the user may not be " +
        "present), on trigger type at (one time, ISO-8601 with offset), every (interval_ms, min 5 minutes) or cron (5-field, " +
        "with an IANA timezone; default ${zone.id}). Only when the user asks for future or recurring work. Resolve relative times " +
        "against the current time first. actions: create, list, get, pause, resume, cancel. Modify an existing schedule " +
        "(pause/resume/cancel) instead of creating duplicates."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            enum("action", listOf("create", "list", "get", "pause", "resume", "cancel"), "What to do.")
            str("id", "get/pause/resume/cancel: the schedule id.")
            str("name", "create: short name.")
            str("prompt", "create: what the scheduled run should do (self-contained: it runs without this conversation).")
            enum("trigger_type", listOf("at", "every", "cron"), "create: kind of trigger.")
            str("at", "create, at: exact time, ISO-8601 with offset, e.g. 2026-09-29T09:00:00+09:00.")
            int("interval_ms", "create, every: interval in milliseconds (>= 300000).")
            str("start_at", "create, every: optional first run (ISO-8601); default now + interval.")
            str("cron", "create, cron: 5-field expression, e.g. \"0 9 * * 1-5\".")
            str("timezone", "create, cron: IANA timezone (default ${zone.id}).")
            int("max_runs", "create: stop after this many runs.")
            str("expires_at", "create: stop after this time (ISO-8601).")
            enum("context", listOf("clean", "snapshot"), "create: clean (default, prompt only) or snapshot (also context_snapshot).")
            str("context_snapshot", "create, snapshot: compact context the run needs from this conversation.")
            enum("catch_up", listOf("skip", "latest", "all"), "create: missed runs while the phone/app was off (default: at=latest, recurring=skip).")
            enum("overlap", listOf("skip", "queue", "parallel"), "create: when the previous run is still going (default skip).")
        }
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
    }

    override fun subject(args: JsonObject) = when (args.string("action")) {
        "create" -> (args.string("name") ?: args.string("prompt").orEmpty()) + " · " + (args.string("cron") ?: args.string("at") ?: args.string("interval_ms") ?: "")
        "list" -> "목록"
        else -> args.string("action").orEmpty() + " · " + args.string("id").orEmpty()
    }.take(160)

    override suspend fun execute(args: JsonObject): JsonObject = try {
        when (args.string("action")) {
            "create" -> buildJsonObject { put("schedule", json(service.create(input(args)))) }
            "list" -> buildJsonObject { put("schedules", JsonArray(service.list().map(::json))) }
            "get" -> id(args).let { id -> buildJsonObject {
                put("schedule", json(service.get(id)))
                put("recent_runs", JsonArray(service.runs(id).take(5).map { r -> buildJsonObject {
                    put("planned_at", iso(r.plannedAt)); put("status", r.status.name.lowercase())
                    r.outputSummary?.let { put("summary", it) }; r.error?.let { put("error", it) }
                } }))
            } }
            "pause" -> buildJsonObject { put("schedule", json(service.pause(id(args)))) }
            "resume" -> buildJsonObject { put("schedule", json(service.resume(id(args)))) }
            "cancel" -> buildJsonObject { put("schedule", json(service.cancel(id(args)))) }
            else -> throw ToolException("action must be create, list, get, pause, resume or cancel")
        }
    } catch (e: ScheduleException) {
        throw ToolException(e.message ?: "schedule error")
    }

    private fun id(args: JsonObject) = args.string("id")?.takeIf { it.isNotBlank() } ?: throw ToolException("id is required")

    private fun input(a: JsonObject): CreateScheduleInput {
        val trigger = when (a.string("trigger_type")) {
            "at" -> ScheduleTrigger.At(time(a, "at") ?: throw ToolException("at is required for trigger_type=at"))
            "every" -> {
                val interval = (a["interval_ms"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: throw ToolException("interval_ms is required")
                ScheduleTrigger.Every(interval, time(a, "start_at") ?: (System.currentTimeMillis() + interval))
            }
            "cron" -> ScheduleTrigger.Cron(a.string("cron") ?: throw ToolException("cron is required"), a.string("timezone") ?: zone.id)
            else -> throw ToolException("trigger_type must be at, every or cron")
        }
        return CreateScheduleInput(
            name = a.string("name"), prompt = a.string("prompt").orEmpty(), trigger = trigger, modelId = modelId,
            creatorSessionId = creatorSessionId, permissions = permissions,
            catchUp = a.string("catch_up")?.let { enumOf<CatchUpPolicy>(it, "catch_up") },
            overlap = a.string("overlap")?.let { enumOf<OverlapPolicy>(it, "overlap") } ?: OverlapPolicy.Skip,
            maxRuns = a.number("max_runs"), expiresAt = time(a, "expires_at"),
            contextMode = if (a.string("context") == "snapshot") ContextMode.Snapshot else ContextMode.Clean,
            contextSnapshot = a.string("context_snapshot")
        )
    }

    private inline fun <reified E : Enum<E>> enumOf(v: String, field: String): E =
        enumValues<E>().firstOrNull { it.name.equals(v, ignoreCase = true) } ?: throw ToolException("invalid $field \"$v\"")

    private fun time(a: JsonObject, key: String): Long? = a.string(key)?.let {
        try { OffsetDateTime.parse(it).toInstant().toEpochMilli() } catch (e: Exception) {
            throw ToolException("$key must be ISO-8601 with an offset, e.g. 2026-09-29T09:00:00+09:00")
        }
    }

    private fun iso(millis: Long) = java.time.Instant.ofEpochMilli(millis).atZone(zone).toOffsetDateTime().toString()

    private fun json(s: Schedule) = buildJsonObject {
        put("id", s.id); put("name", s.name); put("status", s.status.name.lowercase())
        put("trigger", ScheduleService.describe(s.trigger))
        s.nextRunAt?.let { put("next_run_at", iso(it)) }
        s.lastRunAt?.let { put("last_run_at", iso(it)) }
        put("run_count", s.runCount)
        s.maxRuns?.let { put("max_runs", it) }
        put("catch_up", s.catchUp.name.lowercase()); put("overlap", s.overlap.name.lowercase())
    }
}

// ---------------------------------------------------------------- wakeup

/** `schedule_wakeup`: wake this session up after a delay instead of busy-polling. */
class WakeupTool(private val sessionId: String, private val wakeups: WakeupService) : Tool {
    override val name = "schedule_wakeup"
    override val title = "깨우기"
    override val description = "Wake this conversation up after delay_ms (5 s – 1 h) with prompt, e.g. to check a deployment or " +
        "build again instead of waiting. End your turn after calling it. One pending wakeup per conversation (a new one replaces it). " +
        "Not for future work at a fixed time or recurring work: use schedule for that."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            int("delay_ms", "Delay in milliseconds (5000 – 3600000).")
            str("prompt", "What to do when waking up.")
        }
        put("required", buildJsonArray { add(JsonPrimitive("delay_ms")); add(JsonPrimitive("prompt")) })
    }

    override fun subject(args: JsonObject) = "${((args["delay_ms"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0).toLong() / 1000}초 후 · " + args.string("prompt").orEmpty()

    override suspend fun execute(args: JsonObject): JsonObject {
        val delay = (args["delay_ms"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: throw ToolException("delay_ms is required")
        try {
            wakeups.schedule(sessionId, delay, args.string("prompt").orEmpty())
        } catch (e: WakeupException) {
            throw ToolException(e.message ?: "wakeup error")
        }
        return buildJsonObject { put("scheduled", true); put("delay_ms", delay); put("note", "End your turn now; you will be woken up.") }
    }
}
