package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class SandboxExportDestination {
    WORKSPACE_TEXT,
    WORKSPACE_ASSET,
    CHAT_ATTACHMENT,
    DOCUMENT,
}

@Serializable
data class SandboxExportProgress(
    val taskId: String,
    val sandboxId: String,
    val sourcePath: String,
    val destination: SandboxExportDestination,
    val updatedAt: String,
    val phase: String = "queued",
    val assistantId: String? = null,
    val chatId: String? = null,
    val workspacePath: String? = null,
    val bytesCopied: Long = 0,
    val attachment: AttachmentRef? = null,
    val resultUpdatedAt: String? = null,
    val error: String? = null,
    val documentUri: String? = null,
    val documentMayBePartial: Boolean = false,
    val documentWriteCompleted: Boolean = false,
)
