package com.simpletavern.core.llm

import com.simpletavern.core.model.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource

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
    val reportedCostCurrency: String? = null,
    val httpOptions: ProviderHttpOptions = ProviderHttpOptions(),
    val cachedContext: GeminiCachedContext? = null,
    val cachePlan: GeminiCachePlan? = null,
    val unversionedRoot: Boolean = false,
    val codexBackend: Boolean = false,
    val chatgptSubscription: Boolean = false,
    val streaming: Boolean = true,
    val presetId: String? = null,
    val bedrockBackend: Boolean = false,
    val awsCredentials: AwsBedrockCredentials? = null,
    val generationPath: String? = null,
) {
    val credentialMaterials: List<String> get() {
        val values = (listOf(apiKey) + httpOptions.credentialMaterials + awsCredentials?.credentialMaterials.orEmpty()).filter(String::isNotBlank)
        val encoded = if (apiKey.isNotBlank() && apiKey.length <= 8192 &&
            httpOptions.authStyle?.trim()?.replace('-', '_')?.equals("query_key", ignoreCase = true) == true)
            HttpUrl.Builder().scheme("https").host("redaction.invalid").addQueryParameter("key", apiKey)
                .build().encodedQuery?.removePrefix("key=") else null
        return (values + listOfNotNull(encoded)).distinct()
    }
    fun containsCredential(value: String) = credentialMaterials.any(value::contains)
    fun redact(value: String) = credentialMaterials.sortedByDescending(String::length).fold(value) { text, key -> text.replace(key, "<redacted>") }
    override fun toString() = "ProviderConfig(protocol=$protocol, connection=<redacted>)"
}

data class LlmRequest(
    val requestId: String, val protocol: LlmProtocol, val model: String,
    val messages: List<ContextMessage>,
    val temperature: Double? = null, val topP: Double? = null,
    val toolsJson: String? = null, val extra: Map<String, String> = emptyMap(),
    val nativeWebSearch: Boolean = false,
    val taskId: String? = null,
    val nativeWebFetch: Boolean = nativeWebSearch,
)

class LlmClient(
    http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val prepareGeminiCache: (suspend (ProviderConfig, LlmRequest, JsonObject, GeminiCacheFiles) -> GeminiCacheUse)? = null,
) {
    // Default and injected clients share the no-replay policy; disabling connection retry alone misses HTTP follow-ups.
    // Interceptors issuing their own separate Calls are outside this policy.
    private val http = http.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .apply { if (networkInterceptors().none { it is NoHttpReplayInterceptor }) addNetworkInterceptor(NoHttpReplayInterceptor()) }
        .build()

    fun fileSession(provider: ProviderConfig): LlmFileSession = LlmFileSession(provider, this, ProviderFileClient(provider, http))

    fun stream(provider: ProviderConfig, request: LlmRequest, beforeExecute: suspend () -> Unit = {},
        onFileTransfers: suspend (List<ProviderFileTransfer>) -> Unit = {}): Flow<StreamEvent> = flow {
        val session = fileSession(provider)
        try { session.stream(request, beforeExecute, onFileTransfers).collect { emit(it) } }
        finally { session.close() }
    }.flowOn(Dispatchers.IO)

    internal fun streamWithFiles(provider: ProviderConfig, request: LlmRequest, beforeExecute: suspend () -> Unit,
        onFileTransfers: suspend (List<ProviderFileTransfer>) -> Unit, files: ProviderFileClient): Flow<StreamEvent> = flow {
        require(request.protocol == provider.protocol)
        if (ProviderHttpOptions.normalizeAuth(provider.httpOptions.authStyle, provider.protocol) == "aws_sigv4") {
            if (provider.protocol != LlmProtocol.BEDROCK_CONVERSE &&
                (!provider.bedrockBackend || provider.protocol != LlmProtocol.ANTHROPIC_MESSAGES))
                throw StError.Unsupported("AWS signing requires the Bedrock native adapter")
            if (provider.httpOptions.apiVersion != null)
                throw StError.Unsupported("Bedrock does not use API-version query parameters")
            (provider.awsCredentials ?: throw StError.Validation("AWS credentials are missing")).validate()
        } else if (provider.awsCredentials != null) throw StError.Validation("AWS credentials require SigV4 authentication")
        if (provider.codexBackend && !provider.streaming) throw StError.Unsupported("Codex backend non-streaming is not supported")
        if (request.messages.any { it.attachments.isNotEmpty() && it.attachments.size != it.media.size })
            throw StError.Validation("message attachments must be resolved before sending")
        if ((provider.cachedContext != null || provider.cachePlan != null) && request.protocol != LlmProtocol.GEMINI_GENERATE_CONTENT)
            throw StError.Unsupported("bound cached context requires native Gemini")
        validateCachePlan(provider, request)
        val prepared = files.prepare(request, onFileTransfers)
        when (provider.protocol) {
            LlmProtocol.OPENAI_COMPATIBLE -> openAiChat(provider, prepared, beforeExecute)
            LlmProtocol.OPENAI_RESPONSES -> responses(provider, prepared, beforeExecute)
            LlmProtocol.ANTHROPIC_MESSAGES -> anthropic(provider, prepared, beforeExecute)
            LlmProtocol.GEMINI_GENERATE_CONTENT -> gemini(provider, prepared, beforeExecute, files.cacheFiles())
            LlmProtocol.BEDROCK_CONVERSE -> {
                val calls = mutableListOf<StreamEvent.ToolCall>()
                BedrockClient(http, json).run(provider, prepared, beforeExecute) { event ->
                    when (event) {
                        is StreamEvent.ToolCall -> calls += completedCall(event.callId, event.name, event.argumentsJson)
                        is StreamEvent.Done -> { emitCalls(calls, prepared); emit(event) }
                        else -> emit(event)
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun validateCachePlan(provider: ProviderConfig, request: LlmRequest) {
        val plan = provider.cachePlan ?: return
        if (request.model != provider.model || provider.cachedContext != null || request.protocol != provider.protocol)
            throw StError.Conflict("explicit cache plan differs from the generation connection")
        val owner = plan.owner
        if (owner.providerId != provider.providerId || owner.model != provider.model || owner.baseUrl != provider.baseUrl ||
            owner.authStyle != ProviderHttpOptions.normalizeAuth(provider.httpOptions.authStyle, provider.protocol))
            throw StError.Conflict("explicit cache owner differs from the frozen connection")
        if (plan.selection != null) return
        // Reject invalid new-operation inputs before attachment uploads, without inventing a durable task.
        GeminiCacheTaskMetadata.validateOwner(owner)
        val ids = listOfNotNull(request.requestId, request.taskId)
        if (plan.ttlSeconds !in 1..604800 || ids.any { it.isBlank() || it.length > 1024 || it.any(Char::isISOControl) })
            throw StError.Validation("invalid Gemini cache creation input")
        val publicMetadata = listOf(json.encodeToString(GeminiCacheOwner.serializer(), owner)) + ids.map { JsonPrimitive(it).toString() }
        if (publicMetadata.any(provider::containsCredential))
            throw StError.Validation("cache operation metadata contains credentials")
    }

    private data class ToolBuffer(var id: String = "", var name: String = "", val arguments: StringBuilder = StringBuilder())

    private suspend fun FlowCollector<StreamEvent>.openAiChat(p: ProviderConfig, r: LlmRequest, beforeExecute: suspend () -> Unit) {
        if (r.nativeWebSearch) throw StError.Unsupported("Chat Completions uses independent search")
        val wire = GenerationParameters.wire(r.protocol, r.temperature, r.topP, r.extra)
        val body = buildJsonObject {
            put("model", r.model); put("stream", p.streaming); put("messages", messagesOpenAi(r.messages))
            if (p.streaming) put("stream_options", buildJsonObject { put("include_usage", true) })
            wire.forEach { (key, value) -> put(key, value) }
            ProtocolMessages.definitions(r.toolsJson, p.protocol)?.let { put("tools", it) }
        }
        var finish: String? = null
        var usage: UsageTokens? = null
        val calls = sortedMapOf<Int, ToolBuffer>()
        val generated = GeneratedMediaContent()
        val transcript = StringBuilder()
        val audioMetadata = linkedMapOf<String, JsonElement>()
        var audioIndex: Int? = null
        var sawAudio = false
        var hasText = false
        exchange(p, endpoint(p, "v1", "chat", "completions"), body, beforeExecute) { data ->
            if (data == "[DONE]") return@exchange false
            val obj = objectFrame(data)
            checkError(obj)
            val choice = obj.array("choices")?.firstOrNull() as? JsonObject
            val delta = choice?.obj(if (p.streaming) "delta" else "message")
            delta?.text("content")?.let { if (it.isNotEmpty()) hasText = true; emit(StreamEvent.Delta(it)) }
            delta?.text("refusal")?.let { if (it.isNotEmpty()) hasText = true; emit(StreamEvent.Delta(it)) }
            delta?.text("reasoning_content")?.let { emit(StreamEvent.Delta(it, true)) }
            delta?.obj("audio")?.let { audio ->
                sawAudio = true
                val format = wire.obj("audio")?.text("format") ?: throw StError.Unsupported("undeclared audio output")
                audio.text("transcript")?.let { appendBounded(transcript, it) }
                listOf("id", "expires_at").forEach { key -> audio[key]?.let { audioMetadata[key] = it } }
                audio.text("data")?.takeIf { it.isNotEmpty() }?.let { data ->
                    val mime = when (format) {
                        "wav" -> "audio/wav"; "mp3" -> "audio/mpeg"; "aac" -> "audio/aac"
                        "flac" -> "audio/flac"; "opus" -> "audio/opus"; "pcm16" -> "audio/l16"
                        else -> throw StError.Unsupported("unsupported generated audio format")
                    }
                    audioIndex = generated.appendAudio(data, mime, "chat:audio").first
                }
            }
            // id/name generally occur once; arguments arrive in subsequent indexed fragments.
            delta?.array("tool_calls")?.forEachIndexed { ordinal, element ->
                val tc = element.jsonObject
                val index = tc.long("index")?.toInt() ?: if (!p.streaming) ordinal else throw StError.Provider("tool call index missing")
                if (index < 0 || calls.size >= 64 && index !in calls) throw StError.Provider("too many tool calls")
                val call = calls.getOrPut(index) { ToolBuffer() }
                tc.text("id")?.let {
                    if (call.id.isNotEmpty() && call.id != it) throw StError.Provider("tool call ID changed")
                    call.id = it
                }
                tc.obj("function")?.let { fn ->
                    fn.text("name")?.let { call.name += it }
                    fn.text("arguments")?.let { call.arguments.append(it) }
                }
                if (call.arguments.length > 1_048_576) throw StError.Provider("tool arguments too large")
            }
            choice?.text("finish_reason")?.let { finish = it }
            obj.obj("usage")?.let { usage = parseUsage(it, p, obj, r.extra); emit(StreamEvent.Usage(usage!!)) }
            true
        }
        if (finish !in setOf("stop", "tool_calls")) throw StError.Provider("generation incomplete: ${finish ?: "stream disconnected"}", mapOf("finishReason" to finish.orEmpty()))
        if (finish == "tool_calls" && calls.isEmpty() || finish == "stop" && calls.isNotEmpty()) {
            throw StError.Provider("inconsistent tool completion")
        }
        val completed = calls.values.map { c ->
            if (c.id.isBlank() || c.name.isBlank()) throw StError.Provider("incomplete tool call")
            val args = c.arguments.toString()
            if (runCatching { json.parseToJsonElement(args) as? JsonObject }.getOrNull() == null) {
                throw StError.Provider("tool arguments must be a complete JSON object")
            }
            StreamEvent.ToolCall(c.id, c.name, args)
        }
        if (completed.map { it.callId }.distinct().size != completed.size) throw StError.Provider("duplicate tool call ID")
        if (sawAudio && audioIndex == null) throw StError.Provider("audio response ended without audio data")
        if (!hasText && transcript.isNotEmpty()) emit(StreamEvent.Delta(transcript.toString()))
        val media = generated.finish()
        val turn = audioIndex?.let { index -> ProviderTurn(p.protocol, p.model, buildJsonArray {
            add(buildJsonObject {
                put("type", "audio"); put("audio", JsonObject(audioMetadata + ("transcript" to JsonPrimitive(transcript.toString()))))
                put("__st_media_index", index)
            })
        }.toString()) }
        emitCalls(completed, r)
        emit(StreamEvent.Done(true, usage = usage, providerTurn = turn, media = media))
    }

    private suspend fun FlowCollector<StreamEvent>.responses(p: ProviderConfig, r: LlmRequest, beforeExecute: suspend () -> Unit) {
        val wire = GenerationParameters.wire(r.protocol, r.temperature, r.topP, r.extra)
        val imageGeneration = wire.obj("image_generation")
        if (p.chatgptSubscription && imageGeneration != null) throw StError.Unsupported("ChatGPT subscription preview does not support image generation tools")
        val cacheSystem = wire.obj("prompt_cache_options")?.text("mode") == "explicit"
        if (cacheSystem && r.messages.none { it.role == ChatRole.system && it.content.isNotBlank() })
            throw StError.Validation("explicit system cache requires a nonempty system message")
        val body = buildJsonObject {
            put("model", r.model); put("stream", p.streaming || p.chatgptSubscription)
            put("store", false)
            put("include", buildJsonArray {
                add(JsonPrimitive("reasoning.encrypted_content"))
                if (r.nativeWebSearch) add(JsonPrimitive("web_search_call.action.sources"))
            })
            if (p.codexBackend || p.chatgptSubscription) put("instructions", if (cacheSystem) "" else
                r.messages.filter { it.role == ChatRole.system }.joinToString("\n\n") { it.content })
            val input = if ((p.codexBackend && !cacheSystem) || p.chatgptSubscription) r.messages.filter { it.role != ChatRole.system } else r.messages
            put("input", ProtocolMessages.responses(input, p, cacheSystem))
            val definitions = ProtocolMessages.definitions(r.toolsJson, p.protocol).orEmpty()
            if (definitions.isNotEmpty() || r.nativeWebSearch || r.nativeWebFetch || imageGeneration != null) put("tools", buildJsonArray {
                if (p.chatgptSubscription && definitions.isNotEmpty()) add(buildJsonObject {
                    put("type", "namespace"); put("name", "simpletavern"); put("description", "SimpleTavern client tools")
                    put("tools", JsonArray(definitions))
                }) else definitions.forEach { add(it) }
                if (r.nativeWebSearch || r.nativeWebFetch) add(buildJsonObject { put("type", "web_search") })
                imageGeneration?.let { add(JsonObject(it + ("type" to JsonPrimitive("image_generation")))) }
            })
            wire.filterKeys { it != "image_generation" && (!p.chatgptSubscription || it !in CHATGPT_UNSUPPORTED) }.forEach { (key, value) -> put(key, value) }
        }
        var completed = false
        var usage: UsageTokens? = null
        val items = sortedMapOf<Int, JsonObject>()
        val partialCalls = mutableMapOf<Int, ToolBuffer>()
        var output = JsonArray(emptyList())
        exchange(if (p.chatgptSubscription) p.copy(streaming = true) else p, endpoint(p, "v1", "responses"), body, beforeExecute) { data ->
            if (data == "[DONE]") return@exchange false
            val obj = objectFrame(data)
            when (obj.text("type")) {
                "response.output_text.delta" -> obj.text("delta")?.let { emit(StreamEvent.Delta(it)) }
                "response.refusal.delta" -> obj.text("delta")?.let { emit(StreamEvent.Delta(it)) }
                "response.reasoning_text.delta", "response.reasoning_summary_text.delta", "response.reasoning.delta" ->
                    obj.text("delta")?.let { emit(StreamEvent.Delta(it, true)) }
                "response.output_item.added" -> {
                    val index = outputIndex(obj)
                    val item = obj.obj("item") ?: throw StError.Provider("Responses output item missing")
                    if (items.put(index, item) != null) throw StError.Provider("duplicate Responses output item")
                    if (item.text("type") == "function_call") partialCalls[index] = ToolBuffer(
                        item.text("call_id").orEmpty(), item.text("name").orEmpty(), StringBuilder(item.text("arguments").orEmpty()))
                }
                "response.function_call_arguments.delta" -> {
                    val call = partialCalls[outputIndex(obj)] ?: throw StError.Provider("orphan Responses arguments")
                    call.arguments.append(obj.text("delta") ?: throw StError.Provider("missing Responses argument delta"))
                    if (call.arguments.length > 1_048_576) throw StError.Provider("tool arguments too large")
                }
                "response.output_item.done" -> {
                    val index = outputIndex(obj)
                    val item = obj.obj("item") ?: throw StError.Provider("Responses completed item missing")
                    val previous = items[index] ?: throw StError.Provider("orphan Responses completed item")
                    if (item.text("id") != previous.text("id") || item.text("type") != previous.text("type")) throw StError.Provider("Responses item identity changed")
                    partialCalls[index]?.let { call ->
                        if (item.text("call_id") != call.id || item.text("name") != call.name ||
                            item.text("arguments") != call.arguments.toString()) throw StError.Provider("Responses function fragments changed")
                    }
                    items[index] = item
                }
                "response.completed" -> {
                    val response = obj.obj("response")
                    if (response?.text("status") != "completed") throw StError.Provider("response did not complete",
                        mapOf("finishReason" to response?.obj("incomplete_details")?.text("reason").orEmpty()))
                    output = response.array("output") ?: JsonArray(items.values.toList())
                    response.obj("usage")?.let {
                        val parsed = parseUsage(it, p, response, r.extra)
                        usage = if (output.any { item -> (item as? JsonObject)?.text("type") in setOf("web_search_call", "image_generation_call") })
                            parsed.copy(pricingWarnings = (parsed.pricingWarnings + "additional_tool_billing").distinct()) else parsed
                        emit(StreamEvent.Usage(usage!!))
                    }
                    if (output.size > 256) throw StError.Provider("Responses output exceeds budget")
                    if (items.isNotEmpty() && (items.size != output.size || items.values.zip(output).any { (saved, item) -> saved != item })) {
                        throw StError.Provider("Responses final output differs from streamed items")
                    }
                    completed = true
                    return@exchange false
                }
                "response.failed", "response.incomplete", "error" -> throw StError.Provider("response failed or incomplete",
                    mapOf("finishReason" to obj.obj("response")?.obj("incomplete_details")?.text("reason").orEmpty()))
            }
            checkError(obj)
            true
        }
        if (!completed) throw StError.Provider("response stream disconnected")
        val generated = GeneratedMediaContent()
        if (output.any { it.jsonObject.text("type") == "image_generation_call" } && imageGeneration == null)
            throw StError.Unsupported("undeclared native image generation")
        output = generated.responses(output, imageGeneration?.text("output_format") ?: "png")
        if (output.toString().length > 4_194_304) throw StError.Provider("Responses output exceeds text budget")
        val calls = output.mapNotNull { element ->
            val item = element as? JsonObject ?: throw StError.Provider("invalid Responses output item")
            when (item.text("type")) {
                "function_call" -> completedCall(item.text("call_id"), item.text("name"), item.text("arguments"))
                "message", "reasoning", "image_generation_call" -> null
                "web_search_call" -> {
                    if (!r.nativeWebSearch && !r.nativeWebFetch) throw StError.Unsupported("undeclared native web search")
                    if (item.text("status") != "completed") throw StError.Provider("native web search did not complete")
                    null
                }
                else -> throw StError.Unsupported("unsupported Responses output type")
            }
        }
        emitCalls(calls, r)
        emit(StreamEvent.Done(true, usage = usage, providerTurn = ProviderTurn(p.protocol, p.model, output.toString()),
            search = NativeSearchMetadata.responses(output, p.credentialMaterials), media = generated.media))
    }

    private suspend fun FlowCollector<StreamEvent>.anthropic(p: ProviderConfig, r: LlmRequest, beforeExecute: suspend () -> Unit) {
        if (p.bedrockBackend && r.nativeWebSearch) throw StError.Unsupported("Bedrock native server search is not configured; use independent search")
        val wire = GenerationParameters.wire(r.protocol, r.temperature, r.topP, r.extra)
        val control = r.extra["cache_control"]?.let {
            val value = json.parseToJsonElement(it).jsonObject
            if (p.bedrockBackend && value["ttl"] == JsonPrimitive("5m")) JsonObject(value - "ttl") else value
        }
        val breakpoints = r.extra["cache_breakpoints"]?.let { json.parseToJsonElement(it).jsonArray.map { v -> v.jsonPrimitive.content }.toSet() }.orEmpty()
        val system = r.messages.filter { it.role == ChatRole.system }.joinToString("\n") { it.content }
        if ("system" in breakpoints && system.isBlank()) throw StError.Validation("system cache requires a nonempty system message")
        val body = buildJsonObject {
            put("model", r.model); put("stream", p.streaming); put("max_tokens", maxTokens(r) ?: 4096)
            if (system.isNotBlank()) {
                if ("system" in breakpoints) put("system", buildJsonArray { add(buildJsonObject {
                    put("type", "text"); put("text", system); put("cache_control", control!!)
                }) }) else put("system", system)
            }
            put("messages", ProtocolMessages.anthropic(r.messages, p))
            val definitions = ProtocolMessages.definitions(r.toolsJson, p.protocol).orEmpty()
            if ("tools" in breakpoints && definitions.isEmpty() && !r.nativeWebSearch) throw StError.Validation("tools cache requires tool definitions")
            if (definitions.isNotEmpty() || r.nativeWebSearch || r.nativeWebFetch) {
                val tools = buildJsonArray {
                definitions.forEach { add(it) }
                if (r.nativeWebSearch) add(buildJsonObject { put("type", "web_search_20250305"); put("name", "web_search"); put("max_uses", 5) })
                if (r.nativeWebFetch) add(buildJsonObject { put("type", "web_fetch_20250910"); put("name", "web_fetch"); put("max_uses", 5); put("max_content_tokens", 12000); put("citations", buildJsonObject { put("enabled", true) }) })
                }
                put("tools", if ("tools" in breakpoints) JsonArray(tools.mapIndexed { index, value ->
                    if (index == tools.lastIndex) JsonObject(value.jsonObject + ("cache_control" to control!!)) else value
                }) else tools)
            }
            wire.forEach { (key, value) -> put(key, if (p.bedrockBackend && key == "cache_control") control!! else value) }
        }
        // Preserved opaque history is not rewritten. Its existing markers also consume vendor slots.
        if (cacheMarkerCount(body) > 4) throw StError.Validation("Claude request exceeds four cache breakpoints")
        var stopped = false
        var finish: String? = null
        var usage: UsageTokens? = null
        val blocks = sortedMapOf<Int, JsonObject>()
        val texts = mutableMapOf<Int, StringBuilder>()
        val signatures = mutableMapOf<Int, StringBuilder>()
        val inputs = mutableMapOf<Int, StringBuilder>()
        val citations = mutableMapOf<Int, MutableList<JsonElement>>()
        var serverSearch = false
        var searchFailed = false
        val closed = mutableSetOf<Int>()
        val payload = if (p.bedrockBackend) JsonObject((body - setOf("model", "stream")) +
            ("anthropic_version" to JsonPrimitive("bedrock-2023-05-31"))) else body
        val url = if (p.bedrockBackend) BedrockClient.endpoint(p, r.model,
            if (p.streaming) "invoke-with-response-stream" else "invoke") else endpoint(p, "v1", "messages")
        exchange(p, url, payload, beforeExecute) { data ->
            val obj = objectFrame(data)
            when (obj.text("type")) {
                "message_start" -> obj.obj("message")?.let { message -> message.obj("usage")?.let {
                    usage = parseUsage(it, p, message, r.extra); emit(StreamEvent.Usage(usage!!))
                } }
                "content_block_start" -> {
                    val index = blockIndex(obj)
                    val block = obj.obj("content_block") ?: throw StError.Provider("Anthropic block missing")
                    if (blocks.put(index, block) != null) throw StError.Provider("duplicate Anthropic block")
                    when (block.text("type")) {
                        "text" -> {
                            val initial = block.text("text").orEmpty(); texts[index] = StringBuilder(initial)
                            citations[index] = block.array("citations")?.toMutableList() ?: mutableListOf()
                            if (initial.isNotEmpty()) emit(StreamEvent.Delta(initial))
                        }
                        "thinking" -> {
                            val initial = block.text("thinking").orEmpty(); texts[index] = StringBuilder(initial)
                            signatures[index] = StringBuilder(block.text("signature").orEmpty())
                            if (initial.isNotEmpty()) emit(StreamEvent.Delta(initial, true))
                        }
                        "tool_use" -> inputs[index] = StringBuilder()
                        "server_tool_use" -> {
                            if (!((r.nativeWebSearch && block.text("name") == "web_search") || (r.nativeWebFetch && block.text("name") == "web_fetch"))) throw StError.Unsupported("undeclared Anthropic server tool")
                            serverSearch = true
                            inputs[index] = StringBuilder()
                        }
                        "web_search_tool_result", "web_fetch_tool_result" -> {
                            val fetch = block.text("type") == "web_fetch_tool_result"
                            if (if (fetch) !r.nativeWebFetch else !r.nativeWebSearch) throw StError.Unsupported("undeclared Anthropic search result")
                            serverSearch = true
                            if (block.obj("content")?.text("type") in setOf("web_search_tool_result_error", "web_fetch_tool_result_error")) searchFailed = true
                            else if (if (fetch) block.obj("content") == null else block.array("content") == null) throw StError.Provider("invalid Anthropic search result")
                        }
                        "redacted_thinking" -> Unit
                        else -> throw StError.Unsupported("unsupported Anthropic content block")
                    }
                }
                "content_block_delta" -> obj.obj("delta")?.let { d ->
                    val index = blockIndex(obj)
                    val block = blocks[index] ?: throw StError.Provider("orphan Anthropic delta")
                    if (index in closed) throw StError.Provider("Anthropic delta after block completion")
                    when (d.text("type")) {
                        "text_delta", "thinking_delta" -> {
                            val thinking = d.text("type") == "thinking_delta"
                            if (block.text("type") != if (thinking) "thinking" else "text") throw StError.Provider("Anthropic block type changed")
                            val delta = d.text(if (thinking) "thinking" else "text") ?: throw StError.Provider("Anthropic text delta missing")
                            appendBounded(texts[index]!!, delta); emit(StreamEvent.Delta(delta, thinking))
                        }
                        "input_json_delta" -> appendBounded(inputs[index] ?: throw StError.Provider("invalid Anthropic tool delta"),
                            d.text("partial_json") ?: throw StError.Provider("Anthropic tool delta missing"))
                        "signature_delta" -> appendBounded(signatures[index] ?: throw StError.Provider("invalid Anthropic signature delta"),
                            d.text("signature") ?: throw StError.Provider("Anthropic signature missing"))
                        "citations_delta" -> {
                            val list = citations[index] ?: throw StError.Provider("orphan Anthropic citation")
                            if (list.size >= 256) throw StError.Provider("too many Anthropic citations")
                            list.add(d.obj("citation") ?: throw StError.Provider("Anthropic citation missing"))
                        }
                        else -> throw StError.Unsupported("unsupported Anthropic delta")
                    }
                }
                "content_block_stop" -> {
                    val index = blockIndex(obj)
                    val block = blocks[index] ?: throw StError.Provider("orphan Anthropic block stop")
                    if (!closed.add(index)) throw StError.Provider("duplicate Anthropic block stop")
                    blocks[index] = buildJsonObject {
                        block.forEach { (key, value) -> put(key, value) }
                        when (block.text("type")) {
                            "text" -> {
                                put("text", texts[index].toString())
                                if (citations[index].orEmpty().isNotEmpty()) put("citations", JsonArray(citations[index]!!))
                            }
                            "thinking" -> {
                                val signature = signatures[index].toString()
                                if (signature.isBlank()) throw StError.Provider("Anthropic thinking signature missing")
                                put("thinking", texts[index].toString()); put("signature", signature)
                            }
                            "tool_use", "server_tool_use" -> put("input", if (inputs[index]!!.isEmpty()) block["input"] ?: JsonObject(emptyMap())
                                else argumentObject(inputs[index].toString()))
                        }
                    }
                }
                "message_delta" -> {
                    obj.obj("delta")?.text("stop_reason")?.let { finish = it }
                    obj.obj("usage")?.let { u ->
                        val parsed = parseUsage(u, p, obj, r.extra)
                        usage = parsed.copy(inputTokens = parsed.inputTokens ?: usage?.inputTokens,
                            cacheReadInputTokens = parsed.cacheReadInputTokens ?: usage?.cacheReadInputTokens,
                            cacheWriteInputTokens = parsed.cacheWriteInputTokens ?: usage?.cacheWriteInputTokens,
                            cacheWriteShortTokens = parsed.cacheWriteShortTokens ?: usage?.cacheWriteShortTokens,
                            cacheWriteLongTokens = parsed.cacheWriteLongTokens ?: usage?.cacheWriteLongTokens,
                            providerCost = parsed.providerCost ?: usage?.providerCost,
                            resolvedModel = parsed.resolvedModel ?: usage?.resolvedModel,
                            serviceTier = parsed.serviceTier ?: usage?.serviceTier,
                            pricingWarnings = (parsed.pricingWarnings + usage?.pricingWarnings.orEmpty() +
                                (if (parsed.serviceTier != null && usage?.serviceTier != null && parsed.serviceTier != usage?.serviceTier)
                                    listOf("inconsistent_service_tier") else emptyList()) +
                                (if (serverSearch) listOf("additional_tool_billing") else emptyList())).distinct())
                        emit(StreamEvent.Usage(usage!!))
                    }
                }
                "message_stop" -> { stopped = true; return@exchange false }
                "error" -> throw StError.Provider("Anthropic stream error")
            }
            if (serverSearch && usage != null && "additional_tool_billing" !in usage!!.pricingWarnings) {
                usage = usage!!.copy(pricingWarnings = usage!!.pricingWarnings + "additional_tool_billing")
                emit(StreamEvent.Usage(usage!!))
            }
            true
        }
        if (!stopped || finish !in (setOf("end_turn", "stop_sequence", "tool_use") + if (r.nativeWebSearch || r.nativeWebFetch) setOf("pause_turn") else emptySet()) || closed.size != blocks.size) {
            throw StError.Provider("generation incomplete: ${finish ?: "stream disconnected"}", mapOf("finishReason" to finish.orEmpty()))
        }
        val output = JsonArray(blocks.values.toList())
        if (output.toString().length > 4_194_304) throw StError.Provider("Anthropic output exceeds budget")
        if (searchFailed) throw StError.Provider("Anthropic native web search failed")
        val calls = blocks.values.filter { it.text("type") == "tool_use" }.map {
            completedCall(it.text("id"), it.text("name"), it["input"]?.toString())
        }
        if ((finish == "tool_use") != calls.isNotEmpty()) throw StError.Provider("inconsistent Anthropic tool completion")
        emitCalls(calls, r)
        emit(StreamEvent.Done(true, usage = usage, providerTurn = ProviderTurn(p.protocol, p.model, output.toString()),
            search = NativeSearchMetadata.anthropic(output, p.credentialMaterials), continuation = finish == "pause_turn"))
    }

    private suspend fun FlowCollector<StreamEvent>.gemini(p: ProviderConfig, r: LlmRequest, beforeExecute: suspend () -> Unit,
        files: GeminiCacheFiles) {
        val system = r.messages.filter { it.role == ChatRole.system }.joinToString("\n") { it.content }
        val body = buildJsonObject {
            put("contents", ProtocolMessages.gemini(r.messages, p))
            val definitions = ProtocolMessages.definitions(r.toolsJson, p.protocol)
            if (!definitions.isNullOrEmpty() || r.nativeWebSearch) put("tools", buildJsonArray {
                if (!definitions.isNullOrEmpty()) add(buildJsonObject { put("functionDeclarations", definitions) })
                if (r.nativeWebSearch) add(buildJsonObject { put("google_search", JsonObject(emptyMap())) })
            })
            if (system.isNotBlank()) put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
            })
            put("generationConfig", buildJsonObject {
                GenerationParameters.wire(r.protocol, r.temperature, r.topP, r.extra).forEach { (key, value) -> put(key, value) }
            })
        }
        val model = r.model.removePrefix("models/")
        if (model.isBlank() || '/' in model) throw StError.Validation("invalid Gemini model name")
        if (p.cachePlan != null && (body["contents"] as? JsonArray).isNullOrEmpty())
            throw StError.Validation("explicit cache generation requires new content")
        if (r.model != p.model && p.cachedContext != null) throw StError.Conflict("cached context model differs from request")
        val url = endpoint(p, "v1beta", "models", "$model:${if (p.streaming) "streamGenerateContent" else "generateContent"}")
            .newBuilder().apply { if (p.streaming) addQueryParameter("alt", "sse") }.build()
        val use = if (p.cachePlan != null) {
            if (p.cachedContext != null) throw StError.Validation("explicit cache plan conflicts with an existing binding")
            val prepare = prepareGeminiCache ?: throw StError.Unsupported("explicit cache task manager is not installed")
            prepare(p, r, body, files)
        } else null
        try {
            val active = use?.provider ?: p
            val payload = active.cachedContext?.apply(active, body, files) ?: body
            geminiPayload(active, r, payload, url, beforeExecute)
        } catch (error: StError.Provider) {
            if (error.details["httpStatus"] == "404") use?.missing()
            throw error // No generation replay: the next explicit round can bind a fresh cache.
        } finally { use?.close() }
    }

    private suspend fun FlowCollector<StreamEvent>.geminiPayload(p: ProviderConfig, r: LlmRequest, payload: JsonObject,
        url: HttpUrl, beforeExecute: suspend () -> Unit) {
        var finish: String? = null
        var usage: UsageTokens? = null
        val parts = mutableListOf<JsonObject>()
        val generated = GeneratedMediaContent(payload.obj("generationConfig")?.obj("responseFormat")?.obj("audio")?.long("sampleRate")?.toInt() ?: 24000, p.streaming)
        val grounding = mutableListOf<JsonObject>()
        var groundingBytes = 0
        exchange(p, url, payload, beforeExecute) { data ->
            val obj = objectFrame(data)
            checkError(obj)
            obj.obj("promptFeedback")?.text("blockReason")?.let { throw StError.Provider("prompt blocked: $it") }
            val candidate = obj.array("candidates")?.firstOrNull() as? JsonObject
            candidate?.obj("groundingMetadata")?.let { metadata ->
                if ((!metadata.array("webSearchQueries").isNullOrEmpty() || !metadata.array("groundingChunks").isNullOrEmpty()) &&
                    usage != null && "additional_tool_billing" !in usage!!.pricingWarnings) {
                    usage = usage!!.copy(pricingWarnings = usage!!.pricingWarnings + "additional_tool_billing")
                    emit(StreamEvent.Usage(usage!!))
                }
                if (grounding.lastOrNull() != metadata) {
                    groundingBytes += metadata.toString().toByteArray(Charsets.UTF_8).size
                    if (grounding.size >= 256 || groundingBytes > 131_072) throw StError.Provider("Gemini search metadata exceeds budget")
                    grounding += metadata
                }
            }
            candidate?.obj("content")?.array("parts")?.forEach { part ->
                val value = part.jsonObject
                if (parts.size >= 4096) throw StError.Provider("too many Gemini response parts")
                parts += generated.gemini(value)
                value.text("text")?.let { emit(StreamEvent.Delta(it, value["thought"]?.jsonPrimitive?.booleanOrNull == true)) }
            }
            candidate?.text("finishReason")?.let { finish = it }
            obj.obj("usageMetadata")?.let {
                val parsed = parseUsage(it, p, obj, r.extra)
                usage = if (grounding.any { g -> !g.array("webSearchQueries").isNullOrEmpty() || !g.array("groundingChunks").isNullOrEmpty() })
                    parsed.copy(pricingWarnings = (parsed.pricingWarnings + "additional_tool_billing").distinct()) else parsed
                emit(StreamEvent.Usage(usage!!))
            }
            true
        }
        if (finish != "STOP") throw StError.Provider("generation incomplete: ${finish ?: "stream disconnected"}", mapOf("finishReason" to finish.orEmpty()))
        if (grounding.any { g -> !g.array("webSearchQueries").isNullOrEmpty() || !g.array("groundingChunks").isNullOrEmpty() } &&
            usage != null && "additional_tool_billing" !in usage!!.pricingWarnings) {
            usage = usage!!.copy(pricingWarnings = usage!!.pricingWarnings + "additional_tool_billing")
            emit(StreamEvent.Usage(usage!!))
        }
        val output = JsonArray(parts)
        if (output.toString().length > 4_194_304) throw StError.Provider("Gemini output exceeds budget")
        val calls = parts.mapNotNull { it.obj("functionCall") }.mapIndexed { index, fn ->
            if (fn["partialArgs"] != null || fn["willContinue"] == JsonPrimitive(true)) throw StError.Unsupported("partial Gemini function arguments")
            completedCall(fn.text("id") ?: "${r.requestId}:gemini:$index", fn.text("name"), (fn["args"] ?: JsonObject(emptyMap())).toString())
        }
        emitCalls(calls, r)
        emit(StreamEvent.Done(true, usage = usage, providerTurn = ProviderTurn(p.protocol, p.model, output.toString()),
            search = NativeSearchMetadata.gemini(grounding, p.credentialMaterials), media = generated.finish()))
    }

    private fun argumentObject(raw: String): JsonObject {
        if (raw.length > 1_048_576) throw StError.Provider("tool arguments too large")
        return runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: throw StError.Provider("tool arguments must be a complete JSON object")
    }
    private fun completedCall(callId: String?, name: String?, arguments: String?): StreamEvent.ToolCall {
        if (callId.isNullOrBlank() || name.isNullOrBlank() || arguments == null) throw StError.Provider("incomplete tool call")
        return StreamEvent.ToolCall(callId, name, argumentObject(arguments).toString())
    }
    private suspend fun FlowCollector<StreamEvent>.emitCalls(calls: List<StreamEvent.ToolCall>, request: LlmRequest) {
        if (calls.size > 64 || calls.map { it.callId }.distinct().size != calls.size) throw StError.Provider("too many or duplicate tool calls")
        val allowed = ProtocolMessages.definitions(request.toolsJson, LlmProtocol.OPENAI_COMPATIBLE)?.map {
            it.jsonObject.obj("function")!!.text("name")!!
        }?.toSet().orEmpty()
        if (calls.any { it.name !in allowed }) throw StError.Provider("provider requested an undeclared function")
        calls.forEach { emit(it) }
    }
    private fun appendBounded(buffer: StringBuilder, delta: String) {
        if (buffer.length + delta.length > 1_048_576) throw StError.Provider("provider block exceeds budget")
        buffer.append(delta)
    }
    private fun outputIndex(obj: JsonObject) = checkedIndex(obj, "output_index", 256)
    private fun blockIndex(obj: JsonObject) = checkedIndex(obj, "index", 256)
    private fun checkedIndex(obj: JsonObject, key: String, limit: Int): Int = obj.long(key)?.takeIf { it in 0 until limit }?.toInt()
        ?: throw StError.Provider("provider block index missing or out of bounds")

    /** The cancellation watcher stays active throughout blocking header/body reads. */
    private suspend fun exchange(p: ProviderConfig, url: HttpUrl, body: JsonObject, beforeExecute: suspend () -> Unit,
        frame: suspend (String) -> Boolean) = coroutineScope {
        val encodedBody = body.toString()
        val builder = Request.Builder().url(p.httpOptions.requestUrl(url, p.protocol, p.apiKey))
            .post(encodedBody.toRequestBody("application/json".toMediaType()))
            .header("Accept", if (p.streaming && p.bedrockBackend) "application/vnd.amazon.eventstream"
                else if (p.streaming) "text/event-stream" else "application/json")
        val headers = p.httpOptions.requestHeaders(p.protocol, p.apiKey).toMutableMap()
        if (p.protocol == LlmProtocol.ANTHROPIC_MESSAGES && body.text("speed") == "fast") {
            val betas = headers["anthropic-beta"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
            headers["anthropic-beta"] = (betas + "fast-mode-2026-02-01").distinct().joinToString(",")
            ProviderHttpOptions.validateHeader("anthropic-beta", headers.getValue("anthropic-beta"))
        }
        if (headers.entries.sumOf { it.key.length.toLong() + it.value.length + 4 } > 60 * 1024)
            throw StError.Validation("provider request headers exceed byte budget")
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (p.bedrockBackend && p.streaming) builder.header("X-Amzn-Bedrock-Accept", "application/json")
        val prepared = builder.build()
        val signed = if (p.awsCredentials != null) AwsSigV4.bedrock(prepared, encodedBody.toByteArray(Charsets.UTF_8), p.awsCredentials) else prepared
        val call = http.newCall(signed)
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            currentCoroutineContext().ensureActive()
            beforeExecute() // request/URL/header construction succeeded; durability failure must prevent HTTP.
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                currentCoroutineContext().ensureActive()
                // Do not echo arbitrary upstream bodies: they can contain credentials or request data.
                if (!response.isSuccessful) throw StError.Provider("provider HTTP ${response.code}", mapOf("httpStatus" to response.code.toString()))
                val source = response.body?.source() ?: throw StError.Provider("empty response body")
                if (p.streaming && p.bedrockBackend) AwsEventStream.read(source) { event ->
                    if (event.headers[":event-type"] != "chunk") throw StError.Unsupported("unsupported Bedrock InvokeModel stream event")
                    val bytes = objectFrame(event.payload.toString(Charsets.UTF_8)).text("bytes")
                        ?: throw StError.Provider("Bedrock stream chunk missing")
                    val decoded = try { java.util.Base64.getDecoder().decode(bytes).toString(Charsets.UTF_8) }
                        catch (_: IllegalArgumentException) { throw StError.Provider("invalid Bedrock stream chunk") }
                    frame(decoded)
                } else if (p.streaming) readSse(source, frame) else {
                    if (source.request(16_777_217)) throw StError.Provider("provider response exceeds budget")
                    currentCoroutineContext().ensureActive()
                    nonStreamingFrames(p.protocol, objectFrame(source.readUtf8()), frame)
                }
            }
        } finally { watcher.cancel() }
    }

    /** Normalize one complete JSON response into the existing parser events, not network SSE. */
    private suspend fun nonStreamingFrames(protocol: LlmProtocol, response: JsonObject, frame: suspend (String) -> Boolean) {
        checkError(response)
        suspend fun event(type: String, fields: Map<String, JsonElement> = emptyMap()) =
            frame(JsonObject(fields + ("type" to JsonPrimitive(type))).toString())
        when (protocol) {
            LlmProtocol.BEDROCK_CONVERSE -> throw StError.Unsupported("Converse uses its native response parser")
            LlmProtocol.OPENAI_COMPATIBLE, LlmProtocol.GEMINI_GENERATE_CONTENT -> frame(response.toString())
            LlmProtocol.OPENAI_RESPONSES -> {
                response.array("output")?.forEach { element ->
                    val item = element.jsonObject
                    when (item.text("type")) {
                        "message" -> item.array("content")?.forEach { content ->
                            val part = content.jsonObject
                            val text = part.text("text") ?: part.text("refusal")
                            if (text != null) event(if (part.text("type") == "refusal") "response.refusal.delta" else "response.output_text.delta",
                                mapOf("delta" to JsonPrimitive(text)))
                        }
                        "reasoning" -> (item.array("summary").orEmpty() + item.array("content").orEmpty()).forEach { part ->
                            part.jsonObject.text("text")?.let { event("response.reasoning_text.delta", mapOf("delta" to JsonPrimitive(it))) }
                        }
                    }
                }
                event("response.completed", mapOf("response" to response))
            }
            LlmProtocol.ANTHROPIC_MESSAGES -> {
                event("message_start", mapOf("message" to response))
                val blocks = response.array("content") ?: throw StError.Provider("Anthropic response content missing")
                blocks.forEachIndexed { index, block ->
                    val fields = mapOf("index" to JsonPrimitive(index))
                    event("content_block_start", fields + ("content_block" to block))
                    event("content_block_stop", fields)
                }
                event("message_delta", mapOf("delta" to buildJsonObject { put("stop_reason", response["stop_reason"] ?: JsonNull) },
                    "usage" to (response["usage"] ?: JsonObject(emptyMap()))))
                event("message_stop")
            }
        }
    }

    private suspend fun readSse(source: BufferedSource, frame: suspend (String) -> Boolean) {
        val data = StringBuilder()
        var receivedChars = 0L
        suspend fun dispatch(): Boolean {
            if (data.isEmpty()) return true
            val value = data.toString().removeSuffix("\n")
            data.setLength(0)
            return frame(value)
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            // Bound both single lines and assembled SSE events.
            val line = try { source.readUtf8LineStrict(16_777_216) } catch (e: java.io.EOFException) {
                if (source.exhausted()) { dispatch(); return }
                throw StError.Provider("malformed or oversized SSE line")
            }
            when {
                line.isEmpty() -> if (!dispatch()) return
                line.startsWith("data:") -> {
                    receivedChars += line.length
                    if (receivedChars > 67_108_864) throw StError.Provider("provider stream exceeds budget")
                    data.append(line.substring(5).removePrefix(" ")).append('\n')
                    if (data.length > 16_777_216) throw StError.Provider("SSE event too large")
                }
            }
        }
    }

    private fun endpoint(p: ProviderConfig, defaultVersion: String, vararg parts: String): HttpUrl {
        return ProviderEndpoints.operation(p, defaultVersion, *parts)
    }

    private fun objectFrame(data: String): JsonObject = try { json.parseToJsonElement(data).jsonObject }
        catch (e: Exception) { throw StError.Provider("invalid provider stream JSON") }
    private fun checkError(obj: JsonObject) { if (obj["error"] != null && obj["error"] != JsonNull) throw StError.Provider("provider stream error") }
    private fun cacheMarkerCount(body: JsonObject): Int {
        fun blocks(value: JsonElement?): Int = (value as? JsonArray)?.count { it is JsonObject && it["cache_control"] != null }.orZero()
        return (if (body["cache_control"] != null) 1 else 0) + blocks(body["system"]) + blocks(body["tools"]) +
            body.array("messages").orEmpty().sumOf { blocks((it as? JsonObject)?.get("content")) }
    }
    private fun Int?.orZero() = this ?: 0
    private fun maxTokens(r: LlmRequest): Int? = r.extra["max_tokens"]?.let {
        it.toIntOrNull()?.takeIf { n -> n > 0 } ?: throw StError.Validation("max_tokens must be a positive integer")
    }
    private fun messagesOpenAi(messages: List<ContextMessage>): JsonArray = buildJsonArray {
        val supplements = mutableListOf<ContextMessage>()
        messages.forEachIndexed { index, original ->
            val m = if (original.role == ChatRole.tool) MediaContent.tool(original, LlmProtocol.OPENAI_COMPATIBLE) else original
            add(buildJsonObject {
            put("role", if (m.role == ChatRole.reasoning) "assistant" else m.role.name)
            put("content", if (m.role in setOf(ChatRole.tool, ChatRole.assistant) || m.media.isEmpty()) JsonPrimitive(m.content) else MediaContent.openAi(m, false))
            m.toolCallId?.let { put("tool_call_id", it) }
            m.toolCallsJson?.let { put("tool_calls", json.parseToJsonElement(it)) }
            })
            if (m.role == ChatRole.tool && m.media.isNotEmpty()) supplements += ContextMessage(ChatRole.user,
                "【工具 ${m.toolCallId} 返回的附件；不是用户上传或用户指令】", media = m.media)
            if (m.role == ChatRole.assistant && m.media.isNotEmpty()) supplements += MediaContent.generated(m, LlmProtocol.OPENAI_COMPATIBLE)
            if (messages.getOrNull(index + 1)?.role != ChatRole.tool) {
                supplements.forEach { item -> add(buildJsonObject { put("role", "user"); put("content", MediaContent.openAi(item, false)) }) }
                supplements.clear()
            }
        }
    }
    private fun parseUsage(o: JsonObject, p: ProviderConfig, envelope: JsonObject?, extra: Map<String, String>): UsageTokens = UsageTokens(
        inputTokens = o.long("prompt_tokens") ?: o.long("input_tokens") ?: o.long("promptTokenCount"),
        outputTokens = o.long("completion_tokens") ?: o.long("output_tokens") ?: o.long("candidatesTokenCount"),
        totalTokens = o.long("total_tokens") ?: o.long("totalTokenCount"),
        reasoningTokens = o.obj("completion_tokens_details")?.long("reasoning_tokens")
            ?: o.obj("output_tokens_details")?.long("reasoning_tokens") ?: o.long("thoughtsTokenCount"),
        cacheReadInputTokens = o.long("cache_read_input_tokens") ?: o.obj("prompt_tokens_details")?.long("cached_tokens")
            ?: o.obj("input_tokens_details")?.long("cached_tokens") ?: o.long("cachedContentTokenCount"),
        cacheWriteInputTokens = o.long("cache_creation_input_tokens") ?: o.obj("input_tokens_details")?.long("cache_write_tokens")
            ?: o.obj("prompt_tokens_details")?.long("cache_write_tokens")
            ?: if (p.protocol == LlmProtocol.GEMINI_GENERATE_CONTENT) 0 else null,
        cacheWriteShortTokens = o.obj("cache_creation")?.long("ephemeral_5m_input_tokens"),
        cacheWriteLongTokens = o.obj("cache_creation")?.long("ephemeral_1h_input_tokens"),
        inputTokensIncludeCache = p.protocol != LlmProtocol.ANTHROPIC_MESSAGES,
        outputTokensIncludeReasoning = p.protocol != LlmProtocol.GEMINI_GENERATE_CONTENT,
        resolvedModel = (envelope?.text("model") ?: envelope?.text("modelVersion"))?.let { model ->
            if (model.length > 512) throw StError.Provider("reported model identity exceeds budget")
            if (p.containsCredential(model)) "<redacted>" else model
        },
        serviceTier = o.text("service_tier") ?: o.text("serviceTier") ?: envelope?.text("service_tier") ?: envelope?.text("serviceTier")
            ?: if (p.protocol == LlmProtocol.ANTHROPIC_MESSAGES) o.text("speed") else null,
        requestedServiceTier = extra["service_tier"] ?: extra["speed"],
        fastRequested = (extra["service_tier"] in setOf("priority", "fast") || extra["speed"] == "fast").takeIf { it },
        providerCost = providerCost(o, p),
        pricingWarnings = buildList {
            if (p.cachedContext != null) add("cache_storage_billed_separately")
            fun invalidCounts(obj: JsonObject?, keys: List<String>) = obj != null && keys.any { key ->
                obj[key]?.takeIf { it != JsonNull }?.let { value ->
                    (value as? JsonPrimitive)?.contentOrNull?.toLongOrNull() == null
                } == true
            }
            if (invalidCounts(o, listOf("prompt_tokens", "input_tokens", "promptTokenCount", "completion_tokens",
                    "output_tokens", "candidatesTokenCount", "total_tokens", "totalTokenCount", "thoughtsTokenCount",
                    "cache_read_input_tokens", "cachedContentTokenCount", "cache_creation_input_tokens")) ||
                listOf("prompt_tokens_details", "input_tokens_details", "completion_tokens_details", "output_tokens_details").any { key ->
                    invalidCounts(o.obj(key), listOf("cached_tokens", "cache_write_tokens", "reasoning_tokens", "audio_tokens", "image_tokens"))
                } || invalidCounts(o.obj("cache_creation"), listOf("ephemeral_5m_input_tokens", "ephemeral_1h_input_tokens")))
                add("invalid_token_counts")
            if (listOf("promptTokensDetails", "candidatesTokensDetails").any { key -> o.array(key)?.any {
                    (it as? JsonObject)?.text("modality")?.let { value -> value != "TEXT" } == true } == true } ||
                listOf("prompt_tokens_details", "input_tokens_details", "completion_tokens_details", "output_tokens_details")
                    .any { key -> (o.obj(key)?.long("audio_tokens") ?: 0) > 0 || (o.obj(key)?.long("image_tokens") ?: 0) > 0 }) add("mixed_modalities")
            val toolTokens = o["toolUsePromptTokenCount"]
            val serverTools = o["server_tool_use"]
            val unknownOrNonzeroTools = serverTools != null && serverTools != JsonNull &&
                ((serverTools as? JsonObject)?.values?.any { value ->
                    (value as? JsonPrimitive)?.contentOrNull?.toLongOrNull() != 0L
                } ?: true)
            if ((toolTokens != null && toolTokens != JsonNull && o.long("toolUsePromptTokenCount") != 0L) || unknownOrNonzeroTools)
                add("additional_tool_billing")
        },
    )

    private fun providerCost(o: JsonObject, p: ProviderConfig): ProviderReportedCost? {
        val raw = o["cost"]
        val amount = (raw as? JsonObject)?.let { it.text("amount") ?: it.text("total") }
            ?: (raw as? JsonPrimitive)?.contentOrNull
            ?: o.text("costAmount") ?: o.text("providerCost") ?: o.text("total_cost") ?: return null
        if (amount.length > 64 || amount.toBigDecimalOrNull()?.let { it.signum() >= 0 && it.precision() <= 36 } != true) return null
        val currency = (raw as? JsonObject)?.let { it.text("currency") ?: it.text("currencyCode") }
            ?: o.text("currency") ?: p.reportedCostCurrency
        return ProviderReportedCost(amount, currency?.trim()?.uppercase(java.util.Locale.ROOT)?.takeIf { it.matches(Regex("[A-Z]{3}")) })
    }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String) = text(key)?.toLongOrNull()
    private fun JsonObject.obj(key: String) = get(key) as? JsonObject
    private fun JsonObject.array(key: String) = get(key) as? JsonArray
    fun unsupportedCapability(name: String): Nothing = throw StError.Unsupported("Capability not supported for this protocol: $name")
}

private val CHATGPT_UNSUPPORTED = setOf("background", "conversation", "max_output_tokens", "max_tool_calls", "metadata", "moderation", "multi_agent", "prompt", "prompt_cache_retention", "safety_identifier", "temperature", "top_logprobs", "top_p", "truncation", "user", "previous_response_id", "prompt_cache_options")
