package com.kaori.lingtouch.api

/**
 * Pure URL derivation, verified against the OpenCode Go documentation:
 *   base https://opencode.ai/inference/go
 *   models  <base>/v1/models
 *   chat    <base>/openai/v1/chat/completions
 * Other OpenAI/Anthropic-compatible bases are supported with the same rules.
 */
object ApiEndpoints {

    fun chat(baseUrl: String, protocol: ApiProtocol): String {
        val base = baseUrl.trim().trimEnd('/')
        return when (protocol) {
            ApiProtocol.OPENAI_CHAT -> when {
                base.endsWith("/chat/completions") -> base
                base.endsWith("/v1") -> "$base/chat/completions"
                base.endsWith("/go") -> "$base/openai/v1/chat/completions"
                base.contains("/inference") -> "$base/openai/v1/chat/completions"
                else -> "$base/v1/chat/completions"
            }
            ApiProtocol.OPENAI_RESPONSES -> when {
                base.endsWith("/responses") -> base
                base.endsWith("/go") -> "$base/openai/v1/responses"
                base.contains("/inference") -> "$base/openai/v1/responses"
                base.endsWith("/v1") -> "$base/responses"
                else -> "$base/v1/responses"
            }
            ApiProtocol.ANTHROPIC -> when {
                base.endsWith("/messages") -> base
                base.endsWith("/v1") -> "$base/messages"
                base.contains("/inference") -> "$base/anthropic/v1/messages"
                else -> "$base/v1/messages"
            }
            ApiProtocol.GEMINI -> when {
                base.endsWith("/v1beta") -> "$base/models"
                else -> "$base/v1beta/models"
            }
        }
    }

    fun models(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/v1/models") -> base
            base.endsWith("/go") -> "$base/v1/models"
            base.endsWith("/v1") -> "$base/models"
            base.contains("/inference") -> "$base/v1/models"
            else -> "$base/v1/models"
        }
    }
}
