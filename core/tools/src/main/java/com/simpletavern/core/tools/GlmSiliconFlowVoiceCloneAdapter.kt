package com.simpletavern.core.tools

import com.simpletavern.core.model.SpeechVoiceCloneRequest
import com.simpletavern.core.model.StError
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Provider request mapping only; transport, credentials, limits, and cancellation belong to VoiceCloneHttp. */
internal object GlmSiliconFlowVoiceCloneAdapter {
    suspend fun glm(
        request: SpeechVoiceCloneRequest,
        source: SpeechVoiceCloneInput,
        http: VoiceCloneHttp,
    ): SpeechVoiceCloneAudio {
        val upload = http.multipart(
            path = "/api/paas/v4/files",
            fields = mapOf("purpose" to "voice-clone-input"),
            input = source,
            phase = "uploading_source",
        )
        val uploadedId = upload.requiredString("id").let(http::safeId)
        http.uploaded("source", uploadedId)

        val body = buildJsonObject {
            put("model", request.model ?: "glm-tts-clone")
            put("voice_name", request.voiceId)
            put("input", request.previewText ?: invalidRequest())
            put("file_id", uploadedId)
            request.referenceText?.let { put("text", it) }
        }
        val result = http.json("/api/paas/v4/voice/clone", body, "creating_voice")
        val voiceId = http.safeId(result.requiredString("voice"))
        val previewUrl = result.optionalString("preview_url") ?: result.optionalString("demo_audio")
        return SpeechVoiceCloneAudio(
            voiceId = voiceId,
            voiceType = "private",
            remotePreviewUrl = http.safePreviewUrl(previewUrl),
            warnings = listOf("glm_voice_clone_desktop_contract_unverified"),
        )
    }

    suspend fun siliconFlow(
        request: SpeechVoiceCloneRequest,
        source: SpeechVoiceCloneInput,
        http: VoiceCloneHttp,
    ): SpeechVoiceCloneAudio {
        val body = http.multipart(
            path = "/v1/uploads/audio/voice",
            fields = mapOf(
                "model" to (request.model ?: invalidRequest()),
                "customName" to request.voiceId,
                "text" to (request.referenceText ?: invalidRequest()),
            ),
            input = source,
            phase = "creating_voice",
        )
        val voiceId = http.safeId(body.requiredString("uri"))
        return SpeechVoiceCloneAudio(
            voiceId = voiceId,
            voiceType = "private",
        )
    }

    private fun JsonObject.requiredString(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalidResponse()

    private fun JsonObject.optionalString(key: String): String? {
        val value = this[key] ?: return null
        if (value == JsonNull) return null
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalidResponse()
    }

    private fun invalidRequest(): Nothing = throw StError.Validation("voice clone request is missing a required provider field")

    private fun invalidResponse(): Nothing = throw StError.Speech(
        "voice_clone_invalid_response",
        "Voice clone provider response is invalid",
    )
}
