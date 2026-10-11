package com.simpletavern.core.api

import com.simpletavern.core.tools.SpeechVoiceDesignService
import com.simpletavern.core.tools.SpeechVoiceDesignSession
import com.simpletavern.core.tools.SpeechVoiceCloneService
import com.simpletavern.core.tools.SpeechVoiceCloneSession
import com.simpletavern.core.tools.SpeechVoiceCloneInput
import com.simpletavern.core.tools.LocalSpeechReference
import com.simpletavern.core.tools.SpeechGatewayHealthService
import com.simpletavern.core.tools.SpeechGatewayHealthSession

import androidx.room.withTransaction
import com.simpletavern.core.data.CredentialStore
import com.simpletavern.core.data.ImportStore
import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.model.*
import com.simpletavern.core.tools.SpeechService
import com.simpletavern.core.tools.SpeechSession
import com.simpletavern.core.tools.SpeechDirectoryService
import com.simpletavern.core.tools.SpeechDirectorySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Frozen selection and private input for one playback; processing runs only in its owning worker. */
internal class SpeechPlaybackPlan(val selection: SpeechSelection,
    private val run: suspend (SpeechCallContext, suspend (SpeechPreprocessResult) -> Unit) -> List<Pair<SpeechRequest, Pair<SpeechSelection, SpeechSession>>>) {
    suspend fun prepareParts(context: SpeechCallContext, onPreprocessed: suspend (SpeechPreprocessResult) -> Unit) = run(context, onPreprocessed)
    override fun toString() = "SpeechPlaybackPlan(text=<private>, credentials=<redacted>)"
}

/** No first-preset/source fallback; shared imports keep their own enable flag and credentials. */
class SpeechResolver(private val db: AppDatabase, private val credentials: CredentialStore, private val service: SpeechService,
    private val preprocessing: SpeechPreprocessor,
    private val preprocessingConnection: (suspend (String, JsonObject, String, Int, Boolean) -> com.simpletavern.core.llm.ProviderConfig)? = null,
    private val referenceAudio: (suspend (String) -> LocalSpeechReference)? = null) {
    private data class Selected(val preview: SpeechSelection, val request: SpeechRequest, val preprocessing: SpeechPreprocessSession? = null)
    private data class BaseSelected(val selection: SpeechDirectorySelection, val config: JsonObject,
        val session: JsonObject, val settings: JsonObject)
    private val directory = SpeechDirectoryService(service)
    private val voiceDesign = SpeechVoiceDesignService(service)
    private val voiceClone = SpeechVoiceCloneService(service)
    private val gatewayHealth = SpeechGatewayHealthService(service)
    private fun objectJson(raw: String, limit: Int): JsonObject {
        if (raw.toByteArray(Charsets.UTF_8).size > limit) throw StError.Validation("TTS configuration exceeds read budget")
        return NativeJsonInput.parse(raw) as? JsonObject ?: throw StError.Validation("TTS configuration must be an object")
    }
    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it != JsonNull }?.let {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw StError.Validation("TTS configuration field must be text") }
    private fun JsonObject.flag(key: String): Boolean = when (get(key)) {
        null, JsonNull, JsonPrimitive(false) -> false
        JsonPrimitive(true) -> true
        else -> throw StError.Validation("TTS configuration flag must be boolean")
    }
    private fun identity(value: String) {
        if (value.isBlank() || value.toByteArray(Charsets.UTF_8).size > 1024 || value.any(Char::isISOControl)) throw StError.Validation("invalid TTS identity")
    }
    private suspend fun selectBase(request: SpeechDirectoryRequest): BaseSelected {
        listOfNotNull(request.presetId, request.chatId, request.assistantId).forEach(::identity)
        var chatSource: String? = null
        val session = request.chatId?.let { id ->
            val budget = db.chats().speechOverridesBytes(id) ?: throw StError.NotFound("TTS chat missing")
            if (budget > 262_144) throw StError.Validation("chat overrides exceed TTS read budget")
            chatSource = db.chats().speechSource(id)
            val overrides = objectJson(db.chats().speechOverrides(id)!!, 262_144)
            overrides.text("ttsJson")?.let { objectJson(it, 65_536) }
        } ?: JsonObject(emptyMap())
        val presetId = request.presetId ?: session.text("presetId") ?: throw StError.Validation("select an explicit TTS preset")
        identity(presetId)
        val presetBytes = db.config().presetConfigBytes(presetId) ?: throw StError.NotFound("TTS preset missing")
        if (presetBytes > 65_536) throw StError.Validation("TTS preset exceeds 64 KiB")
        val config = objectJson(db.config().presetConfigJson(presetId)!!, 65_536)
        val source = db.config().presetSource(presetId)
        source?.let(::identity)
        if (request.chatId != null && source != chatSource) throw StError.Conflict("TTS preset belongs to another chat source")
        val assistantId = request.assistantId?.also { id ->
            if (db.config().assistantSettingsBytes(id) == null) throw StError.NotFound("TTS settings assistant missing")
            if (db.config().assistantSource(id) != source) throw StError.Conflict("TTS settings assistant belongs to another source")
        } ?: source?.let { ns ->
            val settingsId = ImportStore(db).resolveId(ns, "0::settings", "source_settings")
            if (settingsId != null) {
                identity(settingsId)
                if (db.config().assistantSettingsBytes(settingsId) == null) throw StError.NotFound("source TTS settings were removed")
                if (db.config().assistantSource(settingsId) != ns) throw StError.Conflict("source TTS settings mapping is inconsistent")
                return@let settingsId
            }
            val ids = db.config().assistantIdsForSource(ns)
            if (ids.size != 1) throw StError.Validation("select unambiguous source TTS settings")
            ids.single()
        }
        assistantId?.let(::identity)
        val settingsBytes = if (assistantId == null) db.config().settingsBytes("global") else db.config().assistantSettingsBytes(assistantId)
        if ((settingsBytes ?: 0) > 262_144) throw StError.Validation("source TTS settings exceed read budget")
        val settings = if (assistantId == null) db.config().getSettings()?.settingsJson else db.config().getAssistant(assistantId)?.settingsJson
        val settingsObject = settings?.let { objectJson(it, 262_144) } ?: JsonObject(emptyMap())
        val enabled = if ("ttsEnabled" !in settingsObject && config.text("sourceFormat") == "rikkahub_tts") true
            else settingsObject.flag("ttsEnabled")
        val kind = config.text("presetKind")
        if (kind !in setOf("tts", "minimax")) throw StError.Validation("preset is not a TTS service")
        val provider = config.text("ttsProvider") ?: "minimax".takeIf { kind == "minimax" }
            ?: throw StError.Validation("select an explicit TTS provider")
        if (listOf("apiKey", "api_key", "token").any { !config.text(it).isNullOrBlank() }) throw StError.Validation("move TTS secrets into encrypted credential storage")
        val credentialId = config.text("credentialId") ?: config.text("sourceProviderId") ?: presetId
        identity(credentialId)
        val endpoint = service.endpoint(provider, config.text("baseUrl"))
        return BaseSelected(SpeechDirectorySelection(presetId, assistantId, provider, endpoint, credentialId,
            credentials.hasSecret(credentialId), enabled, source), config, session, settingsObject)
    }
    private suspend fun select(request: SpeechRequest, requireText: Boolean = true, splitForSpeech: Boolean = false): Selected {
        listOfNotNull(request.characterId, request.personaId).forEach(::identity)
        val base = selectBase(SpeechDirectoryRequest(request.presetId, request.chatId, request.assistantId))
        val selected = base.selection
        if (!selected.enabled) throw StError.Speech("tts_disabled", "TTS is disabled for this source")
        val config = base.config
        val session = base.session
        val provider = selected.provider
        var providerOptions = JsonObject((config["sourceTTS"] as? JsonObject).orEmpty() +
            (config["providerOptions"] as? JsonObject).orEmpty() + (session["providerOptions"] as? JsonObject).orEmpty() + request.providerOptions)
        val model = request.model ?: session.text("model") ?: config.text("model") ?: (config["models"] as? JsonArray)?.singleOrNull()
            ?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?: when (provider) { "xai" -> "xai-tts"; "volcengine" -> providerOptions.text("resourceId") ?: "seed-tts-2.0"
                in SpeechProviders.local -> provider.removeSuffix("_local") + "-local-gateway"
                else -> throw StError.Validation("select an explicit TTS model") }
        fun mapped(key: String, id: String?): String? {
            if (id == null) return null
            val map = session[key]?.takeIf { it != JsonNull }?.let { it as? JsonObject ?: throw StError.Validation("TTS voice mapping must be an object") } ?: return null
            return map.text(id)
        }
        val voice = request.voiceId ?: mapped("voiceByCharacterId", request.characterId) ?: mapped("voiceByPersonaId", request.personaId)
            ?: config.text("voiceId") ?: if (provider == "fish-audio") providerOptions.text("referenceId").orEmpty()
                else throw StError.Validation("select an explicit TTS voice")
        if (provider in SpeechProviders.local) {
            val voices = (config["voiceCatalog"] as? JsonArray).orEmpty().map { item ->
                item as? JsonObject ?: throw StError.Validation("local voice catalog entry must be an object")
            }.filter { it.text("voiceId") == voice }
            if (voices.size > 1) throw StError.Validation("local voice catalog has duplicate selected IDs")
            val defaults = buildJsonObject {
                voices.singleOrNull()?.let { entry ->
                    listOf("promptAttachmentId", "promptText", "promptAudioPath", "instruction").forEach { key ->
                        entry[key]?.let { put(key, it) }
                    }
                }
                config.text(if (provider == "qwen3_local") "ttsQwen3LocalDefaultLanguage" else "ttsOmniVoiceLocalDefaultLanguage")
                    ?.let { put("language", it) }
                config.text("voiceCloneBaseUrl")?.let { put("voiceCloneBaseUrl", it) }
            }
            providerOptions = JsonObject(defaults + providerOptions)
        }
        val preprocess = session.flag("preprocessEnabled")
        if (!preprocess && session.flag("injectEmotionTags")) throw StError.Validation("enable preprocessing before emotion tag injection")
        val resolved = request.copy(presetId = selected.presetId, assistantId = selected.assistantId, model = model, voiceId = voice,
            format = request.format ?: session.text("format") ?: config.text("format") ?: SpeechProviders.defaultFormat(provider),
            instructions = request.instructions ?: session.text("instructions") ?: config.text("instructions") ?: if (provider == "step") providerOptions.text("instruction") else null,
            stability = request.stability ?: (config["stability"] as? JsonPrimitive)?.doubleOrNull,
            similarityBoost = request.similarityBoost ?: (config["similarityBoost"] as? JsonPrimitive)?.doubleOrNull,
            providerOptions = providerOptions)
        val prepared = if (preprocess) selectPreprocessing(SpeechPreprocessRequest(request.text, selected.presetId,
            request.chatId, selected.assistantId, speechModel = model), base, splitForSpeech) else null
        if (prepared == null && requireText) service.validate(provider, resolved) else service.validateControls(provider, resolved)
        val cacheMb = base.settings["ttsAudioCacheLimitMb"]?.takeIf { it != JsonNull }?.let {
            (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.longOrNull?.takeIf { n -> n in 10..10_000 }
                ?: throw StError.Validation("TTS cache limit must be an integer in 10..10000 MiB")
        } ?: 200L
        return Selected(SpeechSelection(selected.presetId, selected.assistantId, provider, model, voice, selected.baseUrl, resolved.format!!,
            selected.credentialId, selected.hasCredential, cacheMb * 1_048_576, selected.sourceNamespace, prepared?.selection), resolved, prepared)
    }
    suspend fun prepareDirectory(request: SpeechDirectoryRequest): SpeechDirectorySession = withContext(Dispatchers.IO) { db.withTransaction {
        val base = selectBase(request)
        // Encrypted credentials never enter catalog results or configuration JSON.
        val secret = credentials.getSecret(base.selection.credentialId)
        directory.prepare(base.selection.copy(hasCredential = !secret.isNullOrBlank()), base.config, secret, request)
    } }
    suspend fun prepareGatewayHealth(request: SpeechGatewayHealthRequest): SpeechGatewayHealthSession = withContext(Dispatchers.IO) { db.withTransaction {
        val base = selectBase(SpeechDirectoryRequest(request.presetId, request.chatId, request.assistantId))
        val secret = credentials.getSecret(base.selection.credentialId)
        val options = JsonObject((base.config["sourceTTS"] as? JsonObject).orEmpty() +
            (base.config["providerOptions"] as? JsonObject).orEmpty() + (base.session["providerOptions"] as? JsonObject).orEmpty())
        gatewayHealth.prepare(base.selection.copy(hasCredential = !secret.isNullOrBlank()), secret,
            options.text("voiceCloneBaseUrl") ?: base.config.text("voiceCloneBaseUrl"), request)
    } }
    suspend fun prepareVoiceDesign(request: SpeechVoiceDesignRequest): SpeechVoiceDesignSession = withContext(Dispatchers.IO) { db.withTransaction {
        val base = selectBase(SpeechDirectoryRequest(request.presetId, request.chatId, request.assistantId))
        val secret = credentials.getSecret(base.selection.credentialId)
            ?: throw StError.Speech("tts_not_configured", "TTS credential missing")
        voiceDesign.prepare(base.selection.copy(hasCredential = true), request, secret)
    } }
    suspend fun prepareVoiceClone(request: SpeechVoiceCloneRequest, source: SpeechVoiceCloneInput,
        prompt: SpeechVoiceCloneInput?): SpeechVoiceCloneSession = withContext(Dispatchers.IO) { db.withTransaction {
        val base = selectBase(SpeechDirectoryRequest(request.presetId, request.chatId, request.assistantId))
        val secret = credentials.getSecret(base.selection.credentialId)
            ?: throw StError.Speech("tts_not_configured", "TTS credential missing")
        val model = request.model ?: if (base.selection.provider == "glm") "glm-tts-clone" else base.config.text("model")
            ?: (base.config["models"] as? JsonArray)?.singleOrNull()?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        voiceClone.prepare(base.selection.copy(hasCredential = true), request.copy(model = model), secret, source, prompt)
    } }
    suspend fun preparePreprocessing(request: SpeechPreprocessRequest): SpeechPreprocessSession = withContext(Dispatchers.IO) { db.withTransaction {
        val base = selectBase(SpeechDirectoryRequest(request.presetId, request.chatId, request.assistantId))
        selectPreprocessing(request, base)
    } }
    private suspend fun selectPreprocessing(request: SpeechPreprocessRequest, base: BaseSelected, splitForSpeech: Boolean = false): SpeechPreprocessSession {
        val selected = base.selection
        if (!selected.enabled) throw StError.Speech("tts_disabled", "TTS is disabled for this source")
        val presetId = request.llmPresetId ?: base.session.text("preprocessPresetId")
            ?: throw StError.Validation("select an explicit preprocessing LLM preset")
        identity(presetId)
        val bytes = db.config().presetConfigBytes(presetId) ?: throw StError.NotFound("preprocessing LLM preset missing")
        if (bytes > 65_536) throw StError.Validation("preprocessing preset exceeds 64 KiB")
        if (db.config().presetSource(presetId) != selected.sourceNamespace) throw StError.Conflict("preprocessing preset belongs to another source")
        val config = objectJson(db.config().presetConfigJson(presetId)!!, 65_536)
        fun model(config: JsonObject): String? = config.text("model") ?: config.text("defaultModel")
            ?: (config["selectedModel"] as? JsonObject)?.text("modelId")
            ?: (config["models"] as? JsonArray)?.singleOrNull()?.let {
                if (it is JsonObject) it.text("modelId") else (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
            }
        val llmModel = request.llmModel ?: base.session.text("preprocessModel") ?: model(config)
            ?: throw StError.Validation("select an explicit preprocessing model")
        val speechModel = request.speechModel ?: base.session.text("model") ?: model(base.config)
            ?: throw StError.Validation("select an explicit downstream speech model")
        val credentialId = config.text("credentialId") ?: config.text("sourceProviderId") ?: presetId
        identity(credentialId)
        suspend fun resolve(allowRefresh: Boolean): com.simpletavern.core.llm.ProviderConfig {
            preprocessingConnection?.let { return it(presetId, config, llmModel, request.maxOutputTokens, allowRefresh) }
            val secrets = credentials.getSecrets(ProviderResolver.preprocessingHeaders(config).credentialIds + credentialId)
            return ProviderResolver.explicit(config, credentialId, secrets[credentialId].orEmpty(), llmModel, request.maxOutputTokens, secrets)
        }
        val connection = resolve(false)
        val selection = SpeechPreprocessSelection(selected.presetId, selected.assistantId, selected.sourceNamespace,
            selected.provider, speechModel, presetId, llmModel, connection.protocol, connection.providerId, connection.credentialMaterials.isNotEmpty(),
            (request.targetLanguage ?: base.session.text("preprocessTargetLanguage"))?.trim()?.takeIf { it.isNotEmpty() },
            request.injectEmotionTags ?: base.session.flag("injectEmotionTags"), request.maxOutputTokens)
        return preprocessing.prepare(request.text, request.chatId, selection, connection, splitForSpeech = splitForSpeech) { resolve(true) }
    }
    private suspend fun prepared(selected: Selected, key: String): SpeechSession {
        val preview = selected.preview
        val reference = localReference(selected)
        val preprocess = selected.preprocessing
        val scope = preview.assistantId?.let { "assistant:$it" } ?: "global"
        if (preprocess == null) return service.prepare(preview.provider, preview.baseUrl, key, selected.request, scope, preview.sourceNamespace, reference)
        service.validatePreparation(preview.provider, preview.baseUrl, key, selected.request, requireText = false)
        if (key.isNotEmpty() && (selected.request.text.contains(key) || Jsonx.encode(preview).contains(key)))
            throw StError.Validation("preprocessing source or metadata contains synthesis credential material")
        return SpeechSession { context, onPreprocessed, onAudio ->
            if (context.preprocessingRequestId != null) throw StError.Validation("new speech pipeline must not reuse preprocessing evidence")
            val result = preprocess.process(context)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (key.isNotEmpty() && result.processedText.contains(key)) throw StError.Validation("processed speech text contains synthesis credential material")
            val synthesis = service.prepare(preview.provider, preview.baseUrl, key, selected.request.copy(text = result.processedText), scope, preview.sourceNamespace, reference)
            onPreprocessed(result)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val audio = synthesis.synthesize(context.copy(preprocessingRequestId = result.requestId), onAudio)
            SpeechAudio(audio.provider, audio.model, audio.voiceId, audio.format, audio.sampleRate, audio.bytes,
                audio.reportedUsageCharacters, audio.warnings + result.warnings, audio.requestId, result.processedText, result.requestId)
        }
    }
    private suspend fun synthesisCredential(selection: SpeechSelection): String =
        credentials.getSecret(selection.credentialId) ?: if (selection.provider in SpeechProviders.local) ""
            else throw StError.Speech("tts_not_configured", "TTS credential missing")

    private suspend fun localReference(selected: Selected): LocalSpeechReference? {
        if (selected.preview.provider !in SpeechProviders.local) return null
        val id = selected.request.providerOptions.text("promptAttachmentId")?.takeIf { it.isNotBlank() } ?: return null
        return (referenceAudio ?: throw StError.Unsupported("managed speech reference resolver unavailable"))(id)
    }
    suspend fun preview(request: SpeechRequest): SpeechSelection = withContext(Dispatchers.IO) { db.withTransaction { select(request).preview } }
    suspend fun prepare(request: SpeechRequest): SpeechSession = withContext(Dispatchers.IO) {
        val (selected, key) = db.withTransaction { select(request).let { it to synthesisCredential(it.preview) } }
        prepared(selected, key)
    }
    suspend fun prepareTask(request: SpeechRequest): Pair<SpeechSelection, SpeechSession> = withContext(Dispatchers.IO) {
        val (selected, key) = db.withTransaction { select(request).let { it to synthesisCredential(it.preview) } }
        selected.preview to prepared(selected, key)
    }
    internal suspend fun preparePlayback(request: SpeechRequest): SpeechPlaybackPlan =
        withContext(Dispatchers.IO) {
            SpeechText.validate(request.text, 40_000, spoken = true)
            val (whole, key) = db.withTransaction {
                select(request, requireText = false, splitForSpeech = true).let { it to synthesisCredential(it.preview) }
            }
            val preview = whole.preview
            val reference = localReference(whole)
            service.validatePreparation(preview.provider, preview.baseUrl, key, whole.request, requireText = false)
            if (key.isNotEmpty() && whole.preprocessing != null && (request.text.contains(key) || Jsonx.encode(preview).contains(key)))
                throw StError.Validation("preprocessing source or metadata contains synthesis credential material")
            val scope = preview.assistantId?.let { "assistant:$it" } ?: "global"
            SpeechPlaybackPlan(preview) { context, onPreprocessed ->
                // No Room transaction or configuration reselection while the whole draft is transformed.
                val result = whole.preprocessing?.process(context)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val text = result?.processedText ?: whole.request.text
                if (result != null) {
                    if (key.isNotEmpty() && text.contains(key)) throw StError.Validation("processed speech text contains synthesis credential material")
                    onPreprocessed(result)
                }
                val selection = preview.copy(preprocessingTaskId = context.taskId.takeIf { result != null })
                SpeechSegments.split(text, SpeechProviders.textLimit(preview.provider, preview.model)).map { segment ->
                    val part = whole.request.copy(text = segment)
                    val synthesis = service.prepare(preview.provider, preview.baseUrl, key, part, scope, preview.sourceNamespace, reference)
                    val session = if (result == null) synthesis else SpeechSession { childContext, childPreprocessed, onAudio ->
                        // Every segment points to the one parent-owned transformation; it never invokes the LLM again.
                        childPreprocessed(result.copy(processedText = segment))
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val audio = synthesis.synthesize(childContext.copy(preprocessingRequestId = result.requestId), onAudio)
                        SpeechAudio(audio.provider, audio.model, audio.voiceId, audio.format, audio.sampleRate, audio.bytes,
                            audio.reportedUsageCharacters, audio.warnings + result.warnings, audio.requestId, segment, result.requestId)
                    }
                    part to (selection to session)
                }
            }
        }
}
