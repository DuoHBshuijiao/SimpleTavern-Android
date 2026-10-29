package com.simpletavern.core.api

import com.simpletavern.core.data.ContentRepository
import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.importer.WorldBookFileDecoder
import com.simpletavern.core.model.*
import java.time.Instant
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class WorldBookLibraryService(private val content: ContentRepository) {
    suspend fun importFile(source: File): WorldBookImportResult {
        val text = withContext(Dispatchers.IO) {
            if (!source.isFile) throw StError.NotFound("worldbook input missing")
            source.inputStream().use { NativeJsonInput.read(it, WorldBookFileDecoder.MAX_INPUT_BYTES) }
        }
        return importJson(text, source.name)
    }

    suspend fun importJson(text: String, filename: String): WorldBookImportResult {
        val result = withContext(Dispatchers.Default) { WorldBookFileDecoder.decode(text, filename) }
        content.transaction { content.saveWorldBook(result.worldBook) }
        return result
    }

    suspend fun attach(characterId: String, worldBookId: String, expectedUpdatedAt: String): CharacterCard = content.transaction {
        val character = character(characterId, expectedUpdatedAt)
        content.getWorldBook(worldBookId) ?: throw StError.NotFound("worldbook")
        if (worldBookId in character.attachedWorldBookIds) return@transaction character
        character.copy(attachedWorldBookIds = character.attachedWorldBookIds + worldBookId,
            updatedAt = Instant.now().toString()).also { content.saveCharacter(it) }
    }

    suspend fun detach(characterId: String, worldBookId: String, expectedUpdatedAt: String): CharacterCard = content.transaction {
        val character = character(characterId, expectedUpdatedAt)
        if (worldBookId !in character.attachedWorldBookIds) return@transaction character
        character.copy(attachedWorldBookIds = character.attachedWorldBookIds - worldBookId,
            updatedAt = Instant.now().toString()).also { content.saveCharacter(it) }
    }

    private suspend fun character(id: String, expectedUpdatedAt: String): CharacterCard {
        val current = content.getCharacter(id) ?: throw StError.NotFound("character")
        if (current.updatedAt != expectedUpdatedAt) throw StError.Conflict("character changed since it was read")
        return current
    }
}
