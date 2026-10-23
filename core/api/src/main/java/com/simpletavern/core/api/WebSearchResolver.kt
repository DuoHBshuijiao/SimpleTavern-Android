package com.simpletavern.core.api

import androidx.room.withTransaction
import com.simpletavern.core.data.CredentialStore
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.data.db.AppSettingsEntity
import com.simpletavern.core.model.*
import com.simpletavern.core.tools.GenerationSearchPlan
import com.simpletavern.core.tools.WebSearchService
import com.simpletavern.core.tools.WebSearchSession
import com.simpletavern.core.tools.WebSearchAccountSession
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Database/credential snapshot only. resolve() must run after releasing the data gate/transaction. */
class PreparedWebSearchStatus internal constructor(private val status: WebSearchStatus,
    private val sessions: Map<String, WebSearchAccountSession>) {
    suspend fun resolve(): WebSearchStatus = coroutineScope {
        val pending = sessions.mapValues { (_, session) -> async { session.fetch() } }
        status.copy(accounts = status.accounts.mapValues { (provider, previous) -> pending[provider]?.await() ?: previous })
    }
    override fun toString() = "PreparedWebSearchStatus(credentials=<redacted>)"
}

/** Explicit per-source search selection; never borrow a different source's credential. */
class WebSearchResolver(private val db: AppDatabase, private val credentials: CredentialStore,
    private val service: WebSearchService) {
    private val processEpoch = UUID.randomUUID().toString()
    private suspend fun epoch() = "$processEpoch:${db.fileChanges().restoreEpoch().orEmpty()}"
    private fun scope(assistantId: String?): String {
        if (assistantId != null && (assistantId.isBlank() || assistantId.toByteArray(Charsets.UTF_8).size > 1024 || assistantId.any(Char::isISOControl)))
            throw StError.Validation("invalid search configuration scope")
        return assistantId?.let { "assistant:$it" } ?: "global"
    }
    private fun connection(config: WebSearchConfiguration, provider: String) = when (provider) {
        "tavily" -> config.tavily; "bocha" -> config.bocha; "brave" -> config.brave; else -> null
    }
    private fun validate(config: WebSearchConfiguration) {
        if (config.provider !in WebSearchService.PROVIDERS || config.geminiSearch !in setOf("native", "independent"))
            throw StError.Validation("invalid search provider or Gemini search mode")
        WebSearchService.PROVIDERS.forEach { provider -> connection(config, provider)?.let { service.validate(provider, it) } }
    }
    private suspend fun read(assistantId: String?): Pair<WebSearchConfigurationRecord, Boolean> {
        val target = scope(assistantId)
        if (assistantId != null && db.config().assistantSettingsBytes(assistantId) == null) throw StError.NotFound("search assistant scope missing")
        val rowId = "web_search_v1:$target"
        if ((db.config().settingsBytes(rowId) ?: 0) > 65_536) throw StError.Validation("search configuration exceeds 64 KiB")
        val raw = db.config().getSettings(rowId)?.settingsJson
        if (raw != null) {
            NativeJsonInput.parse(raw)
            val record = Jsonx.decodeOrNull<WebSearchConfigurationRecord>(raw) ?: throw StError.Validation("invalid search configuration JSON")
            if (record.version != 1 || record.revision < 0) throw StError.Validation("unsupported search configuration version")
            validate(record.configuration)
            return record to true
        }
        val bytes = if (assistantId == null) db.config().settingsBytes("global") else db.config().assistantSettingsBytes(assistantId)
        if ((bytes ?: 0) > 262_144) throw StError.Validation("source settings exceed search read budget")
        val settings = if (assistantId == null) db.config().getSettings()?.settingsJson else db.config().getAssistant(assistantId)?.settingsJson
        val root = settings?.let { runCatching { NativeJsonInput.parse(it) as? JsonObject }.getOrNull()
            ?: throw StError.Validation("source search settings are invalid") }
        val ws = root?.get("webSearch")?.takeIf { it != JsonNull }?.let { it as? JsonObject ?: throw StError.Validation("webSearch must be an object") }
        fun text(obj: JsonObject?, key: String) = (obj?.get(key) as? JsonPrimitive)?.contentOrNull
        fun fromSource(provider: String): WebSearchConnection? {
            val obj = ws?.get(provider)?.takeIf { it != JsonNull }?.let { it as? JsonObject ?: throw StError.Validation("search connection must be an object") } ?: return null
            if (listOf("apiKey", "token", "api_key").any { !text(obj, it).isNullOrBlank() })
                throw StError.Validation("move search secrets into encrypted credential storage")
            val params = obj["parametersJson"]?.let { (it as? JsonPrimitive)?.contentOrNull ?: throw StError.Validation("search parametersJson must be text") }
                ?: JsonObject(obj.filterKeys { it in WebSearchService.PARAMETERS.getValue(provider) }).toString()
            return WebSearchConnection(text(obj, "credentialId") ?: "web-search:$target:$provider", text(obj, "baseUrl"), params)
        }
        val config = WebSearchConfiguration(text(ws, "provider") ?: "tavily", text(ws, "geminiSearch") ?: "native",
            fromSource("tavily"), fromSource("bocha"), fromSource("brave"))
        validate(config)
        return WebSearchConfigurationRecord(configuration = config) to false
    }
    private suspend fun snapshot(assistantId: String?): WebSearchConfigurationSnapshot {
        val (record, dedicated) = read(assistantId)
        val version = WebSearchVersion(scope(assistantId), epoch(), record.revision, if (dedicated) null else Jsonx.encode(record.configuration))
        val configured = WebSearchService.PROVIDERS.associateWith { provider -> connection(record.configuration, provider)
            ?.let { credentials.hasSecret(it.credentialId) } ?: false }
        return WebSearchConfigurationSnapshot(record, version, configured)
    }
    suspend fun get(assistantId: String? = null) = withContext(Dispatchers.IO) { db.withTransaction { snapshot(assistantId) } }
    suspend fun prepareStatus(assistantId: String? = null, fetchAccounts: Boolean = false): PreparedWebSearchStatus = withContext(Dispatchers.IO) { db.withTransaction {
        val (record, dedicated) = read(assistantId)
        val config = record.configuration
        val version = WebSearchVersion(scope(assistantId), epoch(), record.revision, if (dedicated) null else Jsonx.encode(config))
        val sessions = linkedMapOf<String, WebSearchAccountSession>()
        val statuses = WebSearchService.PROVIDERS.associateWith { provider ->
            val connection = connection(config, provider)
            val secret = connection?.let { credentials.getSecret(it.credentialId) }
            val warnings = if (provider == "brave") listOf("account_query_unsupported", "search_result_storage_rights_required") else emptyList()
            when {
                connection == null || secret.isNullOrBlank() -> WebSearchAccountStatus(provider, false, WebSearchAccountState.NOT_CONFIGURED, warnings = warnings)
                !fetchAccounts -> WebSearchAccountStatus(provider, true,
                    if (provider == "brave") WebSearchAccountState.UNSUPPORTED else WebSearchAccountState.NOT_CHECKED, warnings = warnings)
                else -> try {
                    sessions[provider] = service.prepareAccount(provider, connection, secret)
                    WebSearchAccountStatus(provider, true, WebSearchAccountState.NOT_CHECKED, warnings = warnings)
                } catch (_: StError.Validation) {
                    WebSearchAccountStatus(provider, true, WebSearchAccountState.FAILED, errorCode = "web_search_credential_invalid", warnings = warnings)
                }
            }
        }
        PreparedWebSearchStatus(WebSearchStatus(configurationVersion = version, provider = config.provider,
            capturedAt = Instant.now().toString(), accounts = statuses), sessions)
    } }
    suspend fun save(config: WebSearchConfiguration, expected: WebSearchVersion, assistantId: String? = null) = withContext(Dispatchers.IO) { db.withTransaction {
        val before = snapshot(assistantId)
        if (before.version != expected) throw StError.Conflict("search configuration changed; refresh before saving")
        validate(config)
        if (before.value.revision == Long.MAX_VALUE) throw StError.Validation("search revision exhausted")
        val record = WebSearchConfigurationRecord(revision = before.value.revision + 1, configuration = config, updatedAt = Instant.now().toString())
        val raw = Jsonx.encode(record)
        if (raw.toByteArray(Charsets.UTF_8).size > 65_536) throw StError.Validation("search configuration exceeds 64 KiB")
        db.config().upsertSettings(AppSettingsEntity("web_search_v1:${scope(assistantId)}", raw, record.updatedAt!!))
        snapshot(assistantId)
    } }
    suspend fun prepare(assistantId: String? = null): WebSearchSession = withContext(Dispatchers.IO) { db.withTransaction {
        val config = read(assistantId).first.configuration
        val connection = connection(config, config.provider) ?: throw StError.Search("web_search_not_configured", "selected search provider is not configured")
        val secret = credentials.getSecret(connection.credentialId) ?: throw StError.Search("web_search_not_configured", "selected search credential is missing")
        service.prepare(config.provider, connection, secret, scope(assistantId))
    } }
    suspend fun forGeneration(chat: ChatSession, provider: com.simpletavern.core.llm.ProviderConfig, options: WebSearchOptions): GenerationSearchPlan = withContext(Dispatchers.IO) { db.withTransaction {
        if (!options.enabled) return@withTransaction GenerationSearchPlan()
        val presetConfig = provider.presetId?.let { db.config().presetConfigJson(it) }?.let {
            NativeJsonInput.parse(it) as? JsonObject ?: throw StError.Validation("invalid preset web configuration")
        }
        val source = options.source ?: (presetConfig?.get("webSearchSource") as? JsonPrimitive)?.contentOrNull
        val searchEnabled = options.searchEnabled && (presetConfig?.get("webSearchSearchEnabled") as? JsonPrimitive)?.booleanOrNull != false
        val fetchEnabled = options.fetchEnabled && (presetConfig?.get("webSearchFetchEnabled") as? JsonPrimitive)?.booleanOrNull != false
        if (!searchEnabled && !fetchEnabled) return@withTransaction GenerationSearchPlan()
        val protocol = provider.protocol
        if (source !in setOf(null, "native", "independent")) throw StError.Validation("invalid web capability source")
        val assistantId = options.assistantId?.also { id ->
            scope(id)
            if (db.config().assistantSettingsBytes(id) == null) throw StError.NotFound("selected search assistant missing")
            if (db.config().assistantSource(id) != chat.sourceNamespace) throw StError.Conflict("selected search assistant belongs to another source")
        } ?: chat.overrides.assistant?.assistantId ?: chat.sourceNamespace?.let { namespace ->
            val ids = db.config().assistantIdsForSource(namespace)
            if (ids.size != 1) throw StError.Validation("select an unambiguous source search configuration")
            ids.single()
        }
        val config = get(assistantId).value.configuration
        val chatParams = chat.overrides.paramsJson?.let { runCatching { Jsonx.json.parseToJsonElement(it) as? JsonObject }.getOrNull()
            ?: throw StError.Validation("chat search overrides are invalid") }
        val mode = options.geminiSearch ?: (chatParams?.get("geminiSearch") as? JsonPrimitive)?.contentOrNull ?: config.geminiSearch
        if (mode !in setOf("native", "independent")) throw StError.Validation("invalid Gemini search mode")
        val deepSeek = java.net.URI(provider.baseUrl).host == "api.deepseek.com"
        val hosted = source != "independent" && (deepSeek && source == "native" || protocol in setOf(LlmProtocol.OPENAI_RESPONSES, LlmProtocol.ANTHROPIC_MESSAGES) ||
            protocol == LlmProtocol.GEMINI_GENERATE_CONTENT && mode == "native")
        if (source == "native" && !hosted) throw StError.Unsupported("selected provider has no native web adapter")
        if (hosted && !deepSeek && protocol == LlmProtocol.OPENAI_RESPONSES)
            return@withTransaction GenerationSearchPlan(native = searchEnabled, searchEnabled = searchEnabled, fetchEnabled = searchEnabled)
        if (hosted) GenerationSearchPlan(native = true, searchEnabled = searchEnabled,
            fetchEnabled = fetchEnabled, deepSeek = deepSeek) else {
            if (!searchEnabled) throw StError.Unsupported("independent fetch-only mode is not supported")
            GenerationSearchPlan(session = prepare(assistantId))
        }
    } }
}
