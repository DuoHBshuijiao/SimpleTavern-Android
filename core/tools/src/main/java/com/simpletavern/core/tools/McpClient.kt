package com.simpletavern.core.tools

import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.llm.NoHttpReplayInterceptor
import com.simpletavern.core.model.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import okio.buffer
import okio.source
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.simpletavern.runtime.sandbox.SandboxStdioProcess

/** One live MCP connection. No reconnect, automatic tool replay, or persisted session handles. */
class McpClient(private val configuration: McpServerConfiguration, private val headers: Map<String, String>,
    private val launchStdio: (suspend () -> SandboxStdioProcess)? = null) {
    private val endpoint by lazy { configuration.url.toHttpUrlOrNull()
        ?.takeIf { it.username.isEmpty() && it.password.isEmpty() }
        ?: throw StError.Validation("invalid MCP HTTP endpoint") }
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .addNetworkInterceptor(NoHttpReplayInterceptor()).build()
    private var sessionId: String? = null
    private var version = "2025-11-25"
    private var postEndpoint: HttpUrl? = null
    private var initialized = false
    private var serverCapabilities = JsonObject(emptyMap())
    private var closed = false
    private val readers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val legacyEndpoint = CompletableDeferred<HttpUrl>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private var legacyReader: Deferred<Unit>? = null
    private var stdio: SandboxStdioProcess? = null
    @Volatile private var stdioFailure: StError? = null
    private val stdioWrite = Mutex()
    private val notificationLock = Any()
    @Volatile private var notificationSink: Channel<JsonObject>? = null
    @Volatile private var watchedResources: Set<String> = emptySet()
    private val eventHttp by lazy { http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build() }

    override fun toString() = "McpClient(serverId=${configuration.id}, credentials=<redacted>)"

    suspend fun initialize() {
        if (initialized) return
        if (configuration.transport !in setOf("streamable_http", "sse", "stdio")) throw StError.Unsupported("unsupported MCP transport")
        if (configuration.transport == "stdio") {
            val start = launchStdio ?: throw StError.Unsupported("MCP stdio sandbox launcher is not installed")
            // Store ownership before a cancelled dispatch can discard a newly launched process.
            withContext(NonCancellable + Dispatchers.IO) { stdio = start() }
            currentCoroutineContext().ensureActive()
            val process = stdio!!
            readers.launch {
                try { process.stderr.use { input -> val bytes = ByteArray(8192); while (input.read(bytes) >= 0) { currentCoroutineContext().ensureActive() } } }
                catch (_: IOException) { /* stderr is drained, never mixed into RPC or credential-bearing logs. */ }
            }
            legacyReader = readers.async {
                try {
                    process.stdout.source().buffer().use { source ->
                        while (!source.exhausted()) {
                            val newline = source.indexOf('\n'.code.toByte(), 0, MAX_MESSAGE_BYTES + 1L)
                            if (newline < 0) throw StError.Provider("MCP stdio message is oversized or not newline-delimited")
                            val line = source.readUtf8(newline).removeSuffix("\r")
                            source.skip(1)
                            receive(parseMessage(line))
                        }
                    }
                    throw StError.Provider("MCP stdio server exited; no automatic replay")
                } catch (error: Exception) {
                    val failure = if (error is StError) error else StError.Provider("MCP stdio transport failed; no automatic replay")
                    stdioFailure = failure
                    pending.values.forEach { it.completeExceptionally(failure) }
                    notificationSink?.close(failure)
                    throw failure
                } finally { process.close() }
            }
        }
        if (configuration.transport == "sse") {
            legacyReader = readers.async {
                try {
                    exchange(request(endpoint).get().header("Accept", "text/event-stream").build(), streaming = notificationSink != null) { response ->
                        requireSuccess(response)
                        if (response.header("Content-Type")?.substringBefore(';')?.trim() != "text/event-stream")
                            throw StError.Provider("MCP SSE endpoint did not return an event stream")
                        events(response.body!!.source()) { type, data ->
                            if (type == "endpoint") {
                                val target = endpoint.resolve(data) ?: throw StError.Provider("invalid MCP SSE message endpoint")
                                if (target.scheme != endpoint.scheme || target.host != endpoint.host || target.port != endpoint.port ||
                                    target.username.isNotEmpty() || target.password.isNotEmpty())
                                    throw StError.Provider("MCP SSE message endpoint must have the configured origin")
                                postEndpoint = target
                                legacyEndpoint.complete(target)
                            } else if (type == "message") receive(parseMessage(data))
                            false
                        }
                        throw StError.Provider("MCP SSE connection closed")
                    }
                } catch (error: Exception) {
                    legacyEndpoint.completeExceptionally(error)
                    pending.values.forEach { it.completeExceptionally(error) }
                    notificationSink?.close(error)
                    throw error
                }
            }
            postEndpoint = withTimeout(20_000) { legacyEndpoint.await() }
        }
        val result = rpc("initialize", buildJsonObject {
            put("protocolVersion", version); put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", buildJsonObject { put("name", "SimpleTavern-Android"); put("version", "0.1.0") })
        })
        version = result["protocolVersion"]?.jsonPrimitive?.content ?: throw StError.Provider("MCP protocol version missing")
        if (version !in setOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")) throw StError.Unsupported("unsupported negotiated MCP version")
        serverCapabilities = result["capabilities"] as? JsonObject ?: throw StError.Provider("MCP server capabilities missing")
        initialized = true
        send(notification("notifications/initialized"))
    }

    suspend fun listTools(): List<JsonObject> {
        initialize()
        if (serverCapabilities["tools"] !is JsonObject) return emptyList()
        return listAll("tools/list", "tools")
    }

    private suspend fun listAll(method: String, key: String): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            if (cursors.size >= 512) throw StError.Validation("MCP $key discovery exceeds 512 pages")
            val page = rpc(method, buildJsonObject { cursor?.let { put("cursor", it) } })
            val rows = page[key] as? JsonArray ?: throw StError.Provider("invalid MCP $key list")
            result.addAll(rows.map { it as? JsonObject ?: throw StError.Provider("invalid MCP $key descriptor") })
            if (result.size > 512) throw StError.Validation("MCP $key discovery exceeds 512 entries")
            cursor = (page["nextCursor"] as? JsonPrimitive)?.contentOrNull
            if (cursor != null && !cursors.add(cursor!!)) throw StError.Provider("MCP $key pagination repeated its cursor")
        } while (cursor != null)
        return result
    }

    suspend fun callTool(name: String, args: JsonObject): JsonObject = rpc("tools/call", buildJsonObject {
        put("name", name); put("arguments", args)
    })

    suspend fun capabilities(): McpServerCapabilities {
        initialize()
        return McpServerCapabilities(version, serverCapabilities["tools"] is JsonObject,
            serverCapabilities["resources"] is JsonObject, serverCapabilities["prompts"] is JsonObject,
            serverCapabilities["completions"] is JsonObject, capabilityFlag("resources", "subscribe"),
            capabilityFlag("resources", "listChanged"), capabilityFlag("tools", "listChanged"),
            capabilityFlag("prompts", "listChanged"))
    }

    private fun capabilityFlag(name: String, flag: String) =
        ((serverCapabilities[name] as? JsonObject)?.get(flag) as? JsonPrimitive)?.booleanOrNull == true

    /** Collection owns the live subscriptions. Notifications never fetch resource contents or run tools. */
    fun observeNotifications(resourceUris: List<String> = emptyList()): Flow<JsonObject> = flow {
        if (resourceUris.size > 64 || resourceUris.any { it.isBlank() || it.toByteArray(Charsets.UTF_8).size > 65_536 })
            throw StError.Validation("MCP subscriptions require at most 64 nonempty resource URIs of at most 64 KiB")
        val queue = Channel<JsonObject>(256)
        synchronized(notificationLock) {
            if (closed || notificationSink != null) throw StError.Conflict("MCP notification collection is not available")
            watchedResources = resourceUris.toSet()
            notificationSink = queue
        }
        val subscribed = mutableListOf<String>()
        var receiver: Deferred<Unit>? = null
        try {
            val flags = capabilities()
            if (resourceUris.isNotEmpty() && !flags.resourceSubscriptions)
                throw StError.Unsupported("MCP server does not advertise resource subscriptions")
            if (resourceUris.isEmpty() && !flags.resourceListChanged && !flags.toolListChanged && !flags.promptListChanged)
                throw StError.Unsupported("MCP server does not advertise list-change notifications")
            if (configuration.transport == "streamable_http") {
                val ready = CompletableDeferred<Unit>()
                receiver = readers.async {
                    try { receiveNotificationStream(ready) }
                    catch (error: Exception) { ready.completeExceptionally(error); queue.close(error); throw error }
                }
                withTimeout(20_000) { ready.await() }
            }
            for (uri in resourceUris.distinct()) {
                // Track before waiting: a cancelled/lost response does not prove the server ignored the subscription.
                subscribed += uri
                rpc("resources/subscribe", buildJsonObject { put("uri", uri) })
            }
            for (message in queue) emit(message)
        } finally {
            synchronized(notificationLock) { notificationSink = null; watchedResources = emptySet() }
            queue.close()
            withContext(NonCancellable) {
                withTimeoutOrNull(1500) {
                    for (uri in subscribed.asReversed()) {
                        if (!initialized || closed) break
                        try { rpc("resources/unsubscribe", buildJsonObject { put("uri", uri) }) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { /* Closing the session also releases its subscriptions. */ }
                    }
                }
                receiver?.cancelAndJoin()
            }
        }
    }

    private suspend fun receiveNotificationStream(ready: CompletableDeferred<Unit>) {
        var cursor: String? = null
        var retryMillis = 1000L
        while (true) {
            currentCoroutineContext().ensureActive()
            val next = request(endpoint).get().header("Accept", "text/event-stream").apply {
                cursor?.let { header("Last-Event-ID", it) }
            }.build()
            try {
                exchange(next, streaming = true) { response ->
                    if (response.code == 405) throw StError.Unsupported("MCP server does not offer a notification SSE stream")
                    requireSuccess(response)
                    if (response.header("Content-Type")?.substringBefore(';')?.trim() != "text/event-stream")
                        throw StError.Provider("MCP notification endpoint did not return an event stream")
                    ready.complete(Unit)
                    events(response.body!!.source(), control = { field, value ->
                        if (field == "id") cursor = value.takeIf(String::isNotEmpty)
                        if (field == "retry") value.toLongOrNull()?.takeIf { it >= 0 }?.let { retryMillis = it }
                    }) { _, data -> receive(parseMessage(data)); false }
                }
            } catch (error: StError.Provider) {
                if (cursor == null || error.details["transportFailure"] != "true") throw error
            }
            if (cursor == null) throw StError.Provider("MCP notification stream ended without a live cursor; updates may be missing")
            // Resume only this receiving stream. Never repeat subscriptions, tools/call, or initialize.
            delay(retryMillis)
        }
    }

    private suspend fun requireCapability(name: String) {
        initialize()
        if (serverCapabilities[name] !is JsonObject) throw StError.Unsupported("MCP server does not advertise $name")
    }

    suspend fun listResources(): List<JsonObject> {
        requireCapability("resources")
        return listAll("resources/list", "resources")
    }

    suspend fun listResourceTemplates(): List<JsonObject> {
        requireCapability("resources")
        return listAll("resources/templates/list", "resourceTemplates")
    }

    suspend fun readResource(uri: String): JsonObject {
        requireCapability("resources")
        return rpc("resources/read", buildJsonObject { put("uri", uri) })
    }

    suspend fun listPrompts(): List<JsonObject> {
        requireCapability("prompts")
        return listAll("prompts/list", "prompts")
    }

    suspend fun getPrompt(name: String, arguments: Map<String, String>): JsonObject {
        requireCapability("prompts")
        return rpc("prompts/get", buildJsonObject {
            put("name", name); put("arguments", JsonObject(arguments.mapValues { JsonPrimitive(it.value) }))
        })
    }

    suspend fun complete(reference: McpCompletionReference, argument: String, value: String,
                         context: Map<String, String>): JsonObject {
        requireCapability("completions")
        return rpc("completion/complete", buildJsonObject {
            put("ref", buildJsonObject {
                when (reference.target) {
                    McpCompletionTarget.PROMPT -> { put("type", "ref/prompt"); put("name", reference.value) }
                    McpCompletionTarget.RESOURCE -> { put("type", "ref/resource"); put("uri", reference.value) }
                }
            })
            put("argument", buildJsonObject { put("name", argument); put("value", value) })
            if (context.isNotEmpty()) put("context", buildJsonObject {
                put("arguments", JsonObject(context.mapValues { JsonPrimitive(it.value) }))
            })
        })
    }

    private suspend fun rpc(method: String, params: JsonObject): JsonObject {
        if (closed) throw StError.Conflict("MCP connection is not live")
        if (method != "initialize" && !initialized) {
            if (method == "resources/unsubscribe") throw StError.Conflict("MCP subscription session is no longer live")
            initialize()
        }
        val id = UUID.randomUUID().toString()
        val message = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }
        val waiting = CompletableDeferred<JsonObject>()
        var responseReceived = false
        try {
            val response = withTimeout(120_000) {
                if (configuration.transport in setOf("sse", "stdio")) {
                    pending[id] = waiting
                    stdioFailure?.let { throw it }
                    if (legacyReader?.isCompleted == true) throw StError.Provider("MCP receive connection is no longer live")
                    send(message)
                    waiting.await()
                } else {
                    var answer: JsonObject? = null
                    var lastEventId: String? = null
                    var retryMillis = 1000L
                    var transportRequest = post(message)
                    do {
                        try {
                            exchange(transportRequest) { response ->
                                requireSuccess(response)
                                if (method == "initialize") sessionId = response.header("Mcp-Session-Id") ?: sessionId
                                val body = response.body ?: throw StError.Provider("empty MCP response")
                                when (response.header("Content-Type")?.substringBefore(';')?.trim()) {
                                    "application/json" -> { answer = parseMessage(readJson(body.source())) }
                                    "text/event-stream" -> {
                                        events(body.source(), control = { field, value ->
                                            if (field == "id") lastEventId = value.takeIf { it.isNotEmpty() }
                                            if (field == "retry") value.toLongOrNull()?.takeIf { it >= 0 }?.let { retryMillis = it }
                                        }) { _, data ->
                                            val incoming = parseMessage(data)
                                            if (incoming["id"] == JsonPrimitive(id) && incoming["method"] == null) answer = incoming
                                            else receive(incoming)
                                            answer != null
                                        }
                                    }
                                    else -> throw StError.Provider("unsupported MCP response content type")
                                }
                            }
                        } catch (error: StError.Provider) {
                            if (lastEventId == null || error.details["transportFailure"] != "true") throw error
                        }
                        if (answer == null) {
                            val cursor = lastEventId ?: throw StError.Provider("MCP event stream ended without a response or live cursor")
                            delay(retryMillis)
                            // Continue receiving THIS live request; never repeat its POST/tools/call.
                            transportRequest = request(endpoint).get().header("Accept", "text/event-stream").header("Last-Event-ID", cursor).build()
                        }
                    } while (answer == null)
                    answer!!
                }
            }
            responseReceived = true
            if (response["jsonrpc"] != JsonPrimitive("2.0") || response["id"] != JsonPrimitive(id))
                throw StError.Provider("MCP response identity mismatch")
            (response["error"] as? JsonObject)?.let {
                throw StError.Provider("MCP RPC error ${(it["code"] as? JsonPrimitive)?.contentOrNull.orEmpty()}")
            }
            return response["result"] as? JsonObject ?: throw StError.Provider("invalid MCP result")
        } catch (cancel: CancellationException) {
            if (!responseReceived && method != "initialize") withContext(NonCancellable) {
                withTimeoutOrNull(1500) {
                    try { send(notification("notifications/cancelled", buildJsonObject { put("requestId", id); put("reason", "caller stopped") })) }
                    catch (_: Exception) { /* Remote cancellation is best effort, not a retry of the call. */ }
                }
            }
            currentCoroutineContext().ensureActive()
            if (cancel is TimeoutCancellationException) throw StError.Provider("MCP request timed out; no automatic replay")
            throw cancel
        } finally { pending.remove(id) }
    }

    private suspend fun receive(message: JsonObject) {
        if (message["method"] == null) {
            (message["id"] as? JsonPrimitive)?.contentOrNull?.let { pending[it]?.complete(message) }
        } else if (message["id"] != null) {
            // No sampling, roots or elicitation capability was advertised.
            send(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", message.getValue("id"))
                if (message["method"] == JsonPrimitive("ping")) put("result", JsonObject(emptyMap()))
                else put("error", buildJsonObject { put("code", -32601); put("message", "client method not supported") })
            })
        } else {
            val sink = notificationSink ?: return
            val method = (message["method"] as? JsonPrimitive)?.contentOrNull ?: return
            if (method !in setOf("notifications/resources/updated", "notifications/resources/list_changed",
                    "notifications/tools/list_changed", "notifications/prompts/list_changed")) return
            if (message["jsonrpc"] != JsonPrimitive("2.0")) throw StError.Provider("invalid MCP notification version")
            val uri = if (method == "notifications/resources/updated") {
                val value = ((message["params"] as? JsonObject)?.get("uri") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw StError.Provider("MCP resource update URI missing")
                if (value.toByteArray(Charsets.UTF_8).size > 65_536) throw StError.Provider("MCP resource update URI exceeds 64 KiB")
                if (value !in watchedResources) return
                value
            } else null
            val event = buildJsonObject { put("method", method); uri?.let { put("uri", it) } }
            if (!sink.trySend(event).isSuccess) {
                val failure = StError.Provider("MCP notification consumer is too slow; updates may be missing")
                sink.close(failure)
                throw failure
            }
        }
    }

    private fun notification(method: String, params: JsonObject? = null) = buildJsonObject {
        put("jsonrpc", "2.0"); put("method", method); params?.let { put("params", it) }
    }
    private fun request(url: HttpUrl): Request.Builder = Request.Builder().url(url).apply {
        try { headers.forEach { (name, value) -> header(name, value) } }
        catch (_: IllegalArgumentException) { throw StError.Validation("invalid MCP HTTP header") }
        header("Accept", "application/json, text/event-stream")
        sessionId?.let { header("Mcp-Session-Id", it) }
        if (initialized) header("MCP-Protocol-Version", version)
    }
    private fun post(message: JsonObject) = request(postEndpoint ?: endpoint)
        .post(message.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    private suspend fun send(message: JsonObject) {
        if (configuration.transport == "stdio") {
            val process = stdio ?: throw StError.Conflict("MCP stdio process is not live")
            val bytes = (message.toString() + "\n").toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_MESSAGE_BYTES + 1) throw StError.Validation("MCP message exceeds 8 MB")
            withContext(Dispatchers.IO) { coroutineScope {
                val caller = currentCoroutineContext().job
                val stopping = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { if (caller.isCancelled) process.close() }
                }
                try { stdioWrite.withLock { process.stdin.write(bytes); process.stdin.flush() } }
                catch (_: IOException) { currentCoroutineContext().ensureActive(); throw StError.Provider("MCP stdio write failed; no automatic replay") }
                finally { stopping.cancel() }
            } }
        } else exchange(post(message)) { response ->
            requireSuccess(response)
            if (configuration.transport != "sse" && response.code != 202)
                throw StError.Provider("MCP notification/response was not accepted with HTTP 202")
        }
    }
    private fun requireSuccess(response: Response) {
        if (response.code == 404 && sessionId != null) { initialized = false; sessionId = null }
        if (!response.isSuccessful) {
            val details = linkedMapOf("httpStatus" to response.code.toString())
            if (response.code == 401 || response.code == 403) {
                val challenge = response.challenges().firstOrNull { it.scheme.equals("Bearer", ignoreCase = true) }
                challenge?.authParams?.get("error")?.takeIf { it in setOf("invalid_token", "insufficient_scope") }
                    ?.let { details["oauthError"] = it }
                challenge?.authParams?.get("scope")?.takeIf { scope -> scope.length <= 4096 && scope.all { it.code in 32..126 } }
                    ?.let { details["oauthScope"] = it }
            }
            // Let the host request explicit new authorization. Never replay a possibly executed tool.
            throw StError.Provider("MCP HTTP ${response.code}; no automatic replay", details)
        }
    }
    private suspend fun <T> exchange(request: Request, streaming: Boolean = false, block: suspend (Response) -> T): T = withContext(Dispatchers.IO) {
        val call = (if (streaming) eventHttp else http).newCall(request)
        coroutineScope {
            val cancelCall = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
            try { call.execute().use { block(it) } }
            catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                throw StError.Provider("MCP transport failed; no automatic replay", mapOf("transportFailure" to "true"))
            }
            finally { cancelCall.cancel() }
        }
    }
    private fun parseMessage(raw: String): JsonObject = try {
        Jsonx.json.parseToJsonElement(raw) as? JsonObject ?: throw StError.Provider("MCP message must be a JSON object")
    } catch (_: IllegalArgumentException) { throw StError.Provider("invalid MCP JSON message") }
    private fun readJson(source: BufferedSource): String {
        val buffer = okio.Buffer()
        while (buffer.size <= MAX_MESSAGE_BYTES) {
            val read = source.read(buffer, minOf(8192L, MAX_MESSAGE_BYTES + 1L - buffer.size))
            if (read == -1L) return buffer.readUtf8()
        }
        throw StError.Validation("MCP response exceeds 8 MB")
    }
    private suspend fun events(source: BufferedSource, control: (String, String) -> Unit = { _, _ -> },
        receive: suspend (String, String) -> Boolean) {
        var type = "message"
        val data = StringBuilder()
        var dataBytes = 0L
        while (!source.exhausted()) {
            currentCoroutineContext().ensureActive()
            val newline = source.indexOf('\n'.code.toByte(), 0, MAX_MESSAGE_BYTES + 1L)
            val line = if (newline >= 0) source.readUtf8LineStrict(MAX_MESSAGE_BYTES.toLong()) else {
                if (source.buffer.size > MAX_MESSAGE_BYTES) throw StError.Validation("MCP event exceeds 8 MB")
                source.readUtf8().removeSuffix("\r")
            }
            if (line.isEmpty()) {
                if (data.isNotBlank() && receive(type, data.toString().removeSuffix("\n"))) return
                data.setLength(0); dataBytes = 0; type = "message"
            } else if (line.startsWith("event:")) type = line.substring(6).removePrefix(" ")
            else if (line.startsWith("id:")) control("id", line.substring(3).removePrefix(" "))
            else if (line.startsWith("retry:")) control("retry", line.substring(6).removePrefix(" "))
            else if (line.startsWith("data:")) {
                val value = line.substring(5).removePrefix(" ")
                dataBytes += value.toByteArray(Charsets.UTF_8).size + 1L
                if (dataBytes > MAX_MESSAGE_BYTES) throw StError.Validation("MCP event exceeds 8 MB")
                data.append(value).append('\n')
            }
        }
        if (data.isNotBlank()) receive(type, data.toString().removeSuffix("\n"))
    }

    suspend fun close() {
        if (closed) return
        closed = true
        notificationSink?.close(StError.Interrupted("MCP session closed"))
        stdio?.close()
        readers.cancel()
        pending.values.forEach { it.completeExceptionally(StError.Interrupted("MCP session closed")) }
        if (configuration.transport == "streamable_http" && sessionId != null) withContext(NonCancellable) {
            withTimeoutOrNull(1500) { try { exchange(request(endpoint).delete().build()) { } } catch (_: Exception) { } }
        }
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }
    private companion object { const val MAX_MESSAGE_BYTES = 8_000_000 }
}
