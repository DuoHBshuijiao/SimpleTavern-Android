package com.simpletavern.core.memory

import com.simpletavern.core.data.*
import com.simpletavern.core.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Fixed raw sources for comparison; the model sees only ordinary body text and attribution. */
internal object StateHistorySources {
    fun eligible(row: SelectedMessage): Boolean = row.candidate.role in setOf(ChatRole.user, ChatRole.assistant, ChatRole.system) &&
        row.candidate.content.isNotBlank() && Jsonx.decodeOrNull<GenerationMetadata>(row.candidate.generationMetadataJson)
            ?.status?.lowercase() !in setOf("queued", "running", "awaiting_tools", "stopping", "stopped", "failed", "interrupted")

    fun body(row: SelectedMessage): SelectedMessage = row.copy(candidate = MessageCandidate(
        id = row.candidate.id, role = row.candidate.role, content = row.candidate.content,
        characterId = row.candidate.characterId, senderName = row.candidate.senderName,
        senderPersonaId = row.candidate.senderPersonaId, createdAt = row.candidate.createdAt))

    suspend fun read(chats: ChatRepository, chatId: String, through: Long): List<SelectedMessage> {
        val result = mutableListOf<SelectedMessage>()
        var offset = 0
        var chars = 0L
        var previousOrder = -1L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = chats.selectedMessagePage(chatId, -1, through, offset)
            for ((index, row) in page.withIndex()) {
                if (offset + index >= 20_000) throw StError.Validation("state rebuild exceeds 20000 selected nodes")
                if (row.orderIndex <= previousOrder) throw StError.Conflict("duplicate message order; repair history before state rebuild")
                previousOrder = row.orderIndex
                chars += Jsonx.encode(row).length
                if (chars > 20_000_000) throw StError.Validation("state rebuild source snapshot exceeds budget")
                if (eligible(row)) result += row
            }
            offset += page.size
            if (page.size < 200) break
        }
        if (result.isEmpty()) throw StError.Validation("no completed history for state rebuild")
        return result
    }

    fun segments(sources: List<SelectedMessage>): List<List<SelectedMessage>> {
        val result = mutableListOf<List<SelectedMessage>>()
        var segment = mutableListOf<SelectedMessage>()
        var chars = 0
        for (row in sources) {
            val size = Jsonx.encode(body(row)).length + 1
            if (size > 25_000) throw StError.Validation("one state history message exceeds 25000 characters; no body was truncated")
            if (segment.isNotEmpty() && (segment.size == 50 || chars + size > 25_000)) {
                result += segment; segment = mutableListOf(); chars = 0
            }
            segment += row; chars += size
        }
        if (segment.isNotEmpty()) result += segment
        if (result.size > 256) throw StError.Validation("state rebuild exceeds 256 segments")
        return result
    }

    suspend fun unchanged(chats: ChatRepository, chatId: String, sources: List<SelectedMessage>): Boolean {
        var offset = 0
        var index = 0
        while (true) {
            val page = chats.selectedMessagePage(chatId, -1, sources.last().orderIndex, offset)
            for (row in page.filter(::eligible)) {
                if (index >= sources.size || sources[index++] != row) return false
            }
            offset += page.size
            if (page.size < 200) break
        }
        return index == sources.size
    }
}
