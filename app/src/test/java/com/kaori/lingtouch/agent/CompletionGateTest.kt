package com.kaori.lingtouch.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class CompletionGateTest {

    private fun verified() = Verification(
        VerificationStatus.VERIFIED_SUCCESS, "界面存在目标文字", ExpectedState(text = "目标")
    )

    private fun unknown() = Verification(
        VerificationStatus.UNKNOWN, "没有可独立验证的条件", ExpectedState()
    )

    private fun failed() = Verification(
        VerificationStatus.VERIFIED_FAILED, "界面中未找到目标文字", ExpectedState(text = "目标")
    )

    private fun plan(vararg statuses: SubGoalStatus): TaskPlan = TaskPlan(
        goal = "测试目标",
        subGoals = statuses.mapIndexed { index, status ->
            SubGoal(id = index + 1, title = "子目标${index + 1}", status = status)
        }
    )

    @Test
    fun `verified completion is allowed`() {
        val gate = CompletionGate.evaluate(plan(SubGoalStatus.SUCCEEDED), verified(), 3)
        assertEquals(GateOutcome.VERIFIED, gate.outcome)
    }

    @Test
    fun `pending sub-goal does not block a verified terminal state`() {
        val gate = CompletionGate.evaluate(plan(SubGoalStatus.PENDING), verified(), 2)
        assertEquals(GateOutcome.VERIFIED, gate.outcome)
    }

    @Test
    fun `failed sub-goal blocks completion`() {
        val gate = CompletionGate.evaluate(plan(SubGoalStatus.FAILED), verified(), 3)
        assertEquals(GateOutcome.REJECTED, gate.outcome)
    }

    @Test
    fun `verification failure blocks completion`() {
        val gate = CompletionGate.evaluate(plan(SubGoalStatus.SUCCEEDED), failed(), 3)
        assertEquals(GateOutcome.REJECTED, gate.outcome)
    }

    @Test
    fun `unverifiable completion after real actions needs user confirmation`() {
        val gate = CompletionGate.evaluate(plan(SubGoalStatus.PENDING), unknown(), 2)
        assertEquals(GateOutcome.NEEDS_CONFIRMATION, gate.outcome)
    }

    @Test
    fun `unverifiable completion without any action is rejected`() {
        val gate = CompletionGate.evaluate(plan(SubGoalStatus.PENDING), unknown(), 0)
        assertEquals(GateOutcome.REJECTED, gate.outcome)
    }
}
