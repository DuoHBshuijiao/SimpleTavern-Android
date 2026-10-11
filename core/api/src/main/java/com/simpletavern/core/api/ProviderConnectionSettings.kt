package com.simpletavern.core.api

import com.simpletavern.core.llm.ProviderHttpOptions
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.model.GoogleVertexOptions
import com.simpletavern.core.model.LlmProtocol
import com.simpletavern.core.model.StError
import java.net.URI
import kotlinx.serialization.json.*

/** Resolves only the chosen connection. Catalog data never changes stored settings or user models. */
internal object ProviderConnectionSettings {
    data class Selection(val protocol: LlmProtocol, val baseUrl: String, val authStyle: String,
        val apiVersion: String?, val catalogProviderId: String?)

    fun isCopilot(config: JsonObject): Boolean {
        val id = config.text("providerId")
        if (id != null) return id in setOf("github-copilot", "copilot")
        val host = config.text("baseUrl")?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }
        return host in setOf("api.individual.githubcopilot.com", "api.business.githubcopilot.com", "api.enterprise.githubcopilot.com")
    }

    fun isCodex(config: JsonObject): Boolean {
        val id = config.text("providerId")
        if (id != null) return id in setOf("openai-codex", "codex")
        return config.text("baseUrl")?.trimEnd('/') == CodexAuthService.BASE
    }

    fun isVertexIam(config: JsonObject): Boolean = config.text("providerId") == "google-vertex" &&
        (config.text("authStyle") == "google_service_account" || config["googleVertex"] is JsonObject)

    fun select(config: JsonObject, model: String): Selection {
        if (isVertexIam(config)) return selectVertex(config, model)
        if (config.text("providerId") == "google-vertex" && config.text("authStyle") == "query_key")
            return selectVertexExpress(config, model)
        val params = parameters(config)
        val explicitId = config.text("providerId")
        val configuredBase = config.text("baseUrl")
        val renderedBase = configuredBase?.let { render(it, params).also(::validateBase) }
        val explicitEntry = explicitId?.let(ProviderCatalog::find)
        val entry = explicitEntry ?: renderedBase?.let(ProviderCatalog::match)
        if (explicitId == "amazon-bedrock" || entry?.id == "amazon-bedrock")
            return selectBedrock(config, params, configuredBase, explicitEntry ?: entry!!)
        if (isCodex(config)) {
            val base = configuredBase?.trimEnd('/') ?: CodexAuthService.BASE
            if (base != CodexAuthService.BASE) throw StError.Validation("Codex compatibility tokens require the fixed Codex API root")
            if (config.text("authStyle") !in setOf(null, "oauth_pkce", "oauth_device", "bearer") ||
                config.text("protocolVariant") != null || config["protocolPaths"] !in setOf(null, JsonNull, JsonObject(emptyMap())))
                throw StError.Unsupported("Codex custom authentication, variants and paths are not supported")
            val protocol = ProviderProtocolSelection.select(JsonObject(config + mapOf(
                "protocol" to (config["protocol"]?.takeIf { it != JsonNull } ?: JsonPrimitive("openai_responses")),
                "defaultProtocol" to JsonPrimitive("openai_responses"),
                "supportedProtocols" to JsonArray(listOf(JsonPrimitive("openai_responses"))))), model, base)
            if (protocol.protocol != LlmProtocol.OPENAI_RESPONSES) throw StError.Unsupported("Codex compatibility requires Responses")
            return Selection(protocol.protocol, base, "oauth_pkce", null, "openai-codex")
        }
        if (isCopilot(config)) {
            val copilot = ProviderCatalog.find("github-copilot") ?: throw StError.Validation("Copilot catalog entry missing")
            val base = configuredBase ?: copilot.baseUrlTemplate!!
            CopilotAuthService.validateBase(base)
            if (config.text("authStyle") !in setOf(null, "oauth_device", "bearer") ||
                config.text("protocolVariant") != null || config["protocolPaths"] !in setOf(null, JsonNull, JsonObject(emptyMap())))
                throw StError.Unsupported("Copilot custom authentication, variants and paths are not supported")
            val effective = JsonObject(config + mapOf("defaultProtocol" to JsonPrimitive(copilot.defaultProtocol),
                "supportedProtocols" to JsonArray(copilot.supportedProtocols.map(::JsonPrimitive))))
            val protocol = ProviderProtocolSelection.select(effective, model, base)
            if (protocol.protocol == LlmProtocol.GEMINI_GENERATE_CONTENT) throw StError.Unsupported("Copilot does not use the Gemini native protocol")
            return Selection(protocol.protocol, base.trimEnd('/'), "oauth_device", null, copilot.id)
        }
        if (entry?.requiresOAuth == true) throw StError.Unsupported("selected provider requires its OAuth connection adapter")
        val variant = config.text("protocolVariant") ?: entry?.protocolVariant
        if (variant != null) throw StError.Unsupported("selected provider requires its protocol variant adapter")
        val template = configuredBase ?: entry?.baseUrlTemplate ?: throw StError.Validation("base URL is not configured")
        val base = render(template, params)
        validateBase(base)
        // Public metadata declarations can override catalog capabilities, never silently rewrite an origin.
        val effective = config.toMutableMap()
        fun default(name: String, value: JsonElement?) {
            val current = effective[name]
            if (current == null || current == JsonNull || current is JsonPrimitive && current.isString && current.content.isBlank())
                value?.let { effective[name] = it }
        }
        default("protocol", explicitEntry?.defaultProtocol?.let(::JsonPrimitive))
        default("defaultProtocol", entry?.defaultProtocol?.let(::JsonPrimitive))
        default("supportedProtocols", entry?.supportedProtocols?.let { JsonArray(it.map(::JsonPrimitive)) })
        val userPaths = config["protocolPaths"]?.takeIf { it != JsonNull }?.let { value ->
            val paths = value as? JsonObject ?: invalid()
            if (paths.size > 4 || paths.keys.any { it !in setOf("openai_compatible_chat", "openai_responses", "anthropic_messages", "gemini_generate_content") }) invalid()
            paths.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid() }
        }
        val catalogOwnsBase = configuredBase == null || entry?.baseUrlTemplate?.let {
            runCatching { samePrefix(render(it, params), base) }.getOrDefault(false)
        } == true || entry?.protocolPaths?.values?.any {
            runCatching { samePrefix(render(it, params), base) }.getOrDefault(false)
        } == true
        val paths = userPaths ?: if (catalogOwnsBase) entry?.protocolPaths.orEmpty() else emptyMap()
        val renderedPaths = paths.mapValues { (_, path) ->
            val url = render(path, params)
            validateBase(url)
            if (!sameOrigin(base, url)) throw StError.Validation("protocol paths cannot change the configured connection origin")
            JsonPrimitive(url)
        }
        effective["protocolPaths"] = JsonObject(renderedPaths)
        val protocol = ProviderProtocolSelection.select(JsonObject(effective), model, base)
        val explicitStyle = config.text("authStyle")
        // A pinned protocol retains its old per-protocol default unless the user bound a catalog provider.
        val defaultStyle = if (explicitStyle == null && protocol.protocol == LlmProtocol.GEMINI_GENERATE_CONTENT &&
            URI(protocol.baseUrl).host == "generativelanguage.googleapis.com") "x-goog-api-key" else entry?.authStyle?.takeIf {
            explicitEntry != null || config.text("protocol")?.equals("auto", ignoreCase = true) == true ||
                protocol.protocol.name == entry?.defaultProtocol?.let(::catalogProtocolName)
        }
        val style = ProviderHttpOptions.normalizeAuth(explicitStyle ?: defaultStyle, protocol.protocol)
        if (style == "aws_sigv4")
            throw StError.Unsupported("AWS SigV4 authentication is supported only by the Bedrock adapter")
        val version = listOfNotNull(params["api_version"], params["api-version"], config.text("apiVersion"))
        if (version.distinct().size > 1) throw StError.Validation("conflicting API versions")
        val apiVersion = version.firstOrNull()
        ProviderHttpOptions.validateApiVersion(apiVersion)
        return Selection(protocol.protocol, protocol.baseUrl, style, apiVersion, entry?.id)
    }

    private fun selectVertexExpress(config: JsonObject, model: String): Selection {
        val base = "https://aiplatform.googleapis.com/v1/publishers/google"
        if (config.text("baseUrl")?.trimEnd('/')?.let { it != base } == true)
            throw StError.Validation("Vertex Express requires its official global publisher root")
        if (config.text("protocolVariant") != null ||
            config["protocolPaths"]?.let { it != JsonNull && it != JsonObject(emptyMap()) } == true)
            throw StError.Unsupported("Vertex Express does not support custom protocol variants or paths")
        val effective = JsonObject(config + mapOf(
            "protocol" to (config["protocol"]?.takeIf { it != JsonNull } ?: JsonPrimitive("gemini_generate_content")),
            "defaultProtocol" to JsonPrimitive("gemini_generate_content"),
            "supportedProtocols" to JsonArray(listOf(JsonPrimitive("gemini_generate_content"))),
        ))
        val protocol = ProviderProtocolSelection.select(effective, model, base)
        if (protocol.protocol != LlmProtocol.GEMINI_GENERATE_CONTENT)
            throw StError.Unsupported("Vertex Express requires the Gemini native protocol")
        return Selection(protocol.protocol, base, "query_key", null, "google-vertex")
    }

    private fun selectVertex(config: JsonObject, model: String): Selection {
        if (config.text("protocolVariant") != null ||
            config["protocolPaths"]?.let { it != JsonNull && it != JsonObject(emptyMap()) } == true)
            throw StError.Unsupported("Vertex IAM does not support custom protocol variants or paths")
        val options = config["googleVertex"]?.let { Jsonx.decodeOrNull<GoogleVertexOptions>(it.toString()) }
            ?: throw StError.Validation("Google Vertex project options are required")
        if (!options.projectId.matches(Regex("(?:[a-z][a-z0-9-]{4,28}[a-z0-9]|[0-9]{6,30})")) ||
            !options.location.matches(Regex("[a-z][a-z0-9-]{0,62}")) || options.apiVersion !in setOf("v1", "v1beta1"))
            throw StError.Validation("invalid Google Vertex project options")
        val effective = JsonObject(config + mapOf(
            "protocol" to (config["protocol"]?.takeIf { it != JsonNull } ?: JsonPrimitive("auto")),
            "defaultProtocol" to JsonPrimitive("gemini_generate_content"),
            "supportedProtocols" to JsonArray(listOf(JsonPrimitive("gemini_generate_content"), JsonPrimitive("openai_compatible_chat"))),
        ))
        val protocol = ProviderProtocolSelection.select(effective, model, "https://aiplatform.googleapis.com")
        if (protocol.protocol !in setOf(LlmProtocol.GEMINI_GENERATE_CONTENT, LlmProtocol.OPENAI_COMPATIBLE))
            throw StError.Unsupported("Google Vertex IAM supports Gemini native and OpenAI compatible protocols")
        val host = if (options.location == "global") "aiplatform.googleapis.com" else "${options.location}-aiplatform.googleapis.com"
        val route = if (protocol.protocol == LlmProtocol.GEMINI_GENERATE_CONTENT) "publishers/google" else "endpoints/openapi"
        val base = "https://$host/${options.apiVersion}/projects/${options.projectId}/locations/${options.location}/$route"
        return Selection(protocol.protocol, base, "google_service_account", null, "google-vertex")
    }

    private fun selectBedrock(config: JsonObject, params: Map<String, String>, configuredBase: String?,
        entry: ProviderCatalogEntry): Selection {
        if (entry.id != "amazon-bedrock") throw StError.Unsupported("selected provider is not the Bedrock native adapter")
        if (entry.requiresOAuth) throw StError.Unsupported("Bedrock requires a Bedrock API key")
        val variant = config.text("protocolVariant") ?: entry.protocolVariant
        if (variant !in setOf("bedrock", "bedrock_converse"))
            throw StError.Unsupported("unknown Bedrock protocol variant")
        val authStyle = ProviderHttpOptions.normalizeAuth(config.text("authStyle"), LlmProtocol.BEDROCK_CONVERSE)
        if (authStyle !in setOf("bearer", "aws_sigv4"))
            throw StError.Unsupported("Bedrock supports Bearer or AWS SigV4 authentication")
        if (config["protocolPaths"]?.let { it != JsonNull && it != JsonObject(emptyMap()) } == true)
            throw StError.Unsupported("Bedrock does not support custom protocol paths")
        val requested = config.text("protocol")?.lowercase()
        if (requested != null && requested !in setOf("auto", "anthropic_messages", "bedrock_converse"))
            throw StError.Unsupported("Bedrock does not support the explicitly selected protocol")

        val region = (params["region"] ?: configuredBase?.let { base ->
            val host = runCatching { URI(base).host }.getOrNull().orEmpty()
            Regex("bedrock-runtime\\.([a-z]{2}(?:-[a-z0-9]+)+-[0-9]+)\\.amazonaws\\.com(?:\\.cn)?")
                .matchEntire(host)?.groupValues?.get(1)
        })?.takeIf { it.matches(Regex("[a-z]{2}(?:-[a-z0-9]+)+-[0-9]+")) }
            ?: throw StError.Validation("Bedrock region is required as a public provider parameter")
        val template = entry.baseUrlTemplate ?: throw StError.Validation("Bedrock catalog endpoint is missing")
        val rendered = render(template, params + ("region" to region))
        val expected = if (region.startsWith("cn-")) rendered.replace(".amazonaws.com", ".amazonaws.com.cn") else rendered
        if (configuredBase != null && render(configuredBase, params + ("region" to region)).trimEnd('/') != expected)
            throw StError.Validation("Bedrock base URL must match the selected region's runtime root")
        val uri = runCatching { URI(expected) }.getOrNull()
        val suffix = if (region.startsWith("cn-")) "amazonaws.com.cn" else "amazonaws.com"
        if (uri?.scheme != "https" || uri.host != "bedrock-runtime.$region.$suffix" || uri.port != -1 ||
            uri.rawUserInfo != null || uri.rawPath !in setOf(null, "") || uri.rawQuery != null || uri.rawFragment != null)
            throw StError.Validation("Bedrock requires the regional HTTPS runtime root")
        val protocol = if (requested == "bedrock_converse") LlmProtocol.BEDROCK_CONVERSE else LlmProtocol.ANTHROPIC_MESSAGES
        return Selection(protocol, expected, authStyle, null, entry.id)
    }

    private fun catalogProtocolName(raw: String): String = when (raw) {
        "openai_compatible_chat" -> "OPENAI_COMPATIBLE"
        "openai_responses" -> "OPENAI_RESPONSES"
        "anthropic_messages" -> "ANTHROPIC_MESSAGES"
        "gemini_generate_content" -> "GEMINI_GENERATE_CONTENT"
        "bedrock_converse" -> "BEDROCK_CONVERSE"
        else -> ""
    }
    private fun parameters(config: JsonObject): Map<String, String> = config["providerParams"]?.takeIf { it != JsonNull }?.let { value ->
        val obj = value as? JsonObject ?: invalid()
        if (obj.size > 32 || obj.toString().toByteArray(Charsets.UTF_8).size > 16_384) invalid()
        obj.mapValues { (key, v) ->
            if (!key.matches(Regex("[A-Za-z0-9_-]{1,64}"))) invalid()
            if (ProviderHttpOptions.sensitive(key)) throw StError.Validation("provider parameters are public; put secrets in encrypted credential storage")
            val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: invalid()
            if (text.length > 1024 || text.any(Char::isISOControl)) invalid()
            text
        }
    }.orEmpty()

    private fun render(template: String, params: Map<String, String>): String {
        if (template.length > 8192) invalid()
        val expression = Regex("\\{([A-Za-z0-9_]+)}")
        if (expression.findAll(template).take(65).count() > 64) invalid()
        return expression.replace(template) { match ->
            val key = match.groupValues[1]
            val value = params[key]?.takeIf(String::isNotBlank)
                ?: throw StError.Validation("provider URL requires public placeholder parameters")
            // Single DNS label / path segment only. Never interpolate a URL, slash, query or dot traversal.
            if (value in setOf(".", "..") || !value.matches(Regex("[A-Za-z0-9_-]{1,256}"))) invalid()
            val hostTemplate = template.substringAfter("://").substringBefore('/')
            if (hostTemplate.contains(match.value) && (!value.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")))) invalid()
            value
        }.also { if (it.length > 8192 || it.contains('{') || it.contains('}')) invalid() }
    }
    private fun validateBase(base: String) {
        val uri = runCatching { URI(base) }.getOrNull()
        if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank() || uri?.rawUserInfo != null ||
            uri?.rawQuery != null || uri?.rawFragment != null || uri?.port?.let { it != -1 && it !in 1..65535 } == true ||
            uri?.path?.split('/')?.any { it in setOf(".", "..") } == true) invalid()
    }
    private fun sameOrigin(a: String, b: String): Boolean {
        val first = URI(a); val second = URI(b)
        fun port(uri: URI) = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
        return first.scheme == second.scheme && first.host.equals(second.host, ignoreCase = true) && port(first) == port(second)
    }
    private fun samePrefix(a: String, b: String) = sameOrigin(a, b) && URI(a).rawPath.trimEnd('/') == URI(b).rawPath.trimEnd('/')
    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it != JsonNull }?.let {
        val primitive = (it as? JsonPrimitive)?.takeIf { p -> p.isString } ?: invalid()
        primitive.content.trim().takeIf(String::isNotEmpty)
    }
    private fun invalid(): Nothing = throw StError.Validation("invalid provider URL template or public connection parameters")
}
