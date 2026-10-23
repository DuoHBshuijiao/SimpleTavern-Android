package com.simpletavern.core.llm

import com.simpletavern.core.model.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** One explicit read of the chosen connection; never installs prices or changes a user's model. */
class ProviderModelClient(http: OkHttpClient = OkHttpClient(), private val json: Json = Json { ignoreUnknownKeys = true }) {
    private val http = http.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .addNetworkInterceptor(NoHttpReplayInterceptor()).connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()

    suspend fun list(provider: ProviderConfig, pageToken: String? = null, pageSize: Int = 100,
        kind: ProviderModelDirectoryKind = ProviderModelDirectoryKind.MODELS): ProviderModelPage =
        withContext(Dispatchers.IO) {
            val presetId = provider.presetId ?: throw StError.Validation("model listing requires an explicit preset")
            if (pageSize !in 1..1000 || pageToken?.let { it.isBlank() || it.length > 8192 || it.any(Char::isISOControl) } == true)
                throw StError.Validation("invalid provider model list page")
            if (provider.codexBackend) throw StError.Unsupported("Codex requires its dedicated model directory adapter")
            val base = provider.baseUrl.toHttpUrlOrNull() ?: throw StError.Validation("invalid provider URL")
            val bedrock = provider.bedrockBackend || provider.protocol == LlmProtocol.BEDROCK_CONVERSE ||
                provider.awsCredentials != null || ProviderHttpOptions.normalizeAuth(provider.httpOptions.authStyle, provider.protocol) == "aws_sigv4"
            val vertex = base.host == "aiplatform.googleapis.com" || base.host.endsWith("-aiplatform.googleapis.com")
            if (!bedrock && kind != ProviderModelDirectoryKind.MODELS)
                throw StError.Unsupported("inference profile listing requires the Bedrock adapter")
            val gemini = provider.protocol == LlmProtocol.GEMINI_GENERATE_CONTENT
            val openAi = !bedrock && !vertex && provider.protocol in setOf(LlmProtocol.OPENAI_COMPATIBLE, LlmProtocol.OPENAI_RESPONSES)
            val unpaginated = openAi || bedrock && kind == ProviderModelDirectoryKind.MODELS
            if (unpaginated && pageToken != null) throw StError.Unsupported("this model list endpoint has no standard pagination")
            if (pageToken != null && provider.containsCredential(pageToken))
                throw StError.Validation("model list page token contains credential material")
            val request = when {
                bedrock -> bedrockRequest(provider, base, kind, pageToken, pageSize)
                vertex -> vertexRequest(provider, base, pageToken, pageSize)
                else -> {
                    val root = ProviderEndpoints.operation(provider, if (gemini) "v1beta" else "v1", "models")
                    val url = provider.httpOptions.requestUrl(root, provider.protocol, provider.apiKey).newBuilder().apply {
                        if (gemini) {
                            addQueryParameter("pageSize", pageSize.toString())
                            pageToken?.let { addQueryParameter("pageToken", it) }
                        } else if (!openAi) {
                            addQueryParameter("limit", pageSize.toString())
                            pageToken?.let { addQueryParameter("after_id", it) }
                        }
                    }.build()
                    buildRequest(provider, url)
                }
            }
            val parsed = exchange(request) { root -> when {
                provider.chatgptSubscription -> ProviderModelResponses.chatgpt(root)
                bedrock -> ProviderModelResponses.bedrock(root, kind)
                vertex -> ProviderModelResponses.vertex(root)
                else -> ProviderModelResponses.parse(provider.protocol, root)
            } }
            val result = ProviderModelPage(presetId, provider.protocol, parsed.models, Instant.now().toString(), parsed.nextPageToken,
                warnings = listOf("remote_listing_not_generation_capability_proof", "prices_not_installed") +
                    (if (unpaginated) listOf("unpaginated_endpoint") else emptyList()) +
                    (if (vertex) listOf("vertex_publisher_directory_not_project_deployments") else emptyList()), kind = kind)
            if (provider.containsCredential(result.toString())) throw StError.Provider("model list response contains credential material")
            result
        }

    private fun buildRequest(provider: ProviderConfig, url: HttpUrl): Request {
        val builder = Request.Builder().url(url).get().header("Accept", "application/json")
        provider.httpOptions.requestHeaders(provider.protocol, provider.apiKey).forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private fun bedrockRequest(provider: ProviderConfig, base: HttpUrl, kind: ProviderModelDirectoryKind,
        pageToken: String?, pageSize: Int): Request {
        if (ProviderHttpOptions.normalizeAuth(provider.httpOptions.authStyle, provider.protocol) != "aws_sigv4")
            throw StError.Unsupported("Bedrock model directory requires AWS SigV4 credentials")
        val credentials = provider.awsCredentials ?: throw StError.Validation("AWS credentials are missing")
        credentials.validate()
        val host = Regex("bedrock-runtime\\.([a-z]{2}(?:-[a-z0-9]+)+-[0-9]+)\\.(amazonaws\\.com(?:\\.cn)?)")
            .matchEntire(base.host) ?: throw StError.Validation("Bedrock model directory requires its regional runtime root")
        if (base.scheme != "https" || base.port != 443 || base.username.isNotEmpty() || base.password.isNotEmpty() ||
            base.encodedPath != "/" || base.query != null || base.fragment != null || provider.httpOptions.apiVersion != null)
            throw StError.Validation("invalid Bedrock directory connection")
        val profiles = kind == ProviderModelDirectoryKind.BEDROCK_INFERENCE_PROFILES
        val url = base.newBuilder().host("bedrock.${host.groupValues[1]}.${host.groupValues[2]}")
            .addPathSegment(if (profiles) "inference-profiles" else "foundation-models").apply {
                if (profiles) {
                    addQueryParameter("maxResults", pageSize.toString())
                    pageToken?.let { addQueryParameter("nextToken", it) }
                }
            }.build()
        return AwsSigV4.bedrock(buildRequest(provider, url), byteArrayOf(), credentials)
    }

    private fun vertexRequest(provider: ProviderConfig, base: HttpUrl, pageToken: String?, pageSize: Int): Request {
        if (base.scheme != "https" || base.port != 443 || base.username.isNotEmpty() || base.password.isNotEmpty() ||
            base.query != null || base.fragment != null || provider.httpOptions.apiVersion != null)
            throw StError.Validation("invalid Vertex directory connection")
        if (ProviderHttpOptions.normalizeAuth(provider.httpOptions.authStyle, provider.protocol) != "bearer" || provider.apiKey.isBlank())
            throw StError.Unsupported("Vertex model directory requires an IAM Bearer token")
        // Model Garden listing is v1beta1; the user's generation API version and project route stay unchanged.
        val url = base.newBuilder().encodedPath("/v1beta1/publishers/google/models")
            .addQueryParameter("pageSize", pageSize.toString())
            .addQueryParameter("view", "PUBLISHER_MODEL_VIEW_BASIC")
            .apply { pageToken?.let { addQueryParameter("pageToken", it) } }.build()
        return buildRequest(provider, url)
    }

    private suspend fun exchange(request: Request, decode: (JsonObject) -> ProviderModelResponses.Parsed): ProviderModelResponses.Parsed = coroutineScope {
        val call = http.newCall(request)
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                if (!response.isSuccessful) throw StError.Provider("model list HTTP ${response.code}")
                val body = response.body ?: throw StError.Provider("empty model list response")
                if (body.contentLength() > MAX_RESPONSE_BYTES) throw StError.Provider("model list response exceeds budget")
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > MAX_RESPONSE_BYTES - output.size()) throw StError.Provider("model list response exceeds budget")
                        output.write(buffer, 0, count)
                    }
                }
                val root = json.parseToJsonElement(output.toString(Charsets.UTF_8.name())) as? JsonObject
                    ?: throw StError.Provider("invalid model list response")
                decode(root)
            }
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw StError.Provider("model list request failed")
        } catch (_: SerializationException) {
            throw StError.Provider("invalid model list response")
        } finally { watcher.cancel() }
    }

    private companion object { const val MAX_RESPONSE_BYTES = 2_097_152 }
}
