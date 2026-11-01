package com.kaori.lingtouch.api

enum class ApiProtocol(val label: String) {
    OPENAI_CHAT("openai-chat"),
    OPENAI_RESPONSES("openai-responses"),
    ANTHROPIC("anthropic"),
    GEMINI("gemini");

    companion object {
        fun from(value: String?): ApiProtocol =
            entries.firstOrNull { it.name == value || it.label == value } ?: OPENAI_CHAT
    }
}
