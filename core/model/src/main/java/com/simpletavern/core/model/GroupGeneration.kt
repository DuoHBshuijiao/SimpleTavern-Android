package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class GroupGenerationOptions(
    val rounds: Int = 1,
    /** Empty means the session's member order. A subset may be explicitly ordered. */
    val memberIds: List<String> = emptyList(),
    val respectProbability: Boolean = true,
)

@Serializable
enum class GroupTurnStatus { QUEUED, SKIPPED, RUNNING, SUCCEEDED, FAILED, STOPPED, INTERRUPTED }

@Serializable
data class GroupGenerationTurn(
    val round: Int,
    val characterId: String,
    val status: GroupTurnStatus,
    val assistantMessageIds: List<String> = emptyList(),
)

@Serializable
data class GroupGenerationProgress(
    val taskId: String,
    val chatId: String,
    val rounds: Int,
    val delayMs: Int,
    val turns: List<GroupGenerationTurn>,
    val currentTurnIndex: Int? = null,
    val updatedAt: String,
)
