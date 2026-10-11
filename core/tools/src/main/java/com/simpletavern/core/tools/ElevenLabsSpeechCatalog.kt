package com.simpletavern.core.tools

import com.simpletavern.core.model.SpeechModelInfo
import com.simpletavern.core.model.SpeechVoiceInfo
import com.simpletavern.core.model.StError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal object ElevenLabsSpeechCatalog {
    data class VoicePage(
        val voices: List<SpeechVoiceInfo>,
        val hasMore: Boolean,
        val nextPageToken: String?
    )

    fun voices(json: JsonObject, text: (String, Boolean) -> String): VoicePage {
        val entries = json["voices"] as? JsonArray ?: invalid()
        if (entries.size > MAX_ITEMS) invalid()

        val hasMore = json["has_more"].asBoolean() ?: invalid()
        val nextPageToken = json.optionalString("next_page_token")
        if (nextPageToken != null &&
            (nextPageToken.toByteArray(Charsets.UTF_8).size > MAX_PAGE_TOKEN_BYTES || nextPageToken.any(Char::isISOControl))) {
            invalid()
        }
        if (hasMore && nextPageToken.isNullOrEmpty()) invalid()

        val voices = entries.map { entry ->
            val voice = entry as? JsonObject ?: invalid()
            val id = text(voice.requiredString("voice_id"), true)
            val name = text(voice.optionalString("name") ?: id, false)
            val category = text(voice.optionalString("category") ?: "unclassified", true)
            SpeechVoiceInfo(id, name, category, "remote", null)
        }
        return VoicePage(voices, hasMore, nextPageToken)
    }

    fun models(json: JsonElement, text: (String, Boolean) -> String): List<SpeechModelInfo> {
        val entries = json as? JsonArray ?: invalid()
        if (entries.size > MAX_ITEMS) invalid()

        return entries.map { entry ->
            val model = entry as? JsonObject ?: invalid()
            val id = text(model.requiredString("model_id"), true)
            val name = text(model.optionalString("name") ?: id, false)
            val capability = when (model["can_do_text_to_speech"].asBoolean()) {
                true -> "provider_reported_speech"
                false -> "not_tts"
                null -> "unverified"
            }
            SpeechModelInfo(id, name, "remote", capability)
        }
    }

    private fun JsonObject.requiredString(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()

    private fun JsonObject.optionalString(key: String): String? {
        val value = this[key] ?: return null
        if (value == JsonNull) return null
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
    }

    private fun JsonElement?.asBoolean(): Boolean? {
        if (this == null || this == JsonNull) return null
        val primitive = this as? JsonPrimitive ?: invalid()
        if (primitive.isString) invalid()
        return primitive.booleanOrNull ?: invalid()
    }

    private fun invalid(): Nothing = throw StError.Speech(
        "tts_directory_invalid_response",
        "TTS catalog response is invalid"
    )

    private const val MAX_ITEMS = 10_000
    private const val MAX_PAGE_TOKEN_BYTES = 8_192
}
