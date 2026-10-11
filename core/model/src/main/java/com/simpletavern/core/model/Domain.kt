package com.simpletavern.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
enum class ChatRole { system, user, assistant, tool, reasoning }

@Serializable
data class AttachmentRef(
    val id: String,
    val filename: String,
    val mimeType: String,
    val relativePath: String,
    val size: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val originalName: String? = null,
    val sourceUri: String? = null,
    val modelVisible: Boolean = true,
)

@Serializable
data class MessageCandidate(
    val id: String,
    val role: ChatRole,
    val content: String,
    val reasoningContent: String? = null,
    val reasoningDurationSec: Double? = null,
    val images: List<AttachmentRef> = emptyList(),
    val attachments: List<AttachmentRef> = emptyList(),
    val characterId: String? = null,
    val senderPersonaId: String? = null,
    val senderName: String? = null,
    val senderAvatar: String? = null,
    val toolCallId: String? = null,
    val toolCallsJson: String? = null,
    val toolRecordJson: String? = null,
    val usageJson: String? = null,
    val generationMetadataJson: String? = null,
    val createdAt: String,
    val extraJson: String? = null,
)

@Serializable
data class MessageNode(
    val id: String,
    val chatId: String,
    val orderIndex: Long,
    val candidates: List<MessageCandidate>,
    val selectedIndex: Int = 0,
    val parentNodeId: String? = null,
) {
    val selected: MessageCandidate?
        get() = candidates.getOrNull(selectedIndex)
}

@Serializable
data class GroupMemberSettings(
    val model: String? = null,
    val presetId: String? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val probability: Double = 1.0,
    val includePersonality: Boolean = true,
    val includeScenario: Boolean = true,
    val reasoningEffort: String? = null,
    val fastMode: Boolean? = null,
    val extraJson: String? = null,
)

@Serializable
data class WorldBookAttachment(
    val worldBookId: String,
    val scanDepth: Int? = null,
    val insertDepth: Int = 5,
)

@Serializable
data class ChatOverrides(
    val prompt: String? = null,
    val sessionSystemPromptMode: String = "append",
    val longTermMemory: String? = null,
    val contextStartMessageId: String? = null,
    val contextStartKeepBeforeMessages: Int? = null,
    val presetId: String? = null,
    val pureAiMode: Boolean? = null,
    val worldBookAttachments: List<WorldBookAttachment> = emptyList(),
    val worldBookGlobalExclusions: List<String> = emptyList(),
    val paramsJson: String? = null,
    val autoMemorySummaryEveryN: Int? = null,
    val lastAutoMemorySummaryAfterMessageId: String? = null,
    val autoMemorySummarySilent: Boolean = false,
    val autoMemorySummaryNextAskTier: Int = 1,
    val lastStateMaintenanceAfterMessageId: String? = null,
    val mvuMode: String? = null,
    val mvuDirective: String? = null,
    val groupMvuEnabled: Boolean? = null,
    val groupMvuAnchorCharacterId: String? = null,
    val knowledgeGraphEnabled: Boolean? = null,
    val knowledgeGraphInjectPosition: String? = null,
    val knowledgeGraphInjectDepth: Int = 5,
    val knowledgeGraphBeforeLastRole: String = "assistant",
    val ttsJson: String? = null,
    val contentRegexRulesJson: String? = null,
    val contentRegexScanDepthDefault: Int = 50,
    val contentRegexEnabledByRuleId: Map<String, Boolean> = emptyMap(),
    val extraJson: String? = null,
    val assistant: AssistantChatBinding? = null,
    val sessionSystemPromptEnabled: Boolean = true,
    val worldBookSelectionMode: String = "merge", // merge | character | session
    val contextMessageLimit: Int? = null, // null/0: no message-count limit; positive: stepped history cutoff
    val messageTemplate: String? = null,
    val groupMvuTemplateCharacterId: String? = null,
)

@Serializable
data class ChatCreationDefaults(
    val overrides: ChatOverrides = ChatOverrides(),
    val initialMessages: List<MessageCandidate> = emptyList(),
    val userPersonaId: String? = null,
)

@Serializable
data class ImportedAssistantMemory(val assistantSourceId: String, val content: String)

@Serializable
data class ImportedMemoryLibrary(val entries: Map<String, ImportedAssistantMemory> = emptyMap())

@Serializable
data class AssistantMemoryPool(val assistantId: String, val sourceNamespace: String, val ownerId: String, val enabled: Boolean)

@Serializable
data class AssistantMemoryRow(val id: String, val content: String)

@Serializable
data class AssistantMemoryPage(val pool: AssistantMemoryPool, val entries: List<AssistantMemoryRow>,
    val hasMore: Boolean, val nextAfterId: String? = null)

@Serializable
data class ChatSession(
    val id: String,
    val characterId: String,
    val title: String = "新对话",
    val userPersonaId: String? = null,
    val isGroup: Boolean = false,
    val memberIds: List<String> = emptyList(),
    val memberSettings: Map<String, GroupMemberSettings> = emptyMap(),
    val groupDelayMs: Int = 1500,
    val groupSystemInjectDepth: Int = 5,
    val groupSystemAlwaysAtBottom: Boolean = true,
    val overrides: ChatOverrides = ChatOverrides(),
    val sandboxId: String? = null,
    val forkedFromChatId: String? = null,
    val forkedFromMessageId: String? = null,
    val forkedFromMessageIndex: Int? = null,
    val createdAt: String,
    val updatedAt: String,
    val sourceNamespace: String? = null,
    val sourceId: String? = null,
    val extraJson: String? = null,
)

@Serializable
data class CharacterCard(
    val id: String,
    val name: String = "新角色",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val exampleDialogue: String = "",
    val systemPrompt: String = "",
    val avatarRelativePath: String? = null,
    val attachedWorldBookIds: List<String> = emptyList(),
    val mvuEnabled: Boolean = false,
    val mvuMode: String = "regex",
    val mvuDirective: String? = null,
    val initialStateTablesJson: String? = null,
    val contentRegexRulesJson: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val sourceNamespace: String? = null,
    val sourceId: String? = null,
    val extraJson: String? = null,
)

@Serializable
data class UserPersona(
    val id: String,
    val name: String,
    val description: String = "",
    val avatarRelativePath: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val sourceNamespace: String? = null,
    val sourceId: String? = null,
    val extraJson: String? = null,
)

@Serializable
data class WorldBookEntry(
    val id: String,
    val title: String = "",
    val regex: String = "",
    val content: String = "",
    val enabled: Boolean = true,
    val orderIndex: Int = 0,
    val scanDepth: Int? = null, // null keeps the desktop book/session scan policy
    val constantActive: Boolean = false,
    val priority: Int = 0,
    val position: String? = null, // null keeps the desktop book/session insertion policy
    val injectDepth: Int = 4,
    val role: ChatRole = ChatRole.system,
)

@Serializable
data class WorldBook(
    val id: String,
    val name: String,
    val entries: List<WorldBookEntry> = emptyList(),
    val globalActive: Boolean = false,
    val sessionChatIds: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
    val sourceNamespace: String? = null,
    val sourceId: String? = null,
    val extraJson: String? = null,
)

@Serializable
data class LongTermMemory(
    val chatId: String,
    val content: String,
    val version: Long = 1,
    val anchorMessageId: String? = null,
    val updatedAt: String,
    val source: String = "user",
)

@Serializable
data class MemorySummaryJob(
    val id: String,
    val chatId: String,
    val status: TaskStatus,
    val coveredThroughMessageId: String? = null,
    val sourceMessageIds: List<String> = emptyList(),
    val resultText: String? = null,
    val failureReason: String? = null,
    val expectedMemoryVersion: Long? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class SelectedMessage(val nodeId: String, val orderIndex: Long, val candidate: MessageCandidate)

@Serializable
data class StatusTableRow(val field: String, val cells: Map<String, String> = emptyMap())

@Serializable
data class StatusTableDef(
    val name: String,
    val columns: List<String> = emptyList(),
    val rows: List<StatusTableRow> = emptyList(),
)

@Serializable
data class MvuState(
    val chatId: String,
    val version: Int = 1,
    val updatedAt: String = "",
    val source: String = "mvu_agent",
    val tables: List<StatusTableDef> = emptyList(),
)

@Serializable
data class KgEntity(
    val id: String,
    val name: String,
    val type: String,
    val properties: Map<String, String> = emptyMap(),
    val firstMentionedAt: String? = null,
    val deleted: Boolean = false,
)

@Serializable
data class KgRelation(
    val subject: String,
    val predicate: String,
    val `object`: String,
    val establishedAt: String? = null,
    val confidence: Double = 1.0,
)

@Serializable
data class KnowledgeGraph(
    val chatId: String,
    val entities: List<KgEntity> = emptyList(),
    val relations: List<KgRelation> = emptyList(),
    val version: Int = 0,
    val updatedAt: String = "",
    val source: String = "mvu_agent",
)

@Serializable
data class AssistantConfig(
    val id: String = "default",
    val settingsJson: String,
    val updatedAt: String,
    val sourceNamespace: String? = null,
)

@Serializable
data class ModelPreset(
    val id: String,
    val name: String,
    val configJson: String,
    val createdAt: String,
    val updatedAt: String,
    val sourceNamespace: String? = null,
    val sourceId: String? = null,
)

@Serializable
data class ProviderCredential(
    val id: String,
    val provider: String,
    val label: String,
    val baseUrl: String? = null,
    /** Never logged; stored via encrypted prefs / keystore wrapper. */
    val hasSecret: Boolean = false,
    val extraJson: String? = null,
)

@Serializable
enum class LlmProtocol {
    OPENAI_COMPATIBLE,
    OPENAI_RESPONSES,
    ANTHROPIC_MESSAGES,
    GEMINI_GENERATE_CONTENT,
    BEDROCK_CONVERSE,
}

@Serializable
data class UsageTokens(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val cacheReadInputTokens: Long? = null,
    val cacheWriteInputTokens: Long? = null,
    val serviceTier: String? = null,
    val fastRequested: Boolean? = null,
    val requestedServiceTier: String? = null,
    val inputTokensIncludeCache: Boolean? = null,
    val outputTokensIncludeReasoning: Boolean? = null,
    val cacheWriteShortTokens: Long? = null,
    val cacheWriteLongTokens: Long? = null,
    val resolvedModel: String? = null,
    val providerCost: ProviderReportedCost? = null,
    val pricingWarnings: List<String> = emptyList(),
)

@Serializable
data class GenerationMetadata(
    val version: Int = 1,
    val requestId: String,
    val chatId: String? = null,
    val messageId: String? = null,
    val provider: String? = null,
    val protocol: String? = null,
    val requestedModel: String? = null,
    val resolvedModel: String? = null,
    val startedAt: String? = null,
    val status: String? = null,
    val firstTokenLatencyMs: Long? = null,
    val totalDurationMs: Long? = null,
    val usage: UsageTokens? = null,
    val providerTurn: ProviderTurn? = null,
    val cost: CostSnapshot? = null,
    val search: NativeSearchTrace? = null,
    val webSearchMode: String? = null,
    val webSearchProvider: String? = null,
    val fileTransfers: List<ProviderFileTransfer> = emptyList(),
)

/** Completed native assistant output, including opaque reasoning state; never executable by itself. */
@Serializable
data class ProviderTurn(val protocol: LlmProtocol, val model: String, val contentJson: String,
    val mediaAttachmentIds: List<String> = emptyList())

@Serializable
sealed class StreamEvent {
    @Serializable
    data class Delta(val text: String, val reasoning: Boolean = false) : StreamEvent()

    @Serializable
    data class ToolCall(
        val callId: String,
        val name: String,
        val argumentsJson: String,
    ) : StreamEvent()

    @Serializable
    data class Usage(val usage: UsageTokens) : StreamEvent()

    @Serializable
    data class Done(
        val ok: Boolean,
        val assistantMessageId: String? = null,
        val reasoningContent: String? = null,
        val usage: UsageTokens? = null,
        val metadata: GenerationMetadata? = null,
        val providerTurn: ProviderTurn? = null,
        val search: NativeSearchTrace? = null,
        val continuation: Boolean = false,
        @kotlinx.serialization.Transient val media: List<ToolMedia> = emptyList(),
    ) : StreamEvent()

    @Serializable
    data class Error(val code: String, val message: String) : StreamEvent()
}

@Serializable
data class ContextBuildResult(
    val messages: List<ContextMessage>,
    val injectedMemory: Boolean,
    val injectedWorldBookIds: List<String>,
    val injectedMvu: Boolean,
    val injectedKg: Boolean,
    val tokenEstimate: Int?,
    val truncationNotes: List<String> = emptyList(),
    val diagnosticRedacted: String,
)

@Serializable
data class ContextMessage(
    val role: ChatRole,
    val content: String,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCallsJson: String? = null,
    val providerTurn: ProviderTurn? = null,
    val attachments: List<AttachmentRef> = emptyList(),
    @kotlinx.serialization.Transient val media: List<ContextMedia> = emptyList(),
    @kotlinx.serialization.Transient val sourceCreatedAt: String? = null,
    @kotlinx.serialization.Transient val sourceSenderName: String? = null,
)

/** Request-local file contents; never serialized into task records, messages or backups. */
data class ContextMedia(val filename: String, val mimeType: String, val base64: String? = null, val text: String? = null,
    val byteSize: Int = 0, val sourceMimeType: String? = null,
    val attachmentId: String? = null, val uploadFile: java.io.File? = null, val providerFile: String? = null) {
    override fun toString() = "ContextMedia(<private>)"
}

/** File-operation status only; no upload URLs, local paths, credentials or reusable remote handles. */
@Serializable
data class ProviderFileTransfer(val attachmentId: String?, val filename: String, val bytes: Long,
    val status: String, val cleanupHttpCode: Int? = null, val reuseCount: Int = 0, val retainedUntil: String? = null)

@Serializable
data class ForkLineage(
    val originChatId: String?,
    val originMessageId: String?,
    val originMessageIndex: Int?,
    val siblings: List<ChatSessionSummary> = emptyList(),
)

@Serializable
data class ChatSessionSummary(
    val id: String,
    val title: String,
    val createdAt: String,
)

@Serializable
enum class FileChangeType { CREATED, MODIFIED, DELETED, RENAMED, UNKNOWN }

@Serializable
data class FileChangeRecord(
    val id: String,
    val sandboxId: String,
    val taskId: String?,
    val path: String,
    val changeType: FileChangeType,
    val observedAt: String,
    val beforeSummary: String? = null,
    val afterSummary: String? = null,
    val attribution: String = "confirmed",
    val incomplete: Boolean = false,
)

@Serializable
enum class ApprovalCategory { SHELL, WRITE, EDIT, READ }

@Serializable
enum class ApprovalDecision { PENDING, APPROVED, REJECTED, EXPIRED, SUPERSEDED }

@Serializable
data class ApprovalPolicy(
    val mode: String = "custom", // auto | custom
    val shellRequiresApproval: Boolean = true,
    val writeRequiresApproval: Boolean = true,
    val editRequiresApproval: Boolean = true,
)

@Serializable
data class ApprovalRequest(
    val id: String,
    val sessionId: String,
    val sandboxId: String?,
    val taskId: String,
    val category: ApprovalCategory,
    val toolName: String,
    val argumentsJson: String,
    // File calls carry a paged exact-byte premise manifest; full bytes are live-only, not a replay handle.
    val editPremiseJson: String? = null,
    val status: ApprovalDecision = ApprovalDecision.PENDING,
    val createdAt: String,
    val decidedAt: String? = null,
)

@Serializable
data class TaskSnapshot(
    val id: String,
    val kind: TaskKind,
    val status: TaskStatus,
    val chatId: String? = null,
    val sandboxId: String? = null,
    val terminalReason: TaskTerminalReason? = null,
    val errorMessage: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val resultSummary: String? = null,
    val fileChangeIds: List<String> = emptyList(),
    val incompleteFileChanges: Boolean = false,
)

@Serializable
data class ImportReportItem(
    val kind: String,
    val sourceId: String?,
    val targetId: String?,
    val status: String, // converted | retained | missing | failed | deferred
    val message: String? = null,
)

@Serializable
data class ImportReport(
    val batchId: String,
    val source: String,
    val items: List<ImportReportItem>,
    val success: Boolean,
    val cancelled: Boolean = false,
)

@Serializable
data class BackupOptions(
    val includeDatabase: Boolean = true,
    val includeAttachments: Boolean = true,
    val includeMemory: Boolean = true,
    val includeConfig: Boolean = true,
    val includeSandboxUserFiles: Boolean = true,
    val includeRebuildableRuntimes: Boolean = false,
    val includeCredentials: Boolean = false,
)

@Serializable
data class JsonBag(val raw: JsonObject)
