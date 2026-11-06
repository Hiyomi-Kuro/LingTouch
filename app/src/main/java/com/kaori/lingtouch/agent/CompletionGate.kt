package com.kaori.lingtouch.agent

enum class GateOutcome { VERIFIED, NEEDS_CONFIRMATION, REJECTED }

data class GateResult(val outcome: GateOutcome, val reason: String)

/**
 * Decides whether finish_task may end the task. Local verification is authoritative;
 * an unverifiable claim can only finish after the user explicitly confirms it.
 */
object CompletionGate {
    fun evaluate(plan: TaskPlan, verification: Verification, executedActions: Int): GateResult {
        if (verification.status == VerificationStatus.VERIFIED_FAILED) {
            return GateResult(GateOutcome.REJECTED, "终态校验未通过：${verification.detail}")
        }
        val blocked = plan.subGoals.filter {
            it.status == SubGoalStatus.FAILED || it.status == SubGoalStatus.BLOCKED
        }
        if (blocked.isNotEmpty()) {
            return GateResult(
                GateOutcome.REJECTED,
                "存在未解决的子目标：" + blocked.joinToString("；") { "「${it.title}」${it.failureReason}" }
            )
        }
        if (verification.status == VerificationStatus.VERIFIED_SUCCESS) {
            return GateResult(GateOutcome.VERIFIED, verification.detail)
        }
        if (executedActions <= 0) {
            return GateResult(GateOutcome.REJECTED, "尚未成功执行任何设备操作，且没有可独立验证的完成条件")
        }
        return GateResult(GateOutcome.NEEDS_CONFIRMATION, verification.detail)
    }
}
