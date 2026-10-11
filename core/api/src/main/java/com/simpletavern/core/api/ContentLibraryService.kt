package com.simpletavern.core.api

import com.simpletavern.core.data.ChatRepository
import com.simpletavern.core.data.ContentRepository
import com.simpletavern.core.model.*
import java.time.Instant

/** Shared library editing for Core callers and approved assistant calls. No separate draft engine. */
internal class ContentLibraryService(private val content: ContentRepository, private val chats: ChatRepository) {
    suspend fun editWorldBookBindings(chatId: String, expectedUpdatedAt: String,
        change: suspend (ChatOverrides) -> ChatOverrides): ChatSession = chats.transaction {
        val chat = chats.getSession(chatId) ?: throw StError.NotFound("chat")
        if (chat.updatedAt != expectedUpdatedAt) throw StError.Conflict("chat changed since it was read")
        chat.copy(overrides = change(chat.overrides), updatedAt = Instant.now().toString()).also { chats.saveSession(it) }
    }

    suspend fun setGlobalExclusion(chatId: String, worldBookId: String, excluded: Boolean, expectedUpdatedAt: String) =
        editWorldBookBindings(chatId, expectedUpdatedAt) { overrides ->
            requireBook(worldBookId)
            overrides.copy(worldBookGlobalExclusions = if (excluded) (overrides.worldBookGlobalExclusions + worldBookId).distinct()
                else overrides.worldBookGlobalExclusions - worldBookId)
        }

    suspend fun setSelectionMode(chatId: String, mode: String, expectedUpdatedAt: String) =
        editWorldBookBindings(chatId, expectedUpdatedAt) { overrides ->
            if (mode !in setOf("merge", "character", "session")) throw StError.Validation("worldbook selection mode must be merge/character/session")
            overrides.copy(worldBookSelectionMode = mode)
        }

    suspend fun attach(chatId: String, attachment: WorldBookAttachment, expectedUpdatedAt: String) =
        editWorldBookBindings(chatId, expectedUpdatedAt) { overrides ->
            requireBook(attachment.worldBookId)
            validateDepths(attachment)
            if (overrides.worldBookAttachments.any { it.worldBookId == attachment.worldBookId }) throw StError.Conflict("worldbook already attached")
            overrides.copy(worldBookAttachments = overrides.worldBookAttachments + attachment)
        }

    suspend fun updateAttachment(chatId: String, attachment: WorldBookAttachment, expectedUpdatedAt: String) =
        editWorldBookBindings(chatId, expectedUpdatedAt) { overrides ->
            requireBook(attachment.worldBookId)
            validateDepths(attachment)
            if (overrides.worldBookAttachments.none { it.worldBookId == attachment.worldBookId }) throw StError.NotFound("worldbook attachment")
            overrides.copy(worldBookAttachments = overrides.worldBookAttachments.map { if (it.worldBookId == attachment.worldBookId) attachment else it })
        }

    suspend fun detach(chatId: String, worldBookId: String, expectedUpdatedAt: String) =
        editWorldBookBindings(chatId, expectedUpdatedAt) { overrides ->
            if (overrides.worldBookAttachments.none { it.worldBookId == worldBookId }) throw StError.NotFound("worldbook attachment")
            overrides.copy(worldBookAttachments = overrides.worldBookAttachments.filterNot { it.worldBookId == worldBookId })
        }

    suspend fun reorder(chatId: String, orderedIds: List<String>, expectedUpdatedAt: String) =
        editWorldBookBindings(chatId, expectedUpdatedAt) { overrides ->
            val attached = overrides.worldBookAttachments.associateBy { it.worldBookId }
            if (orderedIds.distinct().size != orderedIds.size || orderedIds.any { it !in attached }) throw StError.Validation("order must contain distinct attached worldbook IDs")
            overrides.copy(worldBookAttachments = orderedIds.map(attached::getValue) + overrides.worldBookAttachments.filter { it.worldBookId !in orderedIds })
        }

    private suspend fun requireBook(id: String) { if (content.getWorldBook(id) == null) throw StError.NotFound("worldbook") }
    private fun validateDepths(attachment: WorldBookAttachment) {
        if (attachment.scanDepth != null && attachment.scanDepth !in 1..500 || attachment.insertDepth !in 1..500)
            throw StError.Validation("worldbook depth must be 1..500; absent scanDepth inherits the source default")
    }
}
