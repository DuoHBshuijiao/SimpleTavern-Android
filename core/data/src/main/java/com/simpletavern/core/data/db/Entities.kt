package com.simpletavern.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chats", indices = [Index("updatedAt"), Index("sourceNamespace", "sourceId")])
data class ChatEntity(
    @PrimaryKey val id: String,
    val characterId: String,
    val title: String,
    val userPersonaId: String?,
    val isGroup: Boolean,
    val memberIdsJson: String,
    val memberSettingsJson: String,
    val groupDelayMs: Int,
    val groupSystemInjectDepth: Int,
    val groupSystemAlwaysAtBottom: Boolean,
    val overridesJson: String,
    val sandboxId: String?,
    val forkedFromChatId: String?,
    val forkedFromMessageId: String?,
    val forkedFromMessageIndex: Int?,
    val createdAt: String,
    val updatedAt: String,
    val sourceNamespace: String?,
    val sourceId: String?,
    val extraJson: String?,
)

@Entity(
    tableName = "message_nodes",
    indices = [Index("chatId", "orderIndex"), Index("chatId")],
)
data class MessageNodeEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val orderIndex: Long,
    val selectedIndex: Int,
    val parentNodeId: String?,
)

@Entity(
    tableName = "message_candidates",
    indices = [Index("nodeId"), Index("chatId")],
)
data class MessageCandidateEntity(
    @PrimaryKey val id: String,
    val nodeId: String,
    val chatId: String,
    val candidateIndex: Int,
    val role: String,
    val content: String,
    val reasoningContent: String?,
    val reasoningDurationSec: Double?,
    val imagesJson: String,
    val attachmentsJson: String,
    val characterId: String?,
    val senderPersonaId: String?,
    val senderName: String?,
    val senderAvatar: String?,
    val toolCallId: String?,
    val toolCallsJson: String?,
    val toolRecordJson: String?,
    val usageJson: String?,
    val generationMetadataJson: String?,
    val createdAt: String,
    val extraJson: String?,
)

@Entity(tableName = "characters", indices = [Index("sourceNamespace", "sourceId")])
data class CharacterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val personality: String,
    val scenario: String,
    val firstMessage: String,
    val exampleDialogue: String,
    val systemPrompt: String,
    val avatarRelativePath: String?,
    val attachedWorldBookIdsJson: String,
    val mvuEnabled: Boolean,
    val mvuMode: String,
    val mvuDirective: String?,
    val initialStateTablesJson: String?,
    val contentRegexRulesJson: String?,
    val createdAt: String,
    val updatedAt: String,
    val sourceNamespace: String?,
    val sourceId: String?,
    val extraJson: String?,
)

@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val avatarRelativePath: String?,
    val createdAt: String,
    val updatedAt: String,
    val sourceNamespace: String?,
    val sourceId: String?,
    val extraJson: String?,
)

@Entity(tableName = "world_books")
data class WorldBookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val entriesJson: String,
    val globalActive: Boolean,
    val sessionChatIdsJson: String,
    val createdAt: String,
    val updatedAt: String,
    val sourceNamespace: String?,
    val sourceId: String?,
    val extraJson: String?,
)

@Entity(tableName = "long_term_memories")
data class LongTermMemoryEntity(
    @PrimaryKey val chatId: String,
    val content: String,
    val version: Long,
    val anchorMessageId: String?,
    val updatedAt: String,
    val source: String,
)

@Entity(tableName = "memory_summary_jobs")
data class MemorySummaryJobEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val status: String,
    val coveredThroughMessageId: String?,
    val sourceMessageIdsJson: String,
    val resultText: String?,
    val failureReason: String?,
    val expectedMemoryVersion: Long?,
    val createdAt: String,
    val updatedAt: String,
)

@Entity(tableName = "mvu_states")
data class MvuStateEntity(
    @PrimaryKey val chatId: String,
    val version: Int,
    val updatedAt: String,
    val source: String,
    val tablesJson: String,
)

@Entity(tableName = "knowledge_graphs")
data class KnowledgeGraphEntity(
    @PrimaryKey val chatId: String,
    val entitiesJson: String,
    val relationsJson: String,
    val version: Int,
    val updatedAt: String,
    val source: String,
)

@Entity(tableName = "mvu_work_logs")
data class MvuWorkLogEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val timestamp: String,
    val eventType: String,
    val summary: String,
    val detailJson: String?,
)

@Entity(tableName = "attachments")
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val relativePath: String,
    val filename: String,
    val mimeType: String,
    val size: Long?,
    val chatId: String?,
    val messageCandidateId: String?,
    val sourceUri: String?,
    val createdAt: String,
)

@Entity(tableName = "assistants")
data class AssistantEntity(
    @PrimaryKey val id: String,
    val settingsJson: String,
    val updatedAt: String,
    val sourceNamespace: String?,
)

@Entity(tableName = "assistant_messages")
data class AssistantMessageEntity(
    @PrimaryKey val id: String,
    val assistantId: String,
    val orderIndex: Long,
    val payloadJson: String,
    val createdAt: String,
)

@Entity(tableName = "model_presets")
data class ModelPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val configJson: String,
    val createdAt: String,
    val updatedAt: String,
    val sourceNamespace: String?,
    val sourceId: String?,
)

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: String = "global",
    val settingsJson: String,
    val updatedAt: String,
)

@Entity(tableName = "import_batches")
data class ImportBatchEntity(
    @PrimaryKey val id: String,
    val source: String,
    val status: String,
    val reportJson: String?,
    val createdAt: String,
    val updatedAt: String,
)

@Entity(tableName = "id_maps", primaryKeys = ["namespace", "sourceId"])
data class IdMapEntity(
    val namespace: String,
    val sourceId: String,
    val targetId: String,
    val entityType: String,
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val status: String,
    val chatId: String?,
    val sandboxId: String?,
    val terminalReason: String?,
    val errorMessage: String?,
    val createdAt: String,
    val updatedAt: String,
    val resultSummary: String?,
    val fileChangeIdsJson: String,
    val incompleteFileChanges: Boolean,
    val payloadJson: String?,
)

@Entity(tableName = "approvals", indices = [Index("sessionId", "createdAt"), Index("status")])
data class ApprovalEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val sandboxId: String?,
    val taskId: String,
    val category: String,
    val toolName: String,
    val argumentsJson: String,
    val editPremiseJson: String?,
    val status: String,
    val createdAt: String,
    val decidedAt: String?,
)

@Entity(tableName = "file_changes", indices = [Index("sandboxId"), Index("taskId")])
data class FileChangeEntity(
    @PrimaryKey val id: String,
    val sandboxId: String,
    val taskId: String?,
    val path: String,
    val changeType: String,
    val observedAt: String,
    val beforeSummary: String?,
    val afterSummary: String?,
    val attribution: String,
    val incomplete: Boolean,
)

@Entity(tableName = "sandboxes")
data class SandboxEntity(
    @PrimaryKey val id: String,
    val name: String,
    val rootRelativePath: String,
    val createdAt: String,
    val updatedAt: String,
    val metaJson: String?,
)

@Entity(tableName = "usage_events", indices = [Index("eventId", unique = true), Index("chatId")])
data class UsageEventEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    val requestId: String,
    val chatId: String?,
    val provider: String?,
    val model: String?,
    val usageJson: String,
    val costJson: String?,
    val createdAt: String,
)

@Entity(tableName = "source_blobs")
data class SourceBlobEntity(
    @PrimaryKey val id: String,
    val namespace: String,
    val sourcePath: String,
    val contentJson: String,
    val importedAt: String,
)
