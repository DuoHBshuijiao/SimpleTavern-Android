package com.simpletavern.core.tools

import com.simpletavern.core.model.StError
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object GeminiSpeechRequests {
    private const val MAX_CHUNK_BYTES = 16_777_216 - 44

    fun supportsStreaming(model: String): Boolean = when (model.removePrefix("models/")) {
        "gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts" -> true
        else -> false
    }

    fun streamChunks(response: JsonObject): List<ByteArray> {
        if (response["error"] != null && response["error"] != JsonNull)
            throw StError.Speech("tts_provider_error", "Gemini speech provider returned an error")
        val candidate = (response["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return emptyList()
        val finishReason = candidate["finishReason"]
        if (finishReason != null && finishReason != JsonNull && finishReason != JsonPrimitive("STOP"))
            throw StError.Speech("tts_provider_error", "Gemini speech provider returned an error")
        val parts = ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        return buildList {
            for (part in parts) {
                val inline = (part as? JsonObject)?.get("inlineData") as? JsonObject ?: continue
                val encoded = inline.string("data") ?: continue
                if (encoded.isEmpty()) continue
                if (inline["mimeType"] != null && inline["mimeType"] != JsonNull) {
                    val parameters = (inline.string("mimeType") ?: throw invalid()).lowercase().split(';')
                    if (parameters.first().trim() !in setOf("audio/l16", "audio/pcm")) throw invalid()
                    for (parameter in parameters.drop(1)) {
                        if (parameter.substringBefore('=').trim() == "rate" &&
                            parameter.substringAfter('=', "").trim().toIntOrNull() != 24_000) throw invalid()
                    }
                }
                val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { throw invalid() }
                if (bytes.size > MAX_CHUNK_BYTES)
                    throw StError.Speech("tts_response_too_large", "TTS response exceeds byte budget")
                add(bytes)
            }
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun invalid() = StError.Speech("tts_invalid_response", "invalid Gemini speech response")
}
