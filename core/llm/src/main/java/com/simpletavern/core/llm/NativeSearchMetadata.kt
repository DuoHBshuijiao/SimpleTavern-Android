package com.simpletavern.core.llm

import com.simpletavern.core.model.NativeSearchTrace
import com.simpletavern.core.model.StError
import kotlinx.serialization.json.*

/** Presentation/source metadata only. Opaque server content stays unchanged in ProviderTurn. */
internal object NativeSearchMetadata {
    fun responses(output: JsonArray, keys: List<String>): NativeSearchTrace? {
        val entries = output.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            when ((item["type"] as? JsonPrimitive)?.content) {
                "web_search_call" -> item
                "message" -> (item["content"] as? JsonArray)?.flatMap { part ->
                    ((part as? JsonObject)?.get("annotations") as? JsonArray)?.toList().orEmpty()
                }?.takeIf { it.isNotEmpty() }?.let { JsonObject(mapOf("annotations" to JsonArray(it))) }
                else -> null
            }
        }
        return entries.takeIf { it.isNotEmpty() }?.let { trace("openai_responses", JsonArray(it), keys) }
    }
    fun anthropic(output: JsonArray, keys: List<String>): NativeSearchTrace? {
        val entries = output.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            when ((item["type"] as? JsonPrimitive)?.content) {
                "server_tool_use" -> item
                "web_search_tool_result", "web_fetch_tool_result" -> JsonObject(item + ("content" to when (val content = item["content"]) {
                    is JsonArray -> JsonArray(content.map { result -> if (result is JsonObject)
                        JsonObject(result.filterKeys { it in setOf("type", "title", "url", "page_age") }) else result })
                    else -> content ?: JsonNull
                }))
                "text" -> (item["citations"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { citations ->
                    JsonObject(mapOf("citations" to JsonArray(citations.map { citation -> if (citation is JsonObject)
                        JsonObject(citation.filterKeys { it in setOf("type", "url", "title", "cited_text") }) else citation }))) }
                else -> null
            }
        }
        return entries.takeIf { it.isNotEmpty() }?.let { trace("anthropic_messages", JsonArray(it), keys) }
    }
    fun gemini(frames: List<JsonObject>, keys: List<String>) = frames.takeIf { it.isNotEmpty() }?.let { trace("gemini_generate_content", JsonArray(it), keys) }
    private fun trace(provider: String, payload: JsonElement, keys: List<String>): NativeSearchTrace {
        var nodes = 0
        val secrets = keys.filter(String::isNotBlank).distinct().sortedByDescending(String::length)
        fun redact(element: JsonElement, depth: Int): JsonElement {
            if (++nodes > 16_384 || depth > 64) throw StError.Provider("native search metadata exceeds structure budget")
            fun safe(value: String) = secrets.fold(value) { text, key -> text.replace(key, "<redacted>") }
            return when (element) {
                is JsonObject -> JsonObject(element.map { (name, value) -> safe(name) to redact(value, depth + 1) }.toMap())
                is JsonArray -> JsonArray(element.map { redact(it, depth + 1) })
                is JsonPrimitive -> if (element.isString) JsonPrimitive(safe(element.content)) else element
            }
        }
        val raw = redact(payload, 0).toString()
        if (raw.toByteArray(Charsets.UTF_8).size > 131_072) throw StError.Provider("native search metadata exceeds 128 KiB")
        return NativeSearchTrace(provider, raw)
    }
}
