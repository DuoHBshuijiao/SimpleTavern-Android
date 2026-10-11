package com.simpletavern.core.api

import com.simpletavern.core.data.ContentRepository
import com.simpletavern.core.data.CredentialStore
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.llm.ProviderConfig
import com.simpletavern.core.llm.ProviderHttpOptions
import com.simpletavern.core.model.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.Locale

/** Public preview intentionally contains no key or raw provider parameters. */
data class ProviderSelection(
    val presetId: String?, val credentialId: String, val protocol: LlmProtocol,
    val baseUrl: String, val model: String, val hasCredential: Boolean,
    val catalogProviderId: String? = null, val authStyle: String? = null, val apiVersion: String? = null,
    val fastRequested: Boolean? = null,
    val streaming: Boolean = true,
)

internal class ProviderResolver(private val content: ContentRepository, private val credentials: CredentialStore,
    private val oauth: ProviderOAuthService) {
    private val selectedCaches = java.util.concurrent.ConcurrentHashMap<Pair<String, String?>, GeminiCacheGenerationSelection>()
    internal fun selectedCache(chatId: String, characterId: String?) = selectedCaches[chatId to characterId]
    internal fun selectCache(chatId: String, characterId: String?, selection: GeminiCacheGenerationSelection?) {
        if (selection == null) selectedCaches.remove(chatId to characterId) else selectedCaches[chatId to characterId] = selection
    }
    private data class Resolved(val preview: ProviderSelection, val config: JsonObject, val params: JsonObject,
                                val temperature: Double?, val topP: Double?, val headers: ProviderHeaderSettings,
                                val fast: ProviderFastSettings, val cache: ProviderPromptCacheSettings.GeminiSettings?)

    suspend fun preview(chat: ChatSession, characterId: String? = null): ProviderSelection = select(chat, characterId).preview

    /** Explicit directory read; no chat, generation defaults, cache selection or model is required. */
    suspend fun resolveModelListing(presetId: String): ProviderConfig {
        val preset = content.getPreset(presetId) ?: throw StError.NotFound("model preset")
        val config = parse(preset.configJson)
        validateConnection(config)
        val selected = config["selectedModel"] as? JsonObject
        val model = config.text("model") ?: selected?.text("modelId") ?: config.text("defaultModel").orEmpty()
        val connection = ProviderConnectionSettings.select(config, model)
        if (connection.catalogProviderId == "openai-codex")
            throw StError.Unsupported("this provider requires a separate cloud model directory adapter")
        if (connection.catalogProviderId == "amazon-bedrock" && connection.authStyle != "aws_sigv4")
            throw StError.Unsupported("Bedrock model directory requires AWS SigV4 credentials")
        if (connection.catalogProviderId == "google-vertex" && connection.authStyle != "google_service_account")
            throw StError.Unsupported("Vertex Express model directory is not supported")
        val login = if (managedAuthentication(connection.catalogProviderId, connection.authStyle))
            oauth.connection(presetId, expectedProvider = connection.catalogProviderId) else null
        val id = if (login != null) managedCredentialId(connection.catalogProviderId, presetId)
            else config.text("credentialId") ?: config.text("sourceProviderId") ?: presetId
        val headers = preprocessingHeaders(config)
        val secrets = credentials.getSecrets(headers.credentialIds + if (login == null) setOf(id) else emptySet(),
            allowAws = connection.authStyle == "aws_sigv4")
        val key = login?.token ?: secrets[id].orEmpty()
        val options = oauthOptions(headers.resolve(secrets, if (login == null) connection.authStyle else "bearer", connection.apiVersion), login)
        val aws = awsCredentials(id, connection.authStyle, key)
        validateAuthentication(config, connection.protocol, key, options, headers.hasAuthenticationReference)
        val provider = ProviderConfig(id, connection.protocol, login?.baseUrl ?: connection.baseUrl,
            if (aws == null) key else "", model, httpOptions = options, unversionedRoot = login != null,
            presetId = presetId, bedrockBackend = connection.catalogProviderId == "amazon-bedrock", awsCredentials = aws,
            generationPath = generationPath(config))
        if (listOf(presetId, config.toString()).any(provider::containsCredential))
            throw StError.Validation("model directory connection metadata contains credential material")
        return provider
    }

    suspend fun resolveMaintenance(chat: ChatSession): ProviderConfig = resolve(maintenanceChat(chat), useSelectedCache = false)
    suspend fun previewMaintenance(chat: ChatSession): ProviderSelection = preview(maintenanceChat(chat))

    private suspend fun maintenanceChat(chat: ChatSession): ChatSession {
        val settings = parse(chat.sourceNamespace?.let { content.getSourceSettingsJson(it) } ?: if (chat.sourceNamespace == null) content.getSettingsJson() ?: "{}" else "{}")
        val llm = settings["llm"] as? JsonObject
        val model = settings.text("mvuModel") ?: llm?.text("defaultModel")
            ?: (llm?.get("modelCandidates") as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (model.isNullOrBlank()) return chat
        return chat.copy(overrides = chat.overrides.copy(paramsJson = JsonObject(
            parse(chat.overrides.paramsJson ?: "{}") + ("model" to JsonPrimitive(model))).toString()))
    }

    suspend fun resolveCacheConnection(chat: ChatSession, characterId: String? = null): ProviderConfig = resolve(chat, characterId, true)
    suspend fun resolve(chat: ChatSession, characterId: String? = null, cacheOperation: Boolean = false,
        useSelectedCache: Boolean = true): ProviderConfig {
        val r = select(chat, characterId, cacheOperation)
        val login = if (managedAuthentication(r.preview.catalogProviderId, r.preview.authStyle)) oauth.connection(
            r.preview.presetId ?: throw StError.Validation("OAuth requires an explicit model preset"),
            expectedProvider = r.preview.catalogProviderId) else null
        val secrets = credentials.getSecrets(r.headers.credentialIds + if (login == null) setOf(r.preview.credentialId) else emptySet(),
            allowAws = r.preview.authStyle == "aws_sigv4")
        val key = login?.token ?: secrets[r.preview.credentialId].orEmpty()
        val originalOptions = r.headers.resolve(secrets, if (login == null) r.preview.authStyle else "bearer", r.preview.apiVersion)
        val options = r.fast.apply(oauthOptions(originalOptions, login))
        val aws = awsCredentials(r.preview.credentialId, r.preview.authStyle, key)
        validateAuthentication(r.config, r.preview.protocol, key, options, r.headers.hasAuthenticationReference)
        val extra = if (cacheOperation) emptyMap() else GenerationParameters.extras(r.params).toMutableMap().apply {
            characterId?.let { chat.memberSettings[it]?.reasoningEffort }?.let { put("reasoning_effort", it) }
        }
        if (!cacheOperation) GenerationParameters.wire(r.preview.protocol, r.temperature, r.topP, extra)
        val costCurrency = if (cacheOperation) null else r.config.text("reportedCostCurrency")
        if (costCurrency != null && !costCurrency.matches(Regex("[A-Z]{3}"))) throw StError.Validation("reportedCostCurrency must be an uppercase three-letter currency")
        val selection = if (!cacheOperation && useSelectedCache) selectedCache(chat.id, characterId) else null
        if (selection != null && r.preview.protocol != LlmProtocol.GEMINI_GENERATE_CONTENT)
            throw StError.Conflict("clear the selected Gemini cache before using another protocol")
        val provider = ProviderConfig(r.preview.credentialId, r.preview.protocol, login?.baseUrl ?: r.preview.baseUrl, if (aws == null) key else "",
            r.preview.model, if (cacheOperation) null else r.temperature, if (cacheOperation) null else r.topP,
            extra = extra, reportedCostCurrency = costCurrency, httpOptions = options, unversionedRoot = login != null,
            codexBackend = login?.codexBackend == true, streaming = GenerationParameters.streaming(r.params), presetId = r.preview.presetId,
            bedrockBackend = r.preview.catalogProviderId == "amazon-bedrock",
            awsCredentials = aws,
            generationPath = generationPath(r.config),
            cachePlan = if (r.cache != null || selection != null) GeminiCachePlan(GeminiCacheOwner(chat.id, characterId, chat.sourceNamespace,
                r.preview.credentialId, r.preview.model, r.preview.baseUrl, ProviderHttpOptions.normalizeAuth(options.authStyle, r.preview.protocol),
                r.preview.presetId), r.cache?.ttlSeconds ?: 3600, r.cache?.historyTail ?: false, selection) else null)
        if (listOf(r.preview.toString(), r.params.toString(), costCurrency.orEmpty()).any(provider::containsCredential))
            throw StError.Validation("connection metadata contains selected credential material")
        return provider
    }

    /** Applies fast mode to the same explicit connection. */
    internal suspend fun applyExplicitModes(chat: ChatSession, characterId: String?, provider: ProviderConfig): ProviderConfig {
        val memberFast = characterId?.let { chat.memberSettings[it]?.fastMode }
        val modeKeys = setOf("fast_mode", "fastMode", "service_tier", "serviceTier", "speed")
        if (memberFast == null && provider.extra.keys.none { it in modeKeys }) return provider

        val modes = JsonObject(provider.extra.filterKeys { it in modeKeys }.mapValues { (_, raw) ->
            try { Jsonx.json.parseToJsonElement(raw) } catch (_: Exception) { JsonPrimitive(raw) }
        })
        val params = GenerationParameters.normalize(modes)
        val host = URI(provider.baseUrl).host.orEmpty().lowercase(Locale.ROOT)
        val catalogId = if (host.endsWith(".openai.azure.com")) "azure" else null
        val fast = withContext(Dispatchers.IO) {
            ProviderFastSettings.select(params, memberFast, provider.protocol, provider.baseUrl, provider.model, catalogId)
        }
        val extra = provider.extra.filterKeys { it !in modeKeys } + GenerationParameters.extras(fast.params)
        return provider.copy(extra = extra, httpOptions = fast.apply(provider.httpOptions))
    }

    /** Preview preparation reads cached tokens only; the explicit preprocessing run may refresh. */
    suspend fun resolvePreprocessing(presetId: String, config: JsonObject, model: String,
        maxOutputTokens: Int, allowRefresh: Boolean): ProviderConfig {
        validateConnection(config)
        val connection = withContext(Dispatchers.IO) { ProviderConnectionSettings.select(config, model) }
        val login = if (managedAuthentication(connection.catalogProviderId, connection.authStyle))
            oauth.connection(presetId, expectedProvider = connection.catalogProviderId, allowRefresh = allowRefresh) else null
        val id = if (login != null) {
            managedCredentialId(connection.catalogProviderId, presetId)
        } else config.text("credentialId") ?: config.text("sourceProviderId") ?: presetId
        val secrets = credentials.getSecrets(preprocessingHeaders(config).credentialIds + if (login == null) setOf(id) else emptySet(),
            allowAws = connection.authStyle == "aws_sigv4")
        return withContext(Dispatchers.IO) { explicit(config, id, login?.token ?: secrets[id].orEmpty(), model,
            maxOutputTokens, secrets, login) }
    }

    private suspend fun select(chat: ChatSession, characterId: String?, cacheOperation: Boolean = false): Resolved {
        chat.overrides.assistant?.let { binding ->
            val config = content.getAssistant(binding.assistantId) ?: throw StError.NotFound("assistant configuration")
            return select(assistantConnection(chat, config, parse(config.settingsJson)), characterId, cacheOperation)
        }
        val member = characterId?.let { chat.memberSettings[it] }
        val presetId = member?.presetId ?: chat.overrides.presetId
        val preset = presetId?.let { content.getPreset(it) ?: throw StError.NotFound("model preset $it") }
        val config: JsonObject
        val defaults: JsonObject
        if (preset != null) {
            config = parse(preset.configJson)
            val scopedSettings = parse(preset.sourceNamespace?.let { content.getSourceSettingsJson(it) }
                ?: if (preset.sourceNamespace == null) content.getSettingsJson() ?: "{}" else "{}")
            defaults = JsonObject(scopedSettings.filterKeys { it in setOf("stream", "streamEnabled", "streamOutput") } +
                (config["generationDefaults"] as? JsonObject ?: scopedSettings["generationDefaults"] as? JsonObject ?: JsonObject(emptyMap())))
        } else {
            // Imported chats never borrow the other application's/global connection implicitly.
            if (chat.sourceNamespace != null) throw StError.Validation("select a model preset for this imported chat")
            val settings = parse(content.getSettingsJson() ?: "{}")
            config = settings["llm"] as? JsonObject ?: settings
            defaults = JsonObject(settings.filterKeys { it in setOf("stream", "streamEnabled", "streamOutput") } +
                (settings["generationDefaults"] as? JsonObject ?: JsonObject(emptyMap())))
        }
        val sourceStream = if (chat.sourceNamespace?.substringBefore(':') == "rikka_hub") {
            val actor = content.getCharacter(characterId ?: chat.characterId)?.takeIf { it.sourceNamespace == chat.sourceNamespace }
            actor?.let { content.sourceAssistant(it) }?.let { parse(it.settingsJson) }
                ?.filterKeys { it in setOf("stream", "streamOutput", "streamEnabled") }.orEmpty()
        } else emptyMap()
        val params = JsonObject(GenerationParameters.normalize(defaults) + GenerationParameters.normalize(config) +
            GenerationParameters.normalize(JsonObject(sourceStream)) +
            GenerationParameters.normalize(parse(chat.overrides.paramsJson ?: "{}")))
        val model = member?.model?.takeIf { it.isNotBlank() }
            ?: params.text("model") ?: (config["selectedModel"] as? JsonObject)?.text("modelId")
            ?: config.text("defaultModel")
            ?: (config["models"] as? JsonArray)?.firstOrNull()?.let {
                if (it is JsonObject) it.text("modelId") else (it as? JsonPrimitive)?.contentOrNull
            }
            ?: (config["modelCandidates"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?: throw StError.Validation("model is not configured")
        validateConnection(config)
        val connection = withContext(Dispatchers.IO) { ProviderConnectionSettings.select(config, model) }
        val fast = if (cacheOperation) ProviderFastSettings(params, null, null) else withContext(Dispatchers.IO) { ProviderFastSettings.select(params, member?.fastMode, connection.protocol,
            connection.baseUrl, model, connection.catalogProviderId) }
        val cache = if (cacheOperation) ProviderPromptCacheSettings.Selection(params) else withContext(Dispatchers.IO) { ProviderPromptCacheSettings.select(fast.params, connection.protocol,
            connection.baseUrl, model, connection.catalogProviderId, chat, characterId) }
        val headers = ProviderHeaderSettings.parse(listOf(config, config["selectedModel"] as? JsonObject ?: JsonObject(emptyMap()),
            parse(chat.overrides.paramsJson ?: "{}")))
        val isOAuth = managedAuthentication(connection.catalogProviderId, connection.authStyle)
        if (headers.credentialIds.any { it.startsWith(CredentialStore.OAUTH_PREFIX) })
            throw StError.Validation("OAuth records cannot be used as custom header credentials")
        val credentialId = if (isOAuth) {
            val id = presetId ?: throw StError.Validation("OAuth requires an explicit model preset")
            managedCredentialId(connection.catalogProviderId, id)
        }
            else config.text("credentialId") ?: config.text("sourceProviderId") ?: presetId ?: "default"
        if (!isOAuth && credentialId.startsWith(CredentialStore.OAUTH_PREFIX))
            throw StError.Validation("OAuth records require the provider login adapter")
        val sigv4 = connection.authStyle == "aws_sigv4"
        if (sigv4 && !credentialId.startsWith(CredentialStore.AWS_PREFIX))
            throw StError.Validation("SigV4 requires the reference returned by setBedrockAwsCredentials")
        val secrets = credentials.getSecrets(headers.credentialIds + if (isOAuth) emptySet() else setOf(credentialId), allowAws = sigv4)
        val loggedIn = if (isOAuth) oauth.status(presetId!!).loggedIn else if (sigv4)
            !secrets[credentialId].isNullOrBlank() else secrets.values.any { it.isNotBlank() }
        val materials = secrets.values.filter(String::isNotBlank) + if (sigv4 && loggedIn)
            awsCredentials(credentialId, connection.authStyle, secrets.getValue(credentialId))!!.credentialMaterials else emptyList()
        if (materials.any { secret ->
                listOfNotNull(presetId, credentialId, model, connection.baseUrl, connection.catalogProviderId, connection.authStyle,
                    connection.apiVersion).any { it.contains(secret) } })
            throw StError.Validation("connection preview metadata contains selected credential material")
        return Resolved(ProviderSelection(presetId, credentialId, connection.protocol, connection.baseUrl, model,
            loggedIn, connection.catalogProviderId, connection.authStyle, connection.apiVersion, fast.fastRequested,
                GenerationParameters.streaming(cache.params)),
            config, cache.params, if (cacheOperation) null else member?.temperature ?: params.number("temperature"),
            if (cacheOperation) null else member?.topP ?: params.number("topP") ?: params.number("top_p"), headers, fast, cache.gemini)
    }

    /** Shared by generation, preview and cache-management connection checks, with one immutable config read. */
    internal suspend fun assistantConnection(session: ChatSession, config: AssistantConfig, settings: JsonObject): ChatSession {
        val namespace = config.sourceNamespace
        if (namespace != session.sourceNamespace) throw StError.Conflict("assistant configuration source differs from its conversation")
        var preset = session.overrides.presetId ?: settings.text("presetId")
        if (namespace != null && session.overrides.presetId == null) {
            val sourceSettings = if (preset == null) content.getSourceSettingsJson(namespace)?.let(::parse) else null
            val sourceModel = settings.text("chatModelId") ?: sourceSettings?.text("chatModelId")
            if (preset == null && sourceModel != null || preset != null && content.getPreset(preset) == null) {
                val sourceId = preset ?: "model:$sourceModel"
                preset = content.listPresets().singleOrNull { it.sourceNamespace == namespace && it.sourceId == sourceId }?.id ?: preset
            }
            if (preset == null && sourceSettings?.get("llm") is JsonObject)
                preset = content.listPresets().singleOrNull { it.sourceNamespace == namespace && it.sourceId == "default-connection" }?.id
        }
        val params = JsonObject(GenerationParameters.normalize(settings) +
            (session.overrides.paramsJson?.let { GenerationParameters.normalize(parse(it)) } ?: emptyMap()))
        // Clear the mode in this private connection view only, so ordinary selection does not recurse.
        return session.copy(overrides = session.overrides.copy(assistant = null, presetId = preset, paramsJson = params.toString()))
    }

    companion object {
    private fun managedAuthentication(providerId: String?, authStyle: String?) =
        providerId in setOf("github-copilot", "openai-codex") || authStyle == "google_service_account"
    private fun managedCredentialId(providerId: String?, presetId: String): String = when (providerId) {
        "github-copilot" -> CopilotAuthService.credentialId(presetId)
        "openai-codex" -> CodexAuthService.credentialId(presetId)
        "google-vertex" -> VertexAuthService.credentialId(presetId)
        else -> throw StError.Unsupported("provider has no managed authentication adapter")
    }
    /** Explicit preset path: no global/first-source defaults or inline secrets. */
    fun explicit(config: JsonObject, credentialId: String, secret: String, model: String, maxOutputTokens: Int,
        headerSecrets: Map<String, String> = emptyMap(), login: ProviderOAuthService.Connection? = null): ProviderConfig {
        listOf("protocol", "authStyle", "baseUrl", "presetKind", "reportedCostCurrency", "maxTokenField", "apiKey", "api_key", "token").forEach { key ->
            val value = config[key]
            if (value != null && value != JsonNull && (value !is JsonPrimitive || !value.isString))
                throw StError.Validation("preprocessing connection field must be text")
        }
        if (config["allowUnauthenticated"] !in setOf(null, JsonNull, JsonPrimitive(true), JsonPrimitive(false)))
            throw StError.Validation("allowUnauthenticated must be boolean")
        validateConnection(config)
        val headers = preprocessingHeaders(config)
        val connection = ProviderConnectionSettings.select(config, model)
        if (managedAuthentication(connection.catalogProviderId, connection.authStyle) && login == null)
            throw StError.Validation("resolve provider login before speech preprocessing")
        val protocol = connection.protocol
        val base = login?.baseUrl ?: connection.baseUrl
        val options = oauthOptions(headers.resolve(headerSecrets, if (login == null) connection.authStyle else "bearer", connection.apiVersion), login)
        val aws = awsCredentials(credentialId, connection.authStyle, secret)
        validateAuthentication(config, protocol, secret, options, headers.hasAuthenticationReference)
        val currency = config.text("reportedCostCurrency")
        if (currency != null && !currency.matches(Regex("[A-Z]{3}"))) throw StError.Validation("invalid reported cost currency")
        val field = config.text("maxTokenField") ?: "max_tokens"
        if (field !in setOf("max_tokens", "max_completion_tokens")) throw StError.Validation("unsupported token limit field")
        val provider = ProviderConfig(credentialId, protocol, base, if (aws == null) secret else "", model, temperature = 0.2,
            extra = mapOf("max_tokens" to maxOutputTokens.toString(), "max_token_field" to field), reportedCostCurrency = currency,
            httpOptions = options, unversionedRoot = login != null, codexBackend = login?.codexBackend == true,
            bedrockBackend = connection.catalogProviderId == "amazon-bedrock",
            awsCredentials = aws,
            generationPath = generationPath(config),
            streaming = GenerationParameters.streaming(config))
        if (listOfNotNull(credentialId, model, base, currency, connection.catalogProviderId, connection.authStyle,
                connection.apiVersion, config.toString()).any(provider::containsCredential))
            throw StError.Validation("preprocessing connection metadata contains credential material")
        return provider
    }
    fun preprocessingHeaders(config: JsonObject) = ProviderHeaderSettings.parse(listOf(config,
        config["selectedModel"] as? JsonObject ?: JsonObject(emptyMap())))

    private fun generationPath(config: JsonObject): String? = config["generationPath"]?.takeIf { it != JsonNull }?.let {
        (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
            ?: throw StError.Validation("generation path must be text")
    }

    private fun oauthOptions(options: ProviderHttpOptions, login: ProviderOAuthService.Connection?): ProviderHttpOptions {
        if (login == null) return options
        if (options.headers.keys.any { ProviderHttpOptions.sensitive(it) })
            throw StError.Validation("provider login owns authentication headers")
        return ProviderHttpOptions("bearer", options.headers + login.headers.mapKeys { it.key.lowercase() },
            options.credentialMaterials + login.privateMaterials, options.apiVersion)
    }

    private fun validateConnection(config: JsonObject) {
        if (config.text("presetKind")?.lowercase() in setOf("tts", "minimax")) throw StError.Validation("TTS preset cannot generate chat text")
        if ((config["selectedModel"] as? JsonObject)?.get("providerOverwrite")?.let { it != JsonNull } == true)
            throw StError.Unsupported("source model provider overwrite requires reimport or its connection adapter")
        if (config.text("type")?.lowercase() == "google" && config["vertexAI"] == JsonPrimitive(true) &&
            config.text("providerId") != "google-vertex")
            throw StError.Unsupported("source Vertex connection requires reimport or an explicit Vertex configuration")
        if (config.text("type")?.lowercase() == "openai" && config["generationPath"] in setOf(null, JsonNull)) {
            val responses = config["useResponseApi"] == JsonPrimitive(true)
            val path = config[if (responses) "responsesPath" else "chatCompletionsPath"]
            val defaultPath = JsonPrimitive(if (responses) "/responses" else "/chat/completions")
            if (path != null && path != JsonNull && path != defaultPath)
                throw StError.Unsupported("source custom generation path requires reimport or an explicit generationPath")
        }
        listOf("authStyle", "apiKey", "api_key", "token", "credentialId").forEach { name ->
            config[name]?.takeIf { it != JsonNull }?.let {
                if (it !is JsonPrimitive || !it.isString) throw StError.Validation("connection field must be text")
            }
        }
        if (listOf("apiKey", "api_key", "token", "accessKeyId", "secretAccessKey", "sessionToken",
                "awsAccessKeyId", "awsSecretAccessKey", "awsSessionToken").any { !config.text(it).isNullOrBlank() })
            throw StError.Validation("move connection secrets into encrypted credential storage")
        if (config["allowUnauthenticated"] !in setOf(null, JsonNull, JsonPrimitive(true), JsonPrimitive(false)))
            throw StError.Validation("allowUnauthenticated must be boolean")
    }
    private fun validateAuthentication(config: JsonObject, protocol: LlmProtocol, key: String, options: ProviderHttpOptions, customAuth: Boolean) {
        val style = ProviderHttpOptions.normalizeAuth(options.authStyle, protocol)
        if (style == "aws_sigv4" && key.isBlank()) throw StError.Validation("AWS credentials are missing")
        if (config["allowUnauthenticated"] != JsonPrimitive(true) &&
            (style != "none" && key.isBlank() || style == "none" && !customAuth))
            throw StError.Validation("connection credential missing; configure encrypted credential storage")
        options.requestHeaders(protocol, if (style == "aws_sigv4") "" else key) // never send a structured AWS record as an API key
    }
    private fun awsCredentials(id: String, style: String?, raw: String): AwsBedrockCredentials? {
        if (style != "aws_sigv4") return null
        if (!id.startsWith(CredentialStore.AWS_PREFIX))
            throw StError.Validation("SigV4 requires the reference returned by setBedrockAwsCredentials")
        if (raw.toByteArray(Charsets.UTF_8).size > 32_768) throw StError.Validation("AWS credential record exceeds budget")
        val value = Jsonx.decodeOrNull<AwsBedrockCredentials>(raw) ?: throw StError.Validation("AWS credentials are missing or invalid")
        value.validate()
        return value
    }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parse(raw: String): JsonObject {
        if (raw.toByteArray(Charsets.UTF_8).size > 262_144) throw StError.Validation("generation configuration exceeds read budget")
        return NativeJsonInput.parse(raw) as? JsonObject ?: throw StError.Validation("model configuration must be a JSON object")
    }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    private fun JsonObject.number(key: String): Double? = get(key)?.takeIf { it != JsonNull }?.let {
        val value = (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        value?.takeIf { n -> n.isFinite() } ?: throw StError.Validation("invalid numeric generation parameter")
    }
}
