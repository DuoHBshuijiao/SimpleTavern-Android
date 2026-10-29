package com.simpletavern.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT length(CAST(overridesJson AS BLOB)) FROM chats WHERE id = :id")
    suspend fun speechOverridesBytes(id: String): Long?
    @Query("SELECT overridesJson FROM chats WHERE id = :id")
    suspend fun speechOverrides(id: String): String?
    @Query("SELECT sourceNamespace FROM chats WHERE id = :id")
    suspend fun speechSource(id: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChatEntity)

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun get(id: String): ChatEntity?

    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    suspend fun list(): List<ChatEntity>

    @Query("SELECT * FROM chats ORDER BY updatedAt DESC, id ASC")
    fun observeList(): Flow<List<ChatEntity>>

    @Query("SELECT id, characterId, title, updatedAt, overridesJson, extraJson FROM chats WHERE sourceNamespace = :namespace")
    suspend fun sourceReferences(namespace: String): List<ConversationReferenceRow>

    @Query("SELECT * FROM chats WHERE forkedFromChatId = :chatId")
    suspend fun listForks(chatId: String): List<ChatEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM chats WHERE sandboxId = :sandboxId)")
    suspend fun hasSandboxReferences(sandboxId: String): Boolean

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE chats SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: String)

    @Query("SELECT * FROM chats WHERE sourceNamespace = :ns AND sourceId = :sourceId LIMIT 1")
    suspend fun findBySource(ns: String, sourceId: String): ChatEntity?
}

@Dao
interface MessageDao {
    @Transaction
    @Query("SELECT * FROM message_nodes WHERE chatId = :chatId ORDER BY orderIndex DESC, id DESC LIMIT :limit OFFSET :offset")
    fun observePage(chatId: String, limit: Int, offset: Int): Flow<List<MessageNodeWithCandidates>>

    @RawQuery
    suspend fun searchSourceReferences(query: SupportSQLiteQuery): List<ConversationReferenceSearchRow>
    @Query("SELECT length(CAST(content AS BLOB)) + COALESCE(length(CAST(extraJson AS BLOB)), 0) + COALESCE(length(CAST(generationMetadataJson AS BLOB)), 0) FROM message_candidates WHERE id = :id AND chatId = :chatId")
    suspend fun speechMessageBytes(id: String, chatId: String): Long?
    @Query("SELECT content, extraJson, generationMetadataJson FROM message_candidates WHERE id = :id AND chatId = :chatId")
    suspend fun speechMessage(id: String, chatId: String): SpeechMessageRow?
    @Query("UPDATE message_candidates SET extraJson = :next WHERE id = :id AND chatId = :chatId AND content = :content AND extraJson IS :previous")
    suspend fun bindSpeech(id: String, chatId: String, content: String, previous: String?, next: String): Int
    @Query("SELECT c.*, n.orderIndex AS nodeOrder FROM message_nodes n JOIN message_candidates c ON c.nodeId = n.id AND c.chatId = n.chatId AND c.candidateIndex = n.selectedIndex WHERE n.chatId = :chatId AND n.orderIndex <= :throughOrder ORDER BY n.orderIndex DESC, n.id DESC LIMIT :limit")
    suspend fun selectedWindow(chatId: String, throughOrder: Long, limit: Int): List<SelectedMessageRow>
    @Query("SELECT c.*, n.orderIndex AS nodeOrder FROM message_nodes n JOIN message_candidates c ON c.nodeId = n.id AND c.chatId = n.chatId AND c.candidateIndex = n.selectedIndex WHERE n.chatId = :chatId AND n.orderIndex > :afterOrder AND n.orderIndex <= :throughOrder ORDER BY n.orderIndex ASC, n.id ASC")
    suspend fun selectedMessages(chatId: String, afterOrder: Long, throughOrder: Long): List<SelectedMessageRow>
    @Query("SELECT c.id AS candidateId, n.orderIndex AS nodeOrder, c.role FROM message_nodes n JOIN message_candidates c ON c.nodeId = n.id AND c.chatId = n.chatId AND c.candidateIndex = n.selectedIndex WHERE n.chatId = :chatId AND n.orderIndex > :afterOrder AND n.orderIndex <= :throughOrder ORDER BY n.orderIndex ASC, n.id ASC")
    suspend fun selectedContextIndex(chatId: String, afterOrder: Long, throughOrder: Long): List<SelectedContextIndexRow>
    @Query("SELECT toolCallsJson FROM message_candidates WHERE id = :candidateId AND chatId = :chatId")
    suspend fun selectedToolCallsJson(chatId: String, candidateId: String): String?
    @Query("SELECT c.*, n.orderIndex AS nodeOrder FROM message_nodes n JOIN message_candidates c ON c.nodeId = n.id AND c.chatId = n.chatId AND c.candidateIndex = n.selectedIndex WHERE n.chatId = :chatId AND n.orderIndex > :afterOrder AND n.orderIndex <= :throughOrder ORDER BY n.orderIndex ASC, n.id ASC LIMIT -1 OFFSET :offset")
    suspend fun selectedMessagesFromOffset(chatId: String, afterOrder: Long, throughOrder: Long, offset: Int): List<SelectedMessageRow>
    @Query("SELECT c.*, n.orderIndex AS nodeOrder FROM message_nodes n JOIN message_candidates c ON c.nodeId = n.id AND c.chatId = n.chatId AND c.candidateIndex = n.selectedIndex WHERE n.chatId = :chatId AND n.orderIndex > :afterOrder AND n.orderIndex <= :throughOrder ORDER BY n.orderIndex ASC, n.id ASC LIMIT :limit OFFSET :offset")
    suspend fun selectedMessagePage(chatId: String, afterOrder: Long, throughOrder: Long, limit: Int, offset: Int): List<SelectedMessageRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNode(node: MessageNodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNodes(nodes: List<MessageNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCandidates(items: List<MessageCandidateEntity>)

    @Query("SELECT id FROM message_nodes WHERE id IN (:ids) AND chatId != :chatId LIMIT 1")
    suspend fun foreignNodeId(ids: List<String>, chatId: String): String?

    @Query("SELECT id FROM message_candidates WHERE id IN (:ids) AND chatId != :chatId LIMIT 1")
    suspend fun foreignCandidateId(ids: List<String>, chatId: String): String?

    @Query("DELETE FROM message_candidates WHERE nodeId = :nodeId")
    suspend fun clearCandidates(nodeId: String)

    @Query("SELECT * FROM message_nodes WHERE chatId = :chatId ORDER BY orderIndex ASC LIMIT :limit OFFSET :offset")
    suspend fun pageNodes(chatId: String, limit: Int, offset: Int): List<MessageNodeEntity>

    @Query("SELECT * FROM message_nodes WHERE chatId = :chatId ORDER BY orderIndex DESC LIMIT :limit")
    suspend fun latestNodes(chatId: String, limit: Int): List<MessageNodeEntity>

    @Query("SELECT * FROM message_nodes WHERE chatId = :chatId AND orderIndex > :afterOrder ORDER BY orderIndex ASC LIMIT :limit")
    suspend fun nodesAfter(chatId: String, afterOrder: Long, limit: Int): List<MessageNodeEntity>

    @Query("SELECT id FROM message_nodes WHERE chatId = :chatId AND orderIndex > :afterOrder ORDER BY orderIndex ASC")
    suspend fun nodeIdsAfter(chatId: String, afterOrder: Long): List<String>

    @Query("UPDATE message_nodes SET orderIndex = orderIndex + :delta WHERE chatId = :chatId AND orderIndex >= :fromOrder")
    suspend fun shiftOrders(chatId: String, fromOrder: Long, delta: Long)

    @Query("UPDATE message_candidates SET nodeId = :nodeId, candidateIndex = :candidateIndex WHERE id = :candidateId")
    suspend fun moveCandidate(candidateId: String, nodeId: String, candidateIndex: Int)

    @Query("SELECT nodeId FROM message_candidates WHERE id = :candidateId")
    suspend fun nodeIdForCandidate(candidateId: String): String?

    @Query("SELECT id FROM message_candidates WHERE nodeId IN (:nodeIds)")
    suspend fun candidateIdsForNodes(nodeIds: List<String>): List<String>

    @Query("DELETE FROM message_candidates WHERE nodeId IN (:nodeIds)")
    suspend fun deleteCandidatesForNodes(nodeIds: List<String>)

    @Query("DELETE FROM message_nodes WHERE id IN (:nodeIds)")
    suspend fun deleteNodes(nodeIds: List<String>)

    @Query("SELECT COUNT(*) FROM message_nodes WHERE chatId = :chatId")
    suspend fun countNodes(chatId: String): Long

    @Query("SELECT * FROM message_nodes WHERE id = :id")
    suspend fun getNode(id: String): MessageNodeEntity?

    @Query("DELETE FROM message_nodes WHERE id=:id")
    suspend fun deleteNode(id: String)

    @Query("UPDATE message_nodes SET parentNodeId=:parentNodeId WHERE chatId=:chatId AND parentNodeId=:nodeId")
    suspend fun reparentChildren(chatId: String, nodeId: String, parentNodeId: String?)

    @Query("SELECT * FROM message_candidates WHERE nodeId = :nodeId ORDER BY candidateIndex ASC")
    suspend fun candidatesForNode(nodeId: String): List<MessageCandidateEntity>

    // Keeps candidates ordered within each node in batch results.
    @Query("SELECT * FROM message_candidates WHERE nodeId IN (:nodeIds) ORDER BY nodeId ASC, candidateIndex ASC")
    suspend fun candidatesForNodes(nodeIds: List<String>): List<MessageCandidateEntity>

    @Query("SELECT * FROM message_candidates WHERE id = :id")
    suspend fun getCandidate(id: String): MessageCandidateEntity?

    @Query("SELECT c.* FROM message_nodes n JOIN message_candidates c ON c.nodeId = n.id AND c.chatId = n.chatId WHERE n.chatId = :chatId AND c.content LIKE '%' || :q || '%' ESCAPE '\\' ORDER BY n.orderIndex ASC, c.candidateIndex ASC, c.id ASC LIMIT :limit OFFSET :offset")
    suspend fun search(chatId: String, q: String, limit: Int, offset: Int): List<MessageCandidateEntity>

    @Query("DELETE FROM message_nodes WHERE chatId = :chatId")
    suspend fun deleteNodesForChat(chatId: String)

    @Query("DELETE FROM message_candidates WHERE chatId = :chatId")
    suspend fun deleteCandidatesForChat(chatId: String)

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM message_nodes WHERE chatId = :chatId")
    suspend fun maxOrder(chatId: String): Long
}

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters ORDER BY name ASC, id ASC")
    fun observeList(): Flow<List<CharacterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CharacterEntity)

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun get(id: String): CharacterEntity?

    @Query("SELECT * FROM characters ORDER BY name ASC")
    suspend fun list(): List<CharacterEntity>

    @Query("SELECT * FROM characters WHERE sourceNamespace = :ns AND sourceId = :sourceId LIMIT 1")
    suspend fun findBySource(ns: String, sourceId: String): CharacterEntity?

    @Query("DELETE FROM characters WHERE id=:id")
    suspend fun delete(id: String): Int
}

@Dao
interface PersonaDao {
    @Query("SELECT * FROM personas ORDER BY name ASC, id ASC")
    fun observeList(): Flow<List<PersonaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PersonaEntity)

    @Query("SELECT * FROM personas WHERE id = :id")
    suspend fun get(id: String): PersonaEntity?

    @Query("SELECT * FROM personas ORDER BY name ASC")
    suspend fun list(): List<PersonaEntity>

    @Query("DELETE FROM personas WHERE id=:id")
    suspend fun delete(id: String): Int
}

@Dao
interface WorldBookDao {
    @Query("SELECT * FROM world_books ORDER BY name ASC, id ASC")
    fun observeList(): Flow<List<WorldBookEntity>>

    @Query("DELETE FROM world_books WHERE id = :id")
    suspend fun delete(id: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorldBookEntity)

    @Query("SELECT * FROM world_books WHERE id = :id")
    suspend fun get(id: String): WorldBookEntity?

    @Query("SELECT * FROM world_books ORDER BY name ASC")
    suspend fun list(): List<WorldBookEntity>

    @Query("SELECT id, globalActive, sourceNamespace, sessionChatIdsJson FROM world_books ORDER BY name ASC")
    suspend fun activationMetadata(): List<WorldBookActivationMetadata>

    @Query("SELECT * FROM world_books WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<WorldBookEntity>
}

data class WorldBookActivationMetadata(
    val id: String,
    val globalActive: Boolean,
    val sourceNamespace: String?,
    val sessionChatIdsJson: String,
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_summary_jobs WHERE status NOT IN ('SUCCEEDED','FAILED','STOPPED','INTERRUPTED')")
    suspend fun incompleteSummaryJobs(): List<MemorySummaryJobEntity>
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

    @Query("DELETE FROM long_term_memories WHERE chatId=:chatId")
    suspend fun deleteMemory(chatId: String): Int

    @Query("DELETE FROM memory_summary_jobs WHERE chatId=:chatId")
    suspend fun deleteSummaryJobs(chatId: String): Int
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

    @Query("SELECT * FROM mvu_work_logs WHERE chatId = :chatId ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun listLogs(chatId: String, limit: Int): List<MvuWorkLogEntity>

    @Query("SELECT COUNT(*) FROM mvu_work_logs WHERE chatId = :chatId")
    suspend fun countLogs(chatId: String): Long

    @Query("DELETE FROM mvu_states WHERE chatId=:chatId")
    suspend fun deleteState(chatId: String): Int

    @Query("DELETE FROM knowledge_graphs WHERE chatId=:chatId")
    suspend fun deleteKg(chatId: String): Int

    @Query("DELETE FROM mvu_work_logs WHERE chatId=:chatId")
    suspend fun deleteLogs(chatId: String): Int
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE relativePath = :path LIMIT 1")
    suspend fun findByRelativePath(path: String): AttachmentEntity?

    @Query("DELETE FROM attachments WHERE id = :id AND relativePath = :path")
    suspend fun deleteSpeech(id: String, path: String): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun get(id: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE chatId = :chatId")
    suspend fun forChat(chatId: String): List<AttachmentEntity>
}

@Dao
interface ConfigDao {
    @Query("SELECT * FROM model_presets ORDER BY name ASC, id ASC")
    fun observePresets(): Flow<List<ModelPresetEntity>>

    @Query("SELECT * FROM app_settings WHERE id = 'global'")
    fun observeSettings(): Flow<AppSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAssistant(entity: AssistantEntity)

    @Query("SELECT * FROM assistants WHERE id = :id")
    suspend fun getAssistant(id: String): AssistantEntity?

    @Query("SELECT * FROM assistants ORDER BY id ASC")
    suspend fun listAssistants(): List<AssistantEntity>

    @Query("DELETE FROM assistants WHERE id = :id")
    suspend fun deleteAssistant(id: String): Int

    @Query("SELECT length(CAST(settingsJson AS BLOB)) FROM assistants WHERE id = :id")
    suspend fun assistantSettingsBytes(id: String): Long?

    @Query("SELECT sourceNamespace FROM assistants WHERE id = :id")
    suspend fun assistantSource(id: String): String?

    @Query("SELECT id FROM assistants WHERE sourceNamespace = :namespace ORDER BY id LIMIT 2")
    suspend fun assistantIdsForSource(namespace: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreset(entity: ModelPresetEntity)

    @Query("SELECT * FROM model_presets WHERE id = :id")
    suspend fun getPreset(id: String): ModelPresetEntity?

    @Query("DELETE FROM model_presets WHERE id=:id")
    suspend fun deletePreset(id: String): Int

    @Query("SELECT length(CAST(configJson AS BLOB)) FROM model_presets WHERE id = :id")
    suspend fun presetConfigBytes(id: String): Long?
    @Query("SELECT configJson FROM model_presets WHERE id = :id")
    suspend fun presetConfigJson(id: String): String?
    @Query("SELECT sourceNamespace FROM model_presets WHERE id = :id")
    suspend fun presetSource(id: String): String?

    @Query("SELECT * FROM model_presets ORDER BY name ASC")
    suspend fun listPresets(): List<ModelPresetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(entity: AppSettingsEntity)

    @Query("SELECT * FROM app_settings WHERE id = :id")
    suspend fun getSettings(id: String = "global"): AppSettingsEntity?

    @Query("SELECT length(CAST(settingsJson AS BLOB)) FROM app_settings WHERE id = :id")
    suspend fun settingsBytes(id: String): Long?

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

    @Query("SELECT * FROM source_blobs WHERE id = :id")
    suspend fun getBlob(id: String): SourceBlobEntity?

    @Query("DELETE FROM source_blobs WHERE id = :id")
    suspend fun deleteBlob(id: String)

    @Query("SELECT sourcePath AS path, importedAt AS updatedAt FROM source_blobs WHERE namespace=:ns AND sourcePath>:afterPath ORDER BY sourcePath LIMIT :limit")
    suspend fun workspaceFiles(ns: String, afterPath: String, limit: Int): List<com.simpletavern.core.model.WorkspaceFileInfo>

    @Query("SELECT * FROM source_blobs WHERE namespace = :ns ORDER BY sourcePath ASC")
    suspend fun sourceBlobs(ns: String): List<SourceBlobEntity>

    @Query("SELECT DISTINCT namespace FROM source_blobs ORDER BY namespace ASC")
    suspend fun sourceNamespaces(): List<String>
}

@Dao
interface TaskDao {
    @Query("SELECT id FROM tasks WHERE kind IN ('SPEECH_VOICE_DESIGN', 'SPEECH_VOICE_CLONE') AND id > :after ORDER BY id LIMIT :limit")
    suspend fun voiceDesignRecoveryPage(after: String, limit: Int): List<String>
    @Query("SELECT substr(id,1,1025) AS id, substr(kind,1,129) AS kind, length(CAST(payloadJson AS BLOB)) AS payloadBytes, length(CAST(id AS BLOB)) + COALESCE(length(CAST(chatId AS BLOB)),0) + COALESCE(length(CAST(errorMessage AS BLOB)),0) + COALESCE(length(CAST(resultSummary AS BLOB)),0) + length(CAST(fileChangeIdsJson AS BLOB)) AS identityBytes FROM tasks WHERE kind IN ('GEMINI_CACHE_OPERATION','GEMINI_CACHE_LIST') AND (:resourcesOnly=0 OR kind='GEMINI_CACHE_OPERATION') AND (:id IS NULL OR id=:id) AND (:chatId IS NULL OR chatId=:chatId) AND id>:after AND (:pending=0 OR status NOT IN ('SUCCEEDED','FAILED','STOPPED','INTERRUPTED')) ORDER BY id LIMIT :limit")
    suspend fun cacheTaskBudgets(id: String?, after: String, pending: Boolean, limit: Int, chatId: String? = null, resourcesOnly: Boolean = false): List<CacheTaskRowBudget>
    @Query("SELECT * FROM tasks WHERE id IN (:ids)")
    suspend fun cacheTasks(ids: List<String>): List<TaskEntity>
    @Query("SELECT substr(kind,1,129) FROM tasks WHERE id=:id")
    suspend fun cacheTaskExistingKind(id: String): String?
    @Query("SELECT length(CAST(payloadJson AS BLOB)) FROM tasks WHERE id = :id AND kind = 'SPEECH_SYNTHESIS'")
    suspend fun speechPayloadBytes(id: String): Long?
    @Query("SELECT t.id, length(CAST(t.payloadJson AS BLOB)) AS payloadBytes FROM tasks t JOIN attachments a ON a.id = 'tts:' || t.id WHERE t.kind = 'SPEECH_SYNTHESIS' AND t.status = 'SUCCEEDED' AND t.id > :after ORDER BY t.id LIMIT :limit")
    suspend fun speechAssetsPage(after: String, limit: Int): List<SpeechTaskRowBudget>
    @Query("SELECT id, length(CAST(payloadJson AS BLOB)) AS payloadBytes FROM tasks WHERE kind = 'SPEECH_SYNTHESIS' AND (status NOT IN ('SUCCEEDED','FAILED','STOPPED','INTERRUPTED') OR resultSummary IN ('speech audio published; cleanup pending','speech cleanup required')) AND id > :after ORDER BY id LIMIT :limit")
    suspend fun speechTasksPage(after: String, limit: Int): List<SpeechTaskRowBudget>
    @Query("SELECT * FROM tasks WHERE chatId = :chatId AND kind IN ('MVU_UPDATE','KG_UPDATE') AND status NOT IN ('SUCCEEDED','FAILED','STOPPED','INTERRUPTED') ORDER BY createdAt ASC, id ASC")
    suspend fun activeMaintenanceForChat(chatId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE chatId = :chatId AND kind IN ('MVU_UPDATE','KG_UPDATE') ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun maintenanceForChat(chatId: String, limit: Int): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE chatId = :chatId AND kind = 'MEMORY_SUMMARY' AND status NOT IN ('SUCCEEDED','FAILED','STOPPED','INTERRUPTED')")
    suspend fun activeSummariesForChat(chatId: String): List<TaskEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM tasks WHERE chatId=:chatId AND status NOT IN ('SUCCEEDED','FAILED','STOPPED','INTERRUPTED'))")
    suspend fun hasNonTerminalForChat(chatId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE status NOT IN ('SUCCEEDED','FAILED','STOPPED','INTERRUPTED')")
    suspend fun runningOrPending(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status NOT IN ('SUCCEEDED','FAILED','STOPPED','INTERRUPTED') ORDER BY createdAt ASC, id ASC")
    fun observeRunningOrPending(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE chatId = :chatId AND kind IN ('CHAT_GENERATION','GROUP_GENERATION') AND status NOT IN ('SUCCEEDED','FAILED','STOPPED','INTERRUPTED')")
    suspend fun activeGenerationForChat(chatId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TaskEntity>
}

data class SpeechTaskRowBudget(val id: String, val payloadBytes: Long?)
data class CacheTaskRowBudget(val id: String, val kind: String, val payloadBytes: Long?, val identityBytes: Long)
data class SpeechMessageRow(val content: String, val extraJson: String?, val generationMetadataJson: String?)
data class SelectedMessageRow(@Embedded val candidate: MessageCandidateEntity, val nodeOrder: Long)
data class SelectedContextIndexRow(val candidateId: String, val nodeOrder: Long, val role: String)

data class ConversationReferenceRow(val id: String, val characterId: String, val title: String,
    val updatedAt: String, val overridesJson: String, val extraJson: String?)

data class ConversationReferenceSearchRow(val chatId: String, val messageId: String, val orderIndex: Long,
    val title: String, val snippet: String, val updatedAt: String)

@Dao
interface ApprovalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ApprovalEntity)

    @Query("SELECT * FROM approvals WHERE id = :id")
    suspend fun get(id: String): ApprovalEntity?

    @Query("SELECT * FROM approvals WHERE sessionId = :sessionId AND status = 'PENDING' ORDER BY rowid ASC")
    suspend fun pendingFifo(sessionId: String): List<ApprovalEntity>

    @Query("SELECT * FROM approvals WHERE sessionId = :sessionId AND status = 'PENDING' ORDER BY rowid ASC")
    fun observePendingFifo(sessionId: String): Flow<List<ApprovalEntity>>

    @Query("SELECT COUNT(*) FROM approvals WHERE status = 'PENDING'")
    suspend fun pendingCount(): Long

    @Query("UPDATE approvals SET status = :status, decidedAt = :decidedAt WHERE taskId = :taskId AND status = 'PENDING'")
    suspend fun expireForTask(taskId: String, status: String, decidedAt: String)
}

data class FileChangeSequenceRow(val sequence: Long, @Embedded val change: FileChangeEntity,
                                val pathTruncated: Boolean, val beforeTruncated: Boolean, val afterTruncated: Boolean)
data class FileChangeRowBudget(val sequence: Long, val identityBytes: Long)

@Dao
interface FileChangeDao {
    @Query("SELECT * FROM file_changes WHERE id = :id LIMIT 1")
    suspend fun get(id: String): FileChangeEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FileChangeEntity)

    @Query("SELECT rowid FROM file_changes WHERE id = :id LIMIT 1")
    suspend fun sequence(id: String): Long?

    @Query("SELECT COALESCE(MAX(rowid), 0) FROM file_changes WHERE sandboxId = :sandboxId")
    suspend fun latestSequence(sandboxId: String): Long

    @Query("SELECT rowid AS sequence, id, sandboxId, taskId, substr(path, 1, 2048) AS path, changeType, observedAt, substr(beforeSummary, 1, 1024) AS beforeSummary, substr(afterSummary, 1, 1024) AS afterSummary, attribution, incomplete, length(CAST(path AS BLOB)) != length(CAST(substr(path, 1, 2048) AS BLOB)) AS pathTruncated, COALESCE(length(CAST(beforeSummary AS BLOB)) != length(CAST(substr(beforeSummary, 1, 1024) AS BLOB)), 0) AS beforeTruncated, COALESCE(length(CAST(afterSummary AS BLOB)) != length(CAST(substr(afterSummary, 1, 1024) AS BLOB)), 0) AS afterTruncated FROM file_changes WHERE sandboxId = :sandboxId AND rowid > :after AND rowid <= :through ORDER BY rowid ASC LIMIT :limit")
    suspend fun page(sandboxId: String, after: Long, through: Long, limit: Int): List<FileChangeSequenceRow>

    @Query("SELECT rowid AS sequence, length(CAST(id AS BLOB)) + length(CAST(sandboxId AS BLOB)) + COALESCE(length(CAST(taskId AS BLOB)), 0) + length(CAST(changeType AS BLOB)) + length(CAST(observedAt AS BLOB)) + length(CAST(attribution AS BLOB)) AS identityBytes FROM file_changes WHERE sandboxId = :sandboxId AND rowid > :after AND rowid <= :through ORDER BY rowid ASC LIMIT :limit")
    suspend fun pageBudgets(sandboxId: String, after: Long, through: Long, limit: Int): List<FileChangeRowBudget>

    @Query("SELECT id FROM file_changes WHERE taskId = :taskId ORDER BY rowid ASC LIMIT :limit")
    suspend fun taskIds(taskId: String, limit: Int): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM file_changes WHERE taskId = :taskId AND length(CAST(id AS BLOB)) > 1024)")
    suspend fun taskIdentityOverBudget(taskId: String): Boolean

    @Query("SELECT COUNT(*) FROM file_changes WHERE taskId = :taskId")
    suspend fun taskCount(taskId: String): Long

    @Query("SELECT EXISTS(SELECT 1 FROM file_changes WHERE taskId = :taskId AND incomplete = 1)")
    suspend fun taskIncomplete(taskId: String): Boolean

    @Query("SELECT id FROM import_batches WHERE source = 'native_restore' AND status = 'done' ORDER BY rowid DESC LIMIT 1")
    suspend fun restoreEpoch(): String?

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

data class UsageSequenceRow(val sequence: Long, @Embedded val event: UsageEventEntity)
data class UsageRowBudget(val sequence: Long, val identityBytes: Long, val usageBytes: Long, val costBytes: Long)
data class CacheEventRowBudget(val id: String, val identityBytes: Long, val usageBytes: Long, val costBytes: Long)

@Dao
interface UsageDao {
    @Query("SELECT EXISTS(SELECT 1 FROM usage_events WHERE rowid=:sequence AND id=eventId AND eventId='gemini_cache:' || requestId AND provider LIKE 'gemini-cache:%')")
    suspend fun cacheBudgetCandidate(sequence: Long): Boolean
    @Query("SELECT * FROM usage_events WHERE id=:id")
    suspend fun cacheEvent(id: String): UsageEventEntity?

    @Query("SELECT rowid AS sequence, length(CAST(id AS BLOB)) + length(CAST(eventId AS BLOB)) + length(CAST(requestId AS BLOB)) + COALESCE(length(CAST(chatId AS BLOB)),0) + COALESCE(length(CAST(provider AS BLOB)),0) + COALESCE(length(CAST(model AS BLOB)),0) + length(CAST(createdAt AS BLOB)) AS identityBytes, length(CAST(usageJson AS BLOB)) AS usageBytes, COALESCE(length(CAST(costJson AS BLOB)),0) AS costBytes FROM usage_events WHERE id=:id")
    suspend fun cacheEventBudget(id: String): UsageRowBudget?

    @Query("SELECT id, length(CAST(id AS BLOB)) + length(CAST(eventId AS BLOB)) + length(CAST(requestId AS BLOB)) + COALESCE(length(CAST(chatId AS BLOB)),0) + COALESCE(length(CAST(provider AS BLOB)),0) + COALESCE(length(CAST(model AS BLOB)),0) + length(CAST(createdAt AS BLOB)) AS identityBytes, length(CAST(usageJson AS BLOB)) AS usageBytes, COALESCE(length(CAST(costJson AS BLOB)),0) AS costBytes FROM usage_events WHERE id IN (:ids)")
    suspend fun cacheEventBudgets(ids: List<String>): List<CacheEventRowBudget>

    @Query("SELECT * FROM usage_events WHERE id IN (:ids)")
    suspend fun cacheEvents(ids: List<String>): List<UsageEventEntity>

    @Query("UPDATE usage_events SET usageJson=:raw, costJson=:cost WHERE id=:id AND eventId=:id AND requestId=:requestId AND chatId IS :chatId AND provider IS :provider AND model IS :model AND createdAt=:createdAt AND usageJson=:expected AND costJson IS :expectedCost")
    suspend fun checkpointCache(id: String, requestId: String, chatId: String?, provider: String?, model: String?, createdAt: String,
        expected: String, expectedCost: String?, raw: String, cost: String?): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: UsageEventEntity): Long

    @Update
    suspend fun updateImported(entity: UsageEventEntity): Int

    @Query("UPDATE usage_events SET usageJson = :raw, costJson = :cost WHERE id = :id AND usageJson = :expected AND costJson IS NULL")
    suspend fun finishSearch(id: String, expected: String, raw: String, cost: String?): Int

    @Query("UPDATE usage_events SET usageJson = :raw, costJson = :cost WHERE id = :id AND usageJson = :expected AND costJson IS NULL")
    suspend fun finishSpeech(id: String, expected: String, raw: String, cost: String?): Int

    @Query("UPDATE usage_events SET usageJson = :raw, costJson = :cost WHERE id = :id AND eventId = :id AND requestId = :id AND chatId IS :chatId AND provider IS :provider AND model IS :model AND createdAt = :createdAt AND usageJson = :expected AND costJson IS NULL")
    suspend fun finishTokenRequest(id: String, chatId: String?, provider: String?, model: String?, createdAt: String,
        expected: String, raw: String, cost: String?): Int

    @Query("SELECT rowid AS sequence, * FROM usage_events WHERE rowid > :after AND costJson IS NULL AND id=requestId AND eventId=id AND substr(usageJson,1,21)='{\"_requestLifecycle\":' ORDER BY rowid LIMIT :limit")
    suspend fun pendingTokenPage(after: Long, limit: Int): List<UsageSequenceRow>

    @Query("SELECT rowid AS sequence, length(CAST(id AS BLOB)) + length(CAST(eventId AS BLOB)) + length(CAST(requestId AS BLOB)) + COALESCE(length(CAST(chatId AS BLOB)), 0) + COALESCE(length(CAST(provider AS BLOB)), 0) + COALESCE(length(CAST(model AS BLOB)), 0) + length(CAST(createdAt AS BLOB)) AS identityBytes, length(CAST(usageJson AS BLOB)) AS usageBytes, 0 AS costBytes FROM usage_events WHERE rowid > :after AND costJson IS NULL AND id=requestId AND eventId=id AND substr(usageJson,1,21)='{\"_requestLifecycle\":' ORDER BY rowid LIMIT :limit")
    suspend fun pendingTokenBudgets(after: Long, limit: Int): List<UsageRowBudget>

    @Query("SELECT rowid AS sequence, * FROM usage_events WHERE rowid > :after AND costJson IS NULL AND substr(eventId,1,4)='tts:' AND provider IN ('tts:minimax','tts:glm','tts:openrouter','tts:siliconflow','tts:openai','tts:groq','tts:gemini','tts:elevenlabs','tts:qwen','tts:xai','tts:fish-audio','tts:step','tts:mimo','tts:volcengine') ORDER BY rowid LIMIT :limit")
    suspend fun pendingSpeechPage(after: Long, limit: Int): List<UsageSequenceRow>

    @Query("SELECT rowid AS sequence, length(CAST(id AS BLOB)) + length(CAST(eventId AS BLOB)) + length(CAST(requestId AS BLOB)) + COALESCE(length(CAST(chatId AS BLOB)), 0) + COALESCE(length(CAST(provider AS BLOB)), 0) + COALESCE(length(CAST(model AS BLOB)), 0) + length(CAST(createdAt AS BLOB)) AS identityBytes, length(CAST(usageJson AS BLOB)) AS usageBytes, 0 AS costBytes FROM usage_events WHERE rowid > :after AND costJson IS NULL AND substr(eventId,1,4)='tts:' AND provider IN ('tts:minimax','tts:glm','tts:openrouter','tts:siliconflow','tts:openai','tts:groq','tts:gemini','tts:elevenlabs','tts:qwen','tts:xai','tts:fish-audio','tts:step','tts:mimo','tts:volcengine') ORDER BY rowid LIMIT :limit")
    suspend fun pendingSpeechBudgets(after: Long, limit: Int): List<UsageRowBudget>

    @Query("SELECT rowid AS sequence, * FROM usage_events WHERE rowid > :after AND costJson IS NULL AND substr(eventId,1,11)='web_search:' AND provider IN ('web-search:tavily','web-search:bocha','web-search:brave') ORDER BY rowid LIMIT :limit")
    suspend fun pendingSearchPage(after: Long, limit: Int): List<UsageSequenceRow>

    @Query("SELECT rowid AS sequence, length(CAST(id AS BLOB)) + length(CAST(eventId AS BLOB)) + length(CAST(requestId AS BLOB)) + COALESCE(length(CAST(chatId AS BLOB)), 0) + COALESCE(length(CAST(provider AS BLOB)), 0) + COALESCE(length(CAST(model AS BLOB)), 0) + length(CAST(createdAt AS BLOB)) AS identityBytes, length(CAST(usageJson AS BLOB)) AS usageBytes, 0 AS costBytes FROM usage_events WHERE rowid > :after AND costJson IS NULL AND substr(eventId,1,11)='web_search:' AND provider IN ('web-search:tavily','web-search:bocha','web-search:brave') ORDER BY rowid LIMIT :limit")
    suspend fun pendingSearchBudgets(after: Long, limit: Int): List<UsageRowBudget>

    @Query("SELECT EXISTS(SELECT 1 FROM usage_events WHERE eventId = :eventId)")
    suspend fun exists(eventId: String): Boolean

    @Query("SELECT length(CAST(costJson AS BLOB)) FROM usage_events WHERE eventId = :eventId")
    suspend fun costBytes(eventId: String): Long?

    @Query("SELECT costJson FROM usage_events WHERE eventId = :eventId")
    suspend fun cost(eventId: String): String?

    @Query("SELECT COALESCE(MAX(rowid), 0) FROM usage_events")
    suspend fun latestSequence(): Long

    @Query("SELECT rowid AS sequence, * FROM usage_events WHERE rowid > :after AND rowid <= :through AND (:chatId IS NULL OR chatId = :chatId) AND (:provider IS NULL OR provider = :provider) AND (:model IS NULL OR model = :model) ORDER BY rowid ASC LIMIT :limit")
    suspend fun page(after: Long, through: Long, chatId: String?, provider: String?, model: String?, limit: Int): List<UsageSequenceRow>

    @Query("SELECT rowid AS sequence, length(CAST(id AS BLOB)) + length(CAST(eventId AS BLOB)) + length(CAST(requestId AS BLOB)) + COALESCE(length(CAST(chatId AS BLOB)), 0) + COALESCE(length(CAST(provider AS BLOB)), 0) + COALESCE(length(CAST(model AS BLOB)), 0) + length(CAST(createdAt AS BLOB)) AS identityBytes, length(CAST(usageJson AS BLOB)) AS usageBytes, COALESCE(length(CAST(costJson AS BLOB)), 0) AS costBytes FROM usage_events WHERE rowid > :after AND rowid <= :through AND (:chatId IS NULL OR chatId = :chatId) AND (:provider IS NULL OR provider = :provider) AND (:model IS NULL OR model = :model) ORDER BY rowid ASC LIMIT :limit")
    suspend fun pageBudgets(after: Long, through: Long, chatId: String?, provider: String?, model: String?, limit: Int): List<UsageRowBudget>

    @Query("SELECT rowid AS sequence, length(CAST(id AS BLOB)) + length(CAST(eventId AS BLOB)) + length(CAST(requestId AS BLOB)) + COALESCE(length(CAST(chatId AS BLOB)), 0) + COALESCE(length(CAST(provider AS BLOB)), 0) + COALESCE(length(CAST(model AS BLOB)), 0) + length(CAST(createdAt AS BLOB)) AS identityBytes, length(CAST(usageJson AS BLOB)) AS usageBytes, COALESCE(length(CAST(costJson AS BLOB)), 0) AS costBytes FROM usage_events WHERE chatId = :chatId ORDER BY createdAt DESC, rowid DESC LIMIT :limit")
    suspend fun forChatBudgets(chatId: String, limit: Int): List<UsageRowBudget>

    @Query("SELECT * FROM usage_events WHERE chatId = :chatId ORDER BY createdAt DESC, rowid DESC LIMIT :limit")
    suspend fun forChat(chatId: String, limit: Int): List<UsageEventEntity>
}

@Dao
interface ContentRegexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ContentRegexRecordEntity)

    @Query("UPDATE content_regex_records SET orderIndex = orderIndex + :delta WHERE chatId = :chatId AND orderIndex >= :fromOrder")
    suspend fun shiftOrders(chatId: String, fromOrder: Long, delta: Long)

    @Query("UPDATE content_regex_records SET orderIndex = :orderIndex WHERE nodeId = :nodeId")
    suspend fun setNodeOrder(nodeId: String, orderIndex: Long)

    @Query("UPDATE content_regex_records SET nodeId = :nodeId, orderIndex = :orderIndex WHERE messageId = :messageId")
    suspend fun moveMessage(messageId: String, nodeId: String, orderIndex: Long)

    @Query("SELECT * FROM content_regex_records WHERE messageId = :messageId")
    suspend fun get(messageId: String): ContentRegexRecordEntity?

    @Query("SELECT * FROM content_regex_records WHERE chatId = :chatId AND queueStatus = 'pending' AND orderIndex <= :throughOrder ORDER BY orderIndex ASC, messageId ASC LIMIT :limit")
    suspend fun pending(chatId: String, throughOrder: Long, limit: Int): List<ContentRegexRecordEntity>

    @Query("SELECT * FROM content_regex_records WHERE chatId = :chatId ORDER BY orderIndex DESC, messageId DESC LIMIT :limit")
    suspend fun list(chatId: String, limit: Int): List<ContentRegexRecordEntity>

    @Query("DELETE FROM content_regex_records WHERE chatId = :chatId")
    suspend fun deleteForChat(chatId: String)

    @Query("DELETE FROM content_regex_records WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String)

    @Query("UPDATE content_regex_records SET queueStatus='stale', revision=revision+1, updatedAt=:at WHERE messageId=:messageId")
    suspend fun markStaleForMessage(messageId: String, at: String)

    @Query("DELETE FROM content_regex_records WHERE messageId IN (SELECT id FROM message_candidates WHERE nodeId=:nodeId)")
    suspend fun deleteForNode(nodeId: String)

    @Query("DELETE FROM content_regex_records WHERE messageId IN (SELECT id FROM message_candidates WHERE nodeId IN (:nodeIds))")
    suspend fun deleteForNodes(nodeIds: List<String>)
}
