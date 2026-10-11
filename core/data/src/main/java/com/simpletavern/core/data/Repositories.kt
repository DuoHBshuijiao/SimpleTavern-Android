package com.simpletavern.core.data

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.simpletavern.core.data.db.*
import com.simpletavern.core.model.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ChatRepository(private val db: AppDatabase) {
    suspend fun selectedWindow(chatId: String, throughOrder: Long, limit: Int = 50): List<SelectedMessage> =
        db.messages().selectedWindow(chatId, throughOrder, limit).asReversed().map {
            SelectedMessage(it.candidate.nodeId, it.nodeOrder, Mappers.toDomain(it.candidate))
        }
    suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }

    suspend fun selectedMessages(chatId: String, afterOrder: Long = -1, throughOrder: Long = Long.MAX_VALUE): List<SelectedMessage> =
        db.messages().selectedMessages(chatId, afterOrder, throughOrder).map {
            SelectedMessage(it.candidate.nodeId, it.nodeOrder, Mappers.toDomain(it.candidate))
        }

    data class ContextMessageIndex(val candidateId: String, val orderIndex: Long, val role: ChatRole)

    /** Lightweight order/role snapshot; context cutoff selection does not need body or attachment JSON. */
    suspend fun selectedContextIndex(chatId: String, afterOrder: Long, throughOrder: Long): List<ContextMessageIndex> =
        db.messages().selectedContextIndex(chatId, afterOrder, throughOrder).map {
            ContextMessageIndex(it.candidateId, it.nodeOrder, ChatRole.valueOf(it.role))
        }

    suspend fun selectedToolCallsJson(chatId: String, candidateId: String): String? =
        db.messages().selectedToolCallsJson(chatId, candidateId)

    suspend fun selectedMessagesFromOffset(chatId: String, afterOrder: Long, throughOrder: Long, offset: Int): List<SelectedMessage> =
        db.messages().selectedMessagesFromOffset(chatId, afterOrder, throughOrder, offset).map {
            SelectedMessage(it.candidate.nodeId, it.nodeOrder, Mappers.toDomain(it.candidate))
        }

    suspend fun selectedMessagePage(chatId: String, afterOrder: Long, throughOrder: Long, offset: Int, limit: Int = 200): List<SelectedMessage> {
        require(limit in 1..500 && offset >= 0)
        return db.messages().selectedMessagePage(chatId, afterOrder, throughOrder, limit, offset).map {
            SelectedMessage(it.candidate.nodeId, it.nodeOrder, Mappers.toDomain(it.candidate))
        }
    }

    suspend fun selectedMessageOrder(chatId: String, messageOrNodeId: String?): Long {
        if (messageOrNodeId == null) return -1
        val candidate = db.messages().getCandidate(messageOrNodeId)
        val node = db.messages().getNode(candidate?.nodeId ?: messageOrNodeId)
            ?: throw StError.Conflict("summary anchor was deleted; rebuild memory explicitly")
        if (node.chatId != chatId || candidate != null && (candidate.chatId != chatId || candidate.candidateIndex != node.selectedIndex)) {
            throw StError.Conflict("summary anchor or selection changed; rebuild memory explicitly")
        }
        return node.orderIndex
    }

    suspend fun getCandidate(chatId: String, id: String): MessageCandidate? =
        db.messages().getCandidate(id)?.takeIf { it.chatId == chatId }?.let(Mappers::toDomain)

    /** A context anchor follows its node even when a different candidate is selected. */
    suspend fun contextAnchorOrder(chatId: String, messageOrNodeId: String): Long? = db.withTransaction {
        val nodeId = db.messages().nodeIdForCandidate(messageOrNodeId)
        db.messages().getNode(nodeId ?: messageOrNodeId)?.takeIf { it.chatId == chatId }?.orderIndex
    }

    /** Update only our metadata when a local edit prevented the last streaming checkpoint. */
    suspend fun finishGenerationMetadata(chatId: String, id: String, metadata: GenerationMetadata): MessageCandidate? = db.withTransaction {
        val row = db.messages().getCandidate(id)?.takeIf { it.chatId == chatId } ?: return@withTransaction null
        val current = Mappers.toDomain(row)
        if (Jsonx.decodeOrNull<GenerationMetadata>(current.generationMetadataJson)?.requestId != metadata.requestId) return@withTransaction current
        val next = current.copy(generationMetadataJson = Jsonx.encode(metadata))
        db.messages().upsertCandidates(listOf(Mappers.fromDomain(row.nodeId, chatId, row.candidateIndex, next)))
        next
    }
    suspend fun listSessions(): List<ChatSession> = db.chats().list().map(Mappers::toDomain)

    suspend fun recentSourceConversations(namespace: String, assistantId: String, characterId: String?, limit: Int): List<ConversationReferenceSummary> {
        require(limit in 1..30)
        val zone = java.time.ZoneId.systemDefault()
        fun updatedTime(value: String): java.time.Instant = try {
            java.time.OffsetDateTime.parse(value).toInstant()
        } catch (_: java.time.format.DateTimeParseException) {
            try { java.time.LocalDateTime.parse(value).atZone(zone).toInstant() }
            catch (_: java.time.format.DateTimeParseException) { throw StError.Validation("conversation timestamp is invalid") }
        }
        return db.chats().sourceReferences(namespace).asSequence().filter { row ->
            val binding = Jsonx.decode<ChatOverrides>(row.overridesJson).assistant
            if (binding != null) binding.assistantId == assistantId else row.characterId == characterId
        }.map { row ->
            val extra = Jsonx.decodeOrNull<JsonObject>(row.extraJson)
            val pinned = ((extra?.get("isPinned") ?: extra?.get("is_pinned")) as? JsonPrimitive)?.let {
                it.booleanOrNull ?: (it.contentOrNull == "1")
            } == true
            ConversationReferenceSummary(row.id, row.title, row.updatedAt, pinned) to updatedTime(row.updatedAt)
        }.sortedWith(compareByDescending<Pair<ConversationReferenceSummary, java.time.Instant>> { it.first.pinned }
            .thenByDescending { it.second }.thenBy { it.first.id })
            .take(limit).map { it.first }.toList()
    }

    /** Bound literal keywords, selected candidate only; no SQL/LIKE/regular-expression syntax from the model. */
    suspend fun searchSourceConversations(namespace: String, query: String, limit: Int, offset: Int): ConversationReferenceSearchPage {
        require(limit in 1..50 && offset >= 0)
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()
        if (terms.isEmpty()) throw StError.Validation("conversation search query must not be blank")
        val predicates = terms.joinToString(" AND ") { "instr(lower(c.content), lower(?)) > 0" }
        val sql = "SELECT c.chatId AS chatId, c.id AS messageId, n.orderIndex AS orderIndex, h.title AS title, " +
            "substr(c.content, max(1, instr(lower(c.content), lower(?)) - 80), 320) AS snippet, h.updatedAt AS updatedAt " +
            "FROM chats h JOIN message_nodes n ON n.chatId=h.id JOIN message_candidates c ON c.nodeId=n.id AND c.chatId=h.id AND c.candidateIndex=n.selectedIndex " +
            "WHERE h.sourceNamespace=? AND c.role IN ('user','assistant') AND $predicates " +
            "ORDER BY (length(c.content)-length(replace(lower(c.content),lower(?),''))) DESC, julianday(h.updatedAt) DESC, h.id ASC, n.orderIndex DESC LIMIT ? OFFSET ?"
        val rows = db.messages().searchSourceReferences(SimpleSQLiteQuery(sql,
            (listOf(terms.first(), namespace) + terms + listOf(terms.first(), limit + 1, offset)).toTypedArray()))
        val highlight = Regex(terms.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        val hits = rows.take(limit).map { row ->
            ConversationReferenceHit(row.chatId, row.messageId, row.orderIndex, row.title,
                highlight.replace(row.snippet) { "[${it.value}]" }, row.updatedAt)
        }
        return ConversationReferenceSearchPage(hits, rows.size > limit, (offset + hits.size).takeIf { rows.size > limit })
    }

    suspend fun getSession(id: String): ChatSession? = db.chats().get(id)?.let(Mappers::toDomain)

    suspend fun saveSession(session: ChatSession) {
        db.withTransaction { db.chats().upsert(Mappers.fromDomain(session)) }
    }

    suspend fun deleteSession(id: String) {
        db.withTransaction {
            if (db.tasks().hasNonTerminalForChat(id)) throw StError.Conflict("stop the chat's active tasks before deleting it")
            val at = java.time.Instant.now().toString()
            db.chats().list().map(Mappers::toDomain).filter { it.id != id && it.overrides.assistant?.targetChatId == id }.forEach { assistant ->
                if (db.tasks().hasNonTerminalForChat(assistant.id)) throw StError.Conflict("stop the bound assistant's active tasks before deleting its main chat")
                val binding = assistant.overrides.assistant!!.copy(targetChatId = null)
                db.chats().upsert(Mappers.fromDomain(assistant.copy(overrides = assistant.overrides.copy(assistant = binding), updatedAt = at)))
            }
            db.messages().deleteCandidatesForChat(id)
            db.contentRegex().deleteForChat(id)
            db.messages().deleteNodesForChat(id)
            db.memory().deleteMemory(id)
            db.memory().deleteSummaryJobs(id)
            db.mvu().deleteState(id)
            db.mvu().deleteKg(id)
            db.mvu().deleteLogs(id)
            db.chats().delete(id)
        }
    }

    suspend fun pageMessages(chatId: String, limit: Int, offset: Int): List<MessageNode> = db.withTransaction {
        require(limit > 0 && offset >= 0)
        val nodes = db.messages().pageNodes(chatId, limit, offset)
        // Keep one snapshot, avoid one query per node, and bound temporary rows/SQLite bind parameters.
        nodes.chunked(500).flatMap { batch ->
            val candidates = db.messages().candidatesForNodes(batch.map { it.id }).groupBy { it.nodeId }
            batch.map { node -> Mappers.toDomain(node, candidates[node.id].orEmpty()) }
        }
    }

    suspend fun messageCount(chatId: String): Long = db.messages().countNodes(chatId)

    suspend fun getMessageNode(nodeId: String): MessageNode? = db.withTransaction {
        val node = db.messages().getNode(nodeId) ?: return@withTransaction null
        Mappers.toDomain(node, db.messages().candidatesForNode(nodeId))
    }
    suspend fun nodeIdForCandidate(candidateId: String): String? = db.messages().nodeIdForCandidate(candidateId)
    suspend fun messageNodeIdsAfter(chatId: String, order: Long): List<String> = db.messages().nodeIdsAfter(chatId, order)

    /** Commit only a successful reroll; the old versions and newly generated tool exchanges remain. */
    suspend fun commitRegeneration(expected: MessageNode, tailNodeIds: List<String>, generatedNodeIds: List<String>,
                                  finalCandidateId: String): MessageCandidate = db.withTransaction {
        val target = db.messages().getNode(expected.id) ?: throw StError.NotFound("regeneration target was deleted")
        val oldCandidates = db.messages().candidatesForNode(target.id)
        if (target.chatId != expected.chatId || target.orderIndex != expected.orderIndex ||
            oldCandidates.getOrNull(target.selectedIndex)?.let(Mappers::toDomain) != expected.selected)
            throw StError.Conflict("regeneration target or its selected version changed")
        val final = db.messages().getCandidate(finalCandidateId) ?: throw StError.NotFound("regeneration result was deleted")
        if (final.chatId != target.chatId || final.nodeId !in generatedNodeIds)
            throw StError.Conflict("regeneration result no longer belongs to this invocation")
        if (db.messages().candidateIdsForNodes(listOf(final.nodeId)).size != 1)
            throw StError.Conflict("regeneration result acquired local candidate versions")
        val trace = generatedNodeIds.filter { it != final.nodeId }.map {
            db.messages().getNode(it) ?: throw StError.NotFound("regeneration tool history was deleted")
        }
        if (trace.any { it.chatId != target.chatId }) throw StError.Conflict("regeneration tool history moved to another chat")
        val at = java.time.Instant.now().toString()
        for (ids in tailNodeIds.chunked(100)) {
            val rows = ids.mapNotNull { db.messages().getNode(it) }.sortedBy { it.orderIndex }
            if (rows.any { it.chatId != target.chatId }) throw StError.Conflict("regeneration tail moved to another chat")
            val removedIds = db.messages().candidateIdsForNodes(ids).toSet() + ids
            touchAfterMessageRemoval(target.chatId, removedIds, at)
            for (row in rows) {
                val current = db.messages().getNode(row.id)!!
                db.messages().reparentChildren(target.chatId, row.id, current.parentNodeId)
            }
            db.contentRegex().deleteForNodes(ids)
            db.messages().deleteCandidatesForNodes(ids)
            db.messages().deleteNodes(ids)
        }
        val delta = trace.size.toLong()
        if (delta > 0) {
            db.messages().shiftOrders(target.chatId, target.orderIndex, delta)
            db.contentRegex().shiftOrders(target.chatId, target.orderIndex, delta)
            trace.forEachIndexed { index, node ->
                val order = target.orderIndex + index
                db.messages().upsertNode(db.messages().getNode(node.id)!!.copy(orderIndex = order))
                db.contentRegex().setNodeOrder(node.id, order)
            }
        }
        val order = target.orderIndex + delta
        db.messages().moveCandidate(final.id, target.id, oldCandidates.size)
        db.messages().upsertNode(target.copy(orderIndex = order, selectedIndex = oldCandidates.size))
        db.contentRegex().moveMessage(final.id, target.id, order)
        db.messages().reparentChildren(target.chatId, final.nodeId, target.id)
        db.messages().deleteNode(final.nodeId)
        // A context boundary follows its node; summary coverage cannot follow an older reply version.
        val oldIds = oldCandidates.map { it.id }.toSet()
        val chat = db.chats().get(target.chatId)!!
        val overrides = Jsonx.json.parseToJsonElement(chat.overridesJson).jsonObject
        val start = (overrides["contextStartMessageId"] as? JsonPrimitive)?.contentOrNull
        if (start in oldIds || start == final.nodeId) {
            db.chats().upsert(chat.copy(overridesJson = JsonObject(overrides +
                ("contextStartMessageId" to JsonPrimitive(target.id))).toString(), updatedAt = at))
        }
        touchAfterMessageRemoval(target.chatId, oldIds + final.nodeId, at, target.id)
        Mappers.toDomain(final.copy(nodeId = target.id, candidateIndex = oldCandidates.size))
    }

    /** Streaming cannot undo a local edit or resurrect a deleted message. */
    suspend fun updateCandidate(chatId: String, expected: MessageCandidate, next: MessageCandidate) = db.withTransaction {
        require(expected.id == next.id)
        val row = db.messages().getCandidate(expected.id) ?: throw StError.NotFound("streaming message was deleted")
        if (row.chatId != chatId || Mappers.toDomain(row) != expected) {
            throw StError.Conflict("streaming message was changed locally")
        }
        db.messages().upsertCandidates(listOf(Mappers.fromDomain(row.nodeId, chatId, row.candidateIndex, next)))
        db.chats().touch(chatId, java.time.Instant.now().toString())
    }

    suspend fun latestMessages(chatId: String, limit: Int): List<MessageNode> = db.withTransaction {
        require(limit > 0)
        db.messages().latestNodes(chatId, limit).asReversed().map { node ->
            Mappers.toDomain(node, db.messages().candidatesForNode(node.id))
        }
    }

    suspend fun appendNode(chatId: String, candidate: MessageCandidate, parentNodeId: String? = null): MessageNode {
        return db.withTransaction {
            if (db.chats().get(chatId) == null) throw StError.NotFound("chat $chatId")
            if (db.messages().getCandidate(candidate.id) != null) throw StError.Conflict("candidate ID already exists")
            if (parentNodeId != null && db.messages().getNode(parentNodeId)?.chatId != chatId) {
                throw StError.Validation("parent node belongs to a different chat or is absent")
            }
            val order = db.messages().maxOrder(chatId) + 1
            val nodeId = UUID.randomUUID().toString().replace("-", "")
            val node = MessageNodeEntity(nodeId, chatId, order, 0, parentNodeId)
            db.messages().upsertNode(node)
            db.messages().upsertCandidates(listOf(Mappers.fromDomain(nodeId, chatId, 0, candidate)))
            db.chats().touch(chatId, java.time.Instant.now().toString())
            Mappers.toDomain(node, db.messages().candidatesForNode(nodeId))
        }
    }

    suspend fun appendOpeningNode(chatId: String, candidates: List<MessageCandidate>, selectedIndex: Int): MessageNode = db.withTransaction {
        val order = db.messages().maxOrder(chatId) + 1
        val nodeId = UUID.randomUUID().toString().replace("-", "")
        val node = MessageNodeEntity(nodeId, chatId, order, selectedIndex, null)
        db.messages().upsertNode(node)
        db.messages().upsertCandidates(
            candidates.mapIndexed { candidateIndex, candidate ->
                Mappers.fromDomain(nodeId, chatId, candidateIndex, candidate)
            },
        )
        db.chats().touch(chatId, java.time.Instant.now().toString())
        Mappers.toDomain(node, db.messages().candidatesForNode(nodeId))
    }

    suspend fun addCandidate(nodeId: String, candidate: MessageCandidate, select: Boolean = true): MessageNode {
        return db.withTransaction {
            val node = db.messages().getNode(nodeId) ?: throw StError.NotFound("node $nodeId")
            if (db.messages().getCandidate(candidate.id) != null) throw StError.Conflict("candidate ID already exists")
            val existing = db.messages().candidatesForNode(nodeId)
            val index = existing.size
            db.messages().upsertCandidates(listOf(Mappers.fromDomain(nodeId, node.chatId, index, candidate)))
            val selected = if (select) index else node.selectedIndex
            val updated = node.copy(selectedIndex = selected)
            db.messages().upsertNode(updated)
            db.chats().touch(node.chatId, java.time.Instant.now().toString())
            Mappers.toDomain(updated, db.messages().candidatesForNode(nodeId))
        }
    }

    suspend fun selectCandidate(nodeId: String, index: Int): MessageNode {
        return db.withTransaction {
            val node = db.messages().getNode(nodeId) ?: throw StError.NotFound("node $nodeId")
            val cands = db.messages().candidatesForNode(nodeId)
            if (index !in cands.indices) throw StError.Validation("candidate index out of range")
            val updated = node.copy(selectedIndex = index)
            db.messages().upsertNode(updated)
            db.chats().touch(node.chatId, java.time.Instant.now().toString())
            Mappers.toDomain(updated, cands)
        }
    }

    suspend fun editSelected(nodeId: String, content: String): MessageNode {
        return db.withTransaction {
            val node = db.messages().getNode(nodeId) ?: throw StError.NotFound("node $nodeId")
            val cands = db.messages().candidatesForNode(nodeId).toMutableList()
            val selected = cands.getOrNull(node.selectedIndex) ?: throw StError.NotFound("selected candidate")
            val edited = selected.copy(content = content)
            val at = java.time.Instant.now().toString()
            if (selected.content != content) db.contentRegex().markStaleForMessage(selected.id, at)
            db.messages().upsertCandidates(listOf(edited))
            db.chats().touch(node.chatId, at)
            Mappers.toDomain(node, db.messages().candidatesForNode(nodeId))
        }
    }

    suspend fun editSelected(nodeId: String, edited: MessageCandidate): MessageNode = db.withTransaction {
        val node = db.messages().getNode(nodeId) ?: throw StError.NotFound("node $nodeId")
        val candidates = db.messages().candidatesForNode(nodeId)
        val selected = candidates.getOrNull(node.selectedIndex) ?: throw StError.NotFound("selected candidate")
        if (edited.id != selected.id) throw StError.Conflict("selected candidate changed")
        if (edited.reasoningDurationSec?.let { !it.isFinite() || it < 0 } == true)
            throw StError.Validation("reasoning duration must be finite and non-negative")

        val current = Mappers.toDomain(selected)
        val next = current.copy(
            role = edited.role,
            content = edited.content,
            reasoningContent = edited.reasoningContent,
            reasoningDurationSec = edited.reasoningDurationSec,
            images = edited.images,
            attachments = edited.attachments,
            characterId = edited.characterId,
            senderPersonaId = edited.senderPersonaId,
            senderName = edited.senderName,
            senderAvatar = edited.senderAvatar,
        )
        val at = java.time.Instant.now().toString()
        if (current.role != next.role || current.content != next.content || current.characterId != next.characterId)
            db.contentRegex().markStaleForMessage(selected.id, at)
        db.messages().upsertCandidates(listOf(Mappers.fromDomain(selected.nodeId, selected.chatId, selected.candidateIndex, next)))
        db.chats().touch(node.chatId, at)
        Mappers.toDomain(node, db.messages().candidatesForNode(nodeId))
    }

    suspend fun truncateMessagesAfter(nodeId: String): Long = db.withTransaction {
        val node = db.messages().getNode(nodeId) ?: throw StError.NotFound("node $nodeId")
        truncateAfter(node.chatId, node.orderIndex)
    }

    suspend fun editSelectedAndTruncate(nodeId: String, content: String): MessageNode = db.withTransaction {
        val edited = editSelected(nodeId, content)
        truncateAfter(edited.chatId, edited.orderIndex)
        edited
    }

    suspend fun editSelectedAndTruncate(nodeId: String, edited: MessageCandidate): MessageNode = db.withTransaction {
        val updated = editSelected(nodeId, edited)
        truncateAfter(updated.chatId, updated.orderIndex)
        updated
    }

    private suspend fun truncateAfter(chatId: String, afterOrder: Long): Long {
        var removed = 0L
        val at = java.time.Instant.now().toString()
        while (true) {
            val batch = db.messages().nodesAfter(chatId, afterOrder, 100)
            if (batch.isEmpty()) return removed
            val nodeIds = batch.map { it.id }
            val removedIds = db.messages().candidateIdsForNodes(nodeIds).toSet() + nodeIds
            touchAfterMessageRemoval(chatId, removedIds, at)
            db.contentRegex().deleteForNodes(nodeIds)
            db.messages().deleteCandidatesForNodes(nodeIds)
            db.messages().deleteNodes(nodeIds)
            removed += batch.size
        }
    }

    suspend fun deleteMessageNode(nodeId: String) = db.withTransaction {
        val node = db.messages().getNode(nodeId) ?: throw StError.NotFound("node $nodeId")
        removeMessageNode(node, db.messages().candidatesForNode(nodeId))
    }

    /** Removing the last candidate removes its node; otherwise keep the selected candidate by ID. */
    suspend fun deleteCandidate(nodeId: String, candidateId: String): MessageNode? = db.withTransaction {
        val node = db.messages().getNode(nodeId) ?: throw StError.NotFound("node $nodeId")
        val candidates = db.messages().candidatesForNode(nodeId)
        val removedIndex = candidates.indexOfFirst { it.id == candidateId }
        if (removedIndex < 0) throw StError.NotFound("candidate $candidateId")
        if (candidates.size == 1) {
            removeMessageNode(node, candidates)
            return@withTransaction null
        }
        val selectedId = candidates.getOrNull(node.selectedIndex)?.id
        val remaining = candidates.filterNot { it.id == candidateId }.mapIndexed { index, row ->
            row.copy(candidateIndex = index)
        }
        val selectedIndex = remaining.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
            ?: removedIndex.coerceAtMost(remaining.lastIndex)
        val updated = node.copy(selectedIndex = selectedIndex)
        touchAfterMessageRemoval(node.chatId, setOf(candidateId), java.time.Instant.now().toString())
        db.contentRegex().deleteForMessage(candidateId)
        db.messages().clearCandidates(nodeId)
        db.messages().upsertCandidates(remaining)
        db.messages().upsertNode(updated)
        Mappers.toDomain(updated, remaining)
    }

    private suspend fun removeMessageNode(node: MessageNodeEntity, candidates: List<MessageCandidateEntity>) {
        val removedIds = candidates.map { it.id }.toSet() + node.id
        touchAfterMessageRemoval(node.chatId, removedIds, java.time.Instant.now().toString())
        db.contentRegex().deleteForNode(node.id)
        db.messages().reparentChildren(node.chatId, node.id, node.parentNodeId)
        db.messages().clearCandidates(node.id)
        db.messages().deleteNode(node.id)
    }

    private suspend fun touchAfterMessageRemoval(chatId: String, removedIds: Set<String>, at: String,
                                               invalidatedCoverageNodeId: String? = null) {
        val chat = db.chats().get(chatId) ?: throw StError.NotFound("chat $chatId")
        val overrides = Jsonx.json.parseToJsonElement(chat.overridesJson).jsonObject
        val next = overrides.toMutableMap()
        for (field in listOf("contextStartMessageId", "lastAutoMemorySummaryAfterMessageId", "lastStateMaintenanceAfterMessageId")) {
            val id = (overrides[field] as? JsonPrimitive)?.contentOrNull
            if (id in removedIds || invalidatedCoverageNodeId != null && field != "contextStartMessageId" && id == invalidatedCoverageNodeId) {
                next[field] = JsonNull
                if (field == "contextStartMessageId") next["contextStartKeepBeforeMessages"] = JsonNull
            }
        }
        if (next != overrides) db.chats().upsert(chat.copy(overridesJson = JsonObject(next).toString(), updatedAt = at))
        else db.chats().touch(chatId, at)
        val memory = db.memory().getMemory(chatId)
        if (memory != null && (memory.anchorMessageId in removedIds ||
            invalidatedCoverageNodeId != null && memory.anchorMessageId == invalidatedCoverageNodeId)) {
            // Keep the memory text, but do not claim coverage through a message that no longer exists.
            db.memory().upsertMemory(memory.copy(anchorMessageId = null, version = memory.version + 1, updatedAt = at))
        }
    }

    suspend fun replaceAllMessages(chatId: String, nodes: List<MessageNode>) {
        db.withTransaction {
            require(nodes.all { it.chatId == chatId && it.candidates.isNotEmpty() && it.selectedIndex in it.candidates.indices }) {
                "invalid replacement nodes"
            }
            val nodeIds = nodes.mapTo(mutableSetOf()) { it.id }
            require(nodeIds.size == nodes.size) { "duplicate node IDs" }
            val candidateIds = mutableSetOf<String>()
            for (node in nodes) {
                for (candidate in node.candidates) {
                    require(candidateIds.add(candidate.id)) { "duplicate candidate IDs" }
                }
            }

            val messages = db.messages()
            for (ids in nodeIds.asSequence().chunked(500)) {
                if (messages.foreignNodeId(ids, chatId) != null) {
                    throw StError.Conflict("node ID belongs to another chat")
                }
            }
            for (ids in candidateIds.asSequence().chunked(500)) {
                if (messages.foreignCandidateId(ids, chatId) != null) {
                    throw StError.Conflict("candidate ID belongs to another chat")
                }
            }

            messages.deleteCandidatesForChat(chatId)
            messages.deleteNodesForChat(chatId)
            for (batch in nodes.asSequence().chunked(500)) {
                messages.upsertNodes(batch.map { n ->
                    MessageNodeEntity(n.id, chatId, n.orderIndex, n.selectedIndex, n.parentNodeId)
                })
            }

            val candidateBatch = ArrayList<MessageCandidateEntity>(100)
            for (n in nodes) {
                n.candidates.forEachIndexed { i, candidate ->
                    candidateBatch += Mappers.fromDomain(n.id, chatId, i, candidate)
                    if (candidateBatch.size == 100) {
                        messages.upsertCandidates(candidateBatch)
                        candidateBatch.clear()
                    }
                }
            }
            if (candidateBatch.isNotEmpty()) messages.upsertCandidates(candidateBatch)
        }
    }

    suspend fun search(chatId: String, query: String, limit: Int = 50): List<MessageCandidate> =
        search(chatId, query, limit, 0)

    suspend fun search(chatId: String, query: String, limit: Int, offset: Int): List<MessageCandidate> {
        if (limit <= 0 || offset < 0) throw StError.Validation("search limit must be positive and offset nonnegative")
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return emptyList()
        val escapedQuery = trimmedQuery.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return db.messages().search(chatId, escapedQuery, limit, offset).map(Mappers::toDomain)
    }

    suspend fun listForks(chatId: String): List<ChatSession> =
        db.chats().listForks(chatId).map(Mappers::toDomain)
}

class ContentRepository(private val db: AppDatabase) {
    val skills = SkillStore(db)
    suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }
    suspend fun listCharacters() = db.characters().list().map(Mappers::toDomain)
    suspend fun getCharacter(id: String) = db.characters().get(id)?.let(Mappers::toDomain)
    suspend fun saveCharacter(c: CharacterCard) = db.characters().upsert(Mappers.fromDomain(c))

    fun mergeGroupContentRegexRules(characters: List<CharacterCard>): List<ContentRegexRule> {
        val seenTriples = mutableSetOf<Triple<String, String, String>>()
        return characters.flatMap { character ->
            character.contentRegexRulesJson?.let { Jsonx.decode<List<ContentRegexRule>>(it) }.orEmpty()
        }.filter { rule ->
            val pattern = rule.pattern.trim()
            pattern.isNotEmpty() && seenTriples.add(Triple(pattern, rule.action.trim(), rule.replacement.orEmpty().trim()))
        }.mapIndexed { index, rule -> rule.copy(order = index) }
            .distinctBy { it.id }
    }

    fun hasMvuProfileData(character: CharacterCard): Boolean {
        if (character.initialStateTablesJson?.let { Jsonx.decode<List<StatusTableDef>>(it).isNotEmpty() } == true) return true
        if (!character.mvuDirective.isNullOrBlank()) return true
        return character.contentRegexRulesJson?.let { rulesJson ->
            Jsonx.decode<List<ContentRegexRule>>(rulesJson).any { rule ->
                rule.action.trim() in setOf("extract", "extract_and_replace")
            }
        } == true
    }

    /** Index zero is always the primary greeting, including an intentionally empty one. */
    fun characterOpeningMessages(character: CharacterCard): List<String> {
        val extra = Jsonx.decodeOrNull<JsonObject>(character.extraJson)?.get("extraFirstMessageEntries") as? JsonArray
        val alternates = extra.orEmpty().mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            if ((item["chip"] as? JsonPrimitive)?.booleanOrNull == false) return@mapNotNull null
            (item["text"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }
        return listOf(character.firstMessage) + alternates
    }

    /** Preserve source baselines so a later merge cannot resurrect an explicitly deleted card. */
    suspend fun deleteCharacter(id: String, expectedUpdatedAt: String, deleteRelatedChats: Boolean = true): List<String> = db.withTransaction {
        val character = getCharacter(id) ?: throw StError.NotFound("character")
        if (character.updatedAt != expectedUpdatedAt) throw StError.Conflict("character changed since it was read")
        val owned = db.chats().list().filter { it.characterId == id }
        if (!deleteRelatedChats && owned.isNotEmpty()) throw StError.Conflict("character owns chats; explicitly allow related chat deletion")
        val chats = ChatRepository(db)
        owned.forEach { chats.deleteSession(it.id) }
        val at = java.time.Instant.now().toString()
        db.chats().list().map(Mappers::toDomain).filter { id in it.memberIds || id in it.memberSettings ||
            it.overrides.groupMvuAnchorCharacterId == id || it.overrides.groupMvuTemplateCharacterId == id }
            .forEach { chat ->
                if (db.tasks().hasNonTerminalForChat(chat.id)) throw StError.Conflict("stop the affected group's active tasks before removing its character")
                val overrides = chat.overrides.copy(
                    groupMvuAnchorCharacterId = chat.overrides.groupMvuAnchorCharacterId?.takeUnless { it == id },
                    groupMvuTemplateCharacterId = chat.overrides.groupMvuTemplateCharacterId?.takeUnless { it == id },
                )
                db.chats().upsert(Mappers.fromDomain(chat.copy(memberIds = chat.memberIds - id,
                    memberSettings = chat.memberSettings - id, overrides = overrides, updatedAt = at)))
            }
        db.characters().delete(id)
        owned.map { it.id }
    }

    suspend fun sourceAssistant(character: CharacterCard): AssistantConfig? {
        val namespace = character.sourceNamespace ?: return null
        if (namespace.substringBefore(':') != "rikka_hub") return null
        val sourceId = character.sourceId ?: throw StError.Validation("source character identity missing")
        val mapped = db.imports().getIdMap("${namespace.length}:$namespace:assistant", "0::$sourceId")?.targetId
            ?: throw StError.NotFound("source assistant mapping; explicitly reimport its configuration")
        return getAssistant(mapped)?.takeIf { it.sourceNamespace == namespace }
            ?: throw StError.NotFound("source assistant configuration")
    }

    suspend fun sourceAssistantCharacterId(config: AssistantConfig): String? {
        val namespace = config.sourceNamespace ?: return null
        val sourceId = (Jsonx.decode<JsonObject>(config.settingsJson)["id"] as? JsonPrimitive)?.contentOrNull ?: return null
        return db.imports().getIdMap("${namespace.length}:$namespace:character", "0::$sourceId")?.targetId
    }

    /** RikkaHub switches between its own assistant rows and __global__; never combine both pools. */
    suspend fun sourceAssistantMemory(config: AssistantConfig): String? {
        val pool = assistantMemoryPool(config)?.takeIf { it.enabled } ?: return null
        val library = sourceMemoryLibrary(pool.sourceNamespace) ?: return null
        return Jsonx.decode<ImportedMemoryLibrary>(library.settingsJson).entries.values
            .filter { it.assistantSourceId == pool.ownerId && it.content.isNotBlank() }
            .joinToString("\n\n") { it.content }.takeIf { it.isNotBlank() }
    }

    fun assistantMemoryPool(config: AssistantConfig): AssistantMemoryPool? {
        val namespace = config.sourceNamespace ?: return null
        if (namespace.substringBefore(':') != "rikka_hub") return null
        val settings = Jsonx.decode<JsonObject>(config.settingsJson)
        val owner = if ((settings["useGlobalMemory"] as? JsonPrimitive)?.booleanOrNull == true) "__global__"
            else (settings["id"] as? JsonPrimitive)?.contentOrNull ?: throw StError.Validation("source assistant ID missing")
        return AssistantMemoryPool(config.id, namespace, owner, (settings["enableMemory"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    suspend fun getAssistantMemoryPool(assistantId: String): AssistantMemoryPool? =
        assistantMemoryPool(getAssistant(assistantId) ?: throw StError.NotFound("assistant configuration"))

    private suspend fun sourceMemoryLibrary(namespace: String): AssistantConfig? {
        val mapped = db.imports().getIdMap("${namespace.length}:$namespace:source_memory", "0::memories")?.targetId ?: return null
        return getAssistant(mapped)?.takeIf { it.sourceNamespace == namespace }
            ?: throw StError.NotFound("source memory library")
    }

    private suspend fun requireMemoryPool(expected: AssistantMemoryPool): AssistantMemoryPool {
        val current = getAssistantMemoryPool(expected.assistantId) ?: throw StError.Unsupported("assistant has no source memory pool")
        if (current.sourceNamespace != expected.sourceNamespace || current.ownerId != expected.ownerId)
            throw StError.Conflict("assistant memory pool changed; read it again")
        return current
    }

    suspend fun listAssistantMemories(assistantId: String, afterId: String = "", limit: Int = 100): AssistantMemoryPage = db.withTransaction {
        val pool = getAssistantMemoryPool(assistantId) ?: throw StError.Unsupported("assistant has no source memory pool")
        listAssistantMemories(pool, afterId, limit)
    }

    suspend fun listAssistantMemories(expectedPool: AssistantMemoryPool, afterId: String = "", limit: Int = 100): AssistantMemoryPage = db.withTransaction {
        require(limit in 1..200)
        val pool = requireMemoryPool(expectedPool)
        val library = sourceMemoryLibrary(pool.sourceNamespace)?.let { Jsonx.decode<ImportedMemoryLibrary>(it.settingsJson) }
        val rows = library?.entries.orEmpty().entries.asSequence()
            .filter { it.key > afterId && it.value.assistantSourceId == pool.ownerId }.sortedBy { it.key }
            .take(limit + 1).map { AssistantMemoryRow(it.key, it.value.content) }.toList()
        AssistantMemoryPage(pool, rows.take(limit), rows.size > limit, rows.take(limit).lastOrNull()?.id)
    }

    suspend fun getAssistantMemory(assistantId: String, memoryId: String): AssistantMemoryRow = db.withTransaction {
        val pool = getAssistantMemoryPool(assistantId) ?: throw StError.Unsupported("assistant has no source memory pool")
        val library = sourceMemoryLibrary(pool.sourceNamespace) ?: throw StError.NotFound("source memory library")
        val row = Jsonx.decode<ImportedMemoryLibrary>(library.settingsJson).entries[memoryId]
            ?.takeIf { it.assistantSourceId == pool.ownerId } ?: throw StError.NotFound("assistant memory row")
        AssistantMemoryRow(memoryId, row.content)
    }

    suspend fun createAssistantMemory(pool: AssistantMemoryPool, text: String): AssistantMemoryRow = db.withTransaction {
        requireMemoryPool(pool)
        if (text.isBlank()) throw StError.Validation("memory content must not be blank")
        var config = sourceMemoryLibrary(pool.sourceNamespace)
        if (config == null) {
            config = AssistantConfig(UUID.randomUUID().toString(), Jsonx.encode(ImportedMemoryLibrary()), java.time.Instant.now().toString(), pool.sourceNamespace)
            db.imports().upsertIdMap(IdMapEntity("${pool.sourceNamespace.length}:${pool.sourceNamespace}:source_memory", "0::memories", config.id, "source_memory"))
        }
        val library = Jsonx.decode<ImportedMemoryLibrary>(config.settingsJson)
        val row = AssistantMemoryRow("local:${UUID.randomUUID()}", text)
        saveAssistant(config.copy(settingsJson = Jsonx.encode(library.copy(entries = library.entries +
            (row.id to ImportedAssistantMemory(pool.ownerId, text)))), updatedAt = java.time.Instant.now().toString()))
        row
    }

    suspend fun updateAssistantMemory(pool: AssistantMemoryPool, memoryId: String, text: String, expectedContent: String): AssistantMemoryRow = db.withTransaction {
        requireMemoryPool(pool)
        if (text.isBlank()) throw StError.Validation("memory content must not be blank")
        val config = sourceMemoryLibrary(pool.sourceNamespace) ?: throw StError.NotFound("source memory library")
        val library = Jsonx.decode<ImportedMemoryLibrary>(config.settingsJson)
        val row = library.entries[memoryId]?.takeIf { it.assistantSourceId == pool.ownerId } ?: throw StError.NotFound("assistant memory row")
        if (row.content != expectedContent) throw StError.Conflict("memory changed since it was read")
        saveAssistant(config.copy(settingsJson = Jsonx.encode(library.copy(entries = library.entries + (memoryId to row.copy(content = text)))),
            updatedAt = java.time.Instant.now().toString()))
        AssistantMemoryRow(memoryId, text)
    }

    suspend fun deleteAssistantMemory(pool: AssistantMemoryPool, memoryId: String, expectedContent: String): Boolean = db.withTransaction {
        requireMemoryPool(pool)
        val config = sourceMemoryLibrary(pool.sourceNamespace) ?: throw StError.NotFound("source memory library")
        val library = Jsonx.decode<ImportedMemoryLibrary>(config.settingsJson)
        val row = library.entries[memoryId]?.takeIf { it.assistantSourceId == pool.ownerId } ?: throw StError.NotFound("assistant memory row")
        if (row.content != expectedContent) throw StError.Conflict("memory changed since it was read")
        saveAssistant(config.copy(settingsJson = Jsonx.encode(library.copy(entries = library.entries - memoryId)), updatedAt = java.time.Instant.now().toString()))
        true
    }

    /** New local chats keep their role's source settings without gaining a source chat identity. */
    suspend fun chatCreationDefaults(character: CharacterCard): ChatCreationDefaults {
        fun selectedPersonaSourceId(settingsJson: String?): String? = settingsJson
            ?.let { Jsonx.decode<JsonObject>(it)["selectedPersonaId"] as? JsonPrimitive }
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }

        val namespace = character.sourceNamespace
        if (namespace == null) {
            return ChatCreationDefaults(userPersonaId = selectedPersonaSourceId(getSettingsJson()))
        }
        fun sourceMapping(type: String, sourceId: String) = "${namespace.length}:$namespace:$type" to "0::$sourceId"
        if (namespace.substringBefore(':') == "rikka_hub") {
            val config = sourceAssistant(character)!!
            val defaults = Jsonx.decode<JsonObject>(config.settingsJson)["chatDefaults"]
                ?: throw StError.Validation("source chat defaults absent; explicitly reimport its configuration")
            return Jsonx.decode<ChatCreationDefaults>(defaults.toString()).let {
                it.copy(overrides = it.overrides.copy(assistant = null))
            }
        }
        val personaSourceId = selectedPersonaSourceId(getSourceSettingsJson(namespace))
        val personaId = personaSourceId?.let { sourceId ->
            val key = sourceMapping("persona", sourceId)
            db.imports().getIdMap(key.first, key.second)?.targetId
        }
        val key = sourceMapping("preset", "default-connection")
        val presetId = db.imports().getIdMap(key.first, key.second)?.targetId
        return ChatCreationDefaults(ChatOverrides(presetId = presetId), userPersonaId = personaId)
    }

    suspend fun listPersonas() = db.personas().list().map(Mappers::toDomain)
    suspend fun getPersona(id: String) = db.personas().get(id)?.let(Mappers::toDomain)
    suspend fun savePersona(p: UserPersona) = db.personas().upsert(Mappers.fromDomain(p))

    /** Unbind current sessions, not the historical sender snapshots or shared avatar files. */
    suspend fun deletePersona(id: String, expectedUpdatedAt: String) = db.withTransaction {
        val persona = getPersona(id) ?: throw StError.NotFound("persona")
        if (persona.updatedAt != expectedUpdatedAt) throw StError.Conflict("persona changed since it was read")
        val at = java.time.Instant.now().toString()
        db.chats().list().filter { it.userPersonaId == id }.forEach { chat ->
            if (db.tasks().hasNonTerminalForChat(chat.id)) throw StError.Conflict("stop the affected chat's active tasks before removing its persona")
            db.chats().upsert(chat.copy(userPersonaId = null, updatedAt = at))
        }
        db.personas().delete(id)
        Unit
    }

    suspend fun listWorldBooks() = db.worldBooks().list().map(Mappers::toDomain)
    suspend fun getWorldBook(id: String) = db.worldBooks().get(id)?.let(Mappers::toDomain)
    suspend fun saveWorldBook(b: WorldBook) = db.worldBooks().upsert(Mappers.fromDomain(b))

    /** Keep import baselines/id maps: an explicitly deleted imported book must stay deleted on merge. */
    suspend fun deleteWorldBook(id: String, expectedUpdatedAt: String) = db.withTransaction {
        val book = getWorldBook(id) ?: throw StError.NotFound("worldbook")
        if (book.updatedAt != expectedUpdatedAt) throw StError.Conflict("worldbook changed since it was read")
        val at = java.time.Instant.now().toString()
        listCharacters().filter { id in it.attachedWorldBookIds }.forEach {
            saveCharacter(it.copy(attachedWorldBookIds = it.attachedWorldBookIds - id, updatedAt = at))
        }
        db.chats().list().map(Mappers::toDomain).forEach { chat ->
            val overrides = chat.overrides
            if (overrides.worldBookAttachments.any { it.worldBookId == id } || id in overrides.worldBookGlobalExclusions) {
                db.chats().upsert(Mappers.fromDomain(chat.copy(overrides = overrides.copy(
                    worldBookAttachments = overrides.worldBookAttachments.filterNot { it.worldBookId == id },
                    worldBookGlobalExclusions = overrides.worldBookGlobalExclusions - id), updatedAt = at)))
            }
        }
        db.worldBooks().delete(id)
        Unit
    }

    /** Explicit attachments first, then active library books; global exclusions only suppress global activation. */
    suspend fun activeWorldBooks(session: ChatSession, character: CharacterCard?): List<WorldBook> = db.withTransaction {
        val metadata = db.worldBooks().activationMetadata()
        val metadataById = metadata.associateBy { it.id }
        val sessionIds = session.overrides.worldBookAttachments.map { it.worldBookId }
        val characterIds = character?.attachedWorldBookIds.orEmpty()
        val attached = when (session.overrides.worldBookSelectionMode) {
            "merge" -> sessionIds + characterIds
            "character" -> characterIds
            "session" -> sessionIds
            else -> throw StError.Validation("worldbook selection mode must be merge/character/session")
        }.distinct().filter(metadataById::containsKey)
        val active = metadata.filter { book ->
            if (book.globalActive) book.id !in session.overrides.worldBookGlobalExclusions &&
                (book.sourceNamespace == null || book.sourceNamespace == session.sourceNamespace)
            else session.id in Jsonx.decode<List<String>>(book.sessionChatIdsJson)
        }.map { it.id }
        val selectedIds = (attached + active).distinct()
        buildList {
            selectedIds.asSequence().chunked(500).forEach { ids ->
                val booksById = db.worldBooks().getByIds(ids).associateBy { it.id }
                ids.mapNotNullTo(this) { id -> booksById[id]?.let(Mappers::toDomain) }
            }
        }
    }

    suspend fun getSettingsJson(): String? = db.config().getSettings()?.settingsJson
    /** Exact import settings mapping, never another assistant chosen by iteration order. */
    suspend fun getSourceSettingsJson(namespace: String): String? = db.withTransaction {
        SpeechText.validate(namespace, 1024)
        val database = db.openHelper.readableDatabase
        val mapping = database.query(androidx.sqlite.db.SimpleSQLiteQuery(
            "SELECT rowid,length(CAST(targetId AS BLOB)),entityType='source_settings' FROM id_maps WHERE namespace=? AND sourceId=? LIMIT 1",
            arrayOf("${namespace.length}:$namespace:source_settings", "0::settings"))).use {
            if (!it.moveToFirst()) null else {
                if (it.getLong(1) > 1024) throw StError.Validation("source settings mapping exceeds identity budget")
                if (it.getInt(2) != 1) throw StError.Conflict("source settings mapping type mismatch")
                it.getLong(0)
            }
        }
        val mapped = mapping?.let { rowId -> database.query(androidx.sqlite.db.SimpleSQLiteQuery(
            "SELECT targetId FROM id_maps WHERE rowid=?", arrayOf(rowId))).use { it.moveToFirst(); it.getString(0) } }
        val id = mapped ?: db.openHelper.readableDatabase.query(androidx.sqlite.db.SimpleSQLiteQuery(
            "SELECT rowid,length(CAST(id AS BLOB)) FROM assistants WHERE sourceNamespace=? LIMIT 2", arrayOf(namespace))).use { cursor ->
            if (!cursor.moveToFirst()) return@withTransaction null
            val rowId = cursor.getLong(0)
            if (cursor.getLong(1) > 1024) throw StError.Validation("source settings identity exceeds budget")
            if (cursor.moveToNext()) throw StError.Validation("source settings are ambiguous; select an explicit configuration")
            db.openHelper.readableDatabase.query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT id FROM assistants WHERE rowid=?", arrayOf(rowId))).use {
                it.moveToFirst(); it.getString(0)
            }
        }
        SpeechText.validate(id, 1024)
        val bytes = db.config().assistantSettingsBytes(id) ?: throw StError.NotFound("source settings mapping target missing")
        if (bytes > 262_144) throw StError.Validation("source settings exceed generation read budget")
        database.query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT settingsJson FROM assistants WHERE id=? AND sourceNamespace=?",
            arrayOf(id, namespace))).use {
            if (!it.moveToFirst()) throw StError.Conflict("source settings mapping scope mismatch")
            it.getString(0)
        }
    }
    suspend fun saveSettingsJson(json: String, updatedAt: String) =
        db.config().upsertSettings(AppSettingsEntity(settingsJson = json, updatedAt = updatedAt))

    suspend fun getAssistant(id: String = "default") = db.config().getAssistant(id)?.let {
        AssistantConfig(it.id, it.settingsJson, it.updatedAt, it.sourceNamespace)
    }

    suspend fun saveAssistant(cfg: AssistantConfig) =
        db.config().upsertAssistant(AssistantEntity(cfg.id, cfg.settingsJson, cfg.updatedAt, cfg.sourceNamespace))

    suspend fun listAssistants() = db.config().listAssistants().map {
        AssistantConfig(it.id, it.settingsJson, it.updatedAt, it.sourceNamespace)
    }

    /** Source settings and memory share storage with assistants, but are not selectable conversations. */
    suspend fun isSourceContainer(config: AssistantConfig): Boolean {
        val namespace = config.sourceNamespace ?: return false
        return listOf("source_settings" to "settings", "source_memory" to "memories").any { (type, sourceId) ->
            db.imports().getIdMap("${namespace.length}:$namespace:$type", "0::$sourceId")?.targetId == config.id
        }
    }

    suspend fun listAssistantConfigurations(): List<AssistantConfig> = db.withTransaction {
        listAssistants().filterNot { isSourceContainer(it) }
    }

    /** Delete a configuration and its independent conversations, not a shared source container. */
    suspend fun deleteAssistant(id: String, expectedUpdatedAt: String, deleteRelatedChats: Boolean = true): List<String> = db.withTransaction {
        val config = getAssistant(id) ?: throw StError.NotFound("assistant configuration")
        if (config.updatedAt != expectedUpdatedAt) throw StError.Conflict("assistant configuration changed since it was read")
        if (isSourceContainer(config))
            throw StError.Conflict("shared source settings and memory libraries are not individual assistant configurations")
        config.sourceNamespace?.let { namespace ->
            if (namespace.substringBefore(':') == "rikka_hub") {
                for (character in db.characters().list().filter { it.sourceNamespace == namespace }) {
                    val sourceId = character.sourceId ?: continue
                    if (db.imports().getIdMap("${namespace.length}:$namespace:assistant", "0::$sourceId")?.targetId == id)
                        throw StError.Conflict("delete the associated RikkaHub character before deleting its configuration",
                            mapOf("characterId" to character.id))
                }
            }
        }
        val owned = db.chats().list().map(Mappers::toDomain).filter { it.overrides.assistant?.assistantId == id }
        if (!deleteRelatedChats && owned.isNotEmpty())
            throw StError.Conflict("assistant owns conversations; explicitly allow related chat deletion")
        for (task in db.tasks().runningOrPending()) {
            val assistantId = when (task.kind) {
                TaskKind.SPEECH_SYNTHESIS.name -> Jsonx.decodeOrNull<SpeechTaskProgress>(task.payloadJson)?.selection?.assistantId
                TaskKind.SPEECH_PLAYBACK.name -> Jsonx.decodeOrNull<SpeechPlaybackProgress>(task.payloadJson)?.assistantId
                TaskKind.SPEECH_VOICE_DESIGN.name, TaskKind.SPEECH_VOICE_CLONE.name ->
                    Jsonx.decodeOrNull<SpeechVoiceCreationProgress>(task.payloadJson)?.selection?.assistantId
                else -> null
            }
            if (assistantId == id) throw StError.Conflict("stop the assistant's active speech tasks before deleting its configuration",
                mapOf("taskId" to task.id))
        }
        val chats = ChatRepository(db)
        owned.forEach { chats.deleteSession(it.id) }
        db.config().clearAssistantMessages(id)
        db.config().deleteAssistant(id)
        owned.map { it.id }
    }

    suspend fun getPreset(id: String) = db.config().getPreset(id)?.let {
        ModelPreset(it.id, it.name, it.configJson, it.createdAt, it.updatedAt, it.sourceNamespace, it.sourceId)
    }

    suspend fun listPresets() = db.config().listPresets().map {
        ModelPreset(it.id, it.name, it.configJson, it.createdAt, it.updatedAt, it.sourceNamespace, it.sourceId)
    }

    suspend fun savePreset(p: ModelPreset) =
        db.config().upsertPreset(
            ModelPresetEntity(p.id, p.name, p.configJson, p.createdAt, p.updatedAt, p.sourceNamespace, p.sourceId),
        )

    /** Current selections must be changed explicitly; historical evidence and import tombstones stay. */
    suspend fun deletePreset(id: String, expectedUpdatedAt: String) = db.withTransaction {
        val preset = getPreset(id) ?: throw StError.NotFound("model preset")
        if (preset.updatedAt != expectedUpdatedAt) throw StError.Conflict("model preset changed since it was read")
        fun selected(fields: JsonObject): Boolean =
            listOf("presetId", "preprocessPresetId").any { (fields[it] as? JsonPrimitive)?.contentOrNull == id }
        fun speech(value: kotlinx.serialization.json.JsonElement?): Boolean {
            val fields = when (value) {
                null, kotlinx.serialization.json.JsonNull -> return false
                is JsonObject -> value
                is JsonPrimitive -> Jsonx.decodeOrNull<JsonObject>(value.content) ?: return false
                else -> return false
            }
            return selected(fields)
        }
        fun configuration(fields: JsonObject, namespace: String?): Boolean {
            if (selected(fields) || speech(fields["ttsJson"])) return true
            val defaults = fields["chatDefaults"] as? JsonObject
            val overrides = defaults?.get("overrides") as? JsonObject
            if (overrides != null && (selected(overrides) || speech(overrides["ttsJson"]))) return true
            if (namespace != preset.sourceNamespace || namespace == null) return false
            if (preset.sourceId == "default-connection" && fields["llm"] is JsonObject) return true
            val model = (fields["chatModelId"] as? JsonPrimitive)?.contentOrNull
            if (model != null && preset.sourceId == "model:$model") return true
            val provider = (fields["selectedTTSProviderId"] as? JsonPrimitive)?.contentOrNull
                ?: if (fields["selectedTTSProviderId"] == kotlinx.serialization.json.JsonNull)
                    ((fields["ttsProviders"] as? JsonArray)?.firstOrNull() as? JsonObject)
                        ?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull } else null
            return provider != null && preset.sourceId == "tts:$provider"
        }
        for (chat in db.chats().list().map(Mappers::toDomain)) {
            if (chat.overrides.presetId == id || chat.memberSettings.values.any { it.presetId == id } ||
                speech(chat.overrides.ttsJson?.let(::JsonPrimitive)))
                throw StError.Conflict("model preset is selected by a chat; change its selection before deleting",
                    mapOf("chatId" to chat.id))
        }
        for (assistant in listAssistants()) {
            val fields = Jsonx.decodeOrNull<JsonObject>(assistant.settingsJson) ?: continue
            if (configuration(fields, assistant.sourceNamespace))
                throw StError.Conflict("model preset is selected by assistant/source settings; change the selection before deleting",
                    mapOf("assistantId" to assistant.id))
        }
        if (getSettingsJson()?.let { Jsonx.decodeOrNull<JsonObject>(it)?.let { fields -> configuration(fields, null) } } == true)
            throw StError.Conflict("model preset is selected by global settings; change the selection before deleting")
        db.config().deletePreset(id)
        Unit
    }
}

class MemoryStore(private val db: AppDatabase) {
    suspend fun getRegexRecord(messageId: String) = db.contentRegex().get(messageId)?.let(::regexRecord)
    suspend fun pendingRegex(chatId: String, throughOrder: Long = Long.MAX_VALUE, limit: Int = 100) =
        db.contentRegex().pending(chatId, throughOrder, limit).map(::regexRecord)
    suspend fun listRegex(chatId: String, limit: Int = 100): List<ContentRegexRecord> {
        require(limit in 1..500)
        return db.contentRegex().list(chatId, limit).map(::regexRecord)
    }
    suspend fun saveRegex(record: ContentRegexRecord) = db.contentRegex().upsert(ContentRegexRecordEntity(
        record.messageId, record.chatId, record.nodeId, record.orderIndex, record.revision, record.sourceText,
        record.rulesJson, Jsonx.encode(record.output), record.queueStatus, record.consumedByTaskId, record.updatedAt))
    private fun regexRecord(row: ContentRegexRecordEntity) = ContentRegexRecord(row.messageId, row.chatId, row.nodeId,
        row.orderIndex, row.revision, row.sourceText, row.rulesJson, Jsonx.decode(row.outputJson), row.queueStatus, row.consumedByTaskId, row.updatedAt)
    suspend fun mvuLogCount(chatId: String) = db.mvu().countLogs(chatId)
    suspend fun listMvuLogs(chatId: String, limit: Int = 100): List<MvuWorkLog> {
        require(limit in 1..500)
        return db.mvu().listLogs(chatId, limit).map { MvuWorkLog(it.id, it.chatId, it.timestamp, it.eventType, it.summary, it.detailJson) }
    }
    suspend fun getSummaryJob(id: String) = db.memory().getSummaryJob(id)?.let(::summaryToDomain)
    suspend fun listSummaryJobs(chatId: String) = db.memory().listSummaryJobs(chatId).map(::summaryToDomain)
    suspend fun incompleteSummaryJobs() = db.memory().incompleteSummaryJobs().map(::summaryToDomain)

    private fun summaryToDomain(row: MemorySummaryJobEntity) = MemorySummaryJob(row.id, row.chatId,
        TaskStatus.valueOf(row.status), row.coveredThroughMessageId, Jsonx.decode(row.sourceMessageIdsJson),
        row.resultText, row.failureReason, row.expectedMemoryVersion, row.createdAt, row.updatedAt)
    suspend fun getMemory(chatId: String) = db.memory().getMemory(chatId)?.let(Mappers::toDomain)

    suspend fun saveMemory(mem: LongTermMemory, expectedVersion: Long? = null): LongTermMemory = db.withTransaction {
        val current = db.memory().getMemory(mem.chatId)
        if (expectedVersion != null && (current?.version ?: 0L) != expectedVersion) {
            throw StError.Conflict("memory version mismatch", mapOf(
                "expected" to expectedVersion.toString(),
                "actual" to (current?.version ?: 0L).toString(),
            ))
        }
        val next = if (current == null) mem.copy(version = 1) else mem.copy(version = current.version + 1)
        db.memory().upsertMemory(
            LongTermMemoryEntity(next.chatId, next.content, next.version, next.anchorMessageId, next.updatedAt, next.source),
        )
        next
    }

    suspend fun upsertSummaryJob(job: MemorySummaryJob) {
        db.memory().upsertSummaryJob(
            MemorySummaryJobEntity(
                job.id, job.chatId, job.status.name, job.coveredThroughMessageId,
                Jsonx.encode(job.sourceMessageIds), job.resultText, job.failureReason,
                job.expectedMemoryVersion, job.createdAt, job.updatedAt,
            ),
        )
    }

    suspend fun getMvu(chatId: String) = db.mvu().getState(chatId)?.let(Mappers::toDomain)

    suspend fun saveMvu(state: MvuState, expectedVersion: Int? = null): MvuState = db.withTransaction {
        val current = db.mvu().getState(state.chatId)
        if (expectedVersion != null && (current?.version ?: 0) != expectedVersion) {
            throw StError.Conflict("mvu version mismatch")
        }
        val next = state.copy(version = (current?.version ?: 0) + 1)
        db.mvu().upsertState(
            MvuStateEntity(next.chatId, next.version, next.updatedAt, next.source, Jsonx.encode(next.tables)),
        )
        next
    }

    suspend fun getKg(chatId: String) = db.mvu().getKg(chatId)?.let(Mappers::toDomain)

    suspend fun saveKg(kg: KnowledgeGraph, expectedVersion: Int? = null): KnowledgeGraph = db.withTransaction {
        val current = db.mvu().getKg(kg.chatId)
        if (expectedVersion != null && (current?.version ?: 0) != expectedVersion) {
            throw StError.Conflict("kg version mismatch")
        }
        val next = kg.copy(version = (current?.version ?: 0) + 1)
        db.mvu().upsertKg(
            KnowledgeGraphEntity(
                next.chatId, Jsonx.encode(next.entities), Jsonx.encode(next.relations),
                next.version, next.updatedAt, next.source,
            ),
        )
        next
    }

    suspend fun appendMvuLog(id: String, chatId: String, ts: String, type: String, summary: String, detailJson: String?) {
        db.mvu().insertLog(MvuWorkLogEntity(id, chatId, ts, type, summary, detailJson))
    }
}

class TaskStore(private val db: AppDatabase, private val host: TaskRegistrationHost? = null,
                private val dataGate: DataOperationGate? = null) {
    /** One consistent read result carries both the original JSON and its checked projection to internal consumers. */
    data class CacheOperationRecord(val task: TaskSnapshot, val payload: String,
        val operation: GeminiCacheTaskProgress, val payloadBytes: Long)
    /** Cache task and independent ledger checkpoint commit together; old untracked history is never backfilled. */
    suspend fun upsertCacheOperation(task: TaskSnapshot, payload: String) = db.withTransaction {
        val value = GeminiCacheAccounting.operation(payload)
        if (task.kind != GeminiCacheTaskMetadata.kind(value.action)) throw StError.Validation("not a cache operation task")
        GeminiCacheTaskMetadata.validateTaskBinding(value, task.id, task.chatId, task.createdAt, task.status)
        if (value.accountingVersion == 1) {
            if (db.tasks().cacheTaskExistingKind(task.id)?.let { it != task.kind.name } == true)
                throw StError.Conflict("cache task identity already belongs to another task kind")
            val eventId = GeminiCacheAccounting.eventId(task.id)
            val priorTask = db.tasks().cacheTaskBudgets(task.id, "", false, 1).singleOrNull()?.let { budget ->
                val byteLimit = if (budget.kind == TaskKind.GEMINI_CACHE_LIST.name) GeminiCacheTaskMetadata.MAX_LIST_BYTES else GeminiCacheTaskMetadata.MAX_BYTES
                if (budget.payloadBytes?.let { it in 1..byteLimit.toLong() } != true || budget.identityBytes > 8192)
                    throw StError.Validation("cache operation exceeds storage budget")
                db.tasks().get(task.id) ?: throw StError.Conflict("cache task disappeared")
            }
            val old = db.usage().cacheEventBudget(eventId)?.let { budget ->
                if (budget.identityBytes > 4096 || budget.usageBytes > GeminiCacheAccounting.MAX_EVENT_BYTES || budget.costBytes > 32_768)
                    throw StError.Validation("cache ledger exceeds storage budget")
                db.usage().cacheEvent(eventId) ?: throw StError.Conflict("cache ledger disappeared")
            }
            if (old == null) {
                if (priorTask != null || value.phase != "queued" || value.httpAttempted || value.endedAt != null)
                    throw StError.Conflict("tracked cache operation has no initial ledger; do not fabricate historical accounting")
                if (value.lifecycleVersion == 1 && value.action != GeminiCacheAction.CREATE) {
                    val view = GeminiCacheLifecycle.project(value.owner, value.resourceName!!,
                        cacheResourceHistory(value.owner.chatId), value.createdAt)
                    if (view.frontierTaskIds != value.lifecyclePreviousTaskIds)
                        throw StError.Conflict("cache resource changed before registration")
                }
                val listParentId = value.referenceTaskId
                if (value.action == GeminiCacheAction.LIST && listParentId != null) {
                    val parent = cacheOperationRecord(listParentId).operation
                    if (parent.action != GeminiCacheAction.LIST || parent.owner != value.owner || parent.phase != "completed" ||
                        parent.listing?.hasNextPage != true || parent.listingPageSize != value.listingPageSize ||
                        java.time.OffsetDateTime.parse(value.createdAt).toInstant() < java.time.OffsetDateTime.parse(parent.updatedAt).toInstant())
                        throw StError.Conflict("cache listing parent differs from the new page")
                }
                if (db.usage().insertIgnore(GeminiCacheAccounting.eventFromOperation(value, payload)) == -1L)
                    throw StError.Conflict("cache ledger identity already exists")
            } else {
                val previous = GeminiCacheAccounting.parse(old)
                val ownerTask = priorTask ?: throw StError.Conflict("cache ledger has no owning task")
                val saved = ownerTask.payloadJson?.let(GeminiCacheAccounting::operation)
                    ?: throw StError.Conflict("cache ledger has no owning task")
                GeminiCacheTaskMetadata.validateTaskBinding(saved, ownerTask.id, ownerTask.chatId, ownerTask.createdAt, TaskStatus.valueOf(ownerTask.status))
                if (previous != saved || !GeminiCacheAccounting.samePayload(ownerTask.payloadJson!!, old.usageJson))
                    throw StError.Conflict("cache task and ledger disagree")
                GeminiCacheAccounting.validateTransition(previous, value)
                val next = GeminiCacheAccounting.eventFromOperation(value, payload, old.usageJson)
                if (db.usage().checkpointCache(old.id, old.requestId, old.chatId, old.provider, old.model, old.createdAt,
                        old.usageJson, old.costJson, next.usageJson, next.costJson) != 1)
                    throw StError.Conflict("cache ledger changed before checkpoint")
            }
        }
        upsert(task, payload)
    }
    suspend fun cacheOperations(after: String = "", limit: Int = 100, pending: Boolean = false): List<Pair<TaskSnapshot, String>> =
        cacheRows(null, after, limit, pending).map { it.task to it.payload }
    suspend fun cacheOperation(id: String): Pair<TaskSnapshot, String> = cacheOperationRecord(id).let { it.task to it.payload }
    suspend fun cacheOperationRecords(after: String = "", limit: Int = 100, pending: Boolean = false): List<CacheOperationRecord> =
        cacheRows(null, after, limit, pending)
    suspend fun cacheOperationRecord(id: String): CacheOperationRecord = cacheRows(id, "", 1, false).singleOrNull()
        ?: throw StError.NotFound("Gemini cache operation missing")
    /** A bounded per-chat consistent projection input, not a replay/prompt index or a global ledger load. */
    suspend fun cacheResourceHistory(chatId: String): List<GeminiCacheTaskProgress> = withContext(Dispatchers.IO) { db.withTransaction {
        if (chatId.isBlank() || chatId.length > 1024) throw StError.Validation("invalid cache resource chat")
        val result = mutableListOf<GeminiCacheTaskProgress>()
        var after = ""; var bytes = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = cacheRows(null, after, 100, false, chatId, resourcesOnly = true)
            if (page.isEmpty()) break
            for (row in page) {
                bytes += row.payloadBytes
                if (result.size >= 10000 || bytes > 64L * 1024 * 1024) throw StError.Validation("cache resource history exceeds budget")
                result.add(row.operation)
            }
            after = page.last().task.id
        }
        result
    } }
    private suspend fun cacheRows(id: String?, after: String, limit: Int, pending: Boolean, chatId: String? = null,
        resourcesOnly: Boolean = false): List<CacheOperationRecord> =
        withContext(Dispatchers.IO) { db.withTransaction {
            if (limit !in 1..200 || after.length > 1024 || (id?.length ?: 0) > 1024) throw StError.Validation("invalid cache operation page")
            val budgets = db.tasks().cacheTaskBudgets(id, after, pending, limit, chatId, resourcesOnly)
            var pageBytes = 0L
            for (budget in budgets) {
                val byteLimit = if (budget.kind == TaskKind.GEMINI_CACHE_LIST.name) GeminiCacheTaskMetadata.MAX_LIST_BYTES else GeminiCacheTaskMetadata.MAX_BYTES
                if (budget.payloadBytes?.let { it in 1..byteLimit.toLong() } != true || budget.identityBytes > 8192)
                    throw StError.Validation("cache operation exceeds storage budget")
                pageBytes += budget.payloadBytes!! + budget.identityBytes
                if (pageBytes > 8L * 1024 * 1024) throw StError.Validation("cache operation page exceeds 8 MiB; reduce limit")
            }
            // A listing ledger can approach 1 MiB; retain only a small batch, not a whole page of ledgers.
            budgets.chunked(16).flatMap { batch ->
                currentCoroutineContext().ensureActive()
                val rows = db.tasks().cacheTasks(batch.map { it.id }).associateBy { it.id }
                val records = batch.map { budget ->
                    val row = rows[budget.id] ?: throw StError.Conflict("cache operation disappeared")
                    val raw = row.payloadJson ?: throw StError.Validation("cache operation payload missing")
                    val operation = GeminiCacheAccounting.operation(raw)
                    if (row.kind != GeminiCacheTaskMetadata.kind(operation.action).name) throw StError.Validation("cache task kind differs from action")
                    val task = Mappers.toDomain(row)
                    GeminiCacheTaskMetadata.validateTaskBinding(operation, task.id, task.chatId, task.createdAt, task.status)
                    CacheOperationRecord(task, raw, operation, budget.payloadBytes!!)
                }
                val tracked = records.filter { it.operation.accountingVersion == 1 }
                if (tracked.isNotEmpty()) {
                    val eventIds = tracked.map { GeminiCacheAccounting.eventId(it.operation.taskId) }
                    val ledgerBudgets = db.usage().cacheEventBudgets(eventIds).associateBy { it.id }
                    for (eventId in eventIds) {
                        val budget = ledgerBudgets[eventId] ?: throw StError.Conflict("tracked cache ledger missing")
                        if (budget.identityBytes > 4096 || budget.usageBytes > GeminiCacheAccounting.MAX_EVENT_BYTES || budget.costBytes > 32_768)
                            throw StError.Validation("cache ledger exceeds storage budget")
                    }
                    // Validate projected sizes before fetching any full ledger payload in this batch.
                    val ledgers = db.usage().cacheEvents(eventIds).associateBy { it.id }
                    for (record in tracked) {
                        val ledger = ledgers[GeminiCacheAccounting.eventId(record.operation.taskId)]
                            ?: throw StError.Conflict("tracked cache ledger missing")
                        if (!GeminiCacheAccounting.sameEvidence(record.operation, GeminiCacheAccounting.parse(ledger)) ||
                            !GeminiCacheAccounting.samePayload(record.payload, ledger.usageJson))
                            throw StError.Conflict("cache task and ledger disagree")
                    }
                }
                records
            }
        } }
    suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }
    suspend fun activeMaintenance(chatId: String) = db.tasks().activeMaintenanceForChat(chatId).map(Mappers::toDomain)
    suspend fun maintenanceForChat(chatId: String, limit: Int = 100): List<TaskSnapshot> {
        require(limit in 1..500)
        return db.tasks().maintenanceForChat(chatId, limit).map(Mappers::toDomain)
    }
    suspend fun activeSummaries(chatId: String) = db.tasks().activeSummariesForChat(chatId).map(Mappers::toDomain)
    suspend fun payloadJson(id: String) = db.tasks().get(id)?.payloadJson
    suspend fun checkpoint(id: String, payloadJson: String, updatedAt: String) = db.withTransaction {
        val row = db.tasks().get(id) ?: return@withTransaction
        val task = Mappers.toDomain(row)
        if (!task.status.isTerminal) upsert(task.copy(updatedAt = updatedAt), payloadJson)
    }
    suspend fun update(id: String, transform: (TaskSnapshot) -> TaskSnapshot): TaskSnapshot = db.withTransaction {
        val row = db.tasks().get(id) ?: throw StError.NotFound("task $id")
        val current = Mappers.toDomain(row)
        if (current.status.isTerminal) return@withTransaction current
        val next = transform(current)
        require(next.id == id)
        upsert(next, row.payloadJson)
        next
    }
    suspend fun upsert(task: TaskSnapshot, payloadJson: String? = null) = db.withTransaction {
        val newActive = !task.status.isTerminal && db.tasks().get(task.id) == null
        val lease = if (newActive) dataGate?.sharedLease() else null
        try {
            if (newActive) host?.beforeRegister(task)
            db.tasks().upsert(
                TaskEntity(
                    task.id, task.kind.name, task.status.name, task.chatId, task.sandboxId,
                    task.terminalReason?.name, task.errorMessage, task.createdAt, task.updatedAt,
                    task.resultSummary, Jsonx.encode(task.fileChangeIds), task.incompleteFileChanges, payloadJson,
                ),
            )
        } catch (error: Throwable) {
            if (newActive) host?.registrationFailed(task.id)
            throw error
        } finally { lease?.close() }
    }

    suspend fun requestStop(id: String, reason: TaskTerminalReason = TaskTerminalReason.USER_STOP): TaskSnapshot {
        require(reason in setOf(TaskTerminalReason.USER_STOP, TaskTerminalReason.HOST_TIMEOUT, TaskTerminalReason.HOST_UNAVAILABLE))
        return update(id) { if (it.status == TaskStatus.STOPPING) it else it.copy(status = TaskStatus.STOPPING,
            terminalReason = reason, updatedAt = java.time.Instant.now().toString(), errorMessage = when (reason) {
                TaskTerminalReason.HOST_TIMEOUT -> "foreground host timed out"
                TaskTerminalReason.HOST_UNAVAILABLE -> "foreground host unavailable"
                else -> it.errorMessage
            }) }
    }
    suspend fun requestStopAll(reason: TaskTerminalReason): List<TaskSnapshot> = db.withTransaction {
        nonTerminal().map { requestStop(it.id, reason) }
    }

    suspend fun get(id: String) = db.tasks().get(id)?.let(Mappers::toDomain)
    suspend fun activeGeneration(chatId: String) = db.tasks().activeGenerationForChat(chatId).map(Mappers::toDomain)
    suspend fun nonTerminal() = db.tasks().runningOrPending().map(Mappers::toDomain)
    fun observeNonTerminal() = db.tasks().observeRunningOrPending().map { rows -> rows.map(Mappers::toDomain) }
    fun retainHostHandoff() = host?.retainHandoff()
    suspend fun recent(limit: Int = 100) = db.tasks().recent(limit).map(Mappers::toDomain)
}

class ApprovalStore(private val db: AppDatabase) {
    suspend fun upsert(a: ApprovalRequest) {
        val fields = listOf(a.id, a.sessionId, a.sandboxId, a.taskId, a.category.name, a.toolName,
            a.argumentsJson, a.editPremiseJson, a.status.name, a.createdAt, a.decidedAt)
        if (fields.sumOf { it?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L } > 1_700_000L)
            throw StError.Validation("approval row exceeds 1700000 UTF-8 bytes")
        db.approvals().upsert(
            ApprovalEntity(
                a.id, a.sessionId, a.sandboxId, a.taskId, a.category.name, a.toolName,
                a.argumentsJson, a.editPremiseJson, a.status.name, a.createdAt, a.decidedAt,
            ),
        )
    }

    suspend fun get(id: String) = db.approvals().get(id)?.let(Mappers::toDomain)
    suspend fun pendingFifo(sessionId: String) = db.approvals().pendingFifo(sessionId).map(Mappers::toDomain)
    suspend fun pendingCount() = db.approvals().pendingCount()
    fun observePending(sessionId: String) = db.approvals().observePendingFifo(sessionId).map { rows -> rows.map(Mappers::toDomain) }
    suspend fun userAnswers(id: String): Map<String, UserQuestionAnswer>? =
        db.imports().getBlob("tool-user-answer:$id")?.let {
            Jsonx.decode<Map<String, Map<String, UserQuestionAnswer>>>(it.contentJson)["userQuestionAnswers"]
        }
    suspend fun answerUserQuestion(request: ApprovalRequest, answers: Map<String, UserQuestionAnswer>) = db.withTransaction {
        db.imports().upsertBlob(SourceBlobEntity("tool-user-answer:${request.id}", "tool-user-answers", request.id,
            Jsonx.encode(mapOf("userQuestionAnswers" to answers)), request.decidedAt ?: request.createdAt))
        upsert(request)
    }
    suspend fun expirePendingForTask(taskId: String, at: String) =
        db.approvals().expireForTask(taskId, ApprovalDecision.EXPIRED.name, at)
}

class FileChangeStore(private val db: AppDatabase) {
    private val processEpoch = UUID.randomUUID().toString()
    suspend fun get(id: String) = db.fileChanges().get(id)?.let(Mappers::toDomain)
    suspend fun record(r: FileChangeRecord) {
        db.fileChanges().upsert(
            FileChangeEntity(
                r.id, r.sandboxId, r.taskId, r.path, r.changeType.name, r.observedAt,
                r.beforeSummary, r.afterSummary, r.attribution, r.incomplete,
            ),
        )
    }

    suspend fun recordCursor(r: FileChangeRecord): FileChangeCursor = db.withTransaction {
        record(r)
        FileChangeCursor(epoch(), r.sandboxId, db.fileChanges().sequence(r.id) ?: error("file change missing after insert"))
    }

    private suspend fun epoch() = "$processEpoch:${db.fileChanges().restoreEpoch().orEmpty()}"

    suspend fun page(sandboxId: String, cursor: FileChangeCursor? = null, limit: Int = 100,
                     throughSequence: Long? = null): FileChangePage = db.withTransaction {
        if (limit !in 1..200) throw StError.Validation("file change page limit must be 1..200")
        val currentEpoch = epoch()
        if (cursor != null && (cursor.streamEpoch != currentEpoch || cursor.sandboxId != sandboxId))
            throw StError.Conflict("file change stream reset; request a fresh page")
        val after = cursor?.afterSequence ?: 0L
        val through = throughSequence ?: db.fileChanges().latestSequence(sandboxId)
        if (after < 0 || through < 0 || (throughSequence != null && through < after))
            throw StError.Validation("invalid file change sequence range")
        if (db.fileChanges().pageBudgets(sandboxId, after, through, limit + 1).any { it.identityBytes > 4096 })
            throw StError.Validation("file change page identity exceeds row budget")
        val rows = db.fileChanges().page(sandboxId, after, through, limit + 1)
        val items = rows.take(limit).map { row -> SequencedFileChange(row.sequence, Mappers.toDomain(row.change),
            buildList {
                if (row.pathTruncated) add("path")
                if (row.beforeTruncated) add("beforeSummary")
                if (row.afterTruncated) add("afterSummary")
            }) }
        FileChangePage(items, FileChangeCursor(currentEpoch, sandboxId, items.lastOrNull()?.sequence ?: after),
            through, rows.size > limit)
    }

    suspend fun taskSummary(taskId: String, limit: Int = 1000): FileChangeTaskSummary = db.withTransaction {
        require(limit in 1..1000)
        if (db.fileChanges().taskIdentityOverBudget(taskId)) throw StError.Validation("file change task identity exceeds row budget")
        FileChangeTaskSummary(db.fileChanges().taskIds(taskId, limit), db.fileChanges().taskCount(taskId),
            db.fileChanges().taskIncomplete(taskId))
    }

    suspend fun forTask(taskId: String) = db.fileChanges().forTask(taskId).map(Mappers::toDomain)
    suspend fun forSandbox(sandboxId: String, limit: Int = 200) =
        db.fileChanges().forSandbox(sandboxId, limit).map(Mappers::toDomain)
}

class SandboxStore(private val db: AppDatabase) {
    suspend fun hasChatReferences(id: String) = db.chats().hasSandboxReferences(id)
    suspend fun upsert(id: String, name: String, rootRelativePath: String, createdAt: String, updatedAt: String, metaJson: String?) {
        db.sandboxes().upsert(SandboxEntity(id, name, rootRelativePath, createdAt, updatedAt, metaJson))
    }

    suspend fun get(id: String) = db.sandboxes().get(id)
    suspend fun list() = db.sandboxes().list()
    suspend fun delete(id: String) = db.sandboxes().delete(id)
}

class ImportStore(private val db: AppDatabase) {
    val skills = SkillStore(db)
    val workspace = AssistantWorkspaceStore(db)
    suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }
    suspend fun importedUsage(id: String): UsageEventEntity? {
        val budget = db.usage().cacheEventBudget(id) ?: return null
        if (budget.identityBytes > 4096 || budget.usageBytes > 65536 || budget.costBytes > 32768) {
            throw StError.Conflict("imported usage event exceeds the supported size")
        }
        return db.usage().cacheEvent(id)
    }

    suspend fun saveImportedUsage(entity: UsageEventEntity) = db.withTransaction {
        val dao = db.usage()
        val saved = if (dao.cacheEventBudget(entity.id) != null) {
            dao.updateImported(entity) == 1
        } else {
            dao.insertIgnore(entity) != -1L
        }
        if (!saved) throw StError.Conflict("imported usage event conflicts with existing data")
    }

    suspend fun upsertBatch(e: ImportBatchEntity) = db.imports().upsertBatch(e)
    suspend fun getBatch(id: String) = db.imports().getBatch(id)
    /** Commit authority without loading a potentially large report into a CursorWindow. */
    suspend fun batchStatus(id: String): String? = db.withTransaction {
        db.openHelper.readableDatabase.query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT status FROM import_batches WHERE id=? LIMIT 1", arrayOf(id))).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    suspend fun incomplete() = db.imports().incompleteBatches()
    suspend fun mapId(ns: String, sourceId: String, targetId: String, type: String) =
        db.imports().upsertIdMap(IdMapEntity(typedNamespace(ns, type), sourceId, targetId, type))
    suspend fun resolveId(ns: String, sourceId: String, type: String) =
        db.imports().getIdMap(typedNamespace(ns, type), sourceId)?.targetId
    suspend fun getBlob(id: String) = db.imports().getBlob(id)
    suspend fun sourceBlobs(ns: String) = db.imports().sourceBlobs(ns)
    suspend fun sourceNamespaces() = db.imports().sourceNamespaces().filterNot {
        it.startsWith("assistant-workspace:") || it.startsWith("assistant-workspace-assets:") ||
            it.startsWith("skill-catalog:") || it.startsWith("skill-files:") || it == "tool-user-answers"
    }
    suspend fun retainBlob(id: String, ns: String, path: String, content: String, at: String) =
        db.imports().upsertBlob(SourceBlobEntity(id, ns, path, content, at))

    private fun typedNamespace(ns: String, type: String) = "${ns.length}:$ns:$type"
}

class AttachmentStore(private val db: AppDatabase, private val paths: StoragePaths) {
    suspend fun findByRelativePath(path: String) = db.attachments().findByRelativePath(path)
    suspend fun upsert(e: AttachmentEntity) = db.attachments().upsert(e)
    suspend fun get(id: String) = db.attachments().get(id)
    suspend fun forChat(chatId: String) = db.attachments().forChat(chatId)
    fun resolveFile(relativePath: String) = paths.attachmentFile(relativePath)
}
