package com.simpletavern.core.model

import kotlinx.serialization.Serializable

/** A rebuild's intermediate draft is diagnostic data, not the formal long-term memory. */
@Serializable
data class MemorySummaryProgress(
    val taskId: String,
    val chatId: String,
    val rebuild: Boolean,
    val totalChunks: Int = 0,
    val completedChunks: Int = 0,
    val activeChunk: Int? = null,
    val sourceMessageCount: Int = 0,
    val processedMessageCount: Int = 0,
    val sourceThroughMessageId: String? = null,
    val draftCoveredThroughMessageId: String? = null,
    val chunkSourceMessageIds: List<String> = emptyList(),
    val draftText: String? = null,
    val appliedMemoryVersion: Long? = null,
    val checkpointRevision: Long = 0,
    val updatedAt: String,
)
