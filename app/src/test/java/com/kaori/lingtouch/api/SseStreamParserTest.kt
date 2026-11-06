package com.kaori.lingtouch.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseStreamParserTest {

    @Test
    fun `parses openai text and reasoning deltas`() {
        val delta = SseStreamParser.parseData(
            """{"choices":[{"delta":{"content":"hello","reasoning_content":"think"},"finish_reason":null}]}"""
        )
        assertNotNull(delta)
        assertEquals("hello", delta!!.text)
        assertEquals("think", delta.reasoning)
    }

    @Test
    fun `parses anthropic thinking delta`() {
        val delta = SseStreamParser.parseData(
            """{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"plan"}}"""
        )
        assertEquals("plan", delta!!.reasoning)
        assertEquals("", delta.text)
    }

    @Test
    fun `parses responses output text delta`() {
        val delta = SseStreamParser.parseData(
            """{"type":"response.output_text.delta","delta":"answer"}"""
        )
        assertEquals("answer", delta!!.text)
    }

    @Test
    fun `parses gemini thought part`() {
        val delta = SseStreamParser.parseData(
            """{"candidates":[{"content":{"parts":[{"text":"a"},{"text":"b","thought":true}]},"finishReason":"STOP"}]}"""
        )
        assertEquals("a", delta!!.text)
        assertEquals("b", delta.reasoning)
        assertEquals("STOP", delta.finish)
    }

    @Test
    fun `surfaces error envelope`() {
        val delta = SseStreamParser.parseData("""{"error":{"message":"quota exhausted"}}""")
        assertEquals("quota exhausted", delta!!.error)
    }

    @Test
    fun `done and empty payloads are ignored`() {
        assertNull(SseStreamParser.parseData("[DONE]"))
        assertNull(SseStreamParser.parseData(""))
        assertNull(SseStreamParser.parseData("{}"))
    }

    @Test
    fun `usage tokens are read`() {
        val delta = SseStreamParser.parseData(
            """{"choices":[{"delta":{"content":"x"}}],"usage":{"prompt_tokens":12,"completion_tokens":3}}"""
        )
        assertEquals(12, delta!!.promptTokens)
        assertEquals(3, delta.completionTokens)
        assertTrue(delta.text.isNotEmpty())
    }
}
