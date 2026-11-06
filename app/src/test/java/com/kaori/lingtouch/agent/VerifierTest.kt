package com.kaori.lingtouch.agent

import com.kaori.lingtouch.accessibility.UiNode
import com.kaori.lingtouch.accessibility.UiTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifierTest {

    private fun node(
        index: Int,
        text: String = "",
        checked: Boolean = false,
        selected: Boolean = false
    ): UiNode = UiNode(
        index = index,
        className = "android.widget.TextView",
        text = text,
        desc = "",
        resourceId = "",
        packageName = "com.example",
        left = 0,
        top = 0,
        right = 10,
        bottom = 10,
        clickable = false,
        longClickable = false,
        editable = false,
        scrollable = false,
        checkable = checked,
        checked = checked,
        selected = selected,
        enabled = true,
        focused = false,
        depth = 0
    )

    private fun tree(
        pkg: String = "com.example",
        activity: String = "com.example.MainActivity",
        nodes: List<UiNode> = emptyList()
    ): UiTree = UiTree(
        packageName = pkg,
        activityName = activity,
        windowTitle = "",
        width = 1080,
        height = 2400,
        rotation = 0,
        nodes = nodes,
        timestamp = System.currentTimeMillis(),
        windowCount = 1
    )

    @Test
    fun `text present verifies success`() {
        val result = Verifier.verify(
            ExpectedState(text = "蓝牙", source = "test"),
            tree(nodes = listOf(node(0, "蓝牙"))),
            null
        )
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
    }

    @Test
    fun `missing text fails verification`() {
        val result = Verifier.verify(
            ExpectedState(text = "蓝牙", source = "test"),
            tree(nodes = listOf(node(0, "无线网络"))),
            null
        )
        assertEquals(VerificationStatus.VERIFIED_FAILED, result.status)
    }

    @Test
    fun `package mismatch fails verification`() {
        val result = Verifier.verify(
            ExpectedState(packageName = "com.android.settings"),
            tree(pkg = "com.other.app"),
            null
        )
        assertEquals(VerificationStatus.VERIFIED_FAILED, result.status)
    }

    @Test
    fun `matching package verifies success`() {
        val result = Verifier.verify(
            ExpectedState(packageName = "com.android.settings"),
            tree(pkg = "com.android.settings"),
            null
        )
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
    }

    @Test
    fun `no verifiable condition is unknown`() {
        val result = Verifier.verify(ExpectedState(), tree(), null)
        assertEquals(VerificationStatus.UNKNOWN, result.status)
    }

    @Test
    fun `unreadable tree with text expectation is unknown, not success`() {
        val result = Verifier.verify(ExpectedState(text = "蓝牙"), null, null)
        assertEquals(VerificationStatus.UNKNOWN, result.status)
    }

    @Test
    fun `checked control verifies selected state`() {
        val result = Verifier.verify(
            ExpectedState(checkedText = "蓝牙"),
            tree(nodes = listOf(node(0, "蓝牙", checked = true))),
            null
        )
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
    }

    @Test
    fun `unchecked control fails selected state`() {
        val result = Verifier.verify(
            ExpectedState(checkedText = "蓝牙"),
            tree(nodes = listOf(node(0, "蓝牙", checked = false))),
            null
        )
        assertEquals(VerificationStatus.VERIFIED_FAILED, result.status)
    }

    @Test
    fun `textAbsent succeeds when text disappeared`() {
        val result = Verifier.verify(
            ExpectedState(text = "正在加载", textAbsent = true),
            tree(nodes = listOf(node(0, "设置"))),
            null
        )
        assertEquals(VerificationStatus.VERIFIED_SUCCESS, result.status)
    }

    @Test
    fun `merge prefers primary expectation and keeps sub-goal fallback`() {
        val merged = Verifier.merge(
            ExpectedState(text = "完成", source = "finish"),
            ExpectedState(packageName = "com.android.settings", text = "设置", source = "subgoal")
        )
        assertEquals("完成", merged.text)
        assertEquals("com.android.settings", merged.packageName)
        assertTrue(merged.source.contains("finish"))
    }
}
