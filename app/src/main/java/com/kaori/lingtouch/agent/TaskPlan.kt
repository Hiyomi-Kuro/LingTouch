package com.kaori.lingtouch.agent

import org.json.JSONArray
import org.json.JSONObject

enum class SubGoalStatus { PENDING, RUNNING, SUCCEEDED, FAILED, BLOCKED, SKIPPED }

data class SubGoal(
    val id: Int,
    val title: String,
    val verifyPackage: String = "",
    val verifyActivity: String = "",
    val verifyText: String = "",
    val status: SubGoalStatus = SubGoalStatus.PENDING,
    val attempts: Int = 0,
    val failureReason: String = "",
    val recovery: String = ""
) {
    val verifiable: Boolean
        get() = verifyPackage.isNotBlank() || verifyActivity.isNotBlank() || verifyText.isNotBlank()

    val isOpen: Boolean
        get() = status == SubGoalStatus.PENDING || status == SubGoalStatus.RUNNING

    fun expected(): ExpectedState = ExpectedState(
        packageName = verifyPackage,
        activity = verifyActivity,
        text = verifyText,
        source = "subgoal#$id"
    )

    fun marker(): String = when (status) {
        SubGoalStatus.PENDING -> "○"
        SubGoalStatus.RUNNING -> "▶"
        SubGoalStatus.SUCCEEDED -> "✔"
        SubGoalStatus.FAILED -> "✘"
        SubGoalStatus.BLOCKED -> "!"
        SubGoalStatus.SKIPPED -> "–"
    }

    fun line(): String = buildString {
        append(marker()).append(' ').append(id).append(". ").append(title)
        if (verifiable) {
            append("（验证：")
            append(expected().describe())
            append("）")
        }
        if (failureReason.isNotBlank()) append(" 原因：").append(failureReason.take(60))
        if (recovery.isNotBlank()) append(" 恢复：").append(recovery.take(60))
    }
}

/**
 * Lightweight, immutable task plan. Simple goals get a single sub-goal so the engine does
 * not pay for a separate planning round; complex goals may be decomposed or re-planned.
 */
data class TaskPlan(
    val goal: String,
    val constraints: List<String> = emptyList(),
    val subGoals: List<SubGoal> = emptyList(),
    val currentIndex: Int = 0,
    val revision: Int = 1,
    val notes: List<String> = emptyList()
) {
    val openIndex: Int?
        get() = subGoals.indices.firstOrNull { subGoals[it].isOpen }

    val current: SubGoal? get() = openIndex?.let { subGoals[it] }

    val isResolved: Boolean get() = openIndex == null

    val succeededCount: Int get() = subGoals.count { it.status == SubGoalStatus.SUCCEEDED }

    fun updateCurrent(status: SubGoalStatus, reason: String = "", recovery: String = ""): TaskPlan {
        val index = openIndex ?: return this
        val next = subGoals.toMutableList()
        val goal = next[index]
        val attempts = if (status == SubGoalStatus.SUCCEEDED) goal.attempts else goal.attempts + 1
        next[index] = goal.copy(
            status = status,
            attempts = attempts,
            failureReason = reason,
            recovery = recovery
        )
        return copy(subGoals = next, currentIndex = next.indexOfFirst { it.isOpen }.let { if (it >= 0) it else index })
    }

    fun markOpen(status: SubGoalStatus, reason: String = ""): TaskPlan {
        if (openIndex == null) return this
        val next = subGoals.map {
            if (it.isOpen) it.copy(status = status, failureReason = reason) else it
        }
        return copy(subGoals = next, currentIndex = next.indexOfFirst { it.isOpen }.let { if (it >= 0) it else 0 })
    }

    /** Replaces the remaining plan after the model re-planned; completed history is folded into notes. */
    fun withSubGoals(newGoals: List<SubGoal>, note: String = ""): TaskPlan {
        val cleaned = newGoals
            .filter { it.title.isNotBlank() }
            .take(8)
            .mapIndexed { index, goal -> goal.copy(id = index + 1, status = SubGoalStatus.PENDING) }
        if (cleaned.isEmpty()) return this
        return copy(
            subGoals = cleaned,
            currentIndex = 0,
            revision = revision + 1,
            notes = (notes + note).filter { it.isNotBlank() }.takeLast(6)
        )
    }

    fun progressText(): String {
        if (subGoals.isEmpty()) return "单步执行"
        val currentTitle = current?.title ?: "已完成"
        return "${succeededCount}/${subGoals.size} 子目标完成 · 当前：$currentTitle"
    }

    fun summaryLines(): List<String> = subGoals.map { it.line() }

    fun toJson(): JSONObject = JSONObject().apply {
        put("goal", goal)
        put("revision", revision)
        put("current", currentIndex)
        put("subgoals", JSONArray().apply {
            subGoals.forEach { goal ->
                put(
                    JSONObject()
                        .put("id", goal.id)
                        .put("title", goal.title)
                        .put("verify_package", goal.verifyPackage)
                        .put("verify_activity", goal.verifyActivity)
                        .put("verify_text", goal.verifyText)
                        .put("status", goal.status.name)
                        .put("attempts", goal.attempts)
                        .put("failure", goal.failureReason)
                        .put("recovery", goal.recovery)
                )
            }
        })
        put("notes", JSONArray(notes))
    }

    companion object {
        fun single(goal: String, constraints: List<String> = emptyList()): TaskPlan = TaskPlan(
            goal = goal,
            constraints = constraints,
            subGoals = listOf(SubGoal(id = 1, title = goal.take(80)))
        )

        /**
         * Parses an optional plan emitted by the model. Supports:
         * {"plan":[{...}]}, {"plan":{"subgoals":[...]}}, {"subgoals":[...]}, {"task_plan":[...]}
         */
        fun parse(goal: String, root: JSONObject): TaskPlan? {
            val array = root.optJSONArray("plan")
                ?: root.optJSONArray("subgoals")
                ?: root.optJSONArray("task_plan")
                ?: root.optJSONArray("task_goals")
                ?: root.optJSONObject("plan")?.let {
                    it.optJSONArray("subgoals") ?: it.optJSONArray("steps") ?: it.optJSONArray("plan")
                }
                ?: return null
            val goals = ArrayList<SubGoal>()
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is String -> if (item.isNotBlank()) {
                        goals.add(SubGoal(id = goals.size + 1, title = item.trim().take(80)))
                    }
                    is JSONObject -> {
                        val title = item.optString("title")
                            .ifBlank { item.optString("name") }
                            .ifBlank { item.optString("step") }
                            .ifBlank { item.optString("goal") }
                        if (title.isBlank()) continue
                        goals.add(
                            SubGoal(
                                id = goals.size + 1,
                                title = title.trim().take(80),
                                verifyPackage = item.optString("verify_package")
                                    .ifBlank { item.optString("expect_package") },
                                verifyActivity = item.optString("verify_activity")
                                    .ifBlank { item.optString("expect_activity") },
                                verifyText = item.optString("verify_text")
                                    .ifBlank { item.optString("expect_text") }
                            )
                        )
                    }
                }
            }
            if (goals.isEmpty()) return null
            val constraints = parseConstraints(root)
            return TaskPlan(
                goal = goal,
                constraints = constraints,
                subGoals = goals.take(8)
            )
        }

        private fun parseConstraints(root: JSONObject): List<String> {
            val array = root.optJSONArray("constraints") ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                array.optString(index).trim().takeIf { it.isNotBlank() }
            }.take(8)
        }
    }
}
