package com.simpletavern.core.tools

import com.simpletavern.core.model.SpeechRequest
import com.simpletavern.core.model.SpeechText
import com.simpletavern.core.model.StError
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

class LocalSpeechReference(val file: File, val filename: String, val mimeType: String) {
    override fun toString() = "LocalSpeechReference(file=<private>, filename=<private>, mimeType=<private>)"
}

internal object LocalSpeechRequests {
    const val MAX_REFERENCE_BYTES = 20_000_000L

    fun payload(provider: String, request: SpeechRequest, includeText: Boolean): JsonObject {
        if (provider !in LOCAL_PROVIDERS) unsupported()
        val options = request.providerOptions
        val model = request.model ?: invalid("select a local TTS model")
        val voice = request.voiceId ?: invalid("select a local TTS voice")
        validateLabel(model, "model")
        validateLabel(voice, "voice")
        if (request.format != null && request.format != "wav") unsupported("local TTS supports WAV only")
        if (request.volume != 1.0 || request.pitch != 0 || request.emotion != null || request.gain != null ||
            request.stability != null || request.similarityBoost != null)
            unsupported("local TTS does not support the requested voice controls")

        when (provider) {
            GLM -> {
                if (request.speed != 1.0) unsupported("GLM local does not support speech speed")
                if (request.sampleRate != null && request.sampleRate !in 8_000..96_000) invalid("invalid local TTS sample rate")
            }
            QWEN -> {
                if (request.speed != 1.0) unsupported("Qwen local does not support speech speed")
                if (request.sampleRate != null) unsupported("Qwen local cannot set a sample rate")
            }
            OMNI -> {
                if (!request.speed.isFinite() || request.speed !in 0.25..4.0) invalid("OmniVoice speed must be 0.25..4")
                if (request.sampleRate != null) unsupported("OmniVoice local cannot set a sample rate")
            }
        }

        val promptText = optionText(options, "promptText", "prompt_text", 4096)
        val attachmentId = optionString(options, "promptAttachmentId", 512)
        val legacyPath = optionString(options, "promptAudioPath", 4096)
            ?: optionString(options, "prompt_audio_path", 4096)
        if (!legacyPath.isNullOrBlank() && attachmentId.isNullOrBlank())
            invalid("local reference audio must use a managed attachment")
        val instructionValue = request.instructions ?: optionString(options, "instruction", 16_384)
        val instruction = instructionValue?.trim()?.takeIf { it.isNotEmpty() }
        if (instruction != null) {
            if (provider == GLM) unsupported("GLM local does not support instructions")
            SpeechText.validate(instruction, 16_384)
            if (instruction.codePointCount(0, instruction.length) > 4096) invalid("speech instructions exceed 4096 characters")
        }
        val language = optionString(options, "language", 512)?.trim()?.takeIf { it.isNotEmpty() }
        val seed = optionInt(options, "seed", 42)
        val useCache = optionBoolean(options, "useCache", true)
        val cloneBase = optionString(options, "voiceCloneBaseUrl", 4096)
        val hasReference = !attachmentId.isNullOrBlank()
        if (provider == GLM && (!hasReference || promptText.isNullOrBlank()))
            invalid("GLM local requires reference text and a managed reference attachment")
        if (provider == QWEN && hasReference && promptText.isNullOrBlank())
            invalid("Qwen voice cloning requires reference text and a managed reference attachment")
        if (provider == QWEN && hasReference && instruction != null)
            unsupported("Qwen voice cloning does not support instructions")
        if (provider == QWEN && hasReference) {
            val base = cloneBase?.toHttpUrlOrNull() ?: invalid("Qwen voice cloning requires voiceCloneBaseUrl")
            validateHttpBase(base)
        }

        val text = if (includeText) {
            SpeechText.validate(request.text, 40_000, spoken = true)
            request.text
        } else null
        return buildJsonObject {
            when (provider) {
                GLM -> {
                    text?.let { put("input_text", it) }
                    put("prompt_text", promptText!!)
                    put("sample_rate", request.sampleRate ?: 24_000)
                    put("seed", seed)
                    put("use_cache", useCache)
                }
                QWEN -> if (hasReference) {
                    text?.let { put("text", it) }
                    put("language", language ?: "Auto")
                    put("ref_text", promptText!!)
                    put("x_vector_only", false)
                } else {
                    text?.let { put("text", it) }
                    put("speaker", voice)
                    put("language", language ?: "Auto")
                    put("instruct", instruction.orEmpty())
                }
                OMNI -> {
                    text?.let { put("text", it) }
                    put("speed", request.speed)
                    language?.let { put("language", it) }
                    if (hasReference) promptText?.let { put("ref_text", it) }
                    else instruction?.let { put("instruct", it) }
                }
            }
        }
    }

    fun transport(
        base: HttpUrl,
        provider: String,
        request: SpeechRequest,
        payload: JsonObject,
        reference: LocalSpeechReference?,
    ): Pair<HttpUrl, RequestBody> {
        val isClone = provider == GLM || reference != null
        val checkedReference = reference?.let(::validateReference)
        val endpointPath = when (provider) {
            GLM -> "/api/v1/tts"
            QWEN -> if (isClone) "/v1/tts/voice_clone" else "/v1/tts/custom_voice"
            OMNI -> "/v1/tts"
            else -> unsupported()
        }
        val targetBase = if (provider == QWEN && isClone) {
            val configured = optionString(request.providerOptions, "voiceCloneBaseUrl", 4096)
                ?: invalid("Qwen voice cloning requires voiceCloneBaseUrl")
            configured.toHttpUrlOrNull()!! // Already checked at the external option boundary in payload().
        } else base
        val url = appendPathOnce(targetBase, endpointPath)
        val body = when (provider) {
            GLM -> multipart {
                payload.forEach { (key, value) -> addFormDataPart(key, value.jsonPrimitive.content) }
                addFormDataPart("prompt_audio", checkedReference!!)
            }
            QWEN -> if (isClone) multipart {
                payload.forEach { (key, value) -> addFormDataPart(key, value.jsonPrimitive.content) }
                addFormDataPart("ref_audio", checkedReference!!)
            } else payload.toJsonBody()
            OMNI -> {
                if (checkedReference == null) payload.toJsonBody()
                else {
                    val audio = readBounded(checkedReference.file)
                    val expanded = buildJsonObject {
                        payload.forEach { (key, value) -> put(key, value) }
                        put("ref_audio_base64", java.util.Base64.getEncoder().encodeToString(audio))
                    }
                    expanded.toJsonBody()
                }
            }
            else -> unsupported()
        }
        return url to body
    }

    private data class CheckedReference(val file: File, val filename: String, val mimeType: MediaType)

    private fun validateReference(reference: LocalSpeechReference): CheckedReference {
        if (reference.filename.isBlank() || reference.filename.length > 255 || reference.filename.any {
                it.isISOControl() || it == '/' || it == '\\'
            }) invalid("invalid managed reference filename")
        val mime = runCatching { reference.mimeType.toMediaType() }.getOrNull()
            ?: invalid("invalid managed reference audio type")
        if (mime.type != "audio") invalid("managed reference must be audio")
        val file = reference.file
        if (!file.isFile || !file.canRead()) invalid("managed reference audio is unavailable")
        val length = file.length()
        if (length <= 0L || length > MAX_REFERENCE_BYTES) invalid("managed reference audio exceeds the size limit")
        return CheckedReference(file, reference.filename, mime)
    }

    private fun MultipartBody.Builder.addFormDataPart(name: String, reference: CheckedReference) {
        addFormDataPart(name, reference.filename, object : RequestBody() {
            override fun contentType(): MediaType = reference.mimeType
            override fun contentLength(): Long = reference.file.length().takeIf { it in 1..MAX_REFERENCE_BYTES } ?: -1L
            override fun isOneShot(): Boolean = true
            override fun writeTo(sink: BufferedSink) {
                var total = 0L
                reference.file.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_REFERENCE_BYTES) invalid("managed reference audio exceeds the size limit")
                            sink.write(buffer, 0, count)
                        }
                }
                if (total == 0L) invalid("managed reference audio is empty")
            }
        })
    }

    private fun readBounded(file: File): ByteArray {
        val output = ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_REFERENCE_BYTES) invalid("managed reference audio exceeds the size limit")
                output.write(buffer, 0, count)
            }
        }
        if (output.size() == 0) invalid("managed reference audio is empty")
        return output.toByteArray()
    }

    private fun appendPathOnce(base: HttpUrl, suffix: String): HttpUrl {
        val path = base.encodedPath.trimEnd('/')
        val target = when {
            path.endsWith(suffix) -> path
            path.endsWith("/api/v1") && suffix.startsWith("/api/v1/") -> path + suffix.removePrefix("/api/v1")
            path.endsWith("/v1") && suffix.startsWith("/v1/") -> path + suffix.removePrefix("/v1")
            else -> path + suffix
        }
        return base.newBuilder().encodedPath(target.ifEmpty { "/" }).build()
    }

    private fun validateHttpBase(url: HttpUrl) {
        if (url.scheme !in setOf("http", "https") || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedQuery != null || url.fragment != null) invalid("invalid Qwen voiceCloneBaseUrl")
    }

    private fun optionString(options: JsonObject, key: String, maxLength: Int): String? {
        val value = options[key] ?: return null
        val primitive = value as? JsonPrimitive ?: invalid("provider option $key must be text")
        if (!primitive.isString) invalid("provider option $key must be text")
        val text = primitive.content
        SpeechText.validate(text, maxLength * 4)
        if (text.length > maxLength || text.any(Char::isISOControl)) invalid("provider option $key is invalid")
        return text
    }

    private fun optionText(options: JsonObject, camel: String, snake: String, maxCodePoints: Int): String? {
        val primitive = (options[camel] ?: options[snake]) as? JsonPrimitive
            ?: if (camel !in options && snake !in options) return null else invalid("provider option $camel must be text")
        if (!primitive.isString) invalid("provider option $camel must be text")
        val value = primitive.content.trim().takeIf { it.isNotEmpty() } ?: return null
        SpeechText.validate(value, maxCodePoints * 4)
        if (value.codePointCount(0, value.length) > maxCodePoints || value.any { it.isISOControl() && it !in "\n\r\t" })
            invalid("provider option $camel exceeds its text limit")
        return value
    }

    private fun optionInt(options: JsonObject, key: String, default: Int): Int {
        val value = options[key] ?: return default
        val primitive = value as? JsonPrimitive ?: invalid("provider option $key must be an integer")
        if (primitive.isString || primitive.intOrNull == null) invalid("provider option $key must be an integer")
        return primitive.intOrNull!!
    }

    private fun optionBoolean(options: JsonObject, key: String, default: Boolean): Boolean {
        val value = options[key] ?: return default
        val primitive = value as? JsonPrimitive ?: invalid("provider option $key must be boolean")
        if (primitive.isString || primitive.booleanOrNull == null) invalid("provider option $key must be boolean")
        return primitive.booleanOrNull!!
    }

    private fun validateLabel(value: String, name: String) {
        SpeechText.validate(value, 2048)
        if (value.isBlank() || value.length > 512 || value.any(Char::isISOControl)) invalid("invalid local TTS $name")
    }

    private fun JsonObject.toJsonBody(): RequestBody = toString().toRequestBody(JSON)

    private fun multipart(block: MultipartBody.Builder.() -> Unit): RequestBody =
        MultipartBody.Builder().setType(MultipartBody.FORM).apply(block).build()

    private fun invalid(message: String): Nothing = throw StError.Validation(message)
    private fun unsupported(message: String = "unsupported local TTS provider"): Nothing = throw StError.Unsupported(message)

    private val LOCAL_PROVIDERS = setOf(GLM, QWEN, OMNI)
    private const val GLM = "glm_local"
    private const val QWEN = "qwen3_local"
    private const val OMNI = "omnivoice_local"
    private val JSON = "application/json".toMediaType()
}
