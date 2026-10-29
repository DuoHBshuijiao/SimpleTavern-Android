package com.simpletavern.core.api

import com.simpletavern.core.data.ContentRepository
import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.model.*
import java.time.Instant
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal class AppSettingsService(private val content: ContentRepository) {
    suspend fun get(): AppSettings = withContext(Dispatchers.Default) { AppSettingsCodec.decode(content.getSettingsJson()) }
    fun observe() = content.observeSettingsJson().map(AppSettingsCodec::decode).flowOn(Dispatchers.Default)
    suspend fun save(value: AppSettings): AppSettings = content.transaction {
        val json = AppSettingsCodec.encode(value, content.getSettingsJson())
        content.saveSettingsJson(json, Instant.now().toString())
        AppSettingsCodec.decode(json)
    }
}

/** Preserve unknown fields at every typed level, including fields added since this UI loaded. */
internal object AppSettingsCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val appKeys = setOf("selectedPersonaId", "themeId", "pureAiMode", "streamEnabled", "worldBookEntryScanDepthDefault", "llm", "generationDefaults", "prompts")
    private val llmKeys = setOf("providerId", "protocol", "baseUrl", "model", "defaultModel", "credentialId")
    private val generationKeys = setOf("temperature", "top_p", "max_tokens", "context_size")
    private val promptKeys = setOf("globalSystem", "globalPrefill", "globalPrefillEnabled")
    private val aliases = mapOf("topP" to "top_p", "maxTokens" to "max_tokens", "contextSize" to "context_size")
    private fun objectValue(value: JsonElement?): JsonObject = when (value) {
        null, JsonNull -> JsonObject(emptyMap())
        is JsonObject -> value
        else -> throw StError.Validation("settings sections must be objects")
    }
    private fun root(text: String?): JsonObject = objectValue(text?.let(NativeJsonInput::parse))
    private fun normalize(value: JsonObject): JsonObject = JsonObject(value.filterKeys { it !in aliases } +
        aliases.mapNotNull { (alias, canonical) -> value[alias]?.takeIf { canonical !in value }?.let { canonical to it } }.toMap())

    fun decode(text: String?): AppSettings = try {
        val raw = root(text)
        val defaulted = setOf("pureAiMode", "streamEnabled", "worldBookEntryScanDepthDefault")
        val fields = JsonObject(raw.filter { (key, value) -> key in appKeys && key !in setOf("llm", "generationDefaults", "prompts") &&
            (value != JsonNull || key !in defaulted) })
        val llm = raw["llm"]?.takeUnless { it == JsonNull }?.let { objectValue(it) }
        val generation = normalize(objectValue(raw["generationDefaults"]))
        val prompts = objectValue(raw["prompts"])
        json.decodeFromJsonElement<AppSettings>(fields).copy(
            llm = llm?.let { json.decodeFromJsonElement<LlmSettings>(JsonObject(it.filter { (key, value) ->
                key in llmKeys && (key != "protocol" || value != JsonNull) })).copy(extensions = JsonObject(it - llmKeys)) },
            generationDefaults = json.decodeFromJsonElement<GenerationDefaults>(JsonObject(generation.filterKeys { it in generationKeys })).copy(extensions = JsonObject(generation - generationKeys)),
            prompts = json.decodeFromJsonElement<PromptSettings>(JsonObject(prompts.filter { (key, value) -> key in promptKeys && value != JsonNull })).copy(extensions = JsonObject(prompts - promptKeys)),
            extensions = JsonObject(raw - appKeys),
        ).also(::validate)
    } catch (error: StError) { throw error }
    catch (_: Exception) { throw StError.Validation("invalid typed application settings") }

    fun encode(value: AppSettings, previous: String? = null): String {
        validate(value)
        val raw = root(previous)
        fun merge(base: JsonObject, extensions: JsonObject, typed: JsonObject) = JsonObject(base + extensions + (typed - "extensions"))
        val result = merge(raw, value.extensions, json.encodeToJsonElement(value).jsonObject).toMutableMap()
        result["llm"] = value.llm?.let { merge(objectValue(raw["llm"]), it.extensions, json.encodeToJsonElement(it).jsonObject) } ?: JsonNull
        result["generationDefaults"] = merge(normalize(objectValue(raw["generationDefaults"])), value.generationDefaults.extensions,
            json.encodeToJsonElement(value.generationDefaults).jsonObject)
        result["prompts"] = merge(objectValue(raw["prompts"]), value.prompts.extensions, json.encodeToJsonElement(value.prompts).jsonObject)
        return JsonObject(result).toString()
    }

    private fun validate(value: AppSettings) {
        val gen = value.generationDefaults
        if (value.worldBookEntryScanDepthDefault !in 0..500 || gen.contextSize?.let { it < 0 } == true ||
            gen.maxTokens?.let { it <= 0 } == true || gen.temperature?.let { !it.isFinite() || it !in 0.0..2.0 } == true ||
            gen.topP?.let { !it.isFinite() || it !in 0.0..1.0 } == true)
            throw StError.Validation("settings contain invalid generation limits or worldbook scan depth")
    }
}
