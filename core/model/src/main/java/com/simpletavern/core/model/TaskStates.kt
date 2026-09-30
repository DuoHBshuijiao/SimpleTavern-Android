package com.simpletavern.core.model

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
    IMPORT,
    BACKUP,
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
}
