package com.kaori.lingtouch.security

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    @Test
    fun `typed input text is never stored verbatim`() {
        val safe = Redactor.redactArgs("input_text", JSONObject().put("text", "my-secret-value"))
        assertEquals("[已隐藏输入 15 字]", safe.getString("text"))
    }

    @Test
    fun `sensitive argument keys are masked`() {
        val safe = Redactor.redactArgs(
            "tap",
            JSONObject().put("password", "123456").put("token", "abcdef").put("text", "登录")
        )
        assertEquals("***", safe.getString("password"))
        assertEquals("***", safe.getString("token"))
        assertEquals("登录", safe.getString("text"))
    }

    @Test
    fun `password and verification code patterns are masked in text`() {
        val text = Redactor.redactText("账号 admin 密码：hunter2 验证码：654321")
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("654321"))
        assertTrue(text.contains("***"))
    }

    @Test
    fun `api keys and bearer tokens are masked`() {
        val text = Redactor.redactText("Authorization: Bearer abcdef123456789 key sk-abcdef123456")
        assertFalse(text.contains("abcdef123456789"))
        assertFalse(text.contains("sk-abcdef123456"))
    }

    @Test
    fun `normal interface text is unchanged`() {
        val text = "蓝牙已开启"
        assertEquals(text, Redactor.redactText(text))
    }

    @Test
    fun `json export redacts nested sensitive values`() {
        val raw = JSONObject()
            .put("ok", true)
            .put("nested", JSONObject().put("api_key", "sk-secret").put("note", "password=abc"))
            .toString()
        val redacted = Redactor.redactJson(raw)
        assertFalse(redacted.contains("sk-secret"))
        assertFalse(redacted.contains("password=abc"))
    }

    @Test
    fun `sensitive hints detect password fields`() {
        assertTrue(Redactor.isSensitiveHint("登录密码", "pwd_input"))
        assertTrue(Redactor.isSensitiveHint("验证码输入框"))
        assertFalse(Redactor.isSensitiveHint("搜索框"))
    }
}
