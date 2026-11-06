package com.kaori.lingtouch.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskBudgetTest {

    @Test
    fun `request counter increments`() {
        val budget = TaskBudget().request().request()
        assertEquals(2, budget.apiRequests)
    }

    @Test
    fun `reported usage is exact`() {
        val budget = TaskBudget().usage(120, 30)
        assertTrue(budget.exactUsage)
        assertEquals(150, budget.totalTokens)
        assertTrue(budget.displayText().contains("真实"))
    }

    @Test
    fun `estimate is marked as estimate`() {
        val budget = TaskBudget().estimate(900)
        assertFalse(budget.exactUsage)
        assertTrue(budget.displayText().contains("估算"))
    }

    @Test
    fun `zero usage keeps budget unchanged`() {
        val budget = TaskBudget(apiRequests = 1)
        assertEquals(budget, budget.usage(0, 0))
    }

    @Test
    fun `failure kinds map from tool messages`() {
        assertEquals(FailureKind.STALE_NODE, FailureKind.fromMessage("nodeId=3 已失效"))
        assertEquals(FailureKind.TARGET_NOT_FOUND, FailureKind.fromMessage("未找到目标控件"))
        assertEquals(FailureKind.UI_TIMEOUT, FailureKind.fromMessage("等待超时"))
        assertEquals(FailureKind.PERMISSION_DENIED, FailureKind.fromMessage("无障碍服务未连接"))
        assertEquals(FailureKind.VISION_UNAVAILABLE, FailureKind.fromMessage("截图失败"))
    }

    @Test
    fun `step trace distinguishes dispatch from verification`() {
        val dispatched = StepTrace(0, "tap", "text=蓝牙", true, ActionCommit.DISPATCHED, "已提交")
        val verified = StepTrace(1, "tap", "text=蓝牙", true, ActionCommit.VERIFIED_SUCCESS, "已验证")
        assertTrue(dispatched.line().contains("已提交"))
        assertTrue(verified.line().contains("已验证成功"))
    }
}
