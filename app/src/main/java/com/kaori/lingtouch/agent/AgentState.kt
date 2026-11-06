package com.kaori.lingtouch.agent

import com.kaori.lingtouch.tools.Risk

/**
 * Explicit task state machine. Pause/stop from the task page, notification and overlay all
 * read this single flow, so the UI can never disagree about whether the agent is running.
 */
enum class AgentStatus {
    IDLE,
    PLANNING,
    RUNNING,
    WAITING_USER,
    PAUSED,
    RECOVERING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

data class AgentStepUi(
    val index: Int,
    val action: String,
    val detail: String,
    val ok: Boolean,
    val message: String,
    val commit: ActionCommit = ActionCommit.UNKNOWN,
    val at: Long = System.currentTimeMillis()
)

data class AgentUiState(
    val status: AgentStatus = AgentStatus.IDLE,
    val taskId: Long = -1,
    val goal: String = "",
    val plan: String = "",
    val subGoals: List<SubGoal> = emptyList(),
    val steps: List<AgentStepUi> = emptyList(),
    val currentStep: Int = 0,
    val maxSteps: Int = 24,
    val message: String = "",
    val result: String = "",
    val error: String? = null,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val pendingRequest: ConfirmationBroker.Request? = null,
    val screen: String = "",
    val reasoning: String = "",
    val recovery: String = "",
    val apiRequests: Int = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val estimatedTokens: Int = 0,
    val exactUsage: Boolean = false
) {
    val isActive: Boolean
        get() = status == AgentStatus.PLANNING || status == AgentStatus.RUNNING ||
            status == AgentStatus.PAUSED || status == AgentStatus.WAITING_USER ||
            status == AgentStatus.RECOVERING

    val budgetText: String
        get() = TaskBudget(
            apiRequests = apiRequests,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            estimatedTokens = estimatedTokens,
            exactUsage = exactUsage
        ).displayText()
}
