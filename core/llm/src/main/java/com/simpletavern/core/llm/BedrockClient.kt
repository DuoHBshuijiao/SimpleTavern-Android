package com.simpletavern.core.llm

import com.simpletavern.core.model.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Explicit Converse protocol; uses the same checkpoint, cancellation and canonical tool events. */
internal class BedrockClient(private val http: OkHttpClient, private val json: Json) {
    private class Block {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val signature = StringBuilder()
        val redacted = ByteArrayOutputStream()
        val input = StringBuilder()
        var toolId: String? = null
        var toolName: String? = null
        var closed = false
    }

    suspend fun run(p: ProviderConfig, r: LlmRequest, beforeExecute: suspend () -> Unit,
        emit: suspend (StreamEvent) -> Unit) = coroutineScope {
        if (r.nativeWebSearch) throw StError.Unsupported("Converse uses independent search")
        val wire = GenerationParameters.wire(r.protocol, r.temperature, r.topP, r.extra)
        var messages = ProtocolMessages.bedrock(r.messages, p)
        if (messages.isEmpty()) throw StError.Validation("Converse requires message content")
        val system = r.messages.filter { it.role == ChatRole.system && it.content.isNotEmpty() }
            .map { buildJsonObject { put("text", it.content) } }.toMutableList<JsonElement>()
        val tools = ProtocolMessages.definitions(r.toolsJson, r.protocol).orEmpty().toMutableList()
        // GenerationParameters already validates these shared controls. Only encode request-local markers.
        r.extra["cache_control"]?.let { raw ->
            val control = json.parseToJsonElement(raw).jsonObject
            val points = r.extra["cache_breakpoints"]?.let {
                json.parseToJsonElement(it).jsonArray.map { value -> value.jsonPrimitive.content }.toSet()
            } ?: setOf("history_tail")
            val point = buildJsonObject { put("cachePoint", buildJsonObject {
                put("type", "default")
                // Omit the default TTL so models with only 5-minute support can accept the marker.
                if (control["ttl"] == JsonPrimitive("1h")) put("ttl", "1h")
            }) }
            if ("system" in points) {
                if (system.none { (it as JsonObject).text("text")?.isNotBlank() == true })
                    throw StError.Validation("system cache requires a nonempty system message")
                system += point
            }
            if ("tools" in points) {
                if (tools.isEmpty()) throw StError.Validation("tools cache requires tool definitions")
                tools += point
            }
            if ("history_tail" in points) {
                val last = messages.last().jsonObject
                messages = JsonArray(messages.dropLast(1) + JsonObject(last +
                    ("content" to JsonArray(last.getValue("content").jsonArray + point))))
            }
        }
        val body = buildJsonObject {
            put("messages", messages)
            if (system.isNotEmpty()) put("system", JsonArray(system))
            wire.forEach { (key, value) -> put(key, value) }
            if (tools.isNotEmpty()) put("toolConfig", buildJsonObject { put("tools", JsonArray(tools)) })
        }
        val encoded = body.toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > 20_971_520) throw StError.Validation("Converse request exceeds 20 MiB")
        val request = Request.Builder().url(endpoint(p, r.model, if (p.streaming) "converse-stream" else "converse"))
            .post(encoded.toRequestBody("application/json".toMediaType()))
            .header("Accept", if (p.streaming) "application/vnd.amazon.eventstream" else "application/json")
        p.httpOptions.requestHeaders(p.protocol, p.apiKey).forEach { (name, value) -> request.header(name, value) }
        val prepared = request.build()
        val signed = if (p.awsCredentials != null) AwsSigV4.bedrock(prepared, encoded.toByteArray(Charsets.UTF_8), p.awsCredentials) else prepared
        val call = http.newCall(signed)
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            currentCoroutineContext().ensureActive()
            beforeExecute()
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                if (!response.isSuccessful) throw StError.Provider("Bedrock HTTP ${response.code}")
                val source = response.body?.source() ?: throw StError.Provider("empty Bedrock response")
                if (!p.streaming) {
                    if (source.request(16_777_217)) throw StError.Provider("Bedrock response exceeds budget")
                    val obj = objectValue(source.readUtf8())
                    val output = obj.obj("output")?.obj("message") ?: invalid()
                    if (output.text("role") != "assistant") invalid()
                    val contents = output["content"] as? JsonArray ?: invalid()
                    if (contents.toString().toByteArray(Charsets.UTF_8).size > 4_194_304) throw StError.Provider("Converse output exceeds budget")
                    for (element in contents) emitContent(element as? JsonObject ?: invalid(), emit)
                    val usage = usage(obj.obj("usage"), obj, p, r.extra)
                    usage?.let { emit(StreamEvent.Usage(it)) }
                    val finish = obj.text("stopReason") ?: invalid()
                    requireFinished(finish, contents.count { (it as? JsonObject)?.get("toolUse") != null })
                    emit(StreamEvent.Done(true, usage = usage,
                        providerTurn = ProviderTurn(p.protocol, p.model, contents.toString())))
                } else stream(source, p, r.extra, emit)
            }
        } finally { watcher.cancel() }
    }

    private suspend fun stream(source: okio.BufferedSource, p: ProviderConfig, extra: Map<String, String>, emit: suspend (StreamEvent) -> Unit) {
        val blocks = sortedMapOf<Int, Block>()
        var started = false
        var stopped = false
        var finish: String? = null
        var tokens: UsageTokens? = null
        var outputBytes = 0L
        fun append(buffer: StringBuilder, value: String) {
            outputBytes += value.toByteArray(Charsets.UTF_8).size
            if (outputBytes > 4_194_304) throw StError.Provider("Converse content exceeds budget")
            buffer.append(value)
        }
        AwsEventStream.read(source) { event ->
            val obj = objectValue(event.payload.toString(Charsets.UTF_8))
            when (event.headers[":event-type"]) {
                "messageStart" -> {
                    if (started || obj.text("role") != "assistant") invalid()
                    started = true
                }
                "contentBlockStart" -> {
                    if (!started || stopped) invalid()
                    val block = blocks.getOrPut(index(obj)) { Block() }
                    if (block.closed || block.toolId != null) invalid()
                    val start = obj.obj("start") ?: invalid()
                    start.obj("toolUse")?.let {
                        block.toolId = it.text("toolUseId") ?: invalid()
                        block.toolName = it.text("name") ?: invalid()
                    } ?: run { if (start.isNotEmpty()) throw StError.Unsupported("unsupported Converse content start") }
                }
                "contentBlockDelta" -> {
                    if (!started || stopped) invalid()
                    val block = blocks.getOrPut(index(obj)) { Block() }
                    if (block.closed) invalid()
                    val delta = obj.obj("delta") ?: invalid()
                    when {
                        delta.text("text") != null -> delta.text("text")!!.let {
                            append(block.text, it); emit(StreamEvent.Delta(it))
                        }
                        delta.obj("toolUse") != null -> {
                            if (block.toolId == null) invalid()
                            append(block.input, delta.obj("toolUse")!!.text("input") ?: invalid())
                        }
                        delta.obj("reasoningContent") != null -> {
                            val reasoning = delta.obj("reasoningContent")!!
                            reasoning.text("text")?.let { append(block.reasoning, it); emit(StreamEvent.Delta(it, true)) }
                            reasoning.text("signature")?.let { append(block.signature, it) }
                            reasoning.text("redactedContent")?.let {
                                val bytes = try { Base64.getDecoder().decode(it) } catch (_: IllegalArgumentException) { invalid() }
                                outputBytes += bytes.size
                                if (outputBytes > 4_194_304) throw StError.Provider("Converse content exceeds budget")
                                block.redacted.write(bytes)
                            }
                        }
                        else -> throw StError.Unsupported("unsupported Converse streamed content")
                    }
                }
                "contentBlockStop" -> {
                    val block = blocks[index(obj)] ?: invalid()
                    if (block.closed || stopped) invalid()
                    block.closed = true
                }
                "messageStop" -> {
                    if (!started || stopped || blocks.values.any { !it.closed }) invalid()
                    stopped = true; finish = obj.text("stopReason") ?: invalid()
                }
                "metadata" -> {
                    tokens = usage(obj.obj("usage"), obj, p, extra)
                    tokens?.let { emit(StreamEvent.Usage(it)) }
                }
                else -> throw StError.Unsupported("unsupported Converse stream event")
            }
            true
        }
        if (!started || !stopped) throw StError.Provider("incomplete Bedrock Converse stream")
        val contents = buildJsonArray {
            for (block in blocks.values) {
                if (block.text.isNotEmpty()) add(buildJsonObject { put("text", block.text.toString()) })
                if (block.reasoning.isNotEmpty() || block.signature.isNotEmpty()) add(buildJsonObject {
                    put("reasoningContent", buildJsonObject { put("reasoningText", buildJsonObject {
                        put("text", block.reasoning.toString()); put("signature", block.signature.toString())
                    }) })
                })
                if (block.redacted.size() > 0) add(buildJsonObject {
                    put("reasoningContent", buildJsonObject { put("redactedContent", Base64.getEncoder().encodeToString(block.redacted.toByteArray())) })
                })
                block.toolId?.let { id ->
                    val input = objectValue(block.input.toString())
                    val name = block.toolName ?: invalid()
                    add(buildJsonObject { put("toolUse", buildJsonObject { put("toolUseId", id); put("name", name); put("input", input) }) })
                    emit(StreamEvent.ToolCall(id, name, input.toString()))
                }
            }
        }
        if (contents.toString().toByteArray(Charsets.UTF_8).size > 4_194_304) throw StError.Provider("Converse output exceeds budget")
        requireFinished(finish, blocks.values.count { it.toolId != null })
        emit(StreamEvent.Done(true, usage = tokens,
            providerTurn = ProviderTurn(p.protocol, p.model, contents.toString())))
    }

    private suspend fun emitContent(block: JsonObject, emit: suspend (StreamEvent) -> Unit) {
        when {
            block.text("text") != null -> emit(StreamEvent.Delta(block.text("text")!!))
            block.obj("toolUse") != null -> block.obj("toolUse")!!.let {
                val input = it["input"] as? JsonObject ?: invalid()
                emit(StreamEvent.ToolCall(it.text("toolUseId") ?: invalid(), it.text("name") ?: invalid(), input.toString()))
            }
            block.obj("reasoningContent") != null -> block.obj("reasoningContent")!!.let {
                it.obj("reasoningText")?.text("text")?.let { value -> emit(StreamEvent.Delta(value, true)) }
            }
            else -> throw StError.Unsupported("unsupported Converse output content")
        }
    }
    private fun usage(obj: JsonObject?, response: JsonObject, p: ProviderConfig, extra: Map<String, String>): UsageTokens? {
        if (obj == null) return null
        fun count(name: String): Long? = obj[name]?.let { (it as? JsonPrimitive)?.longOrNull?.takeIf { n -> n >= 0 } ?: invalid() }
        val details = obj["cacheDetails"]?.let { value ->
            val entries = value as? JsonArray ?: invalid()
            val totals = mutableMapOf("5m" to 0L, "1h" to 0L)
            for (element in entries) {
                val entry = element as? JsonObject ?: invalid()
                val ttl = entry.text("ttl")?.takeIf { it in totals } ?: invalid()
                val amount = (entry["inputTokens"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 } ?: invalid()
                totals[ttl] = try { Math.addExact(totals.getValue(ttl), amount) }
                    catch (_: ArithmeticException) { invalid() }
            }
            totals
        }
        return UsageTokens(inputTokens = count("inputTokens"), outputTokens = count("outputTokens"), totalTokens = count("totalTokens"),
            cacheReadInputTokens = count("cacheReadInputTokens"), cacheWriteInputTokens = count("cacheWriteInputTokens"),
            cacheWriteShortTokens = details?.get("5m"), cacheWriteLongTokens = details?.get("1h"),
            inputTokensIncludeCache = false, outputTokensIncludeReasoning = true, resolvedModel = p.model,
            requestedServiceTier = extra["service_tier"], fastRequested = (extra["service_tier"] == "priority").takeIf { it },
            serviceTier = response.obj("serviceTier")?.text("type"))
    }
    private fun index(obj: JsonObject): Int = (obj["contentBlockIndex"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..4095 } ?: invalid()
    private fun requireFinished(reason: String?, calls: Int) {
        if (reason !in setOf("end_turn", "stop_sequence", "tool_use") || (reason == "tool_use") != (calls > 0))
            throw StError.Provider("Bedrock generation did not complete consistently")
    }
    private fun objectValue(raw: String): JsonObject = try { json.parseToJsonElement(raw) as? JsonObject ?: invalid() }
        catch (_: IllegalArgumentException) { invalid() }
    private fun JsonObject.obj(name: String) = get(name) as? JsonObject
    private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun invalid(): Nothing = throw StError.Provider("invalid Bedrock Converse response")

    companion object {
        internal fun endpoint(p: ProviderConfig, model: String, operation: String): HttpUrl {
            val root = p.baseUrl.toHttpUrlOrNull() ?: throw StError.Validation("invalid Bedrock endpoint")
            if (root.scheme != "https" || root.port != 443 || root.username.isNotEmpty() || root.password.isNotEmpty() ||
                root.query != null || root.fragment != null || root.encodedPath != "/" ||
                !root.host.matches(Regex("bedrock-runtime\\.[a-z]{2}(?:-[a-z0-9]+)+-[0-9]+\\.amazonaws\\.com(?:\\.cn)?")))
                throw StError.Unsupported("Bedrock requires its regional runtime root")
            if (model.isBlank() || model.length > 2048 || model.any(Char::isISOControl) || model.any(Char::isWhitespace))
                throw StError.Validation("invalid Bedrock model identity")
            if (ProviderHttpOptions.normalizeAuth(p.httpOptions.authStyle, p.protocol) !in setOf("bearer", "aws_sigv4"))
                throw StError.Unsupported("Bedrock requires Bearer API keys or SigV4 credentials")
            return root.newBuilder().addPathSegment("model").addPathSegment(model).addPathSegment(operation).build()
        }
    }
}
