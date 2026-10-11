package com.simpletavern.core.llm

import com.simpletavern.core.model.*
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Runtime-only binding. Its private owner snapshot and prompt prefix are never serialized/logged. */
class GeminiCacheConnection(provider: ProviderConfig) {
    private val protocol = provider.protocol
    private val providerId = provider.providerId
    private val model = provider.model
    private val baseUrl = provider.baseUrl
    private val apiKey = provider.apiKey
    private val authStyle = provider.httpOptions.authStyle
    private val apiVersion = provider.httpOptions.apiVersion
    private val headers = provider.httpOptions.headers.toMap()
    private val credentials = provider.httpOptions.credentialMaterials.toList()
    // IAM access tokens rotate; the encrypted account/key identity, not its short-lived token,
    // owns a Vertex cache. Ordinary API-key connections still compare their exact key snapshot.
    private val vertexAccount = providerId.startsWith("oauth:google-vertex:") && authStyle == "bearer" &&
        credentials.any { it.startsWith("google-service-account:") }
    fun matches(provider: ProviderConfig) = protocol == provider.protocol && providerId == provider.providerId &&
        model == provider.model && baseUrl == provider.baseUrl && (vertexAccount || apiKey == provider.apiKey) &&
        authStyle == provider.httpOptions.authStyle && apiVersion == provider.httpOptions.apiVersion &&
        headers == provider.httpOptions.headers && credentials == provider.httpOptions.credentialMaterials
    override fun toString() = "GeminiCacheConnection(<redacted>)"
}

class GeminiCacheUse(val provider: ProviderConfig, private val release: suspend () -> Unit) {
    private val closed = AtomicBoolean(false)
    suspend fun close() { if (closed.compareAndSet(false, true)) withContext(NonCancellable) { release() } }
    override fun toString() = "GeminiCacheUse(<redacted>)"
}

class GeminiCachedContext internal constructor(val metadata: GeminiCacheMetadata, private val owner: ProviderConfig,
    private val prefix: JsonObject, private val files: GeminiCacheFiles? = null,
    private val contentMode: GeminiCacheContentMode = GeminiCacheContentMode.MATCH_PREFIX) {
    private val connection = GeminiCacheConnection(owner)
    fun matchesConnection(provider: ProviderConfig) = connection.matches(provider)
    override fun toString() = "GeminiCachedContext(<redacted>)"
    /** Exact runtime value comparison; no digest, credential-derived key, network or fallback. */
    fun matches(provider: ProviderConfig, body: JsonObject, currentFiles: GeminiCacheFiles? = null): Boolean = try { apply(provider, body, currentFiles); true }
        catch (_: StError.Conflict) { false } catch (_: StError.Validation) { false }
    internal fun requireOwner(provider: ProviderConfig) {
        if (provider.protocol != LlmProtocol.GEMINI_GENERATE_CONTENT || !connection.matches(provider))
            throw StError.Conflict("cached context belongs to a different connection or credential snapshot")
    }
    fun updated(value: GeminiCacheMetadata): GeminiCachedContext {
        if (value.name != metadata.name || value.model != metadata.model) throw StError.Provider("cache identity changed during TTL update")
        return GeminiCachedContext(value, owner, prefix, files, contentMode)
    }
    fun forGeneration(mode: GeminiCacheContentMode) = GeminiCachedContext(metadata, owner, prefix, files, mode)
    internal fun apply(provider: ProviderConfig, body: JsonObject, currentFiles: GeminiCacheFiles? = null): JsonObject {
        requireOwner(provider)
        if (!Instant.now().isBefore(OffsetDateTime.parse(metadata.expireTime).toInstant()))
            throw StError.Conflict("cached context has expired; explicitly create a new cache")
        if (files?.available() == false) throw StError.Conflict("cached source files have expired; explicitly create a new cache")
        if (contentMode == GeminiCacheContentMode.PREPEND) {
            if (listOf("systemInstruction", "tools").any { body[it] != null && body[it] != prefix[it] })
                throw StError.Conflict("cached system or tools differ from the current request")
            val contents = body["contents"] as? JsonArray ?: throw StError.Validation("Gemini contents missing")
            if (contents.isEmpty()) throw StError.Validation("selected cache requires new content")
            return JsonObject(body.filterKeys { it !in setOf("systemInstruction", "tools") } +
                ("cachedContent" to JsonPrimitive(metadata.name)))
        }
        if (body["systemInstruction"] != prefix["systemInstruction"] || body["tools"] != prefix["tools"])
            throw StError.Conflict("cached system or tools differ from the current request")
        val contents = body["contents"] as? JsonArray ?: throw StError.Validation("Gemini contents missing")
        val matching = files?.matchContents(contents, currentFiles) ?: contents
        val saved = prefix["contents"] as? JsonArray ?: JsonArray(emptyList())
        val remaining = if (saved.isEmpty()) contents else {
            if (contents.size < saved.size || matching.take(saved.size - 1) != saved.dropLast(1))
                throw StError.Conflict("cached history differs from the current request")
            // ProtocolMessages coalesces adjacent equal roles. A new user turn may extend the
            // last cached content's parts rather than create another content object.
            val last = saved.last() as? JsonObject ?: throw StError.Validation("cached content missing")
            val current = matching[saved.lastIndex] as? JsonObject ?: throw StError.Validation("current content missing")
            val oldParts = last["parts"] as? JsonArray ?: throw StError.Validation("cached parts missing")
            val newParts = current["parts"] as? JsonArray ?: throw StError.Validation("current parts missing")
            if (last.filterKeys { it != "parts" } != current.filterKeys { it != "parts" } ||
                newParts.size < oldParts.size || newParts.take(oldParts.size) != oldParts.toList())
                throw StError.Conflict("cached history parts differ from the current request")
            // URI aliases are only for prefix comparison; new parts use this request's upload.
            val original = contents[saved.lastIndex].jsonObject
            val tail = original["parts"]!!.jsonArray.drop(oldParts.size)
            JsonArray((if (tail.isEmpty()) emptyList() else listOf(JsonObject(original + ("parts" to JsonArray(tail))))) + contents.drop(saved.size))
        }
        if (remaining.isEmpty()) throw StError.Validation("no new content follows the cached prefix")
        return JsonObject(body.filterKeys { it !in setOf("systemInstruction", "tools") } + mapOf(
            "cachedContent" to JsonPrimitive(metadata.name), "contents" to remaining))
    }
}

/** Native cache transport, not an automatic cache manager. Every operation requires a caller checkpoint. */
class GeminiCacheClient(http: OkHttpClient = OkHttpClient.Builder()
    .callTimeout(60, TimeUnit.SECONDS).build()) {
    // Even a custom client must not replay a cache mutation after an HTTP follow-up or an uncertain outcome.
    // Interceptors issuing their own separate Calls are outside this policy.
    private val http = http.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .apply { if (networkInterceptors().none { it is NoHttpReplayInterceptor }) addNetworkInterceptor(NoHttpReplayInterceptor()) }
        .build()

    private val json = Json { isLenient = false }
    fun validateConnection(provider: ProviderConfig) {
        root(provider); route(provider).requestModel(provider.model)
        provider.httpOptions.requestHeaders(provider.protocol, provider.apiKey)
    }

    /** Caller supplies resolved managed attachments; uploading is not a cache/model POST. */
    suspend fun createWithFiles(provider: ProviderConfig, system: String, toolsJson: String?, ttlSeconds: Long,
        contents: List<ContextMessage>, nativeWebSearch: Boolean,
        beforeExecute: suspend (GeminiCacheFiles) -> Unit,
        onFileTransfers: suspend (List<ProviderFileTransfer>) -> Unit): GeminiCachedContext = withContext(Dispatchers.IO) {
        validateConnection(provider)
        ttl(ttlSeconds)
        val files = ProviderFileClient(provider, http)
        try {
            val prepared = files.prepare(LlmRequest("cache-files", provider.protocol, provider.model, contents,
                toolsJson = toolsJson, nativeWebSearch = nativeWebSearch), onFileTransfers)
            val sources = files.cacheFiles().select(prepared.messages)
            create(provider, system, toolsJson, ttlSeconds, prepared.messages, nativeWebSearch,
                { beforeExecute(sources) }, sources)
        } finally { files.close() }
    }

    suspend fun create(provider: ProviderConfig, system: String, toolsJson: String?, ttlSeconds: Long,
        contents: List<ContextMessage> = emptyList(), nativeWebSearch: Boolean = false,
        beforeExecute: suspend () -> Unit, sourceFiles: GeminiCacheFiles? = null): GeminiCachedContext = withContext(Dispatchers.IO) {
        ttl(ttlSeconds)
        sourceFiles?.requireLifetime(ttlSeconds)
        if (provider.cachedContext != null) throw StError.Validation("cannot create from an already-bound cache")
        val prefix = buildJsonObject {
            if (system.isNotBlank()) put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
            })
            val definitions = ProtocolMessages.definitions(toolsJson, LlmProtocol.GEMINI_GENERATE_CONTENT)
            if (!definitions.isNullOrEmpty() || nativeWebSearch) put("tools", buildJsonArray {
                if (!definitions.isNullOrEmpty()) add(buildJsonObject { put("functionDeclarations", definitions) })
                if (nativeWebSearch) add(buildJsonObject { put("google_search", JsonObject(emptyMap())) })
            })
            if (contents.any { it.role == ChatRole.system }) throw StError.Validation("pass cached system separately from content history")
            if (contents.size > 4096) throw StError.Validation("cached history exceeds message budget")
            if (contents.isNotEmpty()) put("contents", ProtocolMessages.gemini(contents, provider))
        }
        if (prefix.isEmpty()) throw StError.Validation("cache requires a nonempty context prefix")
        val model = route(provider).requestModel(provider.model)
        val body = JsonObject(prefix + mapOf("model" to JsonPrimitive(model), "ttl" to JsonPrimitive("${ttlSeconds}s")))
        val metadata = metadata(exchange(provider, root(provider).addPathSegment("cachedContents").build(), "POST", body) {
            sourceFiles?.requireLifetime(ttlSeconds)
            beforeExecute()
            // Once the cache POST may execute, retain its sources even if its response is lost.
            sourceFiles?.retain()
        }, provider)
        if (!route(provider).matchesModel(metadata.model, provider.model)) throw StError.Provider("cache response model differs from requested model")
        val frozenOptions = ProviderHttpOptions(provider.httpOptions.authStyle, provider.httpOptions.headers,
            provider.httpOptions.credentialMaterials, provider.httpOptions.apiVersion)
        GeminiCachedContext(metadata, provider.copy(extra = emptyMap(), toolsJson = null, httpOptions = frozenOptions, cachePlan = null),
            prefix, sourceFiles?.detached())
    }

    suspend fun get(provider: ProviderConfig, name: String, beforeExecute: suspend () -> Unit): GeminiCacheMetadata =
        metadata(exchange(provider, resource(provider, name), "GET", null, beforeExecute), provider).also {
            if (it.name != name || !route(provider).matchesModel(it.model, provider.model)) throw StError.Provider("cache response identity differs from request")
        }

    suspend fun list(provider: ProviderConfig, pageSize: Int = 100, pageToken: String? = null,
        beforeExecute: suspend () -> Unit): GeminiCachePage {
        if (pageSize !in 1..1000 || pageToken?.let { it.isEmpty() || it.length > 2048 || it.any(Char::isISOControl) || provider.containsCredential(it) } == true)
            throw StError.Validation("invalid cache page")
        val url = root(provider).addPathSegment("cachedContents").addQueryParameter("pageSize", pageSize.toString())
            .apply { pageToken?.let { addQueryParameter("pageToken", it) } }.build()
        val result = exchange(provider, url, "GET", null, beforeExecute)
        val rows = result["cachedContents"]?.let { it as? JsonArray ?: invalid() } ?: JsonArray(emptyList())
        if (rows.size > pageSize) invalid()
        val token = text(result, "nextPageToken")?.takeIf(String::isNotEmpty)
        if (token?.let { it.length > 2048 || it.any(Char::isISOControl) || provider.containsCredential(it) } == true) invalid()
        val caches = rows.map { metadata(it as? JsonObject ?: invalid(), provider) }
        if (caches.map { it.name }.distinct().size != caches.size || token != null && token == pageToken) invalid()
        return GeminiCachePage(caches, token)
    }

    suspend fun updateTtl(provider: ProviderConfig, name: String, ttlSeconds: Long,
        beforeExecute: suspend () -> Unit): GeminiCacheMetadata {
        ttl(ttlSeconds)
        val url = resource(provider, name).newBuilder().addQueryParameter("updateMask", "ttl").build()
        return metadata(exchange(provider, url, "PATCH", buildJsonObject { put("ttl", "${ttlSeconds}s") }, beforeExecute), provider).also {
            if (it.name != name || !route(provider).matchesModel(it.model, provider.model)) throw StError.Provider("cache response identity differs from request")
        }
    }

    suspend fun delete(provider: ProviderConfig, name: String, beforeExecute: suspend () -> Unit) {
        exchange(provider, resource(provider, name), "DELETE", null, beforeExecute)
    }

    suspend fun updateExpireTime(provider: ProviderConfig, name: String, expireTime: String,
        beforeExecute: suspend () -> Unit): GeminiCacheMetadata {
        val at = if (expireTime.length <= 64) runCatching { OffsetDateTime.parse(expireTime).toInstant() }.getOrNull() else null
        val now = Instant.now()
        if (at == null || !at.isAfter(now) || at.isAfter(now.plusSeconds(604_800)))
            throw StError.Validation("cache expiration must be future and within the local seven-day budget")
        val url = resource(provider, name).newBuilder().addQueryParameter("updateMask", "expireTime").build()
        return metadata(exchange(provider, url, "PATCH", buildJsonObject { put("expireTime", expireTime) }, beforeExecute), provider).also {
            if (it.name != name || !route(provider).matchesModel(it.model, provider.model)) throw StError.Provider("cache response identity differs from request")
        }
    }

    suspend fun updateTtl(provider: ProviderConfig, cache: GeminiCachedContext, ttlSeconds: Long,
        beforeExecute: suspend () -> Unit): GeminiCachedContext {
        cache.requireOwner(provider)
        return cache.updated(updateTtl(provider, cache.metadata.name, ttlSeconds, beforeExecute))
    }

    suspend fun delete(provider: ProviderConfig, cache: GeminiCachedContext, beforeExecute: suspend () -> Unit) {
        cache.requireOwner(provider)
        delete(provider, cache.metadata.name, beforeExecute)
    }

    suspend fun updateExpireTime(provider: ProviderConfig, cache: GeminiCachedContext, expireTime: String,
        beforeExecute: suspend () -> Unit): GeminiCachedContext {
        cache.requireOwner(provider)
        return cache.updated(updateExpireTime(provider, cache.metadata.name, expireTime, beforeExecute))
    }

    private fun root(provider: ProviderConfig): HttpUrl.Builder {
        if (provider.protocol != LlmProtocol.GEMINI_GENERATE_CONTENT) throw StError.Unsupported("cachedContents requires native Gemini")
        val route = route(provider)
        if (provider.httpOptions.apiVersion != null || route.isVertex &&
            ProviderHttpOptions.normalizeAuth(provider.httpOptions.authStyle, provider.protocol) != "bearer")
            throw StError.Unsupported("Vertex cachedContents requires Bearer authentication and its configured API version")
        return route.collectionRoot.toHttpUrlOrNull()!!.newBuilder()
    }
    private fun route(provider: ProviderConfig) = GeminiCacheRoutes.select(provider.baseUrl)
    private fun resource(provider: ProviderConfig, name: String): HttpUrl {
        if (!route(provider).validResource(name) || provider.containsCredential(name))
            throw StError.Validation("invalid cached-content resource name")
        return root(provider).addPathSegment("cachedContents").addPathSegment(name.substringAfterLast('/')).build()
    }
    private fun ttl(seconds: Long) { if (seconds !in 1..604_800) throw StError.Validation("cache TTL exceeds local seven-day budget") }
    private fun metadata(obj: JsonObject, provider: ProviderConfig): GeminiCacheMetadata {
        val name = text(obj, "name") ?: invalid()
        resource(provider, name)
        val model = text(obj, "model") ?: invalid()
        if (!route(provider).validModel(model)) invalid()
        fun time(key: String): String? = text(obj, key)?.also {
            if (it.length > 64 || runCatching { OffsetDateTime.parse(it).toInstant() }.isFailure) invalid()
        }
        val count = (obj["usageMetadata"] as? JsonObject)?.get("totalTokenCount")?.let {
            (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.longOrNull?.takeIf { n -> n >= 0 } ?: invalid()
        }
        val value = GeminiCacheMetadata(name, model, time("expireTime") ?: invalid(), count, time("createTime"), time("updateTime"))
        if (provider.containsCredential(listOfNotNull(value.name, value.model, value.expireTime, value.createTime, value.updateTime).joinToString("\n"))) invalid()
        return value
    }
    private suspend fun exchange(provider: ProviderConfig, url: HttpUrl, method: String, body: JsonObject?,
        beforeExecute: suspend () -> Unit): JsonObject = withContext(Dispatchers.IO) {
        val raw = body?.toString()
        if ((raw?.toByteArray(Charsets.UTF_8)?.size ?: 0) > 1_048_576) throw StError.Validation("cached context exceeds 1 MiB request budget")
        val request = Request.Builder().url(provider.httpOptions.requestUrl(url, provider.protocol, provider.apiKey))
            .method(method, raw?.toRequestBody("application/json".toMediaType())).header("Accept", "application/json")
        provider.httpOptions.requestHeaders(provider.protocol, provider.apiKey).forEach { (name, value) -> request.header(name, value) }
        val call = http.newCall(request.build())
        coroutineScope {
            val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                currentCoroutineContext().ensureActive()
                beforeExecute() // A future durable operation ledger must checkpoint here; failure prevents HTTP.
                currentCoroutineContext().ensureActive()
                call.execute().use { response ->
                    currentCoroutineContext().ensureActive()
                    if (!response.isSuccessful) throw StError.Provider("Gemini cache HTTP ${response.code}")
                    val source = response.body?.source() ?: throw StError.Provider("empty Gemini cache response")
                    if (source.request(262_145) && source.buffer.size > 262_144) throw StError.Provider("Gemini cache response exceeds budget")
                    val bytes = source.readByteArray()
                    val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString() } catch (_: Exception) { invalid() }
                    if (text.isBlank() && method == "DELETE") JsonObject(emptyMap())
                    else try { json.parseToJsonElement(text) as? JsonObject ?: invalid() } catch (_: Exception) { invalid() }
                }
            } finally { watcher.cancel() }
        }
    }
    private fun text(obj: JsonObject, key: String): String? = obj[key]?.takeIf { it != JsonNull }?.let {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid()
    }
    private fun invalid(): Nothing = throw StError.Provider("invalid Gemini cache response")
}
