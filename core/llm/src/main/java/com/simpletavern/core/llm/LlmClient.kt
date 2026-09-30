package com.simpletavern.core.llm

import com.simpletavern.core.model.ChatRole
import com.simpletavern.core.model.ContextMessage
import com.simpletavern.core.model.LlmProtocol
import com.simpletavern.core.model.StError
import com.simpletavern.core.model.StreamEvent
import com.simpletavern.core.model.UsageTokens
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class ProviderConfig(
    val providerId: String,
    val protocol: LlmProtocol,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double? = null,
    val topP: Double? = null,
    val toolsJson: String? = null,
    val extra: Map<String, String> = emptyMap(),
)

data class LlmRequest(
    val requestId: String,
    val protocol: LlmProtocol,
    val model: String,
    val messages: List<ContextMessage>,
    val temperature: Double? = null,
    val topP: Double? = null,
    val toolsJson: String? = null,
    val extra: Map<String, String> = emptyMap(),
)

class LlmClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    fun stream(provider: ProviderConfig, request: LlmRequest): Flow<StreamEvent> = flow {
        when (provider.protocol) {
            LlmProtocol.OPENAI_COMPATIBLE -> emitAllOpenAiChat(provider, request)
            LlmProtocol.OPENAI_RESPONSES -> emitAllOpenAiResponses(provider, request)
            LlmProtocol.ANTHROPIC_MESSAGES -> emitAllAnthropic(provider, request)
            LlmProtocol.GEMINI_GENERATE_CONTENT -> emitAllGemini(provider, request)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<StreamEvent>.emitAllOpenAiChat(
        provider: ProviderConfig,
        request: LlmRequest,
    ) {
        val body = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            put("messages", messagesOpenAi(request.messages))
            request.temperature?.let { put("temperature", it) }
            request.topP?.let { put("top_p", it) }
            request.toolsJson?.let { put("tools", json.parseToJsonElement(it)) }
        }.toString()
        val url = provider.baseUrl.trimEnd('/') + "/chat/completions"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${provider.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                emit(StreamEvent.Error("provider_http", "HTTP ${resp.code}: ${resp.body?.string()?.take(500)}"))
                return
            }
            val source = resp.body?.source() ?: run {
                emit(StreamEvent.Error("provider", "empty body"))
                return
            }
            var usage: UsageTokens? = null
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                val delta = choice?.get("delta")?.jsonObject
                delta?.get("content")?.jsonPrimitive?.contentOrNull?.let { emit(StreamEvent.Delta(it)) }
                delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull?.let { emit(StreamEvent.Delta(it, reasoning = true)) }
                val toolCalls = delta?.get("tool_calls")?.jsonArray
                toolCalls?.forEach { tc ->
                    val t = tc.jsonObject
                    val id = t["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val fn = t["function"]?.jsonObject
                    val name = fn?.get("name")?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val args = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                    emit(StreamEvent.ToolCall(id, name, args))
                }
                obj["usage"]?.jsonObject?.let { usage = parseUsage(it) }
            }
            usage?.let { emit(StreamEvent.Usage(it)) }
            emit(StreamEvent.Done(ok = true, usage = usage))
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<StreamEvent>.emitAllOpenAiResponses(
        provider: ProviderConfig,
        request: LlmRequest,
    ) {
        // Distinct from Chat Completions — uses /responses and output array events.
        val input = buildJsonArray {
            request.messages.forEach { m ->
                add(
                    buildJsonObject {
                        put("role", m.role.name)
                        put("content", m.content)
                    },
                )
            }
        }
        val body = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            put("input", input)
        }.toString()
        val url = provider.baseUrl.trimEnd('/') + "/responses"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${provider.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                emit(StreamEvent.Error("provider_http", "HTTP ${resp.code}: ${resp.body?.string()?.take(500)}"))
                return
            }
            val source = resp.body?.source() ?: return
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                when (type) {
                    "response.output_text.delta" -> obj["delta"]?.jsonPrimitive?.contentOrNull?.let { emit(StreamEvent.Delta(it)) }
                    "response.reasoning.delta" -> obj["delta"]?.jsonPrimitive?.contentOrNull?.let { emit(StreamEvent.Delta(it, true)) }
                    "response.completed" -> emit(StreamEvent.Done(ok = true))
                    "error" -> emit(StreamEvent.Error("provider", obj.toString()))
                }
            }
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<StreamEvent>.emitAllAnthropic(
        provider: ProviderConfig,
        request: LlmRequest,
    ) {
        val system = request.messages.filter { it.role == ChatRole.system }.joinToString("\n") { it.content }
        val msgs = buildJsonArray {
            request.messages.filter { it.role != ChatRole.system }.forEach { m ->
                add(buildJsonObject {
                    put("role", if (m.role == ChatRole.assistant) "assistant" else "user")
                    put("content", m.content)
                })
            }
        }
        val body = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            put("max_tokens", 4096)
            if (system.isNotBlank()) put("system", system)
            put("messages", msgs)
        }.toString()
        val url = provider.baseUrl.trimEnd('/') + "/v1/messages"
        val req = Request.Builder()
            .url(url)
            .addHeader("x-api-key", provider.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                emit(StreamEvent.Error("provider_http", "HTTP ${resp.code}: ${resp.body?.string()?.take(500)}"))
                return
            }
            val source = resp.body?.source() ?: return
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "content_block_delta" -> {
                        val delta = obj["delta"]?.jsonObject
                        when (delta?.get("type")?.jsonPrimitive?.contentOrNull) {
                            "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull?.let { emit(StreamEvent.Delta(it)) }
                            "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull?.let { emit(StreamEvent.Delta(it, true)) }
                        }
                    }
                    "message_stop" -> emit(StreamEvent.Done(ok = true))
                    "error" -> emit(StreamEvent.Error("provider", obj.toString()))
                }
            }
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<StreamEvent>.emitAllGemini(
        provider: ProviderConfig,
        request: LlmRequest,
    ) {
        val contents = buildJsonArray {
            request.messages.filter { it.role != ChatRole.system }.forEach { m ->
                add(buildJsonObject {
                    put("role", if (m.role == ChatRole.assistant) "model" else "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", m.content) }) })
                })
            }
        }
        val system = request.messages.filter { it.role == ChatRole.system }.joinToString("\n") { it.content }
        val body = buildJsonObject {
            put("contents", contents)
            if (system.isNotBlank()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
                })
            }
        }.toString()
        val url = provider.baseUrl.trimEnd('/') +
            "/v1beta/models/${request.model}:streamGenerateContent?alt=sse&key=${provider.apiKey}"
        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                emit(StreamEvent.Error("provider_http", "HTTP ${resp.code}: ${resp.body?.string()?.take(500)}"))
                return
            }
            val source = resp.body?.source() ?: return
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                val text = obj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("text")?.jsonPrimitive?.contentOrNull
                if (text != null) emit(StreamEvent.Delta(text))
            }
            emit(StreamEvent.Done(ok = true))
        }
    }

    private fun messagesOpenAi(messages: List<ContextMessage>): JsonArray = buildJsonArray {
        messages.forEach { m ->
            add(buildJsonObject {
                put("role", m.role.name)
                put("content", m.content)
                m.toolCallId?.let { put("tool_call_id", it) }
                m.toolCallsJson?.let { put("tool_calls", json.parseToJsonElement(it)) }
            })
        }
    }

    private fun parseUsage(obj: JsonObject): UsageTokens = UsageTokens(
        inputTokens = obj["prompt_tokens"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: obj["input_tokens"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
        outputTokens = obj["completion_tokens"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: obj["output_tokens"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
        totalTokens = obj["total_tokens"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
        cacheReadInputTokens = obj["cache_read_input_tokens"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
        cacheWriteInputTokens = obj["cache_creation_input_tokens"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
    )

    fun unsupportedCapability(name: String): Nothing =
        throw StError.Unsupported("Capability not supported for this protocol: $name")
}
