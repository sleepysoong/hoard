package com.sleepysoong.hoard.ui.chat

import com.sleepysoong.hoard.data.Goal
import com.sleepysoong.hoard.data.GoalStatus
import com.sleepysoong.hoard.skills.InstalledSkill

/**
 * A command the composer can run. [insert] is what tapping it puts in the field;
 * commands with an argument end in a space so the user keeps typing.
 */
data class SlashCommand(val command: String, val label: String, val description: String, val insert: String)

object SlashCommands {
    /**
     * Goal commands that make sense right now: set/show always; pause only while
     * active; resume when paused, blocked or out of budget; clear when there is a goal.
     * Then /compact (summarize the conversation now), plain or with what to focus on.
     */
    fun available(goal: Goal?, skills: List<InstalledSkill> = emptyList()): List<SlashCommand> = buildList {
        add(SlashCommand("/goal", "/goal <목표>", "목표를 정하고 달성할 때까지 이어서 작업", "/goal "))
        add(SlashCommand("/goal", "/goal", "현재 목표 보기", "/goal"))
        val status = goal?.status
        if (status == GoalStatus.Active) add(SlashCommand("/goal pause", "/goal pause", "목표 일시정지", "/goal pause"))
        if (status == GoalStatus.Paused || status == GoalStatus.Blocked || status == GoalStatus.BudgetLimited) {
            add(SlashCommand("/goal resume", "/goal resume", "목표 재개 · 이어서 작업", "/goal resume"))
        }
        if (goal != null) add(SlashCommand("/goal clear", "/goal clear", "목표 지우기", "/goal clear"))
        skills.filter { it.enabled && it.document.userInvocable && !it.command.equals("goal", ignoreCase = true) }
            .sortedBy { it.command.lowercase() }
            .distinctBy { it.command.lowercase() }
            .forEach { skill ->
                val command = "/${skill.command}"
                val hint = skill.document.argumentHint.takeIf { it.isNotBlank() }
                add(SlashCommand(command, if (hint == null) command else "$command $hint", skill.document.description, "$command "))
            }
        add(SlashCommand("/compact", "/compact", "지금까지의 대화를 요약해 컨텍스트 비우기", "/compact"))
        add(SlashCommand("/compact", "/compact <지시>", "요약에 꼭 남길 내용을 정해서 압축", "/compact "))
    }

    /**
     * Menu entries for what's typed: shown while the text is a prefix of a command
     * (so "/", "/g", "/goal ", "/goal p" all suggest), hidden once an objective is being typed.
     */
    fun matching(typed: String, goal: Goal?, skills: List<InstalledSkill> = emptyList()): List<SlashCommand> {
        val t = typed.trimStart()
        if (!t.startsWith("/")) return emptyList()
        val hits = available(goal, skills).filter { it.insert.startsWith(t) }
        // A finished argument-less command ("/goal pause"): nothing left to suggest.
        return if (hits.size == 1 && hits.single().insert == t) emptyList() else hits
    }
}
