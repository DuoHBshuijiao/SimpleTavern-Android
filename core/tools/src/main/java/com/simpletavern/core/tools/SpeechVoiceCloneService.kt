package com.simpletavern.core.tools

import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.llm.NoHttpReplayInterceptor
import com.simpletavern.core.model.*
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

class SpeechVoiceCloneInput(val file: File, val filename: String, val mimeType: String) {
    override fun toString() = "SpeechVoiceCloneInput(file=<managed>, audio=<private>)"
}
class SpeechVoiceCloneAudio(val voiceId: String, val voiceType: String, val remotePreviewUrl: String? = null,
    val warnings: List<String> = emptyList()) {
    override fun toString() = "SpeechVoiceCloneAudio(voiceId=<private>, preview=<private>)"
}
data class SpeechVoiceCloneEvent(val phase: String, val uploadedFile: SpeechVoiceUploadedFile? = null,
    val requestStarted: Boolean = false)
class SpeechVoiceCloneSession internal constructor(val selection: SpeechDirectorySelection,
    private val run: suspend (suspend (SpeechVoiceCloneEvent) -> Unit) -> SpeechVoiceCloneAudio) {
    suspend fun clone(onEvent: suspend (SpeechVoiceCloneEvent) -> Unit) = run(onEvent)
    override fun toString() = "SpeechVoiceCloneSession(configuration=<private>, audio=<private>, credentials=<redacted>)"
}

/** One explicit cloning flow; source files have already been copied into managed storage by Core. */
class SpeechVoiceCloneService(private val speech: SpeechService) {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .addNetworkInterceptor(NoHttpReplayInterceptor()).connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
    private val slots = Semaphore(2)

    fun prepare(selection: SpeechDirectorySelection, request: SpeechVoiceCloneRequest, secret: String,
        source: SpeechVoiceCloneInput, prompt: SpeechVoiceCloneInput?): SpeechVoiceCloneSession {
        if (selection.provider !in setOf("minimax", "glm", "siliconflow")) throw StError.Unsupported("voice cloning provider is not supported")
        if (!selection.enabled) throw StError.Speech("tts_disabled", "TTS is disabled for this source")
        if (secret.isBlank()) throw StError.Speech("tts_not_configured", "TTS credential missing")
        if (secret.length > 8192 || secret.any { it.code !in 33..126 }) throw StError.Speech("tts_invalid_credential", "TTS credential is invalid")
        fun text(value: String?, max: Int = 10_000, controls: Boolean = false) {
            if (value == null) return
            SpeechText.validate(value, max * 4, spoken = true)
            if (value.codePointCount(0, value.length) > max || controls && value.any(Char::isISOControl) || value.contains(secret))
                throw StError.Validation("invalid voice cloning input")
        }
        listOfNotNull(selection.presetId, selection.assistantId, selection.sourceNamespace, selection.credentialId,
            selection.baseUrl, request.presetId, request.chatId, request.assistantId, request.sourceAttachmentId,
            request.promptAttachmentId, request.voiceId, request.model, request.languageBoost).forEach { text(it, 2048, controls = true) }
        text(request.previewText); text(request.referenceText); text(request.promptText); text(request.textValidation)
        fun input(value: SpeechVoiceCloneInput) {
            if (!value.file.isFile || value.file.length() !in 1..MAX_AUDIO_BYTES ||
                value.filename.isBlank() || value.filename.length > 255 || value.filename.any(Char::isISOControl) ||
                value.filename.contains(secret) || value.mimeType.contains(secret) ||
                !value.mimeType.matches(Regex("audio/[a-z0-9.+-]+"))) throw StError.Validation("invalid managed cloning audio")
            if (selection.provider == "minimax" && value.filename.substringAfterLast('.', "").lowercase() !in setOf("mp3", "m4a", "wav"))
                throw StError.Validation("MiniMax cloning requires mp3, m4a or wav audio")
        }
        input(source); prompt?.let(::input)
        when (selection.provider) {
            "minimax" -> {
                if (!request.voiceId.matches(Regex("[A-Za-z][A-Za-z0-9_-]{6,254}[A-Za-z0-9]")))
                    throw StError.Validation("invalid MiniMax cloned voice ID")
                if (request.referenceText != null) throw StError.Unsupported("use textValidation or promptText for MiniMax cloning")
                if ((prompt == null) != (request.promptText == null)) throw StError.Validation("MiniMax clone prompt requires both audio and text")
                text(request.previewText, 1000); text(request.textValidation, 200)
                if (request.previewText != null && request.model.isNullOrBlank()) throw StError.Validation("select a MiniMax preview model")
                request.accuracy?.let { if (!it.isFinite() || it !in 0.0..1.0 || request.textValidation == null)
                    throw StError.Validation("accuracy requires textValidation and must be in 0..1") }
            }
            else -> {
                if (prompt != null || request.promptText != null || request.languageBoost != null || request.noiseReduction ||
                    request.volumeNormalization || request.aigcWatermark || request.textValidation != null || request.accuracy != null)
                    throw StError.Unsupported("selected provider does not support these MiniMax cloning options")
                if (selection.provider == "glm") {
                    if (request.previewText == null) throw StError.Validation("GLM cloning requires explicit preview text")
                    text(request.previewText, 1024); text(request.referenceText, 1000)
                } else {
                    if (request.previewText != null) throw StError.Unsupported("SiliconFlow upload does not generate a preview")
                    if (request.model.isNullOrBlank() || request.referenceText == null)
                        throw StError.Validation("SiliconFlow cloning requires model and reference text")
                }
            }
        }
        val endpoint = speech.endpoint(selection.provider, selection.baseUrl).toHttpUrl()
        return SpeechVoiceCloneSession(selection) { onEvent -> slots.withPermit {
            val transport = VoiceCloneHttp(http, endpoint, selection.provider, secret, onEvent)
            when (selection.provider) {
                "minimax" -> MiniMaxVoiceCloneAdapter.clone(request, source, prompt, transport)
                "glm" -> GlmSiliconFlowVoiceCloneAdapter.glm(request, source, transport)
                else -> GlmSiliconFlowVoiceCloneAdapter.siliconFlow(request, source, transport)
            }
        } }
    }
    companion object { const val MAX_AUDIO_BYTES = 20_000_000L }
}

/** Shared HTTP only; provider field mapping stays in its adapter. No response URL is fetched. */
internal class VoiceCloneHttp(private val client: OkHttpClient, private val endpoint: HttpUrl, private val provider: String,
    private val secret: String, private val onEvent: suspend (SpeechVoiceCloneEvent) -> Unit) {
    suspend fun uploaded(role: String, id: String) = onEvent(SpeechVoiceCloneEvent("${role}_uploaded", SpeechVoiceUploadedFile(role, safeId(id))))
    suspend fun json(path: String, body: JsonObject, phase: String): JsonObject =
        post(path, body.toString().toRequestBody("application/json".toMediaType()), phase)
    suspend fun multipart(path: String, fields: Map<String, String>, input: SpeechVoiceCloneInput, phase: String): JsonObject {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (key, value) -> body.addFormDataPart(key, value) }
        val length = input.file.length()
        body.addFormDataPart("file", input.filename, object : RequestBody() {
            override fun contentType() = input.mimeType.toMediaType()
            override fun contentLength() = length
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                input.file.inputStream().use { stream ->
                    val buffer = ByteArray(65_536)
                    var bytes = 0L
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        bytes += count
                        if (bytes > SpeechVoiceCloneService.MAX_AUDIO_BYTES) throw IOException("cloning input exceeds upload budget")
                        sink.write(buffer, 0, count)
                    }
                }
            }
        })
        return post(path, body.build(), phase)
    }
    private suspend fun post(path: String, body: RequestBody, phase: String): JsonObject = withContext(Dispatchers.IO) { coroutineScope {
        currentCoroutineContext().ensureActive()
        val request = Request.Builder().url(endpoint.newBuilder().encodedPath(path).build())
            .header("Authorization", "Bearer $secret").header("Accept", "application/json").post(body).build()
        val call = client.newCall(request)
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            onEvent(SpeechVoiceCloneEvent(phase, requestStarted = true))
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                if (!response.isSuccessful) throw StError.Speech(when (response.code) {
                    401, 403 -> "tts_auth_failed"; 402, 429 -> "tts_quota"; in 300..399 -> "tts_redirect_refused"
                    else -> "tts_voice_clone_http_error"
                }, "voice cloning request failed")
                val responseBody = response.body ?: invalid()
                if (responseBody.contentLength() > 1_048_576) invalid()
                val root = try { NativeJsonInput.parse(NativeJsonInput.read(responseBody.byteStream(), 1_048_576)) as? JsonObject ?: invalid() }
                    catch (_: StError.Validation) { invalid() }
                if (root["error"] != null && root["error"] != JsonNull) throw StError.Speech("tts_provider_error", "provider rejected voice cloning")
                if (provider == "minimax") {
                    val code = (root["base_resp"] as? JsonObject)?.get("status_code") as? JsonPrimitive ?: invalid()
                    if (code.isString || code.intOrNull != 0) throw StError.Speech("tts_provider_error", "provider rejected voice cloning")
                }
                currentCoroutineContext().ensureActive()
                root
            }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            throw StError.Speech(if (error is java.io.InterruptedIOException) "tts_timeout" else "tts_network_error", "voice cloning transport failed")
        } finally { watcher.cancel() }
    } }
    fun safeId(value: String): String {
        try { SpeechText.validate(value, 2048) } catch (_: StError.Validation) { invalid() }
        if (value.isBlank() || value.length > 512 || value.any(Char::isISOControl) || value.contains(secret)) invalid()
        return value
    }
    fun safePreviewUrl(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        if (value.length > 8192 || value.contains(secret) || value.any(Char::isISOControl)) invalid()
        val url = value.toHttpUrlOrNull() ?: invalid()
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) invalid()
        return url.toString()
    }
    private fun invalid(): Nothing = throw StError.Speech("tts_voice_clone_invalid_response", "voice cloning response is invalid")
}
