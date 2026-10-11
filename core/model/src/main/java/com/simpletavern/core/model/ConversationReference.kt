package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ConversationReferenceSummary(val id: String, val title: String, val updatedAt: String, val pinned: Boolean)

@Serializable
data class ConversationReferenceHit(val chatId: String, val messageId: String, val orderIndex: Long,
    val title: String, val snippet: String, val updatedAt: String)

@Serializable
data class ConversationReferenceSearchPage(val entries: List<ConversationReferenceHit>, val hasMore: Boolean, val nextOffset: Int?)

@Serializable
data class ConversationReferenceMessage(val id: String, val orderIndex: Long, val role: ChatRole, val content: String)

@Serializable
data class ConversationReferencePage(val chatId: String, val title: String, val entries: List<ConversationReferenceMessage>,
    val hasMore: Boolean, val nextAfterOrder: Long?)
