package com.simpletavern.core.llm

import com.simpletavern.core.model.*
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/** One generation session owns these uploads. No remote handles survive into chat history. */
internal class ProviderFileClient(private val provider: ProviderConfig, http: OkHttpClient) {
    private val http = http.newBuilder().writeTimeout(600, TimeUnit.SECONDS).callTimeout(600, TimeUnit.SECONDS).build()
    private val cleanupHttp = http.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
    private val states = mutableListOf<ProviderFileTransfer>()
    private class Owned(val stateIndex: Int, val name: String, val expiresAt: Instant) { var cacheRetained = false }
    private val owned = mutableListOf<Owned>()
    private data class Uploaded(val value: String, val owner: Owned)
    private val uploaded = mutableMapOf<ProviderFileIdentity, Uploaded>()
    private var changed: suspend (List<ProviderFileTransfer>) -> Unit = {}

    suspend fun prepare(request: LlmRequest, onChanged: suspend (List<ProviderFileTransfer>) -> Unit): LlmRequest {
        changed = onChanged
        if (states.isNotEmpty()) changed(states.toList())
        // Tool/generated supplements keep their existing "saved but not decoded" behavior.
        // Unsupported user input fails before any file is uploaded.
        val eligible = request.messages.map { message -> when (message.role) {
            ChatRole.tool -> MediaContent.tool(message, provider.protocol).media
            ChatRole.assistant -> MediaContent.generated(message, provider.protocol).media
            else -> message.media
        } }.flatten()
        val selected = eligible.filter { it.uploadFile != null }.distinctBy { it.uploadFile!!.path }
        if (selected.isEmpty()) return request
        if (provider.bedrockBackend) throw StError.Unsupported("Bedrock input files must use supported inline content; provider uploads are not supported")
        if (provider.codexBackend) throw StError.Unsupported("Codex backend file uploads require a separate adapter")
        selected.forEach { media ->
            if (!MediaContent.uploadSupported(provider.protocol, media.mimeType))
                throw StError.Unsupported("${provider.protocol} does not support uploaded ${media.mimeType}")
        }
        if (provider.protocol in setOf(LlmProtocol.OPENAI_COMPATIBLE, LlmProtocol.OPENAI_RESPONSES)) {
            val fileBytes = eligible.filter {
                it.mimeType == "application/pdf" || it.uploadFile != null
            }.sumOf { it.byteSize.toLong() }
            if (fileBytes >= 50_000_000) throw StError.Validation("OpenAI file inputs must total less than 50 MB")
        }
        provider.httpOptions.requestHeaders(provider.protocol, provider.apiKey)
        val files = ProviderEndpoints.operation(provider, if (provider.protocol == LlmProtocol.GEMINI_GENERATE_CONTENT) "v1beta" else "v1", "files")
        if (provider.protocol == LlmProtocol.GEMINI_GENERATE_CONTENT) ProviderEndpoints.geminiUpload(provider)
        val active = mutableMapOf<String, String>()
        for (media in selected) {
            currentCoroutineContext().ensureActive()
            val file = media.uploadFile!!
            val key = ProviderFileIdentity(media.attachmentId, file.path, media.sourceMimeType ?: media.mimeType,
                media.byteSize, file.lastModified())
            val previous = uploaded[key]?.takeIf { Instant.now().plusSeconds(60).isBefore(it.owner.expiresAt) }
            if (previous != null) {
                active[file.path] = previous.value
                val index = previous.owner.stateIndex
                states[index] = states[index].copy(status = "reused", reuseCount = states[index].reuseCount + 1)
                changed(states.toList())
                continue
            }
            val index = states.size
            states += ProviderFileTransfer(media.attachmentId, media.filename, media.byteSize.toLong(), "uploading")
            changed(states.toList())
            try {
                val remote = if (provider.protocol == LlmProtocol.GEMINI_GENERATE_CONTENT) gemini(media, index)
                    else multipart(files, media, index)
                uploaded[key] = Uploaded(remote, owned.last())
                active[file.path] = remote
                update(index, "ready")
            } catch (failure: Exception) {
                if (owned.none { it.stateIndex == index }) states[index] = states[index].copy(status = "upload_unconfirmed")
                throw failure
            }
        }
        return request.copy(messages = request.messages.map { message -> message.copy(media = message.media.map { media ->
            active[media.uploadFile?.path]?.let { media.copy(providerFile = it) } ?: media
        }) })
    }

    private suspend fun multipart(url: HttpUrl, media: ContextMedia, index: Int): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            if (provider.protocol == LlmProtocol.ANTHROPIC_MESSAGES) addFormDataPart("expires_in_seconds", "3600")
            else {
                addFormDataPart("purpose", if (media.mimeType in AttachmentMimeTypes.images) "vision" else "user_data")
                addFormDataPart("expires_after[anchor]", "created_at")
                addFormDataPart("expires_after[seconds]", "3600")
            }
            addFormDataPart("file", media.filename, fileBody(media))
        }.build()
        return exchange(builder(url).post(body).build()) { response ->
            val value = json(response)
            val id = value.string("id") ?: throw StError.Provider("uploaded file ID missing")
            if (id.isBlank()) throw StError.Provider("uploaded file ID missing")
            // Bind inside response processing, before coroutine cancellation can discard a return value.
            owned += Owned(index, id, expiry(value["expires_at"], 3600))
            id
        }
    }

    private suspend fun gemini(media: ContextMedia, index: Int): String {
        val start = builder(ProviderEndpoints.geminiUpload(provider))
            .header("X-Goog-Upload-Protocol", "resumable").header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", media.byteSize.toString())
            .header("X-Goog-Upload-Header-Content-Type", uploadMime(media))
            .post(buildJsonObject { put("file", buildJsonObject { put("display_name", media.filename) }) }
                .toString().toRequestBody("application/json".toMediaType())).build()
        val uploadUrl = exchange(start) { response ->
            response.header("X-Goog-Upload-URL")?.toHttpUrlOrNull() ?: throw StError.Provider("upload session URL missing")
        }
        val base = start.url
        val sameOrigin = uploadUrl.scheme == base.scheme && uploadUrl.host == base.host && uploadUrl.port == base.port
        if ((!sameOrigin && uploadUrl.scheme != "https") || uploadUrl.username.isNotEmpty() || uploadUrl.password.isNotEmpty())
            throw StError.Provider("invalid upload session URL")
        // A resumable URL is an upstream capability, not another configured API endpoint.
        // Do not send configured credentials/custom headers to a different origin.
        val sessionUrl = if (sameOrigin && ProviderHttpOptions.normalizeAuth(provider.httpOptions.authStyle, provider.protocol) == "query_key" &&
            provider.apiKey.isNotBlank() && uploadUrl.queryParameterNames.none { it.equals("key", true) })
            uploadUrl.newBuilder().addQueryParameter("key", provider.apiKey).build() else uploadUrl
        val upload = Request.Builder().url(sessionUrl).apply {
            if (sameOrigin) provider.httpOptions.requestHeaders(provider.protocol, provider.apiKey).forEach { (key, value) -> header(key, value) }
        }.header("X-Goog-Upload-Offset", "0").header("X-Goog-Upload-Command", "upload, finalize")
            .post(fileBody(media)).build()
        var value = exchange(upload) { response ->
            val file = json(response)["file"] as? JsonObject ?: throw StError.Provider("uploaded file metadata missing")
            val name = file.string("name") ?: throw StError.Provider("uploaded file name missing")
            if (!name.startsWith("files/") || name.removePrefix("files/").isBlank() || name.count { it == '/' } != 1)
                throw StError.Provider("invalid uploaded file name")
            owned += Owned(index, name, expiry(file["expirationTime"] ?: file["expiration_time"], 48 * 3600))
            file
        }
        val name = owned.last().name
        val resource = ProviderEndpoints.operation(provider, "v1beta", "files", name.removePrefix("files/"))
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
        while (value.string("state") != "ACTIVE") {
            when (value.string("state")) {
                "PROCESSING" -> Unit
                "FAILED" -> throw StError.Provider("uploaded file processing failed")
                else -> throw StError.Provider("uploaded file state missing or unsupported")
            }
            if (System.nanoTime() >= deadline) throw StError.Provider("uploaded file processing timed out")
            update(index, "processing")
            delay(2000)
            value = exchange(builder(resource).get().build()) { json(it) }
        }
        return value.string("uri") ?: throw StError.Provider("uploaded file URI missing")
    }

    private fun uploadMime(media: ContextMedia) = if (AttachmentMimeTypes.isText(media.mimeType)) "text/plain"
        else media.sourceMimeType ?: media.mimeType

    private suspend fun fileBody(media: ContextMedia): RequestBody {
        val context = currentCoroutineContext()
        val file = media.uploadFile!!
        return object : RequestBody() {
            override fun contentType() = uploadMime(media).toMediaType()
            override fun contentLength() = media.byteSize.toLong()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                file.inputStream().use { input ->
                    var sent = 0L
                    val buffer = ByteArray(65536)
                    while (true) {
                        context.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        sent += read
                        if (sent > media.byteSize) throw IOException("managed file size changed")
                        sink.write(buffer, 0, read)
                    }
                    if (sent != media.byteSize.toLong()) throw IOException("managed file size changed")
                }
            }
        }
    }

    private fun builder(url: HttpUrl) = Request.Builder()
        .url(provider.httpOptions.requestUrl(url, provider.protocol, provider.apiKey))
        .header("Accept", "application/json").apply {
            provider.httpOptions.requestHeaders(provider.protocol, provider.apiKey).forEach { (name, value) -> header(name, value) }
        }

    private suspend fun update(index: Int, status: String) {
        states[index] = states[index].copy(status = status)
        changed(states.toList())
    }

    fun cacheFiles(): GeminiCacheFiles = GeminiCacheFiles(uploaded.entries.associate { (key, value) ->
        value.value to GeminiCacheFile(key, value.owner.expiresAt)
    }) { uris ->
        uploaded.values.filter { it.value in uris }.forEach { it.owner.cacheRetained = true }
    }

    /** Only files created by this session, using its credential snapshot. Local files are untouched. */
    suspend fun close() = withContext(NonCancellable) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        for (file in owned.asReversed()) {
            // Files may still back a live or outcome-unknown CachedContent. Keep them until
            // their upstream expiration instead of assuming deletion leaves the cache usable.
            if (file.cacheRetained && Instant.now().isBefore(file.expiresAt)) {
                states[file.stateIndex] = states[file.stateIndex].copy(status = "cache_retained", retainedUntil = file.expiresAt.toString())
                continue
            }
            var code: Int? = null
            val deleted = if (System.nanoTime() >= deadline) false else try {
                val parts = if (provider.protocol == LlmProtocol.GEMINI_GENERATE_CONTENT)
                    arrayOf("files", file.name.removePrefix("files/")) else arrayOf("files", file.name)
                val url = ProviderEndpoints.operation(provider, if (provider.protocol == LlmProtocol.GEMINI_GENERATE_CONTENT) "v1beta" else "v1", *parts)
                exchange(builder(url).delete().build(), cleanup = true) { response ->
                    code = response.code
                    response.isSuccessful || response.code == 404
                }
            } catch (_: Exception) { false }
            states[file.stateIndex] = states[file.stateIndex].copy(status = if (deleted) "deleted" else "delete_failed", cleanupHttpCode = code)
        }
        if (states.isNotEmpty()) changed(states.toList())
        uploaded.clear()
        owned.clear()
    }

    private suspend fun <T> exchange(request: Request, cleanup: Boolean = false, consume: (Response) -> T): T = coroutineScope {
        val call = (if (cleanup) cleanupHttp else http).newCall(request)
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                if (!cleanup && !response.isSuccessful) throw StError.Provider("file operation HTTP ${response.code}")
                consume(response)
            }
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            // Resumable URLs can carry upload-session tokens, so never expose transport messages.
            throw StError.Provider("file transfer connection failed")
        } finally { watcher.cancel() }
    }

    private fun json(response: Response): JsonObject {
        val source = response.body?.source() ?: throw StError.Provider("file response missing")
        if (source.request(262_145)) throw StError.Provider("file metadata response exceeds 256 KiB")
        return try { Json.parseToJsonElement(source.readUtf8()).jsonObject }
            catch (_: Exception) { throw StError.Provider("invalid file metadata response") }
    }
    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun expiry(value: JsonElement?, requestedSeconds: Int): Instant {
        val raw = (value as? JsonPrimitive)?.contentOrNull
        return runCatching {
            raw?.toLongOrNull()?.let(Instant::ofEpochSecond) ?: raw?.let { OffsetDateTime.parse(it).toInstant() }
        }.getOrNull() ?: Instant.now().plusSeconds(requestedSeconds.toLong())
    }
}
