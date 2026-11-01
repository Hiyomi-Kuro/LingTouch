package com.kaori.lingtouch

import com.kaori.lingtouch.api.ApiEndpoints
import com.kaori.lingtouch.api.ApiProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiEndpointsTest {

    @Test
    fun `opencode go chat endpoint`() {
        assertEquals(
            "https://opencode.ai/inference/go/openai/v1/chat/completions",
            ApiEndpoints.chat("https://opencode.ai/inference/go", ApiProtocol.OPENAI_CHAT)
        )
    }

    @Test
    fun `opencode go models endpoint`() {
        assertEquals(
            "https://opencode.ai/inference/go/v1/models",
            ApiEndpoints.models("https://opencode.ai/inference/go/")
        )
    }

    @Test
    fun `generic openai base`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            ApiEndpoints.chat("https://api.openai.com", ApiProtocol.OPENAI_CHAT)
        )
        assertEquals("https://api.openai.com/v1/models", ApiEndpoints.models("https://api.openai.com"))
    }

    @Test
    fun `base ending with v1 is not duplicated`() {
        assertEquals(
            "https://example.com/v1/chat/completions",
            ApiEndpoints.chat("https://example.com/v1", ApiProtocol.OPENAI_CHAT)
        )
        assertEquals("https://example.com/v1/models", ApiEndpoints.models("https://example.com/v1"))
    }

    @Test
    fun `anthropic endpoint on inference base`() {
        assertEquals(
            "https://opencode.ai/inference/anthropic/v1/messages",
            ApiEndpoints.chat("https://opencode.ai/inference", ApiProtocol.ANTHROPIC)
        )
    }

    @Test
    fun `responses endpoint`() {
        assertEquals(
            "https://opencode.ai/inference/go/openai/v1/responses",
            ApiEndpoints.chat("https://opencode.ai/inference/go", ApiProtocol.OPENAI_RESPONSES)
        )
    }

    @Test
    fun `explicit full endpoint is preserved`() {
        val url = "https://example.com/openai/v1/chat/completions"
        assertEquals(url, ApiEndpoints.chat(url, ApiProtocol.OPENAI_CHAT))
    }
}
