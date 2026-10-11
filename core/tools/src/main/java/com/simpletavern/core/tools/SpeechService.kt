package com.simpletavern.core.tools

import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.data.SpeechUsageStore
import com.simpletavern.core.llm.NoHttpReplayInterceptor
import com.simpletavern.core.model.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID
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

class SpeechSession(private val run: suspend (SpeechCallContext, suspend (SpeechPreprocessResult) -> Unit, (suspend (SpeechAudioChunk) -> Unit)?) -> SpeechAudio) {
    suspend fun synthesize(context: SpeechCallContext = SpeechCallContext(), onAudio: (suspend (SpeechAudioChunk) -> Unit)? = null,
        onPreprocessed: suspend (SpeechPreprocessResult) -> Unit = {}) = run(context, onPreprocessed, onAudio)
    override fun toString() = "SpeechSession(request=<private>, credentials=<redacted>)"
}

/** Vendor REST adapters. No downloaded audio URLs, redirect, fallback or automatic replay. */
class SpeechService(private val accounting: SpeechUsageStore) {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .addNetworkInterceptor(NoHttpReplayInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS).build()
    private val slots = Semaphore(2)
    private val localHttp = http.newBuilder().readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS).callTimeout(900, TimeUnit.SECONDS).build()

    fun endpoint(provider: String, base: String?): String {
        val default = when (provider) {
            "minimax" -> "https://api.minimaxi.com/v1"; "glm" -> "https://open.bigmodel.cn/api/paas/v4"
            "openrouter" -> "https://openrouter.ai/api/v1"; "siliconflow" -> "https://api.siliconflow.cn/v1"
            "openai" -> "https://api.openai.com/v1"; "groq" -> "https://api.groq.com/openai/v1"
            "gemini" -> "https://generativelanguage.googleapis.com/v1beta"; "elevenlabs" -> "https://api.elevenlabs.io"
            "qwen" -> "https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1"
            "xai" -> "https://api.x.ai/v1"; "fish-audio" -> "https://api.fish.audio"
            "step" -> "https://api.stepfun.com"; "mimo" -> "https://api.xiaomimimo.com/v1"
            "volcengine" -> "https://openspeech.bytedance.com"
            in SpeechProviders.local -> base?.takeIf { it.isNotBlank() }
                ?: throw StError.Validation("select an explicit local TTS gateway URL")
            else -> throw StError.Unsupported("TTS provider needs a separate adapter")
        }
        val supplied = base?.takeIf { it.isNotBlank() }
        if (provider == "qwen" && (supplied == null || supplied.contains("{WorkspaceId}", ignoreCase = true)))
            throw StError.Validation("replace Qwen {WorkspaceId} with the actual workspace ID")
        val url = runCatching { (supplied ?: default).toHttpUrl() }.getOrNull()
            ?: throw StError.Validation("invalid TTS endpoint")
        if (provider in SpeechProviders.local) {
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.encodedQuery != null || url.fragment != null)
                throw StError.Validation("local TTS gateway URL must not contain credentials, query or fragment")
            return url.toString()
        }
        if (provider !in setOf("minimax", "glm", "openrouter", "siliconflow")) {
            // Imported proxy endpoints are intentional configuration, not an official-host-only catalog.
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.encodedQuery != null || url.fragment != null)
                throw StError.Validation("TTS base URL must not contain credentials, query or fragment")
            val path = url.encodedPath.trimEnd('/')
            val suffix = when (provider) {
                "openai", "groq" -> "/audio/speech"
                "step" -> if (path.endsWith("/v1")) "/audio/speech" else "/v1/audio/speech"
                "xai" -> "/tts"; "fish-audio" -> if (path.endsWith("/v1")) "/tts" else "/v1/tts"
                "qwen" -> "/services/audio/tts/SpeechSynthesizer"
                "mimo" -> "/chat/completions"; "volcengine" -> "/api/v3/tts/unidirectional/sse"
                else -> ""
            }
            val target = if (suffix.isNotEmpty() && !path.endsWith(suffix) && !(provider == "step" && path.endsWith("/audio/speech")) &&
                !(provider == "fish-audio" && path.endsWith("/tts"))) "$path$suffix" else path
            return url.newBuilder().encodedPath(target.ifEmpty { "/" }).build().toString()
        }
        val hosts = when (provider) { "minimax" -> setOf("api.minimaxi.com", "api.minimax.cn", "api.minimax.io")
            "glm" -> setOf("open.bigmodel.cn"); "openrouter" -> setOf("openrouter.ai"); else -> setOf("api.siliconflow.cn") }
        if (url.scheme != "https" || url.host !in hosts || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedQuery != null || url.fragment != null) throw StError.Validation("TTS requires its selected official HTTPS origin")
        val path = url.encodedPath.trimEnd('/')
        val target = when (provider) {
            "minimax" -> { if (path !in setOf("", "/v1", "/v1/t2a_v2")) throw StError.Validation("invalid MiniMax TTS path"); "/v1/t2a_v2" }
            "glm" -> { if (path !in setOf("", "/api", "/api/paas/v4", "/api/paas/v4/audio/speech")) throw StError.Validation("invalid GLM TTS path"); "/api/paas/v4/audio/speech" }
            "openrouter" -> { if (path !in setOf("", "/api/v1", "/api/v1/audio/speech")) throw StError.Validation("invalid OpenRouter TTS path"); "/api/v1/audio/speech" }
            else -> { if (path !in setOf("", "/v1", "/v1/audio/speech")) throw StError.Validation("invalid SiliconFlow TTS path"); "/v1/audio/speech" }
        }
        return url.newBuilder().encodedPath(target).build().toString()
    }

    fun validate(provider: String, request: SpeechRequest): JsonObject = payload(provider, request, true)
    fun validateControls(provider: String, request: SpeechRequest) { payload(provider, request, false) }
    private fun payload(provider: String, request: SpeechRequest, includeText: Boolean): JsonObject {
        if (provider in SpeechProviders.local) return LocalSpeechRequests.payload(provider, request, includeText)
        val model = request.model ?: throw StError.Validation("select a TTS model")
        val voice = request.voiceId ?: throw StError.Validation("select a TTS voice")
        val geminiCurrent = provider == "gemini" && GeminiSpeechRequests.supportsStreaming(model)
        if (model.isBlank() || model.length > 512 || model.any(Char::isISOControl) || !SpeechProviders.validVoice(provider, voice))
            throw StError.Validation("invalid TTS model/voice")
        val options = request.providerOptions
        if (provider == "xai" && model != "xai-tts") throw StError.Unsupported("xAI TTS has no model selector; use the endpoint descriptor xai-tts")
        val text = request.text
        if (includeText && (text.isBlank() || text.codePointCount(0, text.length) > 10_000 || text.toByteArray(Charsets.UTF_8).size > 40_000 ||
            text.any { it.isISOControl() && it !in setOf('\n', '\r', '\t') })) throw StError.Validation("TTS text exceeds budget or contains control characters")
        if (includeText && provider == "glm" && text.codePointCount(0, text.length) > 1024) throw StError.Validation("GLM speech text exceeds 1024 characters; not truncated")
        if (includeText && provider == "minimax" && text.codePointCount(0, text.length) >= 10_000) throw StError.Validation("MiniMax speech text must contain fewer than 10000 characters")
        if (includeText && provider == "openai" && text.codePointCount(0, text.length) > 4096) throw StError.Validation("OpenAI speech text exceeds 4096 characters; not truncated")
        if (includeText && provider == "groq" && model.startsWith("canopylabs/orpheus-") && text.codePointCount(0, text.length) > 200)
            throw StError.Validation("Groq Orpheus speech text exceeds 200 characters; not truncated")
        if (includeText && provider == "step" && text.codePointCount(0, text.length) > 1000) throw StError.Validation("Step speech text exceeds 1000 characters; not truncated")
        val format = request.format ?: SpeechProviders.defaultFormat(provider)
        val gain = request.gain
        val sampleRate = request.sampleRate
        val formats = when (provider) { "minimax" -> setOf("mp3", "wav", "flac", "pcm"); "glm" -> setOf("wav")
            "siliconflow" -> setOf("mp3", "opus", "wav", "pcm"); "openrouter", "openai" -> setOf("mp3", "opus", "wav", "pcm", "flac", "aac")
            "groq", "gemini" -> setOf("wav"); "elevenlabs" -> setOf("mp3")
            "qwen", "fish-audio" -> setOf("wav", "mp3", "pcm", "opus")
            "step" -> setOf("wav", "mp3", "pcm", "opus", "flac")
            "xai", "volcengine" -> setOf("mp3"); "mimo" -> setOf("wav")
            else -> throw StError.Unsupported("TTS provider needs a separate adapter") }
        if (format !in formats) throw StError.Unsupported("TTS format is not supported by this adapter")
        val range = if (provider in setOf("minimax", "glm", "step")) 0.5..2.0 else 0.25..4.0
        if (!request.speed.isFinite() || request.speed !in range || !request.volume.isFinite() || request.volume !in 0.01..10.0 ||
            request.pitch !in -12..12 || request.emotion?.let { it.isBlank() || it.length > 128 || it.any(Char::isISOControl) } == true)
            throw StError.Validation("TTS controls are invalid")
        if (provider !in setOf("minimax", "glm", "step") && request.volume != 1.0) throw StError.Unsupported("TTS adapter does not support linear volume")
        if (provider == "step" && request.volume !in 0.1..2.0) throw StError.Validation("Step volume must be 0.1..2")
        if (provider != "minimax" && (request.pitch != 0 || request.emotion != null)) throw StError.Unsupported("TTS adapter does not support pitch/emotion fields")
        if (provider in setOf("groq", "gemini", "elevenlabs", "qwen", "xai", "mimo", "volcengine") && request.speed != 1.0)
            throw StError.Unsupported("TTS adapter does not support numeric synthesis speed")
        request.instructions?.let {
            if (provider !in setOf("openai", "step", "mimo") && !geminiCurrent) throw StError.Unsupported("selected speech adapter does not support instructions")
            SpeechText.validate(it, 16_384)
            if (it.codePointCount(0, it.length) > 4096) throw StError.Validation("speech instructions exceed 4096 characters")
            if (it.isNotBlank() && model in setOf("tts-1", "tts-1-hd")) throw StError.Unsupported("selected model does not support speech instructions")
            if (provider == "step" && it.codePointCount(0, it.length) > if (model == "stepaudio-3-tts") 500 else 200)
                throw StError.Validation("Step speech instruction exceeds model budget")
        }
        for (value in listOfNotNull(request.stability, request.similarityBoost)) {
            if (provider != "elevenlabs") throw StError.Unsupported("voice stability/similarity require ElevenLabs")
            if (!value.isFinite() || value !in 0.0..1.0) throw StError.Validation("voice stability/similarity must be 0..1")
        }
        if (gain != null && (provider != "siliconflow" || !gain.isFinite() || gain !in -10.0..10.0))
            throw StError.Validation("gain requires SiliconFlow and -10..10 dB")
        if (sampleRate != null) {
            if (provider in setOf("glm", "openrouter", "openai", "groq", "gemini", "elevenlabs", "xai", "mimo", "volcengine")) throw StError.Unsupported("TTS adapter cannot set sample rate")
            if (sampleRate !in 8000..96000) throw StError.Validation("invalid TTS sample rate")
            if (provider == "siliconflow" && sampleRate !in when (format) { "opus" -> setOf(48000); "mp3" -> setOf(32000, 44100); else -> setOf(8000, 16000, 24000, 32000, 44100) })
                throw StError.Validation("sample rate is not valid for SiliconFlow format")
            if (provider == "step" && sampleRate !in setOf(8000, 16000, 22050, 24000, 48000)) throw StError.Validation("invalid Step sample rate")
        }
        val payload = buildJsonObject {
            if (provider !in setOf("elevenlabs", "xai", "fish-audio", "volcengine")) put("model", model)
            if (provider == "qwen") {
                if (model.startsWith("qwen3-tts")) throw StError.Unsupported("this Qwen adapter requires Qwen-Audio-TTS, not the old qwen3-tts endpoint")
                put("input", buildJsonObject {
                    if (includeText) put("text", text)
                    put("voice", voice); put("format", format); put("sample_rate", sampleRate ?: 24000)
                })
            } else if (provider == "xai") {
                if (includeText) put("text", text)
                put("voice_id", voice); put("language", options.text("language") ?: "auto")
            } else if (provider == "fish-audio") {
                if (includeText) put("text", text)
                if (voice.isNotBlank()) put("reference_id", voice)
                put("format", format); put("temperature", options.number("temperature", 0.7)); put("top_p", options.number("topP", 0.7))
                put("prosody", buildJsonObject { put("speed", request.speed) })
                put("chunk_length", options.integer("chunkLength", 300)); put("normalize", options.flag("normalize", true))
                put("latency", options.text("latency") ?: "normal")
                sampleRate?.let { put("sample_rate", it) }
            } else if (provider == "volcengine") {
                put("user", buildJsonObject { put("uid", options.text("id") ?: request.presetId ?: "SimpleTavern") })
                put("req_params", buildJsonObject {
                    if (includeText) put("text", text)
                    put("speaker", voice)
                    put("audio_params", buildJsonObject { put("format", "mp3"); put("speech_rate", options.integer("speechRate", 0).coerceIn(-50, 100)) })
                })
            } else if (provider == "mimo") {
                put("messages", buildJsonArray {
                    request.instructions?.takeIf { it.isNotBlank() }?.let { guidance ->
                        add(buildJsonObject { put("role", "user"); put("content", guidance) })
                    }
                    add(buildJsonObject { put("role", "assistant"); if (includeText) put("content", text) })
                })
                put("audio", buildJsonObject { put("format", "pcm16"); put("voice", voice) }); put("stream", true)
            } else if (provider == "gemini") {
                put("contents", buildJsonArray { add(buildJsonObject {
                    put("role", "user"); put("parts", buildJsonArray { add(buildJsonObject {
                        if (includeText) put("text", text)
                        if (geminiCurrent) request.instructions?.takeIf { it.isNotBlank() }?.let {
                            put("speech_metadata", buildJsonObject { put("style", it) })
                        }
                    }) })
                }) })
                put("generationConfig", buildJsonObject {
                    put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                    put("speechConfig", buildJsonObject { put("voiceConfig", buildJsonObject {
                        // Preserve the source preview-model schema; newer 3.8 models use the voice field.
                        if (model.removePrefix("models/").startsWith("gemini-3.8-")) put("voice", voice)
                        else put("prebuiltVoiceConfig", buildJsonObject { put("voiceName", voice) })
                    }) })
                })
            } else if (provider == "elevenlabs") {
                if (includeText) put("text", text)
                put("model_id", model)
                put("voice_settings", buildJsonObject {
                    request.stability?.let { put("stability", it) }
                    request.similarityBoost?.let { put("similarity_boost", it) }
                })
            } else if (provider == "minimax") {
                if (includeText) put("text", text)
                put("stream", false); put("output_format", "hex")
                put("voice_setting", buildJsonObject { put("voice_id", voice); put("speed", request.speed); put("vol", request.volume); put("pitch", request.pitch)
                    request.emotion?.let { put("emotion", it) } })
                put("audio_setting", buildJsonObject { put("format", format); put("sample_rate", request.sampleRate ?: 32000) })
            } else {
                if (includeText) put("input", text)
                put("voice", voice); put("response_format", format)
                if (provider != "groq") put("speed", request.speed)
                request.instructions?.takeIf { it.isNotBlank() }?.let { put(if (provider == "step") "instruction" else "instructions", it) }
                if (provider in setOf("glm", "step")) put("volume", request.volume)
                if (provider == "step") put("sample_rate", sampleRate ?: 24000)
                if (provider == "siliconflow") { put("stream", false); request.sampleRate?.let { put("sample_rate", it) }; request.gain?.let { put("gain", it) } }
            }
        }
        NativeJsonInput.parse(payload.toString()) // reject malformed UTF-16 instead of sending replacement characters
        return payload
    }

    fun validatePreparation(provider: String, base: String?, secret: String, request: SpeechRequest, requireText: Boolean = true): JsonObject {
        endpoint(provider, base)
        val payload = payload(provider, request, requireText)
        if (secret.isBlank() && provider !in SpeechProviders.local) throw StError.Speech("tts_not_configured", "TTS credential missing")
        if (secret.length > 4096 || secret.any { it.code !in 33..126 }) throw StError.Validation("invalid TTS credential format")
        if (secret.isNotEmpty() && listOfNotNull(request.model, request.voiceId, request.emotion).any { it.contains(secret) })
            throw StError.Validation("TTS metadata must not contain its credential")
        return payload
    }
    fun prepare(provider: String, base: String?, secret: String, request: SpeechRequest,
        scope: String = "global", sourceNamespace: String? = null, reference: LocalSpeechReference? = null): SpeechSession {
        val payload = validatePreparation(provider, base, secret, request)
        val baseEndpoint = endpoint(provider, base).toHttpUrl()
        val geminiStreaming = provider == "gemini" && GeminiSpeechRequests.supportsStreaming(request.model!!)
        val localTransport = if (provider in SpeechProviders.local)
            LocalSpeechRequests.transport(baseEndpoint, provider, request, payload, reference) else null
        val target = localTransport?.first?.toString() ?: when (provider) {
            "gemini" -> baseEndpoint.newBuilder().addPathSegment("models").addPathSegment(request.model!!.removePrefix("models/") + ":generateContent").build().toString()
            "elevenlabs" -> baseEndpoint.newBuilder().apply {
                if (!baseEndpoint.encodedPath.trimEnd('/').endsWith("/v1")) addPathSegment("v1")
            }.addPathSegment("text-to-speech").addPathSegment(request.voiceId!!).addQueryParameter("output_format", "mp3_44100_128").build().toString()
            else -> baseEndpoint.toString()
        }
        val authHeader = when (provider) { "gemini" -> "x-goog-api-key"; "elevenlabs" -> "xi-api-key"; "mimo" -> "api-key"; "volcengine" -> "X-Api-Key"; else -> "Authorization" }
        val auth = if (authHeader == "Authorization") "Bearer $secret" else secret
        val jsonResponse = provider in setOf("minimax", "gemini")
        val sseResponse = provider in setOf("qwen", "mimo", "volcengine")
        val transport = Request.Builder().url(target).header("Accept", if (sseResponse) "text/event-stream" else if (jsonResponse) "application/json" else "audio/*,application/octet-stream")
            .apply {
                if (secret.isNotEmpty()) header(authHeader, auth)
                if (provider == "qwen") header("X-DashScope-SSE", "enable")
                if (provider == "fish-audio") header("model", request.model!!)
                if (provider == "volcengine") {
                    header("X-Api-Resource-Id", request.model!!)
                    header("X-Api-Request-Id", UUID.randomUUID().toString())
                }
            }
            .post(localTransport?.second ?: payload.toString().toRequestBody("application/json".toMediaType())).build()
        val dimensions = buildMap {
            put("format", request.format ?: SpeechProviders.defaultFormat(provider))
            put("voice_id", request.voiceId!!); put("speed", request.speed.toString())
            put("endpoint_host", target.toHttpUrl().host)
            if (provider in setOf("minimax", "glm", "step")) put("volume", request.volume.toString())
            if (provider == "minimax") { put("pitch", request.pitch.toString()); put("sample_rate", (request.sampleRate ?: 32000).toString()) }
            else if (geminiStreaming) put("sample_rate", "24000")
            else request.sampleRate?.let { put("sample_rate", it.toString()) }
            request.emotion?.let { put("emotion", it) }; request.gain?.let { put("gain", it.toString()) }
            request.stability?.let { put("stability", it.toString()) }
            request.similarityBoost?.let { put("similarity_boost", it.toString()) }
            if (provider == "xai") put("language", request.providerOptions.text("language") ?: "auto")
            if (provider == "volcengine") {
                put("resource_id", request.model!!)
                put("speech_rate", request.providerOptions.integer("speechRate", 0).coerceIn(-50, 100).toString())
            }
        }
        return SpeechSession { context, _, onAudio ->
            val format = request.format ?: SpeechProviders.defaultFormat(provider)
            val streamMiniMaxMp3 = onAudio != null && provider == "minimax" && format == "mp3"
            val streamGemini = onAudio != null && geminiStreaming
            val executionJsonResponse = jsonResponse && !streamMiniMaxMp3 && !streamGemini
            val executionSseResponse = sseResponse || streamMiniMaxMp3 || streamGemini
            val executionTransport = if (streamMiniMaxMp3 || streamGemini) {
                val streamPayload = JsonObject(payload.toMutableMap().apply {
                    if (streamMiniMaxMp3) {
                        put("stream", JsonPrimitive(true))
                        put("stream_options", buildJsonObject { put("exclude_aggregated_audio", true) })
                    } else put("generationConfig", JsonObject((payload["generationConfig"] as JsonObject).toMutableMap().apply {
                        put("responseFormat", buildJsonObject { put("audio", buildJsonObject {
                            put("mimeType", "AUDIO_L16"); put("sampleRate", 24000)
                        }) })
                    }))
                })
                transport.newBuilder().header("Accept", "text/event-stream")
                    .apply { if (streamGemini) url(baseEndpoint.newBuilder().addPathSegment("models")
                        .addPathSegment(request.model!!.removePrefix("models/") + ":streamGenerateContent")
                        .addQueryParameter("alt", "sse").build()) }
                    .post(streamPayload.toString().toRequestBody("application/json".toMediaType())).build()
            } else transport
            accounting.execute(provider, request.model!!, scope, sourceNamespace, dimensions,
            request.text.codePointCount(0, request.text.length).toLong(), context.copy(chatId = request.chatId)) { beforeExecute ->
            slots.withPermit { withContext(Dispatchers.IO) { coroutineScope {
            currentCoroutineContext().ensureActive()
            val call = (if (provider in SpeechProviders.local) localHttp else http).newCall(executionTransport)
            val cancel = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
            try { beforeExecute(); currentCoroutineContext().ensureActive(); call.execute().use { response ->
                currentCoroutineContext().ensureActive()
                if (!response.isSuccessful) throw StError.Speech(when (response.code) { 401, 403 -> "tts_auth_failed"; 402, 429 -> "tts_quota";
                    in 300..399 -> "tts_redirect_refused"; else -> "tts_http_error" }, "TTS HTTP ${response.code}", mapOf("provider" to provider, "status" to response.code.toString()))
                val body = response.body ?: throw invalid()
                val contentType = body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty().lowercase()
                if (!executionJsonResponse && !executionSseResponse && (contentType.endsWith("json") || (contentType.isNotEmpty() && !contentType.startsWith("audio/") && contentType != "application/octet-stream"))) throw invalid()
                if (!executionJsonResponse && !executionSseResponse) {
                    val declaredFormat = when (contentType) {
                        "audio/mpeg", "audio/mp3" -> "mp3"
                        "audio/wav", "audio/wave", "audio/x-wav" -> "wav"
                        "audio/flac", "audio/x-flac" -> "flac"
                        "audio/aac", "audio/aacp" -> "aac"
                        "audio/opus" -> "opus"
                        "audio/pcm", "audio/l16" -> "pcm"
                        else -> null // Ogg/octet-stream/absent headers do not identify the codec.
                    }
                    if (declaredFormat != null && declaredFormat != format)
                        throw StError.Speech("tts_format_mismatch", "TTS returned another format")
                }
                val streamed = if (executionSseResponse) {
                    if (contentType != "text/event-stream") throw invalid()
                    streamAudio(provider, body.source()) { bytes ->
                        onAudio?.invoke(SpeechAudioChunk(if (provider == "mimo" || streamGemini) "pcm" else format,
                            if (provider == "mimo" || streamGemini) 24000 else if (streamMiniMaxMp3) request.sampleRate ?: 32000 else request.sampleRate, bytes,
                            if (provider in setOf("mimo", "qwen") || streamGemini) 1 else null))
                    }
                } else null
                val budget = if (executionJsonResponse) MAX_JSON else MAX_AUDIO
                if (streamed == null && body.contentLength() > budget) throw tooLarge()
                val source = body.source(); val buffer = Buffer()
                while (streamed == null) { currentCoroutineContext().ensureActive()
                    val next = Buffer()
                    val n = source.read(next, minOf(65_536L, budget + 1 - buffer.size))
                    if (n == -1L) break
                    val bytes = next.readByteArray(); buffer.write(bytes)
                    if (buffer.size > budget) throw tooLarge()
                    if (!executionJsonResponse) onAudio?.invoke(SpeechAudioChunk(format, request.sampleRate, bytes))
                }
                val raw = buffer.readByteArray()
                var usage: Long? = streamed?.characters
                var rate: Int? = streamed?.sampleRate
                val audio = if (streamed != null) {
                    if (provider == "mimo" || streamGemini) pcmWav(streamed.bytes, 24000) else streamed.bytes
                } else if (provider == "minimax") {
                    val json = parse(raw)
                    if ((json["base_resp"] as? JsonObject)?.get("status_code") != JsonPrimitive(0)) throw StError.Speech("tts_provider_error", "MiniMax rejected synthesis")
                    val data = json["data"] as? JsonObject ?: throw invalid()
                    if (data["status"] != JsonPrimitive(2)) throw invalid()
                    val hex = (data["audio"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw invalid()
                    if (hex.isEmpty() || hex.length % 2 != 0 || hex.length / 2 > MAX_AUDIO) throw invalid()
                    val result = ByteArray(hex.length / 2)
                    for (index in result.indices) {
                        if (index % 65_536 == 0) currentCoroutineContext().ensureActive()
                        val high = hex[index * 2].digitToIntOrNull(16) ?: throw invalid()
                        val low = hex[index * 2 + 1].digitToIntOrNull(16) ?: throw invalid()
                        result[index] = ((high shl 4) or low).toByte()
                    }
                    val extra = json["extra_info"] as? JsonObject
                    usage = (extra?.get("usage_characters") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }
                    rate = (extra?.get("audio_sample_rate") as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 8000..384000 }
                    (extra?.get("audio_format") as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { reported ->
                        if (reported != format) throw StError.Speech("tts_format_mismatch", "TTS returned another format") }
                    result
                } else if (provider == "gemini") geminiAudio(parse(raw)) else raw
                if (audio.size > MAX_AUDIO) throw tooLarge()
                if (audio.isEmpty()) throw invalid()
                // Raw PCM has no magic header. Reject an actual structured error body, not merely its first byte.
                if (format == "pcm" && structuredBody(audio)) throw invalid()
                if (!headerMatches(audio, format)) throw invalid()
                val wavRate = wavRate(audio, format)
                if (wavRate != null && rate != null && wavRate != rate) throw invalid()
                rate = wavRate ?: rate
                currentCoroutineContext().ensureActive()
                if (executionJsonResponse) onAudio?.invoke(SpeechAudioChunk(format, rate, audio))
                SpeechAudio(provider, if (secret.isEmpty()) request.model!! else request.model!!.replace(secret, "<redacted>"),
                    if (secret.isEmpty()) request.voiceId!! else request.voiceId!!.replace(secret, "<redacted>"), format, rate, audio, usage,
                    warnings = buildList { if (rate == null) add("sample_rate_unreported"); if (format == "pcm") add("raw_pcm_layout_unverified")
                        if (provider in SpeechProviders.local) add("local_gateway_desktop_contract_unverified") }, spokenText = request.text)
            } } catch (e: IOException) { currentCoroutineContext().ensureActive()
                throw StError.Speech(if (e is java.io.InterruptedIOException) "tts_timeout" else "tts_network_error", "TTS transport failed", mapOf("provider" to provider))
            } finally { cancel.cancel() }
        } } } } }
    }

    private fun geminiAudio(json: JsonObject): ByteArray {
        val candidate = (json["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw invalid()
        val parts = ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        val audio = Buffer()
        var pcmRate: Int? = null
        var wav = false
        for (part in parts) {
            val inline = (part as? JsonObject)?.get("inlineData") as? JsonObject ?: continue
            val mime = (inline["mimeType"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: throw invalid()
            val encoded = (inline["data"] as? JsonPrimitive)?.contentOrNull ?: throw invalid()
            val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { throw invalid() }
            if (mime.substringBefore(';') in setOf("audio/wav", "audio/x-wav")) {
                if (audio.size != 0L || pcmRate != null) throw invalid()
                wav = true
            } else if (mime.substringBefore(';') in setOf("audio/l16", "audio/pcm")) {
                if (wav) throw invalid()
                val rate = Regex("(?:^|;)\\s*rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24_000
                if (rate !in 8000..384000 || pcmRate != null && pcmRate != rate || bytes.size % 2 != 0) throw invalid()
                pcmRate = rate
            } else throw StError.Speech("tts_invalid_response", "unsupported Gemini audio encoding")
            if (audio.size + bytes.size > MAX_AUDIO - 44) throw tooLarge()
            audio.write(bytes)
        }
        if (audio.size == 0L) throw invalid()
        if (wav) return audio.readByteArray()
        val rate = pcmRate ?: throw invalid()
        val pcm = audio.readByteArray()
        // Source Gemini previews return mono PCM16LE; store a standard WAV for playback/cache/export.
        return pcmWav(pcm, rate)
    }

    private fun pcmWav(pcm: ByteArray, rate: Int): ByteArray {
        if (pcm.isEmpty() || pcm.size % 2 != 0) throw invalid()
        if (pcm.size > MAX_AUDIO - 44) throw tooLarge()
        return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(pcm.size); put(pcm)
        }.array()
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String, default: Double) = (get(key) as? JsonPrimitive)?.doubleOrNull ?: default
    private fun JsonObject.integer(key: String, default: Int) = (get(key) as? JsonPrimitive)?.intOrNull ?: default
    private fun JsonObject.flag(key: String, default: Boolean) = (get(key) as? JsonPrimitive)?.booleanOrNull ?: default

    private data class StreamAudio(val bytes: ByteArray, val characters: Long?, val sampleRate: Int? = null)
    private suspend fun streamAudio(provider: String, source: okio.BufferedSource, onAudio: suspend (ByteArray) -> Unit): StreamAudio {
        val audio = Buffer()
        val data = StringBuilder()
        var completed = false
        var characters: Long? = null
        var sampleRate: Int? = null
        suspend fun event() {
            if (data.isEmpty()) return
            currentCoroutineContext().ensureActive()
            val raw = data.toString().trim(); data.setLength(0)
            if (raw == "[DONE]") { if (provider !in setOf("minimax", "gemini")) completed = true; return }
            val json = parse(raw.toByteArray(Charsets.UTF_8))
            if (json["error"] != null && json["error"] != JsonNull) throw StError.Speech("tts_provider_error", "speech stream returned an error")
            val encoded = when (provider) {
                "gemini" -> {
                    for (bytes in GeminiSpeechRequests.streamChunks(json)) {
                        if (audio.size + bytes.size > MAX_AUDIO - 44) throw tooLarge()
                        audio.write(bytes)
                        onAudio(bytes)
                    }
                    val candidate = (json["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
                    if (candidate?.text("finishReason") == "STOP") { completed = true; sampleRate = 24000 }
                    null
                }
                "minimax" -> {
                    if ((json["base_resp"] as? JsonObject)?.get("status_code") != JsonPrimitive(0))
                        throw StError.Speech("tts_provider_error", "MiniMax rejected synthesis")
                    val responseData = json["data"] as? JsonObject ?: throw invalid()
                    when ((responseData["status"] as? JsonPrimitive)?.intOrNull) {
                        1 -> {
                            val hex = responseData.text("audio") ?: throw invalid()
                            if (hex.isEmpty() || hex.length % 2 != 0 || hex.length / 2 > MAX_AUDIO) throw invalid()
                            val bytes = ByteArray(hex.length / 2)
                            for (index in bytes.indices) {
                                if (index % 65_536 == 0) currentCoroutineContext().ensureActive()
                                val high = hex[index * 2].digitToIntOrNull(16) ?: throw invalid()
                                val low = hex[index * 2 + 1].digitToIntOrNull(16) ?: throw invalid()
                                bytes[index] = ((high shl 4) or low).toByte()
                            }
                            if (audio.size + bytes.size > MAX_AUDIO) throw tooLarge()
                            audio.write(bytes)
                            onAudio(bytes)
                            null
                        }
                        2 -> {
                            completed = true
                            val extra = json["extra_info"] as? JsonObject
                            (extra?.get("usage_characters") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                                ?.takeIf { it >= 0 }?.let { characters = it }
                            (extra?.get("audio_sample_rate") as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                                ?.takeIf { it in 8000..384000 }?.let { sampleRate = it }
                            if (extra?.get("audio_format") != null && extra["audio_format"] != JsonNull) {
                                val reported = extra.text("audio_format") ?: throw invalid()
                                if (reported != "mp3") throw StError.Speech("tts_format_mismatch", "TTS returned another format")
                            }
                            null // MiniMax may repeat aggregated audio in its terminal event.
                        }
                        else -> throw invalid()
                    }
                }
                "qwen" -> {
                    if (json.text("code")?.isNotBlank() == true) throw StError.Speech("tts_provider_error", "Qwen rejected synthesis")
                    val output = json["output"] as? JsonObject
                    if (output?.text("finish_reason") == "stop") completed = true
                    (json["usage"] as? JsonObject)?.get("characters")?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 }?.let { characters = it }
                    (output?.get("audio") as? JsonObject)?.text("data")
                }
                "mimo" -> {
                    val choice = (json["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                    if (choice?.text("finish_reason") == "stop") completed = true
                    ((choice?.get("delta") as? JsonObject)?.get("audio") as? JsonObject)?.text("data")
                }
                else -> {
                    val code = (json["code"] as? JsonPrimitive)?.intOrNull ?: throw invalid()
                    if (code !in setOf(0, 20000000)) throw StError.Speech("tts_provider_error", "Volcengine rejected synthesis")
                    if (code == 20000000) completed = true
                    json.text("data")
                }
            }
            if (!encoded.isNullOrBlank()) {
                val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { throw invalid() }
                if (audio.size + bytes.size > MAX_AUDIO) throw tooLarge()
                audio.write(bytes)
                onAudio(bytes)
            }
        }
        var wireBytes = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val newline = source.indexOf('\n'.code.toByte(), 0, MAX_JSON + 1)
            val line = if (newline >= 0) source.readUtf8LineStrict(MAX_JSON) else {
                if (source.buffer.size > MAX_JSON) throw tooLarge()
                source.readUtf8()
            }.removePrefix("\uFEFF")
            wireBytes += line.toByteArray(Charsets.UTF_8).size + 1
            if (wireBytes > MAX_JSON * 2 || data.length > MAX_JSON) throw tooLarge()
            if (line.isEmpty()) { event(); if (completed) break }
            else if (line.startsWith("data:")) { if (data.isNotEmpty()) data.append('\n'); data.append(line.substring(5).removePrefix(" ")) }
            if (source.exhausted()) {
                event()
                // The source MiMo adapter treats a clean HTTP close as its final audio chunk.
                if (provider == "mimo" && audio.size > 0L) completed = true
                break
            }
        }
        if (!completed || audio.size == 0L) throw StError.Speech("tts_invalid_response", "speech stream ended without complete audio")
        return StreamAudio(audio.readByteArray(), characters, sampleRate)
    }

    private fun parse(raw: ByteArray): JsonObject = try {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        NativeJsonInput.parse(text) as? JsonObject ?: throw invalid()
    } catch (_: Exception) { throw invalid() }
    private fun invalid() = StError.Speech("tts_invalid_response", "invalid TTS response")
    private fun tooLarge() = StError.Speech("tts_response_too_large", "TTS response exceeds byte budget")
    private fun structuredBody(bytes: ByteArray): Boolean {
        val first = bytes.firstOrNull { it.toInt() !in setOf(9, 10, 13, 32) }?.toInt()
        if (first != '{'.code && first != '['.code) return false
        return runCatching {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val body = NativeJsonInput.parse(text)
            body is JsonObject || body is JsonArray
        }.getOrDefault(false)
    }
    private fun headerMatches(bytes: ByteArray, format: String): Boolean {
        fun prefix(text: String, at: Int = 0) = bytes.size >= at + text.length && text.indices.all { bytes[at + it] == text[it].code.toByte() }
        return when (format) { "wav" -> prefix("RIFF") && prefix("WAVE", 8); "flac" -> prefix("fLaC"); "opus" -> prefix("OggS")
            "mp3" -> prefix("ID3") || (bytes.size > 1 && bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 224 == 224)
            "aac" -> bytes.size > 1 && bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 246 == 240
            "pcm" -> bytes.size >= 2; else -> false }
    }
    private fun wavRate(bytes: ByteArray, format: String): Int? {
        if (format != "wav") return null
        var offset = 12
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (offset <= bytes.size - 8 && offset < 65_536) {
            val size = view.getInt(offset + 4).toLong() and 0xffffffffL
            if (offset + 8L + size > bytes.size) return null
            if (bytes[offset] == 'f'.code.toByte() && bytes[offset + 1] == 'm'.code.toByte() && bytes[offset + 2] == 't'.code.toByte() && bytes[offset + 3] == ' '.code.toByte() && size >= 16)
                return view.getInt(offset + 12).takeIf { it in 8000..384000 }
            val next = offset + 8L + size + size % 2
            if (next > Int.MAX_VALUE) return null
            offset = next.toInt()
        }
        return null
    }
    private companion object { const val MAX_AUDIO = 16_777_216L; const val MAX_JSON = 34_078_720L }
}
