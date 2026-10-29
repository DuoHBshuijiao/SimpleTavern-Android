package com.simpletavern.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject

/** Native global settings. Provider-specific and future fields survive typed edits in extensions. */
@Serializable
data class AppSettings(
    val selectedPersonaId: String? = null,
    val themeId: String? = null,
    val pureAiMode: Boolean = false,
    val streamEnabled: Boolean = true,
    val worldBookEntryScanDepthDefault: Int = 2,
    val llm: LlmSettings? = null,
    val generationDefaults: GenerationDefaults = GenerationDefaults(),
    val prompts: PromptSettings = PromptSettings(),
    val extensions: JsonObject = JsonObject(emptyMap()),
)

/** Credentials remain in CredentialStore; this model contains only a credential reference. */
@Serializable
data class LlmSettings(
    val providerId: String? = null,
    val protocol: String = "openai_compatible_chat",
    val baseUrl: String? = null,
    val model: String? = null,
    val defaultModel: String? = null,
    val credentialId: String? = null,
    val extensions: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class GenerationDefaults(
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("context_size") val contextSize: Int? = null,
    val extensions: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class PromptSettings(
    val globalSystem: String = "",
    val globalPrefill: String = "",
    val globalPrefillEnabled: Boolean = true,
    val extensions: JsonObject = JsonObject(emptyMap()),
)

/** The test generates no chat/message/task/cache. Usage, when supplied, is returned to the caller. */
@Serializable
data class ConnectionTestResult(
    val success: Boolean,
    val latencyMs: Long,
    val protocol: LlmProtocol? = null,
    val model: String? = null,
    val usage: UsageTokens? = null,
    val errorCode: String? = null,
    val message: String? = null,
)

@Serializable
data class WorldBookImportResult(val worldBook: WorldBook, val sourceFormat: String, val warnings: List<String> = emptyList())
