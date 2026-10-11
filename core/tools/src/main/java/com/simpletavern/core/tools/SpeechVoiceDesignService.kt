package com.simpletavern.core.tools

import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.llm.NoHttpReplayInterceptor
import com.simpletavern.core.model.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class SpeechVoiceDesignAudio(val voiceId: String, val previewBytes: ByteArray) {
    override fun toString() = "SpeechVoiceDesignAudio(voiceId=<private>, previewBytes=${previewBytes.size}, contents=<omitted>)"
}

class SpeechVoiceDesignSession internal constructor(
    val selection: SpeechDirectorySelection,
    private val run: suspend (suspend () -> Unit) -> SpeechVoiceDesignAudio,
) {
    suspend fun design(onRequest: suspend () -> Unit): SpeechVoiceDesignAudio = run(onRequest)
    override fun toString() = "SpeechVoiceDesignSession(selection=<private>, credentials=<redacted>)"
}

/** Performs one caller-owned MiniMax voice-design request; it never persists or publishes its result. */
class SpeechVoiceDesignService(private val speech: SpeechService) {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .addNetworkInterceptor(NoHttpReplayInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).build()
    private val slots = Semaphore(2)

    fun prepare(
        selection: SpeechDirectorySelection,
        request: SpeechVoiceDesignRequest,
        secret: String,
    ): SpeechVoiceDesignSession {
        if (selection.provider != "minimax") throw StError.Unsupported("voice design requires the MiniMax provider")
        if (!selection.enabled) throw StError.Speech("tts_disabled", "TTS is disabled for this source")
        if (!selection.hasCredential || secret.isBlank()) throw StError.Speech("tts_not_configured", "TTS credential missing")
        if (secret.length > 8192 || secret.any { it.code !in 33..126 })
            throw StError.Speech("tts_invalid_credential", "TTS credential is invalid")

        val endpoint = speech.endpoint(selection.provider, selection.baseUrl).toHttpUrl()
        val identifiers = listOfNotNull(
            selection.presetId, selection.assistantId, selection.credentialId, selection.sourceNamespace,
            request.presetId, request.chatId, request.assistantId, request.voiceId,
        )
        if ((identifiers + selection.baseUrl).any { it.contains(secret) } ||
            request.prompt.contains(secret) || request.previewText.contains(secret))
            throw StError.Validation("voice design request metadata contains credential material")

        SpeechText.validate(request.prompt, 8192, spoken = true)
        if (request.prompt.isBlank()) throw StError.Validation("voice design prompt is required")
        SpeechText.validate(request.previewText, 2000, spoken = true)
        if (request.previewText.isBlank() || request.previewText.codePointCount(0, request.previewText.length) > 500)
            throw StError.Validation("voice design preview text must contain at most 500 characters")
        request.voiceId?.let { validateVoiceId(it, "voice design voice ID") }
        listOfNotNull(request.presetId, request.chatId, request.assistantId).forEach { validateIdentifier(it) }

        val payload = buildString {
            append("{\"prompt\":").append(JsonPrimitive(request.prompt).toString())
            append(",\"preview_text\":").append(JsonPrimitive(request.previewText).toString())
            request.voiceId?.let { append(",\"voice_id\":").append(JsonPrimitive(it).toString()) }
            append('}')
        }
        val url = endpoint.newBuilder().encodedPath("/v1/voice_design").build()

        return SpeechVoiceDesignSession(selection) { onRequest ->
            execute(url, secret, payload, onRequest)
        }
    }

    private suspend fun execute(
        url: HttpUrl,
        secret: String,
        payload: String,
        onRequest: suspend () -> Unit,
    ): SpeechVoiceDesignAudio = withContext(Dispatchers.IO) {
        slots.withPermit {
            kotlinx.coroutines.coroutineScope {
                currentCoroutineContext().ensureActive()
                val request = Request.Builder().url(url)
                    .header("Authorization", "Bearer $secret")
                    .header("Accept", "application/json")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                val call = http.newCall(request)
                val cancel = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    currentCoroutineContext().ensureActive()
                    onRequest()
                    call.execute().use { response ->
                        currentCoroutineContext().ensureActive()
                        if (!response.isSuccessful) throw StError.Speech(
                            when (response.code) {
                                401, 403 -> "tts_auth_failed"
                                402, 429 -> "tts_quota"
                                in 300..399 -> "tts_redirect_refused"
                                else -> "tts_voice_design_http_error"
                            }, "MiniMax voice design request failed",
                        )
                        val body = response.body ?: throw invalidResponse()
                        val type = body.contentType()
                        if (type != null && (type.type != "application" ||
                                type.subtype != "json" && !type.subtype.endsWith("+json"))) throw invalidResponse()
                        if (body.contentLength() > MAX_JSON_BYTES) throw responseTooLarge()
                        val raw = try { NativeJsonInput.read(body.byteStream(), MAX_JSON_BYTES.toInt()) }
                        catch (error: StError.Validation) {
                            if (error.message?.contains("exceeds byte budget") == true) throw responseTooLarge()
                            throw invalidResponse()
                        }
                        val root = try { NativeJsonInput.parse(raw) as? JsonObject ?: throw invalidResponse() }
                        catch (error: StError.Speech) { throw error }
                        catch (_: StError.Validation) { throw invalidResponse() }
                        if (root["error"] != null && root["error"] != kotlinx.serialization.json.JsonNull)
                            throw StError.Speech("tts_provider_error", "MiniMax rejected voice design request")

                        val data = root["data"] as? JsonObject
                        val result = data ?: root
                        val baseResp = (root["base_resp"] ?: result["base_resp"]) as? JsonObject
                            ?: throw invalidResponse()
                        val status = baseResp["status_code"] as? JsonPrimitive ?: throw invalidResponse()
                        if (status.isString || status.content != "0")
                            throw StError.Speech("tts_provider_error", "MiniMax rejected voice design request")

                        val voiceId = result.string("voice_id") ?: throw invalidResponse()
                        validateResponseVoiceId(voiceId, secret)
                        val audioHex = result.string("trial_audio")
                        val audio = if (audioHex.isNullOrEmpty()) ByteArray(0) else decodeHex(audioHex)
                        currentCoroutineContext().ensureActive()
                        SpeechVoiceDesignAudio(voiceId, audio)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw StError.Speech(
                        if (error is java.io.InterruptedIOException) "tts_timeout" else "tts_network_error",
                        "MiniMax voice design transport failed",
                    )
                } finally {
                    cancel.cancel()
                }
            }
        }
    }

    private fun JsonObject.string(key: String): String? {
        val value = this[key] ?: return null
        if (value !is JsonPrimitive || !value.isString) throw invalidResponse()
        return value.content
    }

    private fun decodeHex(value: String): ByteArray {
        if (value.length > MAX_AUDIO_HEX_CHARS || value.length % 2 != 0 || value.any { it.digitToIntOrNull(16) == null })
            throw invalidResponse()
        return ByteArray(value.length / 2) { index ->
            ((value[index * 2].digitToInt(16) shl 4) or value[index * 2 + 1].digitToInt(16)).toByte()
        }
    }

    private fun validateVoiceId(value: String, label: String) {
        SpeechText.validate(value, 2048)
        if (value.isBlank() || value.length > 512 || value.any(Char::isISOControl))
            throw StError.Validation("invalid $label")
    }

    private fun validateIdentifier(value: String) {
        SpeechText.validate(value, 2048)
        if (value.isBlank() || value.length > 512 || value.any(Char::isISOControl))
            throw StError.Validation("invalid voice design identifier")
    }

    private fun validateResponseVoiceId(value: String, secret: String) {
        try { validateVoiceId(value, "voice design response voice ID") }
        catch (_: StError.Validation) { throw invalidResponse() }
        if (value.contains(secret)) throw invalidResponse()
    }

    private fun invalidResponse() = StError.Speech("tts_voice_design_invalid_response", "MiniMax voice design response is invalid")
    private fun responseTooLarge() = StError.Speech("tts_voice_design_response_too_large", "MiniMax voice design response exceeds budget")

    override fun toString() = "SpeechVoiceDesignService(credentials=<redacted>)"

    private companion object {
        const val MAX_JSON_BYTES = 16_777_216L
        const val MAX_AUDIO_HEX_CHARS = 16_777_216
    }
}
