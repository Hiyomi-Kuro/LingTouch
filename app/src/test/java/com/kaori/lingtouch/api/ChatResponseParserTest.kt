package com.kaori.lingtouch.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatResponseParserTest {

    private fun parse(body: String, protocol: ApiProtocol = ApiProtocol.OPENAI_CHAT) =
        ChatResponseParser.parse(protocol, body)

    @Test
    fun `parses standard openai content`() {
        val parsed = parse(
            """{"id":"x","choices":[{"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}]}"""
        )
        assertNotNull(parsed)
        assertEquals("pong", parsed!!.text)
        assertEquals("stop", parsed.finishReason)
        assertTrue(parsed.hasFinalText)
    }

    @Test
    fun `parses content parts array`() {
        val parsed = parse(
            """{"choices":[{"message":{"content":[{"type":"text","text":"po"},{"type":"text","text":"ng"}]},"finish_reason":"stop"}]}"""
        )
        assertEquals("pong", parsed!!.text)
    }

    @Test
    fun `reasoning-only answer is not treated as final text`() {
        val parsed = parse(
            """{"choices":[{"message":{"content":"","reasoning_content":"用户只是打招呼，回答 pong"},"finish_reason":"length"}]}"""
        )
        assertNotNull(parsed)
        assertEquals("", parsed!!.text)
        assertTrue(parsed.reasoning.contains("pong"))
        assertEquals("length", parsed.finishReason)
        assertFalse(parsed.hasFinalText)
    }

    @Test
    fun `parses sse delta stream`() {
        val body = """
            data: {"choices":[{"delta":{"content":"po"}}]}
            
            data: {"choices":[{"delta":{"content":"ng"},"finish_reason":"stop"}]}
            
            data: [DONE]
        """.trimIndent()
        val parsed = parse(body)
        assertNotNull(parsed)
        assertEquals("pong", parsed!!.text)
        assertEquals("stop", parsed.finishReason)
    }

    @Test
    fun `parses sse full message chunk`() {
        val body = "data: {\"choices\":[{\"message\":{\"content\":\"pong\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]"
        assertEquals("pong", parse(body)!!.text)
    }

    @Test
    fun `extracts error message from http 200 error envelope`() {
        val message = ChatResponseParser.errorMessage(
            """{"error":{"message":"model deepseek-v4.1-flash not found","type":"invalid_request_error"}}"""
        )
        assertEquals("model deepseek-v4.1-flash not found", message)
    }

    @Test
    fun `extracts anthropic style error`() {
        val message = ChatResponseParser.errorMessage(
            """{"type":"error","error":{"type":"rate_limit_error","message":"too many requests"}}"""
        )
        assertEquals("too many requests", message)
    }

    @Test
    fun `parses responses api output_text`() {
        val parsed = parse(
            """{"output_text":"pong","model":"x"}""",
            ApiProtocol.OPENAI_RESPONSES
        )
        assertEquals("pong", parsed!!.text)
    }

    @Test
    fun `parses responses api output array`() {
        val parsed = parse(
            """{"output":[{"content":[{"type":"output_text","text":"pong"}]}]}""",
            ApiProtocol.OPENAI_RESPONSES
        )
        assertEquals("pong", parsed!!.text)
    }

    @Test
    fun `falls back to anthropic shape for openai protocol`() {
        val parsed = parse(
            """{"content":[{"type":"text","text":"pong"}],"stop_reason":"end_turn"}"""
        )
        assertEquals("pong", parsed!!.text)
        assertEquals("anthropic", parsed.source)
    }

    @Test
    fun `falls back to gemini shape for openai protocol`() {
        val parsed = parse(
            """{"candidates":[{"content":{"parts":[{"text":"pong"}]},"finishReason":"STOP"}]}"""
        )
        assertEquals("pong", parsed!!.text)
        assertEquals("gemini", parsed.source)
    }

    @Test
    fun `parses direct text field from compatibility gateway`() {
        val parsed = parse("""{"text":"pong"}""")
        assertEquals("pong", parsed!!.text)
    }

    @Test
    fun `parses nested response envelope`() {
        val parsed = parse(
            """{"response":{"choices":[{"message":{"content":"pong"},"finish_reason":"stop"}]}}"""
        )
        assertEquals("pong", parsed!!.text)
    }

    @Test
    fun `structure reports finish reason and keys without content`() {
        val structure = ChatResponseParser.structure(
            """{"id":"x","choices":[{"message":{"content":"secret text","reasoning_content":"r"},"finish_reason":"length"}]}"""
        )
        assertTrue(structure.contains("keys="))
        assertTrue(structure.contains("finish_reason=length"))
        assertFalse(structure.contains("secret text"))
    }

    @Test
    fun `non json body is rejected`() {
        assertNull(parse("<html>bad gateway</html>"))
        assertTrue(ChatResponseParser.structure("<html>bad gateway</html>").contains("非 JSON"))
    }

    @Test
    fun `empty body is rejected`() {
        assertNull(parse(""))
        assertEquals("空响应体", ChatResponseParser.structure(""))
    }
}
