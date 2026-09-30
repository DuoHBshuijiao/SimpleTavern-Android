package com.simpletavern.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChatEntity)

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun get(id: String): ChatEntity?

    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    suspend fun list(): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE forkedFromChatId = :chatId")
    suspend fun listForks(chatId: String): List<ChatEntity>

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM chats WHERE sourceNamespace = :ns AND sourceId = :sourceId LIMIT 1")
    suspend fun findBySource(ns: String, sourceId: String): ChatEntity?
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNode(node: MessageNodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCandidates(items: List<MessageCandidateEntity>)

    @Query("DELETE FROM message_candidates WHERE nodeId = :nodeId")
    suspend fun clearCandidates(nodeId: String)

    @Query("SELECT * FROM message_nodes WHERE chatId = :chatId ORDER BY orderIndex ASC LIMIT :limit OFFSET :offset")
    suspend fun pageNodes(chatId: String, limit: Int, offset: Int): List<MessageNodeEntity>

    @Query("SELECT COUNT(*) FROM message_nodes WHERE chatId = :chatId")
    suspend fun countNodes(chatId: String): Long

    @Query("SELECT * FROM message_nodes WHERE id = :id")
    suspend fun getNode(id: String): MessageNodeEntity?

    @Query("SELECT * FROM message_candidates WHERE nodeId = :nodeId ORDER BY candidateIndex ASC")
    suspend fun candidatesForNode(nodeId: String): List<MessageCandidateEntity>

    @Query("SELECT * FROM message_candidates WHERE chatId = :chatId AND content LIKE '%' || :q || '%' LIMIT :limit")
    suspend fun search(chatId: String, q: String, limit: Int): List<MessageCandidateEntity>

    @Query("DELETE FROM message_nodes WHERE chatId = :chatId")
    suspend fun deleteNodesForChat(chatId: String)

    @Query("DELETE FROM message_candidates WHERE chatId = :chatId")
    suspend fun deleteCandidatesForChat(chatId: String)

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM message_nodes WHERE chatId = :chatId")
    suspend fun maxOrder(chatId: String): Long
}

@Dao
interface CharacterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CharacterEntity)

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun get(id: String): CharacterEntity?

    @Query("SELECT * FROM characters ORDER BY name ASC")
    suspend fun list(): List<CharacterEntity>

    @Query("SELECT * FROM characters WHERE sourceNamespace = :ns AND sourceId = :sourceId LIMIT 1")
    suspend fun findBySource(ns: String, sourceId: String): CharacterEntity?
}

@Dao
interface PersonaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PersonaEntity)

    @Query("SELECT * FROM personas WHERE id = :id")
    suspend fun get(id: String): PersonaEntity?

    @Query("SELECT * FROM personas ORDER BY name ASC")
    suspend fun list(): List<PersonaEntity>
}

@Dao
interface WorldBookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorldBookEntity)

    @Query("SELECT * FROM world_books WHERE id = :id")
    suspend fun get(id: String): WorldBookEntity?

    @Query("SELECT * FROM world_books ORDER BY name ASC")
    suspend fun list(): List<WorldBookEntity>
}

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(entity: LongTermMemoryEntity)

    @Query("SELECT * FROM long_term_memories WHERE chatId = :chatId")
    suspend fun getMemory(chatId: String): LongTermMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummaryJob(entity: MemorySummaryJobEntity)

    @Query("SELECT * FROM memory_summary_jobs WHERE chatId = :chatId ORDER BY createdAt DESC")
    suspend fun listSummaryJobs(chatId: String): List<MemorySummaryJobEntity>

    @Query("SELECT * FROM memory_summary_jobs WHERE id = :id")
    suspend fun getSummaryJob(id: String): MemorySummaryJobEntity?
}

@Dao
interface MvuDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(entity: MvuStateEntity)

    @Query("SELECT * FROM mvu_states WHERE chatId = :chatId")
    suspend fun getState(chatId: String): MvuStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertKg(entity: KnowledgeGraphEntity)

    @Query("SELECT * FROM knowledge_graphs WHERE chatId = :chatId")
    suspend fun getKg(chatId: String): KnowledgeGraphEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(entity: MvuWorkLogEntity)

    @Query("SELECT * FROM mvu_work_logs WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun listLogs(chatId: String, limit: Int): List<MvuWorkLogEntity>
}

@Dao
interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun get(id: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE chatId = :chatId")
    suspend fun forChat(chatId: String): List<AttachmentEntity>
}

@Dao
interface ConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAssistant(entity: AssistantEntity)

    @Query("SELECT * FROM assistants WHERE id = :id")
    suspend fun getAssistant(id: String): AssistantEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreset(entity: ModelPresetEntity)

    @Query("SELECT * FROM model_presets WHERE id = :id")
    suspend fun getPreset(id: String): ModelPresetEntity?

    @Query("SELECT * FROM model_presets ORDER BY name ASC")
    suspend fun listPresets(): List<ModelPresetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(entity: AppSettingsEntity)

    @Query("SELECT * FROM app_settings WHERE id = :id")
    suspend fun getSettings(id: String = "global"): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAssistantMessage(entity: AssistantMessageEntity)

    @Query("SELECT * FROM assistant_messages WHERE assistantId = :assistantId ORDER BY orderIndex ASC")
    suspend fun listAssistantMessages(assistantId: String): List<AssistantMessageEntity>

    @Query("DELETE FROM assistant_messages WHERE assistantId = :assistantId")
    suspend fun clearAssistantMessages(assistantId: String)
}

@Dao
interface ImportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBatch(entity: ImportBatchEntity)

    @Query("SELECT * FROM import_batches WHERE id = :id")
    suspend fun getBatch(id: String): ImportBatchEntity?

    @Query("SELECT * FROM import_batches WHERE status = 'running' OR status = 'staged'")
    suspend fun incompleteBatches(): List<ImportBatchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIdMap(entity: IdMapEntity)

    @Query("SELECT * FROM id_maps WHERE namespace = :ns AND sourceId = :sourceId")
    suspend fun getIdMap(ns: String, sourceId: String): IdMapEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBlob(entity: SourceBlobEntity)
}

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE status NOT IN ('SUCCEEDED','FAILED','STOPPED','INTERRUPTED')")
    suspend fun runningOrPending(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE chatId = :chatId AND kind IN ('CHAT_GENERATION','GROUP_GENERATION') AND status NOT IN ('SUCCEEDED','FAILED','STOPPED','INTERRUPTED')")
    suspend fun activeGenerationForChat(chatId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TaskEntity>
}

@Dao
interface ApprovalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ApprovalEntity)

    @Query("SELECT * FROM approvals WHERE id = :id")
    suspend fun get(id: String): ApprovalEntity?

    @Query("SELECT * FROM approvals WHERE sessionId = :sessionId AND status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun pendingFifo(sessionId: String): List<ApprovalEntity>

    @Query("SELECT COUNT(*) FROM approvals WHERE status = 'PENDING'")
    suspend fun pendingCount(): Long

    @Query("UPDATE approvals SET status = :status, decidedAt = :decidedAt WHERE taskId = :taskId AND status = 'PENDING'")
    suspend fun expireForTask(taskId: String, status: String, decidedAt: String)
}

@Dao
interface FileChangeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FileChangeEntity)

    @Query("SELECT * FROM file_changes WHERE taskId = :taskId ORDER BY observedAt ASC")
    suspend fun forTask(taskId: String): List<FileChangeEntity>

    @Query("SELECT * FROM file_changes WHERE sandboxId = :sandboxId ORDER BY observedAt DESC LIMIT :limit")
    suspend fun forSandbox(sandboxId: String, limit: Int): List<FileChangeEntity>
}

@Dao
interface SandboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SandboxEntity)

    @Query("SELECT * FROM sandboxes WHERE id = :id")
    suspend fun get(id: String): SandboxEntity?

    @Query("SELECT * FROM sandboxes ORDER BY name ASC")
    suspend fun list(): List<SandboxEntity>

    @Query("DELETE FROM sandboxes WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface UsageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: UsageEventEntity): Long

    @Query("SELECT * FROM usage_events WHERE chatId = :chatId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun forChat(chatId: String, limit: Int): List<UsageEventEntity>
}
