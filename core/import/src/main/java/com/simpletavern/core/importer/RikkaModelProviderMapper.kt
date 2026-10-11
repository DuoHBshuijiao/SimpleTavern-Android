package com.simpletavern.core.importer

import com.simpletavern.core.model.StError
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject

internal object RikkaModelProviderMapper {
    private const val OPENAI_BASE_URL = "https://api.openai.com/v1"
    private const val GOOGLE_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    private const val GOOGLE_VERTEX_EXPRESS_BASE_URL = "https://aiplatform.googleapis.com/v1/publishers/google"
    private const val CLAUDE_BASE_URL = "https://api.anthropic.com/v1"

    fun map(clean: JsonObject): JsonObject? {
        val type = (clean["type"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.lowercase()
            ?: return null
        val protocol = when (type) {
            "openai" -> "openai_responses".takeIf { clean.boolean("useResponseApi") }
                ?: "openai_compatible_chat"
            "google" -> "gemini_generate_content"
            "claude" -> "anthropic_messages"
            else -> return null
        }

        val vertex = type == "google" && clean.boolean("vertexAI")
        val vertexIam = vertex && clean.boolean("useServiceAccount")

        val defaultBaseUrl = when (type) {
            "openai" -> OPENAI_BASE_URL
            "google" -> GOOGLE_BASE_URL
            else -> CLAUDE_BASE_URL
        }
        val baseUrl = when {
            vertex && !vertexIam -> JsonPrimitive(GOOGLE_VERTEX_EXPRESS_BASE_URL)
            else -> clean.textField("baseUrl") ?: JsonPrimitive(defaultBaseUrl)
        }
        val googleVertex = if (vertexIam) buildJsonObject {
            put("projectId", clean.textField("projectId") ?: JsonPrimitive(""))
            put("location", clean.textField("location") ?: JsonPrimitive("us-central1"))
            put("apiVersion", "v1")
        } else null
        val generationPath = if (type == "openai") {
            val responses = protocol == "openai_responses"
            clean.textField(if (responses) "responsesPath" else "chatCompletionsPath")
                ?: JsonPrimitive(if (responses) "/responses" else "/chat/completions")
        } else null
        // These source URLs need a query adapter, not a path with silently discarded parameters.
        if (generationPath?.content?.any { it == '?' || it == '#' } == true) return null

        val output = clean.filterKeys {
            it != "models" && !(type == "google" && (
                it == "googleVertex" || (vertex && it == "baseUrl") ||
                    (!vertex && (it == "providerId" || it == "authStyle"))
                ))
        }
        return JsonObject(output + buildJsonObject {
            put("baseUrl", baseUrl)
            put("protocol", protocol)
            if (vertex) {
                put("providerId", "google-vertex")
                put("authStyle", if (vertexIam) "google_service_account" else "query_key")
            }
            googleVertex?.let { put("googleVertex", it) }
            generationPath?.let { put("generationPath", it) }
        })
    }

    private fun JsonObject.textField(key: String): JsonPrimitive? {
        val value = this[key] ?: return null
        if (value == JsonNull) return null
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            throw StError.Validation("RikkaHub provider $key must be text")
        }
        return primitive
    }

    private fun JsonObject.boolean(key: String): Boolean {
        val value = this[key] ?: return false
        if (value == JsonNull) return false
        val primitive = value as? JsonPrimitive
        val parsed = primitive?.takeIf { !it.isString }?.booleanOrNull
            ?: throw StError.Validation("RikkaHub provider $key must be boolean")
        return parsed
    }
}
