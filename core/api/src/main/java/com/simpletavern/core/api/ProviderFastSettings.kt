package com.simpletavern.core.api

import com.simpletavern.core.llm.ProviderHttpOptions
import com.simpletavern.core.model.*
import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.*

/** Explicit Fast is a request mode on the same model, never a retry/fallback or model upgrade. */
internal data class ProviderFastSettings(val params: JsonObject, val fastRequested: Boolean?, val beta: String?) {
    fun apply(options: ProviderHttpOptions): ProviderHttpOptions {
        if (beta == null) return options
        val headers = options.headers.toMutableMap()
        val old = headers.entries.firstOrNull { it.key.equals("anthropic-beta", true) }
        val values = old?.value?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        old?.let { headers.remove(it.key) }
        headers["anthropic-beta"] = (values + beta).distinct().joinToString(",")
        return ProviderHttpOptions(options.authStyle, headers, options.credentialMaterials, options.apiVersion)
    }

    companion object {
        fun select(params: JsonObject, memberFast: Boolean?, protocol: LlmProtocol, baseUrl: String,
            model: String, catalogId: String?): ProviderFastSettings {
            val configured = params["fast_mode"]?.takeIf { it != JsonNull }?.let {
                (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull
                    ?: throw StError.Validation("fastMode must be boolean")
            }
            val requested = memberFast ?: configured
            fun text(name: String): String? = params[name]?.takeIf { it != JsonNull }?.let {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                    ?: throw StError.Validation("request mode must be text")
            }
            val tier = text("service_tier")
            val speed = text("speed")
            val nativeFast = tier in setOf("priority", "fast") || speed == "fast"
            if (requested == false && nativeFast || requested == true &&
                (tier != null && tier !in setOf("priority", "fast") || speed != null && speed != "fast"))
                throw StError.Validation("fastMode conflicts with explicit service tier or speed")
            val output = params.toMutableMap()
            output.remove("fast_mode")
            if (requested != true && speed != "fast") return ProviderFastSettings(JsonObject(output), requested ?: nativeFast.takeIf { it }, null)
            val uri = URI(baseUrl)
            val host = uri.host.orEmpty().lowercase(Locale.ROOT)
            val official = uri.scheme == "https" && (uri.port == -1 || uri.port == 443)
            val entry = ModelCatalog.capabilities(model, catalogId ?: when (host) {
                "api.openai.com" -> "openai"; "api.anthropic.com" -> "anthropic"; else -> "unknown"
            }).matchedModel
            val mode = entry?.modes?.get("fast")
            var beta: String? = null
            when (protocol) {
                LlmProtocol.OPENAI_COMPATIBLE, LlmProtocol.OPENAI_RESPONSES -> {
                    if (!official || !(host == "api.openai.com" || host.endsWith(".openai.azure.com")) ||
                        mode?.body?.get("service_tier") != JsonPrimitive("priority"))
                        throw StError.Unsupported("Fast needs a declared model mode and an OpenAI/Azure native adapter; configure service_tier explicitly for a compatible gateway")
                    output["service_tier"] = JsonPrimitive(tier ?: "priority")
                }
                LlmProtocol.ANTHROPIC_MESSAGES -> {
                    if (!official || host != "api.anthropic.com" || mode?.body?.get("speed") != JsonPrimitive("fast"))
                        throw StError.Unsupported("Claude Fast needs a declared model mode on the Claude API")
                    output["speed"] = JsonPrimitive("fast")
                    beta = mode.headers["anthropic-beta"] ?: throw StError.Unsupported("Claude Fast beta declaration missing")
                }
                else -> throw StError.Unsupported("Fast needs a native adapter for this protocol")
            }
            return ProviderFastSettings(JsonObject(output), true, beta)
        }
    }
}
