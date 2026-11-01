package com.kaori.lingtouch.agent

import com.kaori.lingtouch.tools.Risk

enum class AgentStatus { IDLE, RUNNING, WAITING_CONFIRMATION, PAUSED, COMPLETED, FAILED, CANCELLED }

data class AgentStepUi(
    val index: Int,
    val action: String,
    val detail: String,
    val ok: Boolean,
    val message: String,
    val at: Long = System.currentTimeMillis()
)

data class AgentUiState(
    val status: AgentStatus = AgentStatus.IDLE,
    val taskId: Long = -1,
    val goal: String = "",
    val plan: String = "",
    val steps: List<AgentStepUi> = emptyList(),
    val currentStep: Int = 0,
    val maxSteps: Int = 24,
    val message: String = "",
    val result: String = "",
    val error: String? = null,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val pendingRequest: ConfirmationBroker.Request? = null
) {
    val isActive: Boolean
        get() = status == AgentStatus.RUNNING || status == AgentStatus.PAUSED ||
            status == AgentStatus.WAITING_CONFIRMATION
}
