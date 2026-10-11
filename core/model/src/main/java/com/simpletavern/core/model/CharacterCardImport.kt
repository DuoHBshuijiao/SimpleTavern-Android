package com.simpletavern.core.model

import kotlinx.serialization.Serializable

/** Caller-owned preview; it does not register a pending import or publish any records. */
@Serializable
data class CharacterCardImportPreview(
    val character: CharacterCard,
    val worldBook: WorldBook? = null,
    val openingMessages: List<String> = emptyList(),
    val sourceFormat: String,
    val hasEmbeddedAvatar: Boolean = false,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class CharacterCardImportResult(
    val character: CharacterCard,
    val worldBook: WorldBook? = null,
    val avatar: AttachmentRef? = null,
    val warnings: List<String> = emptyList(),
)
