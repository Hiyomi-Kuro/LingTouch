package com.kaori.lingtouch.api

data class ApiMessage(
    val role: String,
    val text: String,
    val images: List<ByteArray> = emptyList()
)

data class ModelInfo(
    val id: String,
    val name: String = id,
    val vision: Boolean = false,
    val jsonMode: Boolean = true,
    val contextLength: Int = 0,
    val ownedBy: String = ""
) {
    val display: String get() = if (name == id) id else "$name ($id)"
}

data class ChatOutput(
    val text: String,
    val model: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val latencyMs: Long = 0,
    val raw: String = ""
)

enum class ApiErrorKind {
    NETWORK, TIMEOUT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, RATE_LIMIT, SERVER, BAD_REQUEST, EMPTY, PARSE, UNKNOWN
}

data class ApiError(
    val kind: ApiErrorKind,
    val message: String,
    val httpCode: Int = 0,
    val retryable: Boolean = false
)

sealed interface ApiOutcome<out T> {
    data class Ok<T>(val value: T) : ApiOutcome<T>
    data class Err(val error: ApiError) : ApiOutcome<Nothing>

    fun valueOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): ApiError? = (this as? Err)?.error
}

class ApiException(val error: ApiError) : Exception(error.message)
