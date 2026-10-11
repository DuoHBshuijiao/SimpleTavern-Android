package com.simpletavern.core.llm

import com.simpletavern.core.model.LlmProtocol
import com.simpletavern.core.model.StError
import java.util.Locale
import okhttp3.HttpUrl

/** Runtime-only, never serialized into a task, usage event or provider selection. */
class ProviderHttpOptions(authStyle: String? = null, headers: Map<String, String> = emptyMap(),
    credentialMaterials: List<String> = emptyList(), val apiVersion: String? = null) {
    val authStyle = authStyle
    val headers = headers.toMap()
    val credentialMaterials = credentialMaterials.filter(String::isNotBlank).distinct().toList()
    override fun toString() = "ProviderHttpOptions(<redacted>)"

    fun requestHeaders(protocol: LlmProtocol, apiKey: String): Map<String, String> {
        validateSecret(apiKey)
        validateApiVersion(apiVersion)
        val result = linkedMapOf<String, String>()
        val style = normalizeAuth(authStyle, protocol)
        if (protocol == LlmProtocol.BEDROCK_CONVERSE && style !in setOf("bearer", "aws_sigv4"))
            throw StError.Unsupported("Bedrock Converse requires Bearer or AWS SigV4 authentication")
        if (protocol == LlmProtocol.BEDROCK_CONVERSE && headers.keys.any(::sensitive))
            throw StError.Unsupported("Bedrock Converse does not allow sensitive custom HTTP headers")
        if (apiKey.isNotBlank()) when (style) {
            "bearer" -> result["authorization"] = "Bearer $apiKey"
            "x-api-key", "api-key", "x-goog-api-key" -> result[style] = apiKey
        }
        if (protocol == LlmProtocol.ANTHROPIC_MESSAGES) result["anthropic-version"] = "2023-06-01"
        if (headers.size > 64) invalid()
        headers.forEach { (name, value) ->
            validateHeader(name, value)
            if (sensitive(name) && (listOf(apiKey) + credentialMaterials).none { it.isNotBlank() && value.contains(it) })
                throw StError.Validation("sensitive HTTP headers require tracked encrypted credentials")
            result[name.lowercase(Locale.ROOT)] = value
        }
        if (result.entries.sumOf { it.key.length.toLong() + it.value.length + 4 } > 60 * 1024) invalid()
        return result
    }

    /** Runtime URL only: query credentials never enter stored baseUrl, preview or request metadata. */
    fun requestUrl(url: HttpUrl, protocol: LlmProtocol, apiKey: String): HttpUrl {
        validateSecret(apiKey)
        validateApiVersion(apiVersion)
        val style = normalizeAuth(authStyle, protocol)
        if (url.queryParameterNames.any { it.equals("key", true) || it.equals("api-version", true) })
            throw StError.Validation("provider URL already contains managed authentication or API-version parameters")
        return url.newBuilder().apply {
            if (style == "query_key" && apiKey.isNotBlank()) addQueryParameter("key", apiKey)
            apiVersion?.let { addQueryParameter("api-version", it) }
        }.build()
    }

    companion object {
        private val forbidden = setOf("host", "content-length", "content-type", "accept", "connection", "transfer-encoding",
            "upgrade", "trailer", "te", "keep-alive", "proxy-connection", "accept-encoding")
        fun normalizeAuth(raw: String?, protocol: LlmProtocol): String {
            val style = raw?.trim()?.lowercase(Locale.ROOT)
            // Preserve the previously supported api_key alias (protocol default), distinct from desktop api-key.
            if (style.isNullOrBlank() || style == "api_key") return when (protocol) {
                LlmProtocol.ANTHROPIC_MESSAGES -> "x-api-key"
                LlmProtocol.GEMINI_GENERATE_CONTENT -> "x-goog-api-key"
                else -> "bearer"
            }
            return when (val normalized = style.replace('_', '-')) {
                "bearer", "x-api-key", "api-key", "x-goog-api-key", "none" -> normalized
                "aws-sigv4" -> "aws_sigv4"
                "query-key" -> "query_key"
                "google-api-key" -> "x-goog-api-key"
                else -> throw StError.Unsupported("authentication style requires a separate adapter")
            }
        }
        fun validateSecret(secret: String) {
            if (secret.length > 8192 || secret.any { it.code !in 33..126 }) invalid()
        }
        fun validateApiVersion(value: String?) {
            if (value != null && !value.matches(Regex("[A-Za-z0-9._-]{1,128}")))
                throw StError.Validation("invalid public API version")
        }
        fun validateHeader(name: String, value: String) {
            if (name.isEmpty() || name.length > 128 || !name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) ||
                name.lowercase(Locale.ROOT) in forbidden || value.length > 16_384 || value.any { it.code !in 32..126 }) invalid()
        }
        fun sensitive(name: String): Boolean {
            val key = name.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
            return key in setOf("authorization", "proxyauthorization", "cookie", "setcookie", "authenticationtoken") ||
                key.contains("apikey") || key.contains("token") || key.contains("secret") || key.contains("password")
        }
        private fun invalid(): Nothing = throw StError.Validation("invalid provider credential or HTTP header configuration")
    }
}
