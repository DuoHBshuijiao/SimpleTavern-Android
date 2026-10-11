package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProviderModelDirectoryKind {
    MODELS,
    BEDROCK_INFERENCE_PROFILES,
}

@Serializable
data class ProviderModelInfo(
    val id: String,
    val name: String? = null,
    val createdAt: String? = null,
    val ownedBy: String? = null,
    val inputTokenLimit: Long? = null,
    val outputTokenLimit: Long? = null,
    val supportedGenerationMethods: List<String> = emptyList(),
    val resourceName: String? = null,
    val version: String? = null,
    val status: String? = null,
    val inputModalities: List<String> = emptyList(),
    val outputModalities: List<String> = emptyList(),
    val inferenceTypes: List<String> = emptyList(),
    val streamingSupported: Boolean? = null,
    val referencedModels: List<String> = emptyList(),
    val profileType: String? = null,
)

@Serializable
data class ProviderModelPage(
    val presetId: String,
    val protocol: LlmProtocol,
    val models: List<ProviderModelInfo>,
    val fetchedAt: String,
    val nextPageToken: String? = null,
    val warnings: List<String> = listOf(
        "remote_listing_not_generation_capability_proof",
        "prices_not_installed",
    ),
    val kind: ProviderModelDirectoryKind = ProviderModelDirectoryKind.MODELS,
)
