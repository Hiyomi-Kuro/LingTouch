package com.kaori.lingtouch.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressorTest {

    private fun traces(count: Int): List<StepTrace> = (0 until count).map { index ->
        StepTrace(
            index = index,
            action = if (index % 2 == 0) "tap" else "scroll",
            args = "text=项目$index",
            ok = index % 3 != 0,
            commit = ActionCommit.OBSERVED_CHANGED,
            message = "第 $index 步执行完成，界面发生了较明显的变化，模型需要继续分析当前页面",
            screenChanged = true
        )
    }

    @Test
    fun `user constraints survive aggressive compression`() {
        val snapshot = ContextCompressor(tokenBudget = 300).build(
            goal = "在聊天应用里找到张三并查看消息",
            constraints = listOf("不要发送任何消息", "只能查看"),
            plan = TaskPlan.single("找到张三"),
            traces = traces(40),
            failures = listOf(FailureInfo(FailureKind.TARGET_NOT_FOUND, "未找到张三", 5)),
            memoryText = "",
            screen = null,
            uiText = "界面文字".repeat(200)
        )
        assertTrue(snapshot.text.contains("不要发送任何消息"))
        assertTrue(snapshot.text.contains("只能查看"))
        assertTrue(snapshot.constraints.contains("不要发送任何消息"))
    }

    @Test
    fun `older steps are compressed but recent steps remain`() {
        val snapshot = ContextCompressor().build(
            goal = "打开设置",
            constraints = emptyList(),
            plan = TaskPlan.single("打开设置"),
            traces = traces(20),
            failures = emptyList(),
            memoryText = "",
            screen = null,
            uiText = ""
        )
        assertTrue(snapshot.text.contains("较早步骤已压缩"))
        assertTrue(snapshot.text.contains("20."))
        assertTrue(snapshot.recentSteps.size <= 6)
    }

    @Test
    fun `failure kinds are preserved in the context`() {
        val snapshot = ContextCompressor().build(
            goal = "打开设置",
            constraints = emptyList(),
            plan = TaskPlan.single("打开设置"),
            traces = emptyList(),
            failures = listOf(FailureInfo(FailureKind.STALE_NODE, "nodeId 已失效", 2)),
            memoryText = "",
            screen = null,
            uiText = ""
        )
        assertTrue(snapshot.text.contains("STALE_NODE"))
        assertTrue(snapshot.text.contains("nodeId 已失效"))
    }

    @Test
    fun `safety rules are always injected`() {
        val snapshot = ContextCompressor().build(
            goal = "随便看看",
            constraints = emptyList(),
            plan = TaskPlan.single("随便看看"),
            traces = emptyList(),
            failures = emptyList(),
            memoryText = "",
            screen = null,
            uiText = ""
        )
        assertTrue(snapshot.text.contains("低信任数据"))
    }

    @Test
    fun `token estimate grows with text`() {
        val small = TokenEstimator.estimate("打开设置")
        val large = TokenEstimator.estimate("打开设置".repeat(20))
        assertTrue(large > small)
        assertTrue(small >= 1)
    }

    @Test
    fun `constraints extractor keeps prohibition phrases`() {
        val constraints = Constraints.fromGoal("打开微信。不要发送消息。必须只看张三的消息")
        assertTrue(constraints.any { it.contains("不要发送消息") })
        assertTrue(constraints.any { it.contains("必须只看") })
    }
}
