package com.simpletavern.core.tools

import com.simpletavern.core.model.SpeechVoiceCloneRequest
import com.simpletavern.core.model.StError
import java.math.BigInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object MiniMaxVoiceCloneAdapter {
    private const val UPLOAD_PATH = "/v1/files/upload"
    private const val CLONE_PATH = "/v1/voice_clone"
    private const val WARNING = "minimax_cloned_voice_may_expire_without_use"

    suspend fun clone(
        request: SpeechVoiceCloneRequest,
        source: SpeechVoiceCloneInput,
        prompt: SpeechVoiceCloneInput?,
        http: VoiceCloneHttp,
    ): SpeechVoiceCloneAudio {
        val sourceId = upload(http, "source", source, "uploading_source", "voice_clone")
        val promptId = prompt?.let {
            upload(http, "prompt", it, "uploading_prompt", "prompt_audio")
        }

        val body = buildJsonObject {
            put("file_id", JsonPrimitive(BigInteger(sourceId)))
            put("voice_id", request.voiceId)
            put("need_noise_reduction", request.noiseReduction)
            put("need_volume_normalization", request.volumeNormalization)
            put("aigc_watermark", request.aigcWatermark)

            request.previewText?.let { put("text", it) }
            request.model?.let { put("model", it) }
            request.languageBoost?.let { put("language_boost", it) }
            request.textValidation?.let { put("text_validation", it) }
            request.accuracy?.let { put("accuracy", it) }

            if (promptId != null) {
                put("clone_prompt", buildJsonObject {
                    put("prompt_audio", JsonPrimitive(BigInteger(promptId)))
                    put("prompt_text", request.promptText!!)
                })
            }
        }

        val root = http.json(CLONE_PATH, body, "cloning")
        val result = (root["data"] as? JsonObject) ?: root
        val demoAudio = (result["demo_audio"] as? JsonPrimitive)?.let {
            if (it.isString) it.content else null
        } ?: (root["demo_audio"] as? JsonPrimitive)?.let {
            if (it.isString) it.content else null
        }

        return SpeechVoiceCloneAudio(
            voiceId = request.voiceId,
            voiceType = "voice_cloning",
            remotePreviewUrl = http.safePreviewUrl(demoAudio),
            warnings = listOf(WARNING),
        )
    }

    private suspend fun upload(
        http: VoiceCloneHttp,
        role: String,
        input: SpeechVoiceCloneInput,
        phase: String,
        purpose: String,
    ): String {
        val root = http.multipart(UPLOAD_PATH, mapOf("purpose" to purpose), input, phase)
        val file = root["file"] as? JsonObject
        val data = root["data"] as? JsonObject
        val value = file?.get("file_id") ?: root["file_id"] ?: data?.get("file_id")
            ?: invalid()
        val id = parseIntegerId(value)
        http.uploaded(role, id)
        return id
    }

    private fun parseIntegerId(value: kotlinx.serialization.json.JsonElement): String {
        val primitive = value as? JsonPrimitive ?: invalid()
        val raw = primitive.content
        if (!raw.matches(Regex("[0-9]{1,19}")) || raw.toLongOrNull()?.let { it > 0 } != true) invalid()
        return BigInteger(raw).toString()
    }
    private fun invalid(): Nothing = throw StError.Speech("tts_voice_clone_invalid_response", "MiniMax cloning response is invalid")
}
