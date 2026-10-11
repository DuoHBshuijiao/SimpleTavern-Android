package com.simpletavern.core.api

import com.simpletavern.core.model.LlmProtocol
import com.simpletavern.core.model.StError
import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.*

/** Pure selection, never probes a gateway, replaces the model or retries a failed protocol. */
internal object ProviderProtocolSelection {
    data class Selection(val protocol: LlmProtocol, val baseUrl: String)
    fun select(config: JsonObject, model: String, base: String): Selection {
        fun text(key: String): String? = config[key]?.takeIf { it != JsonNull }?.let {
            val primitive = (it as? JsonPrimitive)?.takeIf { p -> p.isString }
                ?: throw StError.Validation("protocol field must be text")
            primitive.content.trim().lowercase(Locale.ROOT).takeIf(String::isNotEmpty)
        }
        val raw = text("protocol") ?: "openai_compatible_chat"
        fun routed(chosen: LlmProtocol): Selection {
            val paths = config["protocolPaths"] as? JsonObject
            val key = when (chosen) {
                LlmProtocol.OPENAI_COMPATIBLE -> "openai_compatible_chat"
                LlmProtocol.OPENAI_RESPONSES -> "openai_responses"
                LlmProtocol.ANTHROPIC_MESSAGES -> "anthropic_messages"
                LlmProtocol.GEMINI_GENERATE_CONTENT -> "gemini_generate_content"
                LlmProtocol.BEDROCK_CONVERSE -> "bedrock_converse"
            }
            val route = (paths?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
            return Selection(chosen, route ?: base)
        }
        if (raw != "auto") return routed(protocol(raw))
        val host = URI(base).host.lowercase(Locale.ROOT)
        val officialDefault = when (host) {
            "api.anthropic.com" -> LlmProtocol.ANTHROPIC_MESSAGES
            "generativelanguage.googleapis.com" -> if (URI(base).path.trimEnd('/').endsWith("/openai"))
                LlmProtocol.OPENAI_COMPATIBLE else LlmProtocol.GEMINI_GENERATE_CONTENT
            "api.openai.com" -> LlmProtocol.OPENAI_RESPONSES
            else -> LlmProtocol.OPENAI_COMPATIBLE
        }
        val default = text("defaultProtocol")?.let(::protocol) ?: officialDefault
        val declared = config["supportedProtocols"]?.takeIf { it != JsonNull }?.let { value ->
            val array = value as? JsonArray ?: throw StError.Validation("supportedProtocols must be an array")
            if (array.size !in 1..4) throw StError.Validation("invalid supportedProtocols")
            array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(::protocol)
                ?: throw StError.Validation("supported protocol must be text") }.toSet()
        }
        if (declared != null && default !in declared) throw StError.Validation("defaultProtocol is not supported")
        val target = when {
            Regex("(^|[/:._-])claude", RegexOption.IGNORE_CASE).containsMatchIn(model) -> LlmProtocol.ANTHROPIC_MESSAGES
            Regex("(^|[/:._-])(gemini|gemma)", RegexOption.IGNORE_CASE).containsMatchIn(model) -> LlmProtocol.GEMINI_GENERATE_CONTENT
            Regex("(^|[/:._-])(gpt-?\\d|gpt-oss|o1|o3|o4|codex|chatgpt|davinci)", RegexOption.IGNORE_CASE).containsMatchIn(model) -> LlmProtocol.OPENAI_RESPONSES
            else -> null
        }
        val supported = declared ?: when (host) {
            "api.anthropic.com" -> setOf(LlmProtocol.ANTHROPIC_MESSAGES)
            "generativelanguage.googleapis.com" -> setOf(LlmProtocol.GEMINI_GENERATE_CONTENT, LlmProtocol.OPENAI_COMPATIBLE)
            "api.openai.com" -> setOf(LlmProtocol.OPENAI_RESPONSES, LlmProtocol.OPENAI_COMPATIBLE)
            else -> setOf(default)
        }
        val chosen = target?.takeIf { it in supported } ?: default
        val routed = routed(chosen)
        if (routed.baseUrl != base) return routed
        // Only the official Google host has a known compatible/native path transition here.
        // Never replace a configured gateway origin with a catalog's public host.
        val effectiveBase = when {
            host == "generativelanguage.googleapis.com" && chosen == LlmProtocol.GEMINI_GENERATE_CONTENT &&
                URI(base).path.trimEnd('/').endsWith("/openai") -> base.trimEnd('/').removeSuffix("/openai")
            chosen == LlmProtocol.ANTHROPIC_MESSAGES && URI(base).path.trimEnd('/').endsWith("/chat/completions") ->
                base.trimEnd('/').removeSuffix("/chat/completions")
            else -> base
        }
        return Selection(chosen, effectiveBase)
    }
    private fun protocol(raw: String): LlmProtocol = when (raw.trim().lowercase(Locale.ROOT)) {
        "openai_compatible", "openai_compatible_chat", "openai", "chat_completions" -> LlmProtocol.OPENAI_COMPATIBLE
        "openai_responses", "responses" -> LlmProtocol.OPENAI_RESPONSES
        "anthropic_messages", "anthropic", "claude" -> LlmProtocol.ANTHROPIC_MESSAGES
        "gemini_generate_content", "gemini", "google" -> LlmProtocol.GEMINI_GENERATE_CONTENT
        "bedrock_converse" -> LlmProtocol.BEDROCK_CONVERSE
        else -> throw StError.Unsupported("protocol requires explicit supported protocol selection")
    }
}
