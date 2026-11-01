package com.kaori.lingtouch.api

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object HttpErrors {
    fun fromHttp(code: Int, body: String): ApiError {
        val detail = extractDetail(body)
        return when {
            code == 401 -> ApiError(ApiErrorKind.UNAUTHORIZED, "API Key 无效（HTTP 401）$detail", code, false)
            code == 403 -> ApiError(ApiErrorKind.FORBIDDEN, "无权限或该模型不可用（HTTP 403）$detail", code, false)
            code == 404 -> ApiError(ApiErrorKind.NOT_FOUND, "接口不存在（HTTP 404），请检查 Base URL$detail", code, false)
            code == 429 -> ApiError(ApiErrorKind.RATE_LIMIT, "请求过于频繁（HTTP 429）$detail", code, true)
            code in 500..599 -> ApiError(ApiErrorKind.SERVER, "服务端错误（HTTP $code）$detail", code, true)
            code == 400 || code == 422 -> ApiError(ApiErrorKind.BAD_REQUEST, "请求被拒绝（HTTP $code）$detail", code, false)
            else -> ApiError(ApiErrorKind.UNKNOWN, "请求失败（HTTP $code）$detail", code, code >= 500)
        }
    }

    fun fromThrowable(t: Throwable): ApiError = when (t) {
        is SocketTimeoutException -> ApiError(ApiErrorKind.TIMEOUT, "请求超时", 0, true)
        is UnknownHostException -> ApiError(ApiErrorKind.NETWORK, "无法解析服务器地址：${t.message}", 0, true)
        is IOException -> ApiError(ApiErrorKind.NETWORK, "网络错误：${t.message}", 0, true)
        else -> ApiError(ApiErrorKind.UNKNOWN, "未知错误：${t.message ?: t.javaClass.simpleName}", 0, false)
    }

    private fun extractDetail(body: String): String {
        if (body.isBlank()) return ""
        val text = body.take(600)
        return "：${text.replace(Regex("\\s+"), " ")}"
    }
}
