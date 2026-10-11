package com.simpletavern.core.api

import com.simpletavern.core.data.*
import com.simpletavern.core.data.db.AttachmentEntity
import com.simpletavern.core.model.*
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** User-selected drafts append ordinary history. No server requests, model calls or system/tool impersonation. */
internal class McpPromptApplicationService(private val content: ContentRepository, private val chats: ChatRepository,
    private val attachments: AttachmentStore, private val paths: StoragePaths, private val tasks: TaskStore,
    private val gate: DataOperationGate) {

    private data class Entry(val role: ChatRole, val text: String, val block: JsonObject, val mediaIndex: Int?)

    suspend fun apply(chatId: String, draft: McpPromptResult, expectedUpdatedAt: String,
                      characterId: String?): McpPromptApplication = gate.write { withContext(Dispatchers.IO) {
        if (draft.messages.isEmpty() || draft.messages.size > 256) throw StError.Validation("MCP prompt requires 1..256 messages")
        if (draft.messages.sumOf { it.contentJson.toByteArray(Charsets.UTF_8).size.toLong() } > 8_388_608L)
            throw StError.Validation("MCP prompt exceeds content budget")
        val entries = draft.messages.map { message ->
            if (message.role !in setOf(ChatRole.user, ChatRole.assistant)) throw StError.Validation("MCP prompt role must be user or assistant")
            val block = Jsonx.decode<JsonObject>(message.contentJson)
            val resource = block["resource"] as? JsonObject
            val type = block["type"]?.jsonPrimitive?.content
            val index = ((resource ?: block)["attachmentIndex"] as? JsonPrimitive)?.intOrNull
            val text = when (type) {
                "text" -> (block["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw StError.Validation("MCP prompt text missing")
                "image", "audio" -> {
                    if (index == null) throw StError.Validation("MCP prompt media missing")
                    ""
                }
                "resource" -> (resource?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: if (index != null) "" else throw StError.Validation("MCP prompt resource has no content")
                else -> throw StError.Unsupported("unsupported MCP prompt content type; inspect the draft before applying")
            }
            if (index != null && index !in draft.media.indices) throw StError.Validation("MCP prompt media index missing")
            Entry(message.role, text, block, index)
        }
        val indices = entries.mapNotNull { it.mediaIndex }.distinct()
        if (indices.size > 20 || indices.sumOf { draft.media[it].bytes.size.toLong() } > MessageAttachmentReader.MAX_CONTEXT_BYTES)
            throw StError.Validation("MCP prompt media exceeds attachment context budget")
        if (indices.any { draft.media[it].bytes.isEmpty() || draft.media[it].bytes.size > MessageAttachmentReader.MAX_FILE_BYTES })
            throw StError.Validation("MCP prompt media is empty or exceeds file budget")
        val actor = scope(chatId, draft, expectedUpdatedAt, characterId)
        val owned = mutableListOf<File>()
        val refs = linkedMapOf<Int, AttachmentRef>()
        val at = Instant.now().toString()
        var committed = false
        try {
            for (index in indices) {
                currentCoroutineContext().ensureActive()
                val media = draft.media[index]
                val id = UUID.randomUUID().toString()
                val relative = "mcp-prompt-$id"
                val file = paths.attachmentFile(relative)
                if (!file.createNewFile()) throw StError.Conflict("prompt attachment file already exists")
                owned += file
                FileOutputStream(file).use { output ->
                    var offset = 0
                    while (offset < media.bytes.size) {
                        currentCoroutineContext().ensureActive()
                        val count = minOf(65_536, media.bytes.size - offset)
                        output.write(media.bytes, offset, count); offset += count
                    }
                    output.fd.sync()
                }
                refs[index] = AttachmentRef(id, media.filename, media.mimeType, relative,
                    media.bytes.size.toLong(), modelVisible = media.modelVisible)
            }
            val candidates = entries.mapIndexed { index, entry ->
                val ref = entry.mediaIndex?.let(refs::getValue)
                MessageCandidate(UUID.randomUUID().toString().replace("-", ""), entry.role, entry.text,
                    images = listOfNotNull(ref?.takeIf { it.mimeType.startsWith("image/") }),
                    attachments = listOfNotNull(ref?.takeUnless { it.mimeType.startsWith("image/") }),
                    characterId = actor.takeIf { entry.role == ChatRole.assistant }, createdAt = at,
                    extraJson = buildJsonObject { put("mcpPrompt", buildJsonObject {
                        put("assistantId", draft.assistantId); put("serverId", draft.serverId); put("name", draft.name)
                        put("messageIndex", index); put("content", entry.block)
                        ref?.let { put("attachmentId", it.id) }
                    }) }.toString())
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val receipt = chats.transaction {
                    // A draft cannot race an active generation or append after a local history/configuration change.
                    if (scope(chatId, draft, expectedUpdatedAt, characterId) != actor) throw StError.Conflict("MCP prompt chat actor changed")
                    refs.values.forEach { ref -> attachments.upsert(AttachmentEntity(ref.id, ref.relativePath,
                        ref.filename, ref.mimeType, ref.size, chatId, null, null, at)) }
                    val nodes = candidates.map { chats.appendNode(chatId, it) }
                    val updated = chats.getSession(chatId) ?: throw StError.NotFound("chat")
                    McpPromptApplication(chatId, draft.assistantId, draft.serverId, draft.name,
                        nodes.map { it.id }, candidates.map { it.id }, refs.values.map { it.id }, updated.updatedAt)
                }
                committed = true
                receipt
            }
        } finally { if (!committed) withContext(NonCancellable) { owned.forEach { it.delete() } } }
    } }

    /** Bind to the actual assistant/member, not any configuration sharing a display name. */
    private suspend fun scope(chatId: String, draft: McpPromptResult, expectedUpdatedAt: String, characterId: String?): String? {
        val chat = chats.getSession(chatId) ?: throw StError.NotFound("chat")
        if (chat.updatedAt != expectedUpdatedAt) throw StError.Conflict("chat changed after prompt selection")
        if (tasks.activeGeneration(chatId).isNotEmpty()) throw StError.Conflict("stop the active generation before applying a prompt")
        val config = content.getAssistant(draft.assistantId) ?: throw StError.NotFound("prompt assistant")
        if (config.sourceNamespace != draft.sourceNamespace || chat.sourceNamespace != draft.sourceNamespace)
            throw StError.Conflict("MCP prompt and chat source differ")
        val binding = chat.overrides.assistant
        if (binding != null) {
            if (binding.assistantId != draft.assistantId || characterId != null) throw StError.Validation("MCP prompt assistant binding differs")
            return null
        }
        val actorId = characterId ?: if (chat.isGroup) content.sourceAssistantCharacterId(config) else chat.characterId
        if (actorId == null || if (chat.isGroup) actorId !in chat.memberIds else actorId != chat.characterId)
            throw StError.Validation("select the prompt's actual chat character or group member")
        val actor = content.getCharacter(actorId) ?: throw StError.NotFound("prompt chat character")
        if (actor.sourceNamespace != chat.sourceNamespace || content.sourceAssistant(actor)?.id != draft.assistantId)
            throw StError.Conflict("MCP prompt does not belong to this chat character")
        return actorId
    }
}
