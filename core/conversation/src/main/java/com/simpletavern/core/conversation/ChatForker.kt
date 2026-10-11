package com.simpletavern.core.conversation

import com.simpletavern.core.data.ChatRepository
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.data.TaskStore
import com.simpletavern.core.memory.MemoryService
import com.simpletavern.core.model.*
import java.time.Instant
import java.util.UUID

/** Known references are rewritten; opaque source JSON remains provenance, not live state. */
internal class ChatForker(
    private val chats: ChatRepository,
    private val tasks: TaskStore,
    private val memory: MemoryService,
) {
    suspend fun fork(sourceChatId: String, forkAtMessageId: String, newTitle: String?): ChatSession = chats.transaction {
        val source = chats.getSession(sourceChatId) ?: throw StError.NotFound("chat $sourceChatId")
        if (tasks.activeGeneration(sourceChatId).isNotEmpty()) {
            throw StError.Conflict("stop generation before forking this chat")
        }
        val all = chats.pageMessages(sourceChatId, Int.MAX_VALUE, 0)
        val anchorIndex = all.indexOfFirst { it.id == forkAtMessageId || it.selected?.id == forkAtMessageId }
        if (anchorIndex < 0) throw StError.NotFound("selected message $forkAtMessageId")
        val anchor = all[anchorIndex].selected ?: throw StError.Validation("fork node has no selected message")
        val keep = all.take(anchorIndex + 1)
        if (keep.any { node -> node.candidates.any {
            Jsonx.decodeOrNull<GenerationMetadata>(it.generationMetadataJson)?.status in
                setOf("queued", "running", "awaiting_tools", "stopping")
        } }) throw StError.Conflict("fork includes an unfinished answer")
        // Precomputed IDs keep copied anchors and references aligned.
        val nodeIds = keep.associate { it.id to id() }
        val messageIds = buildMap {
            for (node in keep) for (candidate in node.candidates) put(candidate.id, id())
        }
        val now = Instant.now().toString()
        val session = source.copy(
            id = id(), title = newTitle?.trim()?.takeIf { it.isNotEmpty() } ?: "分叉：${source.title}",
            forkedFromChatId = source.id, forkedFromMessageId = anchor.id,
            forkedFromMessageIndex = anchorIndex + 1,
            overrides = source.overrides.copy(longTermMemory = null, contextStartMessageId = null,
                contextStartKeepBeforeMessages = null, lastAutoMemorySummaryAfterMessageId = null,
                lastStateMaintenanceAfterMessageId = null,
                autoMemorySummaryNextAskTier = 1),
            // Keep the source scope for referenced presets/world books, but do not claim its source identity.
            sourceId = null, createdAt = now, updatedAt = now,
        )
        val copied = keep.mapIndexed { index, node -> node.copy(
            id = nodeIds.getValue(node.id), chatId = session.id, orderIndex = index.toLong(),
            parentNodeId = node.parentNodeId?.let { nodeIds[it] },
            candidates = node.candidates.map { candidate ->
                val copiedId = messageIds.getValue(candidate.id)
                candidate.copy(id = copiedId, generationMetadataJson =
                    Jsonx.decodeOrNull<GenerationMetadata>(candidate.generationMetadataJson)?.let { metadata ->
                        Jsonx.encode(metadata.copy(chatId = session.id, messageId = copiedId))
                    } ?: candidate.generationMetadataJson)
            },
        ) }
        chats.saveSession(session)
        chats.replaceAllMessages(session.id, copied)
        // Hard fork: no memory, MVU, KG, task, approval or usage-event rows are copied.
        session
    }

    suspend fun copyAll(
        source: ChatSession,
        newTitle: String?,
        configure: suspend (ChatSession) -> ChatSession = { it },
    ): ChatSession = chats.transaction {
        if (tasks.activeGeneration(source.id).isNotEmpty()) {
            throw StError.Conflict("stop generation before copying this chat")
        }
        val all = chats.pageMessages(source.id, Int.MAX_VALUE, 0)

        val nodeIds = all.associate { it.id to id() }
        val messageIds = buildMap {
            for (node in all) for (candidate in node.candidates) put(candidate.id, id())
        }
        val lastSelectedId = all.lastOrNull()?.selected?.id
        val now = Instant.now().toString()
        val session = source.copy(
            id = id(),
            title = newTitle?.trim()?.takeIf { it.isNotEmpty() } ?: "${source.title}-新分支",
            forkedFromChatId = source.id,
            forkedFromMessageId = lastSelectedId,
            forkedFromMessageIndex = all.size,
            overrides = source.overrides.copy(
                contextStartMessageId = source.overrides.contextStartMessageId?.let {
                    nodeIds[it] ?: messageIds[it]
                },
                lastAutoMemorySummaryAfterMessageId = source.overrides.lastAutoMemorySummaryAfterMessageId?.let {
                    messageIds[it] ?: nodeIds[it]
                },
                lastStateMaintenanceAfterMessageId = source.overrides.lastStateMaintenanceAfterMessageId?.let {
                    messageIds[it] ?: nodeIds[it]
                },
            ),
            sourceId = null,
            createdAt = now,
            updatedAt = now,
        )
        val copied = all.mapIndexed { index, node -> node.copy(
            id = nodeIds.getValue(node.id),
            chatId = session.id,
            orderIndex = index.toLong(),
            parentNodeId = node.parentNodeId?.let { nodeIds[it] },
            candidates = node.candidates.map { candidate ->
                val copiedId = messageIds.getValue(candidate.id)
                candidate.copy(
                    id = copiedId,
                    characterId = candidate.characterId ?: candidate.takeIf { it.role == ChatRole.assistant }?.let { source.characterId },
                    generationMetadataJson = Jsonx.decodeOrNull<GenerationMetadata>(candidate.generationMetadataJson)?.let { metadata ->
                        // No worker is copied; stale imported/live markers describe interrupted history here.
                        val status = if (metadata.status in setOf("queued", "running", "awaiting_tools", "stopping"))
                            "interrupted" else metadata.status
                        Jsonx.encode(metadata.copy(chatId = session.id, messageId = copiedId, status = status))
                    } ?: candidate.generationMetadataJson,
                )
            },
        ) }
        val configured = configure(session)
        chats.saveSession(configured)
        chats.replaceAllMessages(configured.id, copied)
        memory.copySnapshot(source.id, configured.id, nodeIds, messageIds)
        configured
    }

    private fun id() = UUID.randomUUID().toString().replace("-", "")
}
