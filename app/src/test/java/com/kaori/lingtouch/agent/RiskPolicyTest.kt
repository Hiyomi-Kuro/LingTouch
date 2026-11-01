package com.kaori.lingtouch

import com.kaori.lingtouch.agent.RiskPolicy
import com.kaori.lingtouch.tools.Risk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RiskPolicyTest {

    @Test
    fun `observation is low risk`() {
        assertEquals(Risk.LOW, RiskPolicy.assess("get_ui_tree", JSONObject()))
    }

    @Test
    fun `sending message is high risk`() {
        assertEquals(Risk.HIGH, RiskPolicy.assess("tap", JSONObject().put("text", "发送")))
    }

    @Test
    fun `payment is high risk`() {
        assertEquals(Risk.HIGH, RiskPolicy.assess("tap", JSONObject().put("text", "确认支付")))
    }

    @Test
    fun `ordinary navigation is low risk`() {
        assertEquals(Risk.LOW, RiskPolicy.assess("tap", JSONObject().put("text", "蓝牙")))
    }

    @Test
    fun `max keeps the more severe risk`() {
        assertEquals(Risk.HIGH, RiskPolicy.max(Risk.LOW, Risk.HIGH))
        assertEquals(Risk.MEDIUM, RiskPolicy.max(Risk.LOW, Risk.MEDIUM))
    }
}
