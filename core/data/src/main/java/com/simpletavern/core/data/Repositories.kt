package com.simpletavern.core.data

import com.simpletavern.core.data.db.*
import com.simpletavern.core.model.*
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChatRepository(private val db: AppDatabase) {
    private val writeMutex = Mutex()

    suspend fun listSessions(): List<ChatSession> = db.chats().list().map(Mappers::toDomain)

    suspend fun getSession(id: String): ChatSession? = db.chats().get(id)?.let(Mappers::toDomain)

    suspend fun saveSession(session: ChatSession) {
        writeMutex.withLock { db.chats().upsert(Mappers.fromDomain(session)) }
    }

    suspend fun deleteSession(id: String) {
        writeMutex.withLock {
            db.messages().deleteCandidatesForChat(id)
            db.messages().deleteNodesForChat(id)
            db.chats().delete(id)
        }
    }

    suspend fun pageMessages(chatId: String, limit: Int, offset: Int): List<MessageNode> {
        val nodes = db.messages().pageNodes(chatId, limit, offset)
        return nodes.map { n ->
            Mappers.toDomain(n, db.messages().candidatesForNode(n.id))
        }
    }

    suspend fun messageCount(chatId: String): Long = db.messages().countNodes(chatId)

    suspend fun appendNode(chatId: String, candidate: MessageCandidate, parentNodeId: String? = null): MessageNode {
        return writeMutex.withLock {
            val order = db.messages().maxOrder(chatId) + 1
            val nodeId = UUID.randomUUID().toString().replace("-", "")
            val node = MessageNodeEntity(nodeId, chatId, order, 0, parentNodeId)
            db.messages().upsertNode(node)
            db.messages().upsertCandidates(listOf(Mappers.fromDomain(nodeId, chatId, 0, candidate)))
            Mappers.toDomain(node, listOf(Mappers.fromDomain(nodeId, chatId, 0, candidate)).let {
                // reload
                db.messages().candidatesForNode(nodeId)
            }.let { cands -> cands })
            Mappers.toDomain(node, db.messages().candidatesForNode(nodeId))
        }
    }

    suspend fun addCandidate(nodeId: String, candidate: MessageCandidate, select: Boolean = true): MessageNode {
        return writeMutex.withLock {
            val node = db.messages().getNode(nodeId) ?: throw StError.NotFound("node $nodeId")
            val existing = db.messages().candidatesForNode(nodeId)
            val index = existing.size
            db.messages().upsertCandidates(listOf(Mappers.fromDomain(nodeId, node.chatId, index, candidate)))
            val selected = if (select) index else node.selectedIndex
            val updated = node.copy(selectedIndex = selected)
            db.messages().upsertNode(updated)
            Mappers.toDomain(updated, db.messages().candidatesForNode(nodeId))
        }
    }

    suspend fun selectCandidate(nodeId: String, index: Int): MessageNode {
        return writeMutex.withLock {
            val node = db.messages().getNode(nodeId) ?: throw StError.NotFound("node $nodeId")
            val cands = db.messages().candidatesForNode(nodeId)
            if (index !in cands.indices) throw StError.Validation("candidate index out of range")
            val updated = node.copy(selectedIndex = index)
            db.messages().upsertNode(updated)
            Mappers.toDomain(updated, cands)
        }
    }

    suspend fun editSelected(nodeId: String, content: String): MessageNode {
        return writeMutex.withLock {
            val node = db.messages().getNode(nodeId) ?: throw StError.NotFound("node $nodeId")
            val cands = db.messages().candidatesForNode(nodeId).toMutableList()
            val selected = cands.getOrNull(node.selectedIndex) ?: throw StError.NotFound("selected candidate")
            val edited = selected.copy(content = content)
            db.messages().upsertCandidates(listOf(edited))
            Mappers.toDomain(node, db.messages().candidatesForNode(nodeId))
        }
    }

    suspend fun replaceAllMessages(chatId: String, nodes: List<MessageNode>) {
        writeMutex.withLock {
            db.messages().deleteCandidatesForChat(chatId)
            db.messages().deleteNodesForChat(chatId)
            nodes.forEach { n ->
                db.messages().upsertNode(
                    MessageNodeEntity(n.id, chatId, n.orderIndex, n.selectedIndex, n.parentNodeId),
                )
                db.messages().upsertCandidates(
                    n.candidates.mapIndexed { i, c -> Mappers.fromDomain(n.id, chatId, i, c) },
                )
            }
        }
    }

    suspend fun search(chatId: String, query: String, limit: Int = 50): List<MessageCandidate> =
        db.messages().search(chatId, query, limit).map(Mappers::toDomain)

    suspend fun listForks(chatId: String): List<ChatSession> =
        db.chats().listForks(chatId).map(Mappers::toDomain)
}

class ContentRepository(private val db: AppDatabase) {
    suspend fun listCharacters() = db.characters().list().map(Mappers::toDomain)
    suspend fun getCharacter(id: String) = db.characters().get(id)?.let(Mappers::toDomain)
    suspend fun saveCharacter(c: CharacterCard) = db.characters().upsert(Mappers.fromDomain(c))

    suspend fun listPersonas() = db.personas().list().map(Mappers::toDomain)
    suspend fun getPersona(id: String) = db.personas().get(id)?.let(Mappers::toDomain)
    suspend fun savePersona(p: UserPersona) = db.personas().upsert(Mappers.fromDomain(p))

    suspend fun listWorldBooks() = db.worldBooks().list().map(Mappers::toDomain)
    suspend fun getWorldBook(id: String) = db.worldBooks().get(id)?.let(Mappers::toDomain)
    suspend fun saveWorldBook(b: WorldBook) = db.worldBooks().upsert(Mappers.fromDomain(b))

    suspend fun getSettingsJson(): String? = db.config().getSettings()?.settingsJson
    suspend fun saveSettingsJson(json: String, updatedAt: String) =
        db.config().upsertSettings(AppSettingsEntity(settingsJson = json, updatedAt = updatedAt))

    suspend fun getAssistant(id: String = "default") = db.config().getAssistant(id)?.let {
        AssistantConfig(it.id, it.settingsJson, it.updatedAt, it.sourceNamespace)
    }

    suspend fun saveAssistant(cfg: AssistantConfig) =
        db.config().upsertAssistant(AssistantEntity(cfg.id, cfg.settingsJson, cfg.updatedAt, cfg.sourceNamespace))

    suspend fun listPresets() = db.config().listPresets().map {
        ModelPreset(it.id, it.name, it.configJson, it.createdAt, it.updatedAt, it.sourceNamespace, it.sourceId)
    }

    suspend fun savePreset(p: ModelPreset) =
        db.config().upsertPreset(
            ModelPresetEntity(p.id, p.name, p.configJson, p.createdAt, p.updatedAt, p.sourceNamespace, p.sourceId),
        )
}

class MemoryStore(private val db: AppDatabase) {
    suspend fun getMemory(chatId: String) = db.memory().getMemory(chatId)?.let(Mappers::toDomain)

    suspend fun saveMemory(mem: LongTermMemory, expectedVersion: Long? = null): LongTermMemory {
        val current = db.memory().getMemory(mem.chatId)
        if (expectedVersion != null && current != null && current.version != expectedVersion) {
            throw StError.Conflict("memory version mismatch", mapOf(
                "expected" to expectedVersion.toString(),
                "actual" to current.version.toString(),
            ))
        }
        val next = if (current == null) mem.copy(version = 1) else mem.copy(version = current.version + 1)
        db.memory().upsertMemory(
            LongTermMemoryEntity(next.chatId, next.content, next.version, next.anchorMessageId, next.updatedAt, next.source),
        )
        return next
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

    suspend fun saveMvu(state: MvuState, expectedVersion: Int? = null): MvuState {
        val current = db.mvu().getState(state.chatId)
        if (expectedVersion != null && current != null && current.version != expectedVersion) {
            throw StError.Conflict("mvu version mismatch")
        }
        val next = state.copy(version = (current?.version ?: 0) + 1)
        db.mvu().upsertState(
            MvuStateEntity(next.chatId, next.version, next.updatedAt, next.source, Jsonx.encode(next.tables)),
        )
        return next
    }

    suspend fun getKg(chatId: String) = db.mvu().getKg(chatId)?.let(Mappers::toDomain)

    suspend fun saveKg(kg: KnowledgeGraph, expectedVersion: Int? = null): KnowledgeGraph {
        val current = db.mvu().getKg(kg.chatId)
        if (expectedVersion != null && current != null && current.version != expectedVersion) {
            throw StError.Conflict("kg version mismatch")
        }
        val next = kg.copy(version = (current?.version ?: 0) + 1)
        db.mvu().upsertKg(
            KnowledgeGraphEntity(
                next.chatId, Jsonx.encode(next.entities), Jsonx.encode(next.relations),
                next.version, next.updatedAt, next.source,
            ),
        )
        return next
    }

    suspend fun appendMvuLog(id: String, chatId: String, ts: String, type: String, summary: String, detailJson: String?) {
        db.mvu().insertLog(MvuWorkLogEntity(id, chatId, ts, type, summary, detailJson))
    }
}

class TaskStore(private val db: AppDatabase) {
    suspend fun upsert(task: TaskSnapshot, payloadJson: String? = null) {
        db.tasks().upsert(
            TaskEntity(
                task.id, task.kind.name, task.status.name, task.chatId, task.sandboxId,
                task.terminalReason?.name, task.errorMessage, task.createdAt, task.updatedAt,
                task.resultSummary, Jsonx.encode(task.fileChangeIds), task.incompleteFileChanges, payloadJson,
            ),
        )
    }

    suspend fun get(id: String) = db.tasks().get(id)?.let(Mappers::toDomain)
    suspend fun activeGeneration(chatId: String) = db.tasks().activeGenerationForChat(chatId).map(Mappers::toDomain)
    suspend fun nonTerminal() = db.tasks().runningOrPending().map(Mappers::toDomain)
    suspend fun recent(limit: Int = 100) = db.tasks().recent(limit).map(Mappers::toDomain)
}

class ApprovalStore(private val db: AppDatabase) {
    suspend fun upsert(a: ApprovalRequest) {
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
    suspend fun expirePendingForTask(taskId: String, at: String) =
        db.approvals().expireForTask(taskId, ApprovalDecision.EXPIRED.name, at)
}

class FileChangeStore(private val db: AppDatabase) {
    suspend fun record(r: FileChangeRecord) {
        db.fileChanges().upsert(
            FileChangeEntity(
                r.id, r.sandboxId, r.taskId, r.path, r.changeType.name, r.observedAt,
                r.beforeSummary, r.afterSummary, r.attribution, r.incomplete,
            ),
        )
    }

    suspend fun forTask(taskId: String) = db.fileChanges().forTask(taskId).map(Mappers::toDomain)
    suspend fun forSandbox(sandboxId: String, limit: Int = 200) =
        db.fileChanges().forSandbox(sandboxId, limit).map(Mappers::toDomain)
}

class SandboxStore(private val db: AppDatabase) {
    suspend fun upsert(id: String, name: String, rootRelativePath: String, createdAt: String, updatedAt: String, metaJson: String?) {
        db.sandboxes().upsert(SandboxEntity(id, name, rootRelativePath, createdAt, updatedAt, metaJson))
    }

    suspend fun get(id: String) = db.sandboxes().get(id)
    suspend fun list() = db.sandboxes().list()
    suspend fun delete(id: String) = db.sandboxes().delete(id)
}

class ImportStore(private val db: AppDatabase) {
    suspend fun upsertBatch(e: ImportBatchEntity) = db.imports().upsertBatch(e)
    suspend fun getBatch(id: String) = db.imports().getBatch(id)
    suspend fun incomplete() = db.imports().incompleteBatches()
    suspend fun mapId(ns: String, sourceId: String, targetId: String, type: String) =
        db.imports().upsertIdMap(IdMapEntity(ns, sourceId, targetId, type))
    suspend fun resolveId(ns: String, sourceId: String) = db.imports().getIdMap(ns, sourceId)?.targetId
    suspend fun retainBlob(id: String, ns: String, path: String, content: String, at: String) =
        db.imports().upsertBlob(SourceBlobEntity(id, ns, path, content, at))
}

class AttachmentStore(private val db: AppDatabase, private val paths: StoragePaths) {
    suspend fun upsert(e: AttachmentEntity) = db.attachments().upsert(e)
    suspend fun get(id: String) = db.attachments().get(id)
    suspend fun forChat(chatId: String) = db.attachments().forChat(chatId)
    fun resolveFile(relativePath: String) = paths.attachmentFile(relativePath)
}

class UsageStore(private val db: AppDatabase) {
    suspend fun record(e: UsageEventEntity): Boolean = db.usage().insertIgnore(e) != -1L
    suspend fun forChat(chatId: String, limit: Int = 100) = db.usage().forChat(chatId, limit)
}
