package com.kaori.lingtouch.api

import com.kaori.lingtouch.settings.AppSettings
import com.kaori.lingtouch.settings.SettingsRepository
import com.kaori.lingtouch.util.int
import com.kaori.lingtouch.util.obj
import com.kaori.lingtouch.util.Json.parseObject
import com.kaori.lingtouch.util.str
import com.kaori.lingtouch.util.Logx
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real HTTP client for OpenCode Go (and any OpenAI/Anthropic/Gemini-compatible service).
 *
 * OpenCode Go endpoints verified against https://opencode.ai/v2/docs/console/go/ :
 *   base https://opencode.ai/inference/go
 *   models  <base>/v1/models
 *   chat    <base>/openai/v1/chat/completions
 * Auth is `Authorization: Bearer <key>`; `x-opencode-session` is sent for routing/caching.
 */
class OpenCodeClient(private val settings: SettingsRepository) {

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val sessionId: String = UUID.randomUUID().toString()

    fun chatEndpoint(baseUrl: String, protocol: ApiProtocol): String =
        ApiEndpoints.chat(baseUrl, protocol)

    fun modelsEndpoint(baseUrl: String): String = ApiEndpoints.models(baseUrl)

    suspend fun listModels(): ApiOutcome<List<ModelInfo>> = withContext(Dispatchers.IO) {
        val cfg = settings.state.value
        val key = settings.apiKey()
        if (key.isNullOrBlank()) {
            return@withContext ApiOutcome.Err(ApiError(ApiErrorKind.UNAUTHORIZED, "尚未配置 API Key", 0, false))
        }
        val url = modelsEndpoint(cfg.baseUrl)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("x-opencode-session", sessionId)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        try {
            val (code, body) = execute(request, cfg.timeoutSeconds * 1000)
            if (code !in 200..299) return@withContext ApiOutcome.Err(HttpErrors.fromHttp(code, body))
            val models = parseModels(body)
            if (models.isEmpty()) {
                ApiOutcome.Err(ApiError(ApiErrorKind.EMPTY, "模型列表为空", code, false))
            } else {
                ApiOutcome.Ok(models)
            }
        } catch (e: Exception) {
            ApiOutcome.Err(HttpErrors.fromThrowable(e))
        }
    }

    suspend fun testConnection(): ApiOutcome<ChatOutput> {
        val cfg = settings.state.value
        if (settings.apiKey().isNullOrBlank()) {
            return ApiOutcome.Err(ApiError(ApiErrorKind.UNAUTHORIZED, "尚未配置 API Key", 0, false))
        }
        if (cfg.model.isBlank()) {
            return ApiOutcome.Err(ApiError(ApiErrorKind.BAD_REQUEST, "未选择模型", 0, false))
        }
        val messages = listOf(
            ApiMessage("system", "You are a connectivity probe. Reply with the single word: pong"),
            ApiMessage("user", "ping")
        )
        return chat(cfg.model, messages, jsonMode = false, maxTokens = 256, retries = 0)
    }

    suspend fun chat(
        model: String,
        messages: List<ApiMessage>,
        jsonMode: Boolean,
        maxTokens: Int = 1600,
        retries: Int = -1
    ): ApiOutcome<ChatOutput> {
        val cfg = settings.state.value
        val key = settings.apiKey()
        if (key.isNullOrBlank()) {
            return ApiOutcome.Err(ApiError(ApiErrorKind.UNAUTHORIZED, "尚未配置 API Key", 0, false))
        }
        val maxAttempts = (if (retries >= 0) retries else cfg.maxRetries) + 1
        var lastError: ApiError? = null
        repeat(maxAttempts) { attempt ->
            val outcome = runCatching { chatOnce(cfg, key, model, messages, jsonMode, maxTokens) }
                .getOrElse { ApiOutcome.Err(HttpErrors.fromThrowable(it)) }
            when (outcome) {
                is ApiOutcome.Ok -> return outcome
                is ApiOutcome.Err -> {
                    lastError = outcome.error
                    Logx.w("Api", "请求失败(${outcome.error.kind}, HTTP ${outcome.error.httpCode}) attempt=$attempt")
                    if (!outcome.error.retryable || attempt == maxAttempts - 1) return outcome
                    val backoff = (1200L shl attempt) + (0..400).random()
                    delay(backoff)
                }
            }
        }
        return ApiOutcome.Err(lastError ?: ApiError(ApiErrorKind.UNKNOWN, "请求失败", 0, false))
    }

    private suspend fun chatOnce(
        cfg: AppSettings,
        key: String,
        model: String,
        messages: List<ApiMessage>,
        jsonMode: Boolean,
        maxTokens: Int,
        allowBoost: Boolean = true
    ): ApiOutcome<ChatOutput> {
        val started = System.currentTimeMillis()
        val url = chatEndpoint(cfg.baseUrl, cfg.protocol)
        val payload = when (cfg.protocol) {
            ApiProtocol.OPENAI_CHAT -> buildOpenAiChat(model, messages, jsonMode, maxTokens)
            ApiProtocol.OPENAI_RESPONSES -> buildResponses(model, messages, maxTokens)
            ApiProtocol.ANTHROPIC -> buildAnthropic(model, messages, maxTokens)
            ApiProtocol.GEMINI -> buildGemini(model, messages, jsonMode, maxTokens)
        }
        Logx.d("Api", "POST $url model=$model protocol=${cfg.protocol} json=$jsonMode bytes=${payload.length}")
        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("x-opencode-session", sessionId)
            .post(payload.toRequestBody(JSON_MEDIA))
        when (cfg.protocol) {
            ApiProtocol.ANTHROPIC -> {
                builder.header("x-api-key", key)
                builder.header("anthropic-version", "2023-06-01")
            }
            ApiProtocol.GEMINI -> builder.header("x-goog-api-key", key)
            else -> builder.header("Authorization", "Bearer $key")
        }
        val (code, body) = execute(builder.build(), cfg.timeoutSeconds * 1000)
        if (code !in 200..299) {
            return ApiOutcome.Err(HttpErrors.fromHttp(code, body))
        }
        ChatResponseParser.errorMessage(body)?.let { detail ->
            return ApiOutcome.Err(
                ApiError(ApiErrorKind.UNKNOWN, "接口在 HTTP $code 下返回错误：${detail.take(300)}", code, false)
            )
        }
        val parsed = ChatResponseParser.parse(cfg.protocol, body)
        val structure = ChatResponseParser.structure(body)
        if (parsed == null || (parsed.text.isBlank() && parsed.reasoning.isBlank())) {
            if (allowBoost && maxTokens < 4096 && structure.contains("finish_reason=length")) {
                Logx.w("Api", "模型输出为空且被长度截断，提升 max_tokens 重试")
                return chatOnce(cfg, key, model, messages, jsonMode, boostTokens(maxTokens), allowBoost = false)
            }
            return ApiOutcome.Err(
                ApiError(ApiErrorKind.EMPTY, "模型返回为空（HTTP $code，$structure）", code, !structure.contains("finish_reason=length"))
            )
        }
        if (parsed.text.isBlank()) {
            if (allowBoost && maxTokens < 4096) {
                Logx.w("Api", "模型仅返回推理内容（finish=${parsed.finishReason}），提升 max_tokens 重试")
                return chatOnce(cfg, key, model, messages, jsonMode, boostTokens(maxTokens), allowBoost = false)
            }
            return ApiOutcome.Err(
                ApiError(
                    ApiErrorKind.EMPTY,
                    "模型只返回了推理内容，没有最终回答（HTTP $code，finish_reason=${parsed.finishReason.ifBlank { "unknown" }}，$structure）",
                    code,
                    false
                )
            )
        }
        val root = parseObject(body)
        val usage = root?.obj("usage")
        val completion = ChatOutput(
            text = parsed.text.trim(),
            model = root?.str("model").orEmpty().ifBlank { model },
            promptTokens = usage?.int("prompt_tokens") ?: usage?.int("input_tokens") ?: 0,
            completionTokens = usage?.int("completion_tokens") ?: usage?.int("output_tokens") ?: 0,
            latencyMs = System.currentTimeMillis() - started,
            raw = body,
            finishReason = parsed.finishReason,
            reasoningChars = parsed.reasoning.length,
            reasoning = parsed.reasoning
        )
        Logx.d("Api", "响应 model=${completion.model} ${completion.latencyMs}ms tokens=${completion.promptTokens}/${completion.completionTokens} chars=${completion.text.length} finish=${completion.finishReason}")
        return ApiOutcome.Ok(completion)
    }

    // ------------------------------------------------------------ payloads


    /**
     * Streaming variant used by the chat home. Emits accumulated final text and accumulated
     * reasoning after every SSE chunk so the UI can render the complete thinking process live.
     * Non-OpenAI protocols, or gateways that ignore `stream`, degrade to one final delta.
     */
    suspend fun chatStream(
        model: String,
        messages: List<ApiMessage>,
        jsonMode: Boolean = false,
        maxTokens: Int = 4096,
        onDelta: (text: String, reasoning: String) -> Unit
    ): ApiOutcome<ChatOutput> {
        val cfg = settings.state.value
        val key = settings.apiKey()
        if (key.isNullOrBlank()) {
            return ApiOutcome.Err(ApiError(ApiErrorKind.UNAUTHORIZED, "尚未配置 API Key", 0, false))
        }
        if (cfg.protocol != ApiProtocol.OPENAI_CHAT) {
            val outcome = chat(model, messages, jsonMode = jsonMode, maxTokens = maxTokens)
            if (outcome is ApiOutcome.Ok) {
                runCatching { onDelta(outcome.value.text, outcome.value.reasoning) }
            }
            return outcome
        }
        val maxAttempts = cfg.maxRetries.coerceAtLeast(0) + 1
        var last: ApiError? = null
        repeat(maxAttempts) { attempt ->
            var received = false
            val outcome = runCatching {
                streamOnce(cfg, key, model, messages, jsonMode, maxTokens) { text, reasoning ->
                    if (text.isNotBlank() || reasoning.isNotBlank()) received = true
                    runCatching { onDelta(text, reasoning) }
                }
            }.getOrElse { ApiOutcome.Err(HttpErrors.fromThrowable(it)) }
            when (outcome) {
                is ApiOutcome.Ok -> return outcome
                is ApiOutcome.Err -> {
                    last = outcome.error
                    Logx.w("Api", "流式请求失败(${outcome.error.kind}, HTTP ${outcome.error.httpCode}) attempt=$attempt")
                    if (received || !outcome.error.retryable || attempt == maxAttempts - 1) {
                        return outcome
                    }
                    delay((1200L shl attempt) + (0..400).random())
                }
            }
        }
        return ApiOutcome.Err(last ?: ApiError(ApiErrorKind.UNKNOWN, "流式请求失败", 0, false))
    }

    private suspend fun streamOnce(
        cfg: AppSettings,
        key: String,
        model: String,
        messages: List<ApiMessage>,
        jsonMode: Boolean,
        maxTokens: Int,
        onDelta: (String, String) -> Unit
    ): ApiOutcome<ChatOutput> = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val url = chatEndpoint(cfg.baseUrl, ApiProtocol.OPENAI_CHAT)
        val payload = buildOpenAiChat(model, messages, jsonMode, maxTokens, stream = true)
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream, application/json")
            .header("User-Agent", USER_AGENT)
            .header("x-opencode-session", sessionId)
            .header("Authorization", "Bearer $key")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        val timeout = maxOf(120, cfg.timeoutSeconds).toLong()
        val client = baseClient.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        val call = client.newCall(request)
        val handle = coroutineContext[Job]?.invokeOnCompletion { runCatching { call.cancel() } }
        try {
            call.execute().use { resp ->
                if (resp.code !in 200..299) {
                    val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    return@withContext ApiOutcome.Err(HttpErrors.fromHttp(resp.code, body))
                }
                val body = resp.body
                    ?: return@withContext ApiOutcome.Err(
                        ApiError(ApiErrorKind.EMPTY, "流式响应为空", resp.code, false)
                    )
                val text = StringBuilder()
                val reasoning = StringBuilder()
                var finish = ""
                var promptTokens = 0
                var completionTokens = 0
                var streamError: String? = null
                val eventStream = resp.header("Content-Type").orEmpty().contains("event-stream", true)
                if (eventStream) {
                    val source = body.source()
                    while (true) {
                        ensureActive()
                        val line = source.readUtf8Line() ?: break
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("data:")) continue
                        val data = trimmed.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val delta = SseStreamParser.parseData(data) ?: continue
                        if (delta.error != null) {
                            streamError = delta.error
                            break
                        }
                        if (delta.text.isNotEmpty()) text.append(delta.text)
                        if (delta.reasoning.isNotEmpty()) reasoning.append(delta.reasoning)
                        if (delta.finish.isNotBlank()) finish = delta.finish
                        if (delta.promptTokens > 0) promptTokens = delta.promptTokens
                        if (delta.completionTokens > 0) completionTokens = delta.completionTokens
                        if (delta.text.isNotEmpty() || delta.reasoning.isNotEmpty()) {
                            runCatching { onDelta(text.toString(), reasoning.toString()) }
                        }
                    }
                } else {
                    val raw = body.string()
                    val parsed = ChatResponseParser.parse(ApiProtocol.OPENAI_CHAT, raw)
                        ?: ChatResponseParser.parse(cfg.protocol, raw)
                    if (parsed != null) {
                        text.append(parsed.text)
                        reasoning.append(parsed.reasoning)
                        finish = parsed.finishReason
                        runCatching { onDelta(text.toString(), reasoning.toString()) }
                    } else {
                        streamError = ChatResponseParser.errorMessage(raw)
                            ?: "网关返回了无法解析的响应（${raw.length} 字符）"
                    }
                }
                if (streamError != null && text.isEmpty() && reasoning.isEmpty()) {
                    return@withContext ApiOutcome.Err(
                        ApiError(ApiErrorKind.UNKNOWN, "流式响应错误：${streamError.take(300)}", resp.code, false)
                    )
                }
                if (text.isEmpty() && reasoning.isEmpty()) {
                    return@withContext ApiOutcome.Err(
                        ApiError(
                            ApiErrorKind.EMPTY,
                            "模型返回为空（流式，finish_reason=${finish.ifBlank { "unknown" }}）",
                            resp.code,
                            false
                        )
                    )
                }
                ApiOutcome.Ok(
                    ChatOutput(
                        text = text.toString().trim(),
                        model = model,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens,
                        latencyMs = System.currentTimeMillis() - started,
                        raw = "",
                        finishReason = finish,
                        reasoningChars = reasoning.length,
                        reasoning = reasoning.toString()
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiOutcome.Err(HttpErrors.fromThrowable(e))
        } finally {
            handle?.dispose()
        }
    }
    private fun boostTokens(maxTokens: Int): Int = minOf(4096, maxOf(1024, maxTokens * 4))

    private fun buildOpenAiChat(model: String, messages: List<ApiMessage>, jsonMode: Boolean, maxTokens: Int, stream: Boolean = false): String {
        val arr = JSONArray()
        messages.forEach { msg ->
            val content = if (msg.images.isEmpty()) {
                msg.text
            } else {
                JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", msg.text))
                    msg.images.forEach { bytes ->
                        put(
                            JSONObject().put("type", "image_url").put(
                                "image_url",
                                JSONObject().put("url", "data:image/jpeg;base64," + base64(bytes))
                            )
                        )
                    }
                }
            }
            arr.put(JSONObject().put("role", msg.role).put("content", content))
        }
        val root = JSONObject()
            .put("model", model)
            .put("messages", arr)
            .put("temperature", 0.2)
            .put("max_tokens", maxTokens)
            .put("stream", stream)
        if (jsonMode) root.put("response_format", JSONObject().put("type", "json_object"))
        return root.toString()
    }

    private fun buildResponses(model: String, messages: List<ApiMessage>, maxTokens: Int): String {
        val input = JSONArray()
        messages.filter { it.role != "system" }.forEach { msg ->
            val parts = JSONArray().apply {
                put(JSONObject().put("type", "input_text").put("text", msg.text))
                msg.images.forEach { bytes ->
                    put(
                        JSONObject().put("type", "input_image")
                            .put("image_url", "data:image/jpeg;base64," + base64(bytes))
                    )
                }
            }
            input.put(JSONObject().put("role", msg.role).put("content", parts))
        }
        val system = messages.firstOrNull { it.role == "system" }?.text
        val root = JSONObject()
            .put("model", model)
            .put("input", input)
            .put("max_output_tokens", maxTokens)
            .put("stream", false)
        if (!system.isNullOrBlank()) root.put("instructions", system)
        return root.toString()
    }

    private fun buildAnthropic(model: String, messages: List<ApiMessage>, maxTokens: Int): String {
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.text }
        val arr = JSONArray()
        messages.filter { it.role != "system" }.forEach { msg ->
            val content = JSONArray()
            if (msg.text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", msg.text))
            msg.images.forEach { bytes ->
                content.put(
                    JSONObject().put("type", "image").put(
                        "source",
                        JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", base64(bytes))
                    )
                )
            }
            arr.put(JSONObject().put("role", if (msg.role == "assistant") "assistant" else "user").put("content", content))
        }
        val root = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("stream", false)
            .put("temperature", 0.2)
            .put("messages", arr)
        if (system.isNotBlank()) root.put("system", system)
        return root.toString()
    }

    private fun buildGemini(model: String, messages: List<ApiMessage>, jsonMode: Boolean, maxTokens: Int): String {
        val contents = JSONArray()
        messages.filter { it.role != "system" }.forEach { msg ->
            val parts = JSONArray().apply {
                put(JSONObject().put("text", msg.text))
                msg.images.forEach { bytes ->
                    put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject().put("mime_type", "image/jpeg").put("data", base64(bytes))
                        )
                    )
                }
            }
            contents.put(JSONObject().put("role", if (msg.role == "assistant") "model" else "user").put("parts", parts))
        }
        val config = JSONObject().put("temperature", 0.2).put("maxOutputTokens", maxTokens)
        if (jsonMode) config.put("responseMimeType", "application/json")
        val root = JSONObject().put("contents", contents).put("generationConfig", config)
        val system = messages.firstOrNull { it.role == "system" }?.text
        if (!system.isNullOrBlank()) {
            root.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        }
        return root.toString()
    }

    // ------------------------------------------------------------- parsing

    private fun parseModels(body: String): List<ModelInfo> {
        val root = parseObject(body) ?: return emptyList()
        val array = root.optJSONArray("data") ?: root.optJSONArray("models") ?: return emptyList()
        val result = LinkedHashMap<String, ModelInfo>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").ifBlank { item.optString("model") }
            if (id.isBlank()) continue
            val name = item.optString("name").ifBlank { item.optString("display_name").ifBlank { id } }
            val caps = item.optJSONObject("capabilities")
            var vision = caps?.optBoolean("vision", false) ?: false
            val modalities = item.optJSONArray("input_modalities") ?: item.optJSONArray("modalities")
            if (modalities != null) {
                for (m in 0 until modalities.length()) {
                    if (modalities.optString(m).contains("image", ignoreCase = true)) vision = true
                }
            }
            if (!vision) vision = VisionHeuristic.looksVision(id)
            result[id] = ModelInfo(
                id = id,
                name = name,
                vision = vision,
                jsonMode = caps?.optBoolean("json", true) ?: true,
                contextLength = item.optInt("context_length", item.optInt("context_window", 0)),
                ownedBy = item.optString("owned_by")
            )
        }
        return result.values.sortedBy { it.id }
    }

    // -------------------------------------------------------------- http

    private suspend fun execute(request: Request, timeoutMs: Int): Pair<Int, String> {
        val client = baseClient.newBuilder()
            .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = try { it.body?.string().orEmpty() } catch (e: IOException) { "" }
                        if (cont.isActive) cont.resume(it.code to text)
                    }
                }
            })
        }
    }

    private fun base64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val USER_AGENT = "LingTouch/1.0 (Android AI agent)"
    }
}

object VisionHeuristic {
    private val visionRegex = Regex(
        "(vision|gpt-4o|gpt-4\\.1|gpt-5|gpt-6|gemini|claude|qwen.*vl|qwen3\\.8|glm-4v|glm-5|kimi.*vision|mimo.*vl|internvl|llava)",
        RegexOption.IGNORE_CASE
    )

    fun looksVision(modelId: String): Boolean = visionRegex.containsMatchIn(modelId)
}
