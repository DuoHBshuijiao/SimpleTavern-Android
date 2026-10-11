package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class StateToolRecord(
    val callId: String,
    val name: String,
    val argumentsJson: String,
    val resultJson: String,
)

/** null session rules means role inheritance; [] means an explicit empty session list. */
@Serializable
data class StateRegexDraft(
    val characterId: String,
    val sessionRulesJson: String? = null,
    val characterRulesJson: String? = null,
    val sessionRead: Boolean = false,
    val characterRead: Boolean = false,
    val sessionChanged: Boolean = false,
    val characterChanged: Boolean = false,
    val applied: Boolean = false,
)

/** Task payload contains drafts/diagnostics, never a provider or a credential. */
@Serializable
data class StateMaintenanceProgress(
    val taskId: String,
    val chatId: String,
    val maintainMvu: Boolean,
    val maintainKg: Boolean,
    val automatic: Boolean = false,
    val throughMessageId: String? = null,
    val sourceMessageIds: List<String> = emptyList(),
    val expectedMvuVersion: Int? = null,
    val expectedKgVersion: Int? = null,
    val draftMvu: MvuState? = null,
    val draftKg: KnowledgeGraph? = null,
    val partialText: String = "",
    val toolRecords: List<StateToolRecord> = emptyList(),
    val appliedMvuVersion: Int? = null,
    val appliedKgVersion: Int? = null,
    val checkpointRevision: Long = 0,
    val regexRecords: List<ContentRegexRecord> = emptyList(),
    val draftRegex: StateRegexDraft? = null,
    // Rebuild sourceMessageIds/partialText/toolRecords describe only the current or last segment.
    // draftMvu/draftKg can contain unfinished segment changes; applied versions alone claim formal writes.
    val historyRebuild: Boolean = false,
    val totalSegments: Int = 0,
    val completedSegments: Int = 0,
    val activeSegment: Int? = null,
    val historySourceMessageCount: Int = 0,
    val processedHistoryMessageCount: Int = 0,
    val historyThroughMessageId: String? = null,
    val draftCoveredThroughMessageId: String? = null,
    val historyRequestCount: Int = 0,
    val historyToolCallCount: Int = 0,
    val historyExtractionCount: Int = 0,
    val updatedAt: String,
)

@Serializable
data class StateMaintenanceResult(val task: TaskSnapshot, val progress: StateMaintenanceProgress)

@Serializable
data class MvuWorkLog(
    val id: String, val chatId: String, val timestamp: String,
    val eventType: String, val summary: String, val detailJson: String? = null,
)
