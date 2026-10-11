package com.simpletavern.core.model

import kotlinx.serialization.Serializable

/**
 * Unified task state machine for generation, memory, and sandbox work.
 * Terminal: SUCCEEDED, FAILED, STOPPED, INTERRUPTED.
 */
enum class TaskStatus {
    QUEUED,
    RUNNING,
    AWAITING_APPROVAL,
    STOPPING,
    SUCCEEDED,
    FAILED,
    STOPPED,
    INTERRUPTED,
    ;

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == STOPPED || this == INTERRUPTED
}

enum class TaskKind {
    CHAT_GENERATION,
    GROUP_GENERATION,
    MEMORY_SUMMARY,
    MVU_UPDATE,
    KG_UPDATE,
    SANDBOX_SHELL,
    SANDBOX_FILE,
    FILE_COPY,
    FILE_EXPORT,
    SPEECH_SYNTHESIS,
    SPEECH_PLAYBACK,
    GEMINI_CACHE_OPERATION,
    GEMINI_CACHE_LIST,
    IMPORT,
    BACKUP,
    RESTORE,
    SPEECH_VOICE_DESIGN,
    SPEECH_VOICE_CLONE,
    INTERNAL,
}

enum class TaskTerminalReason {
    COMPLETED,
    USER_STOP,
    ERROR,
    PROCESS_KILLED,
    APPROVAL_REJECTED,
    APPROVAL_EXPIRED,
    SUPERSEDED,
    HOST_TIMEOUT,
    HOST_UNAVAILABLE,
}

/** The first stop cause wins; platform interruption is not reported as a user stop. */
fun TaskSnapshot.stoppingStatus(): TaskStatus =
    if (terminalReason in setOf(TaskTerminalReason.HOST_TIMEOUT, TaskTerminalReason.HOST_UNAVAILABLE))
        TaskStatus.INTERRUPTED else TaskStatus.STOPPED

fun TaskSnapshot.completionReason(status: TaskStatus): TaskTerminalReason =
    if (this.status == TaskStatus.STOPPING && terminalReason != null) terminalReason
    else when (status) {
        TaskStatus.SUCCEEDED -> TaskTerminalReason.COMPLETED
        TaskStatus.STOPPED -> TaskTerminalReason.USER_STOP
        TaskStatus.INTERRUPTED -> TaskTerminalReason.PROCESS_KILLED
        else -> TaskTerminalReason.ERROR
    }
