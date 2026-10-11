package com.simpletavern.core.tools

import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.llm.NoHttpReplayInterceptor
import com.simpletavern.core.model.*
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

class SpeechDirectorySession internal constructor(private val run: suspend () -> SpeechDirectory) {
    suspend fun resolve() = run()
    override fun toString() = "SpeechDirectorySession(configuration=<private>, credentials=<redacted>)"
}

/** Explicit, caller-owned catalog reads. No synthesis ledger, mutation, URL preview fetch or fallback. */
class SpeechDirectoryService(private val speech: SpeechService) {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .addNetworkInterceptor(NoHttpReplayInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).build()
    private val slots = Semaphore(2)

    fun prepare(selection: SpeechDirectorySelection, config: JsonObject, secret: String?, request: SpeechDirectoryRequest): SpeechDirectorySession {
        val endpoint = speech.endpoint(selection.provider, selection.baseUrl).toHttpUrl()
        if (!secret.isNullOrBlank() && listOfNotNull(selection.presetId, selection.assistantId, selection.credentialId,
                selection.sourceNamespace, selection.baseUrl).any { it.contains(secret) })
            throw StError.Validation("TTS catalog selection contains credential material")
        fun text(value: String, id: Boolean = false): String {
            SpeechText.validate(value, if (id) 2048 else 4096)
            if (value.isBlank() || value.any(Char::isISOControl) || id && value.length > 512 ||
                !secret.isNullOrBlank() && value.contains(secret)) throw StError.Validation("invalid TTS catalog metadata")
            return value
        }
        val configuredModels = buildList {
            config.text("model")?.let { add(SpeechModelInfo(text(it, true), text(it), "configured")) }
            config.array("models", required = false)?.also { if (it.size > 1000) throw StError.Validation("too many configured TTS models") }
                ?.forEach { item ->
                    val id = (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw StError.Validation("TTS models must be text")
                    add(SpeechModelInfo(text(id, true), text(id), "configured"))
                }
        }.distinct()
        val configuredVoices = config.array("voiceCatalog", required = false)?.also {
            if (it.size > 1000) throw StError.Validation("too many configured TTS voices")
        }?.map { item ->
            val obj = item as? JsonObject ?: throw StError.Validation("TTS voice catalog entry must be an object")
            val id = text(obj.text("voiceId") ?: throw StError.Validation("TTS voice ID missing"), true)
            SpeechVoiceInfo(id, text(obj.text("name") ?: id), text(obj.text("voiceType") ?: "system", true), "configured",
                obj.text("model")?.let { text(it, true) })
        }.orEmpty()
        // This is the desktop's model-specific static catalog, NOT an account/server result.
        val builtins = if (selection.provider == "siliconflow" && configuredModels.any { it.model == COSYVOICE2 })
            listOf("alex", "anna", "bella", "benjamin", "charles", "claire", "david", "diana").map {
                SpeechVoiceInfo("$COSYVOICE2:$it", "CosyVoice2 $it", "system", "desktop_builtin", COSYVOICE2)
            } else emptyList()
        val localVoices = voices(configuredVoices + builtins)
        val localWarnings = buildList {
            add("configured_catalog_not_live_validated")
            if (builtins.isNotEmpty()) add("desktop_builtin_catalog_not_live_validated")
            if (selection.provider == "glm") add("glm_voice_list_desktop_contract_unverified")
            if (selection.provider == "siliconflow") add("siliconflow_system_catalog_not_complete")
        }
        return SpeechDirectorySession {
            var allVoices = localVoices
            var allModels = configuredModels
            suspend fun fetch(enabled: Boolean, supported: Boolean, block: suspend () -> List<String>): SpeechDirectoryFetch {
                currentCoroutineContext().ensureActive()
                if (!enabled) return SpeechDirectoryFetch()
                if (!supported) return SpeechDirectoryFetch("unsupported", errorCode = "tts_directory_unsupported")
                return try {
                    if (secret.isNullOrBlank()) throw StError.Speech("tts_not_configured", "TTS credential missing")
                    if (secret.length > 8192 || secret.any { it.code !in 33..126 })
                        throw StError.Speech("tts_invalid_credential", "TTS credential is invalid")
                    val warnings = block()
                    currentCoroutineContext().ensureActive()
                    SpeechDirectoryFetch("completed", Instant.now().toString(), warnings = warnings)
                } catch (error: StError.Speech) {
                    currentCoroutineContext().ensureActive()
                    SpeechDirectoryFetch("failed", errorCode = error.code)
                }
            }
            val voiceFetch = fetch(request.fetchVoices, selection.provider in setOf("minimax", "glm", "siliconflow", "elevenlabs")) {
                if (selection.provider == "elevenlabs") {
                    val remote = elevenLabsVoices(endpoint, secret!!, ::text)
                    allVoices = try { voices(localVoices + remote) } catch (_: StError.Validation) { throw invalid() }
                    return@fetch listOf("voice_catalog_not_synthesis_availability_proof")
                }
                val path = when (selection.provider) { "minimax" -> "/v1/get_voice"; "glm" -> "/api/paas/v4/voice/list"
                    else -> "/v1/audio/voice/list" }
                val builder = Request.Builder().url(endpoint.newBuilder().encodedPath(path).build())
                if (selection.provider == "minimax") builder.post("{\"voice_type\":\"all\"}".toRequestBody("application/json".toMediaType()))
                val json = read(builder, secret!!, selection.provider)
                val warnings = mutableListOf<String>()
                val remote = try {
                    fun voice(id: String, name: String?, type: String, model: String? = null) =
                        SpeechVoiceInfo(text(id, true), text(name ?: id), text(type, true), "remote", model?.let { text(it, true) })
                    when (selection.provider) {
                        "minimax" -> {
                            if ((json["base_resp"] as? JsonObject)?.get("status_code") != JsonPrimitive(0))
                                throw StError.Speech("tts_provider_error", "MiniMax rejected catalog request")
                            val container = if (listOf("system_voice", "voice_cloning", "voice_generation", "voice_list").any { it in json }) json
                                else json["data"] as? JsonObject ?: throw invalid()
                            val categories = listOf("system_voice" to "system", "voice_cloning" to "voice_cloning", "voice_generation" to "voice_generation")
                            warnings += "minimax_inactive_clones_may_be_absent"
                            if (categories.none { it.first in container }) {
                                warnings += "legacy_voice_list_shape"
                                objects(container.array("voice_list")!!).map { item ->
                                    voice(item.text("voice_id") ?: throw invalid(), item.text("voice_name") ?: item.text("name"), item.text("voice_type") ?: "unclassified")
                                }
                            } else buildList {
                                categories.forEach { (key, type) ->
                                    val array = container.array(key, required = false)
                                    if (array == null) warnings += "minimax_category_unreported_$key"
                                    else objects(array).forEach { item ->
                                        val description = item.array("description", required = false)?.firstOrNull()?.let {
                                            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw invalid()
                                        }?.takeIf { it.isNotBlank() }
                                        add(voice(item.text("voice_id") ?: throw invalid(), item.text("voice_name") ?: item.text("name") ?: description, type))
                                    }
                                }
                            }
                        }
                        "glm" -> {
                            warnings += "glm_voice_list_desktop_contract_unverified"
                            val container = if ("voice_list" in json) json else json["data"] as? JsonObject ?: throw invalid()
                            objects(container.array("voice_list")!!).map { item ->
                                voice(item.text("voice") ?: throw invalid(), item.text("voice_name"), item.text("voice_type") ?: "unclassified")
                            }
                        }
                        else -> {
                            warnings += "siliconflow_user_voices_only"
                            objects(json.array("results")!!).map { item ->
                                voice(item.text("uri") ?: throw invalid(), item.text("customName"), "private", item.text("model"))
                            }
                        }
                    }.also { if (it.size > MAX_ITEMS) throw invalid() }
                } catch (error: StError.Speech) { throw error }
                catch (_: StError.Validation) { throw invalid() }
                val merged = try { voices(localVoices + remote) } catch (_: StError.Validation) { throw invalid() }
                allVoices = merged
                warnings
            }
            val modelFetch = fetch(request.fetchModels, selection.provider in setOf("openrouter", "siliconflow", "elevenlabs")) {
                if (selection.provider == "elevenlabs") {
                    val json = readValue(Request.Builder().url(endpoint.newBuilder().encodedPath("/v1/models").build()), secret!!, selection.provider)
                    val remote = try { ElevenLabsSpeechCatalog.models(json, ::text) } catch (_: StError.Validation) { throw invalid() }
                    if (remote.groupBy { it.model }.any { (_, group) -> group.distinct().size > 1 }) throw invalid()
                    allModels = (configuredModels + remote).distinct()
                    return@fetch listOf("model_catalog_not_synthesis_availability_proof")
                }
                val url = endpoint.newBuilder().encodedPath(if (selection.provider == "openrouter") "/api/v1/models" else "/v1/models")
                    .addQueryParameter(if (selection.provider == "openrouter") "output_modalities" else "type",
                        if (selection.provider == "openrouter") "speech" else "audio").build()
                val json = read(Request.Builder().url(url), secret!!, selection.provider)
                val remote = try {
                    objects(json.array("data")!!).map { item ->
                        val id = text(item.text("id") ?: throw invalid(), true)
                        val capability = if (selection.provider == "siliconflow") "audio_unclassified" else {
                            val architecture = item["architecture"]?.takeIf { it != JsonNull }?.let { it as? JsonObject ?: throw invalid() }
                            val modalities = architecture?.array("output_modalities", required = false)?.map {
                                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw invalid()
                            }.orEmpty()
                            if ("speech" in modalities) "provider_reported_speech" else "unverified"
                        }
                        SpeechModelInfo(id, text(item.text("name") ?: id), "remote", capability)
                    }
                } catch (error: StError.Speech) { throw error }
                catch (_: StError.Validation) { throw invalid() }
                if (remote.groupBy { it.model }.any { (_, group) -> group.distinct().size > 1 }) throw invalid()
                allModels = (configuredModels + remote).distinct()
                listOf(if (selection.provider == "siliconflow") "audio_models_include_non_tts" else "model_catalog_not_synthesis_availability_proof")
            }
            currentCoroutineContext().ensureActive()
            SpeechDirectory(selection, allVoices, allModels, voiceFetch, modelFetch, localWarnings)
        }
    }

    private suspend fun elevenLabsVoices(endpoint: okhttp3.HttpUrl, secret: String,
        text: (String, Boolean) -> String): List<SpeechVoiceInfo> {
        val collected = mutableListOf<SpeechVoiceInfo>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(100) {
            currentCoroutineContext().ensureActive()
            val url = endpoint.newBuilder().encodedPath("/v2/voices")
                .addQueryParameter("page_size", "100").addQueryParameter("include_total_count", "false")
            cursor?.let { url.addQueryParameter("next_page_token", it) }
            val page = try { ElevenLabsSpeechCatalog.voices(read(Request.Builder().url(url.build()), secret, "elevenlabs"), text) }
                catch (_: StError.Validation) { throw invalid() }
            if (collected.size + page.voices.size > MAX_ITEMS) throw tooLarge()
            collected += page.voices
            if (!page.hasMore) return collected
            val next = page.nextPageToken ?: throw invalid()
            if (!cursors.add(next)) throw invalid()
            cursor = next
        }
        throw tooLarge() // Do not publish an incomplete catalog as a successful full fetch.
    }

    private fun voices(values: List<SpeechVoiceInfo>): List<SpeechVoiceInfo> {
        if (values.size > MAX_ITEMS + 1008) throw StError.Validation("TTS voice catalog exceeds budget")
        if (values.groupBy { listOf(it.voiceId, it.model, it.voiceType, it.origin) }.any { (_, group) -> group.distinct().size > 1 })
            throw StError.Validation("TTS catalog contains conflicting duplicate voices")
        return values.distinct()
    }
    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it != JsonNull }?.let {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw StError.Validation("TTS catalog field must be text")
    }
    private fun JsonObject.array(key: String, required: Boolean = true): JsonArray? {
        val item = get(key)
        if (!required && (item == null || item == JsonNull)) return null
        return item as? JsonArray ?: throw StError.Validation("TTS catalog field must be an array")
    }
    private fun objects(array: JsonArray): List<JsonObject> {
        if (array.size > MAX_ITEMS) throw invalid()
        return array.map { it as? JsonObject ?: throw invalid() }
    }
    private suspend fun read(builder: Request.Builder, secret: String, provider: String): JsonObject =
        readValue(builder, secret, provider) as? JsonObject ?: throw invalid()

    private suspend fun readValue(builder: Request.Builder, secret: String, provider: String): JsonElement = withContext(Dispatchers.IO) {
        slots.withPermit { coroutineScope {
            currentCoroutineContext().ensureActive()
            val authenticated = if (provider == "elevenlabs") builder.header("xi-api-key", secret)
                else builder.header("Authorization", "Bearer $secret")
            val call = http.newCall(authenticated.header("Accept", "application/json").build())
            val cancel = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
            try { call.execute().use { response ->
                currentCoroutineContext().ensureActive()
                if (!response.isSuccessful) throw StError.Speech(when (response.code) { 401, 403 -> "tts_auth_failed"; 402, 429 -> "tts_quota"
                    in 300..399 -> "tts_redirect_refused"; else -> "tts_directory_http_error" }, "TTS catalog HTTP ${response.code}")
                val body = response.body ?: throw invalid()
                val type = body.contentType()
                if (type != null && (type.type != "application" || type.subtype != "json" && !type.subtype.endsWith("+json"))) throw invalid()
                if (body.contentLength() > MAX_BYTES) throw tooLarge()
                val source = body.source(); val buffer = Buffer()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer, minOf(65_536L, MAX_BYTES + 1 - buffer.size))
                    if (buffer.size > MAX_BYTES) throw tooLarge()
                    if (count == -1L) break
                }
                val json = try { NativeJsonInput.parse(NativeJsonInput.utf8(buffer.readByteArray())) }
                    catch (_: StError.Validation) { throw invalid() }
                if ((json as? JsonObject)?.get("error")?.let { it != JsonNull } == true) throw StError.Speech("tts_provider_error", "TTS provider rejected catalog request")
                currentCoroutineContext().ensureActive()
                json
            } } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                throw StError.Speech(if (error is java.io.InterruptedIOException) "tts_directory_timeout" else "tts_directory_network_error",
                    "TTS catalog transport failed", mapOf("provider" to provider))
            } finally { cancel.cancel() }
        } }
    }
    private fun invalid() = StError.Speech("tts_directory_invalid_response", "TTS catalog response is invalid")
    private fun tooLarge() = StError.Speech("tts_directory_response_too_large", "TTS catalog response exceeds budget")
    private companion object {
        const val MAX_BYTES = 8_388_608L
        const val MAX_ITEMS = 10_000
        const val COSYVOICE2 = "FunAudioLLM/CosyVoice2-0.5B"
    }
}
