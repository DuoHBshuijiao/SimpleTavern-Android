package com.simpletavern.core.model

import kotlinx.serialization.Serializable

/** A process/restore-local cursor, not a portable database identity. */
@Serializable
data class FileChangeCursor(val streamEpoch: String, val sandboxId: String, val afterSequence: Long = 0)

@Serializable
data class SequencedFileChange(val sequence: Long, val change: FileChangeRecord,
                              val truncatedFields: List<String> = emptyList())

data class FileChangeTaskSummary(val ids: List<String>, val total: Long, val incomplete: Boolean)

@Serializable
data class FileChangePage(
    val items: List<SequencedFileChange>,
    val nextCursor: FileChangeCursor,
    val throughSequence: Long,
    val hasMore: Boolean,
)

/** Counts are shared-session observations, never proof of a particular task's writes. */
@Serializable
data class FileObservationReport(
    val sessionId: String,
    val sandboxId: String,
    val taskId: String?,
    val startedAt: String,
    val endedAt: String,
    val fromCursor: FileChangeCursor,
    val throughSequence: Long,
    val recordedEvents: Long,
    val droppedCallbacks: Long,
    val watchAttempts: Int,
    val cachedNodes: Int,
    val uncertainties: List<String>,
    val complete: Boolean = false,
)
