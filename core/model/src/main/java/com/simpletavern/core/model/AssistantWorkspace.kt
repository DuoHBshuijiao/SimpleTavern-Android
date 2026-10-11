package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AssistantWorkspaceFile(val assistantId: String, val path: String, val content: String, val updatedAt: String,
    val sourceNamespace: String? = null, val sourcePath: String? = null)

@Serializable
data class WorkspaceFileInfo(val path: String, val updatedAt: String)

@Serializable
data class WorkspaceFilePage(val files: List<WorkspaceFileInfo>, val nextAfterPath: String?, val hasMore: Boolean)

@Serializable
data class AssistantWorkspaceAsset(val assistantId: String, val path: String, val attachment: AttachmentRef,
    val updatedAt: String, val sourceNamespace: String? = null)
