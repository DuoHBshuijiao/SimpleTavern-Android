package com.simpletavern.core.api

import com.simpletavern.core.data.*
import com.simpletavern.core.model.*
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

internal class AssistantWorkspaceService(private val store: AssistantWorkspaceStore,
    private val content: ContentRepository, private val chats: ChatRepository, private val attachments: AttachmentStore) {
    private suspend fun requireAssistant(id: String) {
        if (content.getAssistant(id) == null) throw StError.NotFound("assistant configuration")
    }
    suspend fun list(assistantId: String, afterPath: String = "", limit: Int = 100): WorkspaceFilePage {
        requireAssistant(assistantId)
        return store.list(assistantId, afterPath, limit)
    }
    suspend fun read(assistantId: String, path: String): AssistantWorkspaceFile {
        requireAssistant(assistantId)
        return store.get(assistantId, path) ?: throw StError.NotFound("workspace file")
    }
    suspend fun listAssets(assistantId: String, afterPath: String = "", limit: Int = 100): WorkspaceFilePage {
        requireAssistant(assistantId)
        return store.listAssets(assistantId, afterPath, limit)
    }
    suspend fun asset(assistantId: String, path: String): AssistantWorkspaceAsset {
        requireAssistant(assistantId)
        return store.getAsset(assistantId, path) ?: throw StError.NotFound("workspace asset")
    }
    suspend fun linkAsset(assistantId: String, path: String, attachmentId: String,
                          expectedAttachmentId: String?): AssistantWorkspaceAsset = chats.transaction {
        requireAssistant(assistantId)
        val current = store.getAsset(assistantId, path)
        if (current?.attachment?.id != expectedAttachmentId) throw StError.Conflict("workspace asset changed")
        val row = attachments.get(attachmentId) ?: throw StError.NotFound("managed attachment")
        val ref = AttachmentRef(row.id, row.filename, row.mimeType, row.relativePath, row.size)
        val asset = AssistantWorkspaceAsset(assistantId, AssistantWorkspaceStore.normalize(path), ref,
            Instant.now().toString(), current?.sourceNamespace)
        store.saveImportedAsset(asset)
        asset
    }
    suspend fun deleteAsset(assistantId: String, path: String, expectedAttachmentId: String) = chats.transaction {
        requireAssistant(assistantId)
        val current = store.getAsset(assistantId, path) ?: throw StError.NotFound("workspace asset")
        if (current.attachment.id != expectedAttachmentId) throw StError.Conflict("workspace asset changed")
        store.deleteAsset(assistantId, path)
    }
    suspend fun write(assistantId: String, path: String, text: String, expectedContent: String?) = chats.transaction {
        requireAssistant(assistantId)
        store.write(assistantId, path, text, expectedContent)
    }
    suspend fun delete(assistantId: String, path: String, expectedContent: String) = chats.transaction {
        requireAssistant(assistantId)
        store.delete(assistantId, path, expectedContent)
    }

    suspend fun beginCard(assistantId: String, characterId: String?, expectedContent: String?): AssistantWorkspaceFile {
        val at = Instant.now().toString()
        val card = characterId?.let { content.getCharacter(it) ?: throw StError.NotFound("character") }
            ?: CharacterCard(UUID.randomUUID().toString(), createdAt = at, updatedAt = at)
        return write(assistantId, CARD_PATH, Jsonx.encode(card), expectedContent)
    }

    suspend fun patchCard(assistantId: String, patch: JsonObject, expectedContent: String): AssistantWorkspaceFile = chats.transaction {
        val current = read(assistantId, CARD_PATH)
        if (current.content != expectedContent) throw StError.Conflict("workspace card changed")
        if (patch.isEmpty() || patch.keys.any { it !in CARD_FIELDS }) throw StError.Validation("invalid workspace card patch fields")
        val original = Jsonx.json.parseToJsonElement(Jsonx.encode(decodeCard(current.content))).jsonObject
        val changed = decodeCard(JsonObject(original + patch + ("updatedAt" to JsonPrimitive(Instant.now().toString()))).toString())
        write(assistantId, CARD_PATH, Jsonx.encode(changed), expectedContent)
    }

    suspend fun replaceCard(assistantId: String, card: JsonObject, expectedContent: String?): AssistantWorkspaceFile =
        write(assistantId, CARD_PATH, Jsonx.encode(decodeCard(card.toString())), expectedContent)

    suspend fun publishCard(assistantId: String, expectedContent: String, targetCharacterId: String?, expectedUpdatedAt: String?): CharacterCard = chats.transaction {
        val file = read(assistantId, CARD_PATH)
        if (file.content != expectedContent) throw StError.Conflict("workspace card changed")
        val draft = decodeCard(file.content)
        val current = targetCharacterId?.let { content.getCharacter(it) ?: throw StError.NotFound("target character") }
        if (current != null && current.updatedAt != expectedUpdatedAt) throw StError.Conflict("target character changed; read it again before publishing")
        if (current == null && expectedUpdatedAt != null) throw StError.Validation("new character publication has no existing timestamp")
        for (bookId in draft.attachedWorldBookIds) if (content.getWorldBook(bookId) == null) throw StError.NotFound("draft worldbook reference")
        val avatar = draft.avatarRelativePath?.let { path ->
            if (path == current?.avatarRelativePath || attachments.findByRelativePath(path) != null) path
            else throw StError.Validation("draft avatar must be a registered managed attachment")
        } ?: current?.avatarRelativePath
        val at = Instant.now().toString()
        // An arbitrary workspace JSON must not install file paths or replace imported record provenance.
        val published = draft.copy(id = current?.id ?: UUID.randomUUID().toString(), createdAt = current?.createdAt ?: at, updatedAt = at,
            avatarRelativePath = avatar, sourceNamespace = current?.sourceNamespace, sourceId = current?.sourceId,
            extraJson = current?.extraJson ?: draft.extraJson)
        content.saveCharacter(published)
        published
    }

    private fun decodeCard(text: String): CharacterCard {
        AssistantWorkspaceStore.validateContent(text)
        return Jsonx.decodeOrNull<CharacterCard>(text) ?: throw StError.Validation("workspace character card must be an Android CharacterCard object")
    }
    companion object {
        const val CARD_PATH = "character_card.json"
        val CARD_FIELDS = setOf("name", "description", "personality", "scenario", "firstMessage", "exampleDialogue", "systemPrompt",
            "avatarRelativePath", "attachedWorldBookIds", "mvuEnabled", "mvuMode", "mvuDirective", "initialStateTablesJson", "contentRegexRulesJson")
    }
}
