package com.kaori.lingtouch

import com.kaori.lingtouch.tools.ActionParser
import com.kaori.lingtouch.tools.ActionValidationException
import com.kaori.lingtouch.tools.Risk
import com.kaori.lingtouch.tools.ToolRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSchemaTest {

    @Test
    fun `parses plain json action`() {
        val action = ActionParser.parse(
            """{"thought":"open settings","action":"launch_app","args":{"package":"com.android.settings"},"risk":"low"}"""
        )
        assertEquals("launch_app", action.name)
        assertEquals("com.android.settings", action.args.getString("package"))
        assertEquals(Risk.LOW, action.declaredRisk)
    }

    @Test
    fun `parses fenced json`() {
        val action = ActionParser.parse("```json\n{\"action\":\"press_back\",\"args\":{}}\n```")
        assertEquals("press_back", action.name)
    }

    @Test
    fun `parses wrapped action object`() {
        val action = ActionParser.parse("""{"step":{"action":"tap","args":{"text":"蓝牙"}}}""")
        assertEquals("tap", action.name)
    }

    @Test
    fun `rejects missing action`() {
        assertThrows(ActionValidationException::class.java) {
            ActionParser.parse("""{"thought":"nothing"}""")
        }
    }

    @Test
    fun `rejects free text`() {
        assertThrows(ActionValidationException::class.java) {
            ActionParser.parse("adb shell input tap 100 200")
        }
    }

    @Test
    fun `unknown tool rejected`() {
        assertThrows(ActionValidationException::class.java) {
            ToolRegistry.validate("run_shell", JSONObject().put("cmd", "rm -rf /"))
        }
    }

    @Test
    fun `required argument enforced`() {
        assertThrows(ActionValidationException::class.java) {
            ToolRegistry.validate("swipe", JSONObject().put("x1", 1))
        }
    }

    @Test
    fun `numeric strings are normalized`() {
        val args = ToolRegistry.validate(
            "swipe",
            JSONObject().put("x1", "10").put("y1", "20").put("x2", "30").put("y2", "40").put("durationMs", "250")
        )
        assertEquals(10, args.getInt("x1"))
        assertEquals(250, args.getInt("durationMs"))
    }

    @Test
    fun `enum is validated`() {
        assertThrows(ActionValidationException::class.java) {
            ToolRegistry.validate("scroll", JSONObject().put("direction", "diagonal"))
        }
        val args = ToolRegistry.validate("scroll", JSONObject().put("direction", "down"))
        assertEquals("down", args.getString("direction"))
    }

    @Test
    fun `launch app requires package or name`() {
        assertThrows(ActionValidationException::class.java) {
            ToolRegistry.validate("launch_app", JSONObject())
        }
        val args = ToolRegistry.validate("launch_app", JSONObject().put("app", "设置"))
        assertEquals("设置", args.getString("app"))
    }

    @Test
    fun `int range is enforced`() {
        assertThrows(ActionValidationException::class.java) {
            ToolRegistry.validate("swipe", JSONObject().put("x1", -5).put("y1", 1).put("x2", 2).put("y2", 3))
        }
    }

    @Test
    fun `tool names are unique and non-empty`() {
        assertTrue(ToolRegistry.specs.isNotEmpty())
        ToolRegistry.specs.forEach { spec ->
            assertEquals(1, ToolRegistry.specs.count { it.name == spec.name })
        }
    }
}
