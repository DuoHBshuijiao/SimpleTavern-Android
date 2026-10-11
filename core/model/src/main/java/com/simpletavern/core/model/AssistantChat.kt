package com.simpletavern.core.model

import kotlinx.serialization.Serializable

/** Explicit configuration and optional main-chat scope; the assistant keeps its own history. */
@Serializable
data class AssistantChatBinding(
    val assistantId: String,
    val targetChatId: String? = null,
    val allowWriteMemory: Boolean = false,
    val allowDestructiveTools: Boolean = false,
    val sandboxToolsEnabled: Boolean = false,
    val allowWriteState: Boolean = false,
)
