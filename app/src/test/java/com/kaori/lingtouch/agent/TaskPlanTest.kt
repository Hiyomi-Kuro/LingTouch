package com.kaori.lingtouch.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPlanTest {

    @Test
    fun `single plan has one open sub goal`() {
        val plan = TaskPlan.single("打开设置")
        assertEquals(1, plan.subGoals.size)
        assertNotNull(plan.current)
        assertFalse(plan.isResolved)
    }

    @Test
    fun `marking success advances to next sub goal`() {
        val plan = TaskPlan(
            goal = "打开设置并进入蓝牙",
            subGoals = listOf(
                SubGoal(1, "打开设置"),
                SubGoal(2, "进入蓝牙")
            )
        )
        val updated = plan.updateCurrent(SubGoalStatus.SUCCEEDED)
        assertEquals(SubGoalStatus.SUCCEEDED, updated.subGoals[0].status)
        assertEquals("进入蓝牙", updated.current?.title)
    }

    @Test
    fun `all succeeded means resolved`() {
        val plan = TaskPlan.single("打开设置").updateCurrent(SubGoalStatus.SUCCEEDED)
        assertTrue(plan.isResolved)
        assertEquals(null, plan.current)
    }

    @Test
    fun `parses plan array of strings`() {
        val plan = TaskPlan.parse(
            "打开设置并进入蓝牙",
            JSONObject("""{"plan":["打开设置","进入蓝牙"]}""")
        )
        assertNotNull(plan)
        assertEquals(2, plan!!.subGoals.size)
        assertEquals("进入蓝牙", plan.subGoals[1].title)
    }

    @Test
    fun `parses verification conditions from objects`() {
        val plan = TaskPlan.parse(
            "打开设置",
            JSONObject(
                """{"plan":[{"title":"打开设置","verify_package":"com.android.settings","verify_text":"蓝牙"}]}"""
            )
        )
        assertNotNull(plan)
        val goal = plan!!.subGoals.first()
        assertEquals("com.android.settings", goal.verifyPackage)
        assertEquals("蓝牙", goal.verifyText)
        assertTrue(goal.verifiable)
    }

    @Test
    fun `parses nested plan object`() {
        val plan = TaskPlan.parse(
            "目标",
            JSONObject("""{"plan":{"subgoals":[{"title":"第一步","expect_text":"完成"}]}}""")
        )
        assertNotNull(plan)
        assertEquals("完成", plan!!.subGoals.first().verifyText)
    }

    @Test
    fun `invalid plan returns null so the single plan survives`() {
        assertEquals(null, TaskPlan.parse("目标", JSONObject("""{"plan":[]}""")))
        assertEquals(null, TaskPlan.parse("目标", JSONObject("""{"action":"tap"}""")))
    }

    @Test
    fun `replan replaces sub goals and records revision`() {
        val plan = TaskPlan.single("目标")
            .withSubGoals(listOf(SubGoal(1, "新方案 A"), SubGoal(2, "新方案 B")), "界面变化后重新规划")
        assertEquals(2, plan.subGoals.size)
        assertEquals(2, plan.revision)
        assertTrue(plan.notes.first().contains("重新规划"))
        assertEquals(0, plan.currentIndex)
    }

    @Test
    fun `progress text reports completed count`() {
        val plan = TaskPlan(
            goal = "g",
            subGoals = listOf(SubGoal(1, "a", status = SubGoalStatus.SUCCEEDED), SubGoal(2, "b"))
        )
        assertTrue(plan.progressText().contains("1/2"))
    }
}
