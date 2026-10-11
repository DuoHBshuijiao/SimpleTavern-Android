package com.simpletavern.core.api

import com.simpletavern.core.model.*
import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.*

/** Maps source controls only. No cache lookup/create/delete, digest, automatic downgrade or replay. */
internal object ProviderPromptCacheSettings {
    data class GeminiSettings(val ttlSeconds: Long, val historyTail: Boolean)
    data class Selection(val params: JsonObject, val gemini: GeminiSettings? = null)
    private val nativeKeys = setOf("prompt_cache_key", "prompt_cache_retention", "prompt_cache_options", "cache_control", "cache_breakpoints")
    fun select(params: JsonObject, protocol: LlmProtocol, baseUrl: String, model: String, catalogId: String?,
        chat: ChatSession, characterId: String?): Selection {
        val config = params["promptCache"]?.takeIf { it != JsonNull }?.let { it as? JsonObject ?: invalid() }
            ?: params["anthropicPromptCache"]?.takeIf { it != JsonNull }?.let { value ->
                val ttl = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
                if (ttl !in setOf("off", "5m", "1h")) invalid()
                buildJsonObject { put("mode", if (ttl == "off") "off" else "explicit"); if (ttl != "off") put("ttl", ttl) }
            } ?: return Selection(params)
        if (config.keys.any { it !in setOf("mode", "ttl", "breakpoints", "cacheKey", "explicitMarkers") })
            throw StError.Unsupported("unknown source prompt-cache control")
        if (params.keys.any { it in nativeKeys }) throw StError.Validation("source promptCache conflicts with native cache controls")
        fun text(name: String): String? = config[name]?.takeIf { it != JsonNull }?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid() }
        val rawMode = text("mode") ?: "auto"
        if (rawMode !in setOf("auto", "off", "implicit", "explicit", "best_effort")) invalid()
        val mode = if (rawMode == "auto") ProviderCatalog.find(catalogId.orEmpty())?.cacheStrategy?.let { if (it == "none") "off" else it } ?: "best_effort" else rawMode
        val output = params.toMutableMap().apply { remove("promptCache"); remove("anthropicPromptCache") }
        if (config["explicitMarkers"] !in setOf(null, JsonNull, JsonPrimitive(false), JsonPrimitive(true))) invalid()
        if (mode == "off") return Selection(JsonObject(output)) // opt-out cannot disable a vendor's transparent implicit cache.
        if (config["explicitMarkers"] == JsonPrimitive(true)) throw StError.Unsupported("provider message-level cache markers need their native adapter")
        val sourceTtl = config["ttl"]?.takeIf { it != JsonNull }
        val ttl = if (protocol == LlmProtocol.GEMINI_GENERATE_CONTENT && sourceTtl is JsonPrimitive && !sourceTtl.isString) {
            sourceTtl.longOrNull?.takeIf { it in 1..604_800 }?.toString() ?: invalid()
        } else text("ttl")
        val breakpoints = config["breakpoints"]?.takeIf { it != JsonNull }?.let { value ->
            val values = value as? JsonArray ?: invalid()
            if (values.size !in 1..3 || values.distinct().size != values.size || values.any {
                    it !is JsonPrimitive || !it.isString || it.content !in setOf("system", "tools", "history_tail") }) invalid()
            values
        } ?: JsonArray(listOf(JsonPrimitive("system")))
        if (mode == "best_effort") {
            if (ttl != null) throw StError.Unsupported("best-effort caching cannot enforce a TTL")
            return Selection(JsonObject(output))
        }
        val uri = URI(baseUrl)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val official = uri.scheme == "https" && (uri.port == -1 || uri.port == 443)
        when (protocol) {
            LlmProtocol.BEDROCK_CONVERSE -> {
                val bedrockRoot = official && Regex("bedrock-runtime\\.([a-z]{2}(?:-[a-z0-9]+)+-[0-9]+)\\.amazonaws\\.com(?:\\.cn)?")
                    .matchEntire(host) != null && uri.rawPath in setOf(null, "") && uri.rawUserInfo == null &&
                    uri.rawQuery == null && uri.rawFragment == null
                if (!bedrockRoot)
                    throw StError.Unsupported("source Bedrock Converse cache controls require an official Bedrock runtime root endpoint")
                when (mode) {
                    "implicit" -> {
                        if (ttl != null) throw StError.Unsupported("Bedrock implicit caching cannot enforce a TTL")
                        return Selection(JsonObject(output))
                    }
                    "explicit" -> {
                        if (ttl != null && ttl !in setOf("5m", "1h"))
                            throw StError.Unsupported("Bedrock Converse cache TTL must be 5m or 1h")
                        output["cache_control"] = buildJsonObject {
                            put("type", "ephemeral")
                            put("ttl", ttl ?: "5m")
                        }
                        output["cache_breakpoints"] = breakpoints
                    }
                    else -> throw StError.Unsupported("source Bedrock Converse cache controls need a native adapter")
                }
            }
            LlmProtocol.GEMINI_GENERATE_CONTENT -> {
                if (mode == "implicit") {
                    if (ttl != null) throw StError.Unsupported("Gemini implicit caching cannot enforce a TTL")
                    return Selection(JsonObject(output))
                }
                if (mode != "explicit" || runCatching { GeminiCacheRoutes.select(baseUrl) }.isFailure)
                    throw StError.Unsupported("Gemini explicit cache requires official Developer API or Vertex native routes")
                val seconds = if (ttl == null) 3600L else ttl.toLongOrNull() ?: Regex("([0-9]+)(s|m|h)").matchEntire(ttl)?.let {
                    val number = it.groupValues[1].toLongOrNull()?.takeIf { n -> n in 1..604800 } ?: invalid()
                    number * when (it.groupValues[2]) { "h" -> 3600; "m" -> 60; else -> 1 }
                } ?: invalid()
                if (seconds !in 1..604800) invalid()
                return Selection(JsonObject(output), GeminiSettings(seconds, JsonPrimitive("history_tail") in breakpoints))
            }
            LlmProtocol.ANTHROPIC_MESSAGES -> {
                val bedrockRoot = official && Regex("bedrock-runtime\\.([a-z]{2}(?:-[a-z0-9]+)+-[0-9]+)\\.amazonaws\\.com(?:\\.cn)?")
                    .matchEntire(host) != null && uri.rawPath in setOf(null, "") && uri.rawUserInfo == null &&
                    uri.rawQuery == null && uri.rawFragment == null
                if (!official || host != "api.anthropic.com" && !bedrockRoot)
                    throw StError.Unsupported("source Claude cache controls need the Claude or Bedrock Anthropic adapter")
                if (ttl != null && ttl !in setOf("5m", "1h")) throw StError.Unsupported("Claude cache TTL must be 5m or 1h")
                output["cache_control"] = buildJsonObject { put("type", "ephemeral"); put("ttl", ttl ?: "5m") }
                if (mode == "explicit") output["cache_breakpoints"] = breakpoints
            }
            LlmProtocol.OPENAI_COMPATIBLE, LlmProtocol.OPENAI_RESPONSES -> {
                if (!official || !(host == "api.openai.com" || host.endsWith(".openai.azure.com")))
                    throw StError.Unsupported("source OpenAI cache controls need an OpenAI/Azure native adapter")
                val newStyle = Regex("^gpt-(?:5\\.(?:[6-9]|[1-9][0-9]+)|[6-9](?:[.-]|$)|[1-9][0-9]+(?:[.-]|$))", RegexOption.IGNORE_CASE)
                    .containsMatchIn(model.removePrefix("models/"))
                if (mode == "explicit" && (!newStyle || protocol != LlmProtocol.OPENAI_RESPONSES || breakpoints != JsonArray(listOf(JsonPrimitive("system")))))
                    throw StError.Unsupported("explicit cache currently supports a system breakpoint on newer OpenAI Responses models")
                val selector = text("cacheKey") ?: if (config.containsKey("cacheKey")) null else "per_chat"
                selector?.let { output["prompt_cache_key"] = JsonPrimitive(cacheKey(it, chat, characterId)) }
                if (newStyle) {
                    if (ttl != null && ttl != "30m") throw StError.Unsupported("new OpenAI cache TTL must be 30m")
                    output["prompt_cache_options"] = buildJsonObject { put("mode", mode); put("ttl", "30m") }
                    if (mode == "explicit") output["cache_breakpoints"] = breakpoints
                } else if (ttl != null) {
                    if (ttl !in setOf("in_memory", "24h") || model.startsWith("gpt-5.5", true) && ttl != "24h")
                        throw StError.Unsupported("unsupported OpenAI cache retention for the selected model")
                    output["prompt_cache_retention"] = JsonPrimitive(ttl)
                }
            }
            else -> throw StError.Unsupported("prompt caching needs a native adapter for this protocol")
        }
        return Selection(JsonObject(output))
    }

    private fun cacheKey(selector: String, chat: ChatSession, characterId: String?): String {
        fun part(value: String) = "${value.length}:$value"
        val namespace = chat.sourceNamespace ?: "local"
        val key = when (selector) {
            "per_chat" -> "st:${part(namespace)}:chat:${part(chat.id)}"
            "per_character" -> "st:${part(namespace)}:character:${part(characterId ?: chat.characterId)}"
            "global" -> "st:${part(namespace)}:global"
            else -> selector
        }
        if (key.isBlank() || key.length > 256 || key.any(Char::isISOControl))
            throw StError.Validation("cache key exceeds public identifier budget; configure a shorter key")
        return key // Deliberately no hashing or secret-derived identity.
    }
    private fun invalid(): Nothing = throw StError.Validation("invalid source prompt-cache controls")
}
