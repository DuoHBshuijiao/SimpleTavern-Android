package com.simpletavern.core.api

import kotlinx.serialization.json.*

import android.content.Context
import android.net.Uri
import com.simpletavern.core.conversation.ConversationService
import com.simpletavern.core.data.*
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.importer.ImportService
import com.simpletavern.core.importer.PreflightResult
import com.simpletavern.core.llm.LlmClient
import com.simpletavern.core.llm.ProviderModelClient
import com.simpletavern.core.llm.ProviderConfig
import com.simpletavern.core.memory.ContextBuilder
import com.simpletavern.core.memory.MemoryService
import com.simpletavern.core.model.*
import com.simpletavern.core.tools.ToolRuntime
import com.simpletavern.core.tools.SandboxTaskService
import com.simpletavern.core.tools.WebSearchService
import com.simpletavern.core.tools.SpeechService
import com.simpletavern.runtime.sandbox.SandboxManager
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

/**
 * Public business facade for future Compose UI (Codex).
 * UI must call this API only — never Room tables or sandbox paths directly.
 *
 * Threading: suspend functions are main-safe; heavy IO uses Dispatchers.IO internally.
 * Streaming does not depend on UI subscribers. Each chat replays its latest 256 events;
 * older text is read from persisted messages. Start of a new generation clears the replay.
 */
class SimpleTavernCore private constructor(
    private val db: AppDatabase,
    val paths: StoragePaths,
    val chats: ChatRepository,
    val content: ContentRepository,
    val memoryStore: MemoryStore,
    val memory: MemoryService,
    val contextBuilder: ContextBuilder,
    val conversation: ConversationService,
    val importer: ImportService,
    val sandbox: SandboxManager,
    val tools: ToolRuntime,
    val tasks: TaskStore,
    val approvals: ApprovalStore,
    val fileChanges: FileChangeStore,
    val scope: CoroutineScope,
    val taskHost: ForegroundTaskHost,
    private val sandboxTasks: SandboxTaskService,
    private val credentials: CredentialStore,
    private val providers: ProviderResolver,
    private val dataGate: DataOperationGate,
    private val nativeBackup: NativeBackupService,
    private val backupTasks: BackupTaskService,
    private val restoreTasks: RestoreTaskService,
    private val importTasks: ImportTaskService,
    private val copyTasks: FileCopyTaskService,
    private val exportTasks: SandboxExportTaskService,
    private val usageLedger: UsageStore,
    private val searchResolver: WebSearchResolver,
    private val searchUsage: SearchUsageStore,
    private val speechUsage: SpeechUsageStore,
    private val speech: SpeechResolver,
    private val speechTasks: SpeechTaskService,
    private val speechCache: SpeechCacheService,
    private val geminiCacheTasks: GeminiCacheTaskService,
    private val geminiCachePrices: GeminiCachePriceStore,
    private val chatAttachments: ChatAttachmentService,
    private val assistants: AssistantService,
    private val providerOAuth: ProviderOAuthService,
    private val workspace: AssistantWorkspaceService,
    private val mcp: AssistantMcpService,
    private val skills: AssistantSkillService,
    private val deviceTools: AssistantDeviceTools,
    private val speechPlayback: SpeechPlaybackService,
    private val mcpPromptApplications: McpPromptApplicationService,
    private val skillExporter: AssistantSkillExporter,
    private val voiceDesignTasks: SpeechVoiceDesignTaskService,
) {
    private lateinit var initialization: Deferred<Unit>
    private val contentLibrary = ContentLibraryService(content, chats)
    private val assistantConversations = AssistantConversationService(content, chats)
    private val providerModels = ProviderModelClient()
    private val modelCatalogRefresh = ModelCatalogRefreshClient()

    suspend fun getDeviceToolPermissions(): DeviceToolPermissions {
        onProcessStart()
        return deviceTools.permissions()
    }

    suspend fun listAssistantSkills(assistantId: String): List<AssistantSkillInfo> = skills.list(assistantId)
    suspend fun exportAssistantSkill(assistantId: String, directory: String, destination: Uri): Uri {
        onProcessStart()
        return skillExporter.export(assistantId, directory, destination)
    }
    /** Installs documents/assets without executing scripts or changing the enabled-name selection. */
    suspend fun installAssistantSkill(assistantId: String, archive: File, options: SkillInstallOptions = SkillInstallOptions()): SkillInstallResult {
        onProcessStart(); return skills.install(assistantId, archive, options)
    }
    suspend fun installAssistantSkill(assistantId: String, archive: Uri, options: SkillInstallOptions = SkillInstallOptions()): SkillInstallResult {
        onProcessStart(); return skills.install(assistantId, archive, options)
    }
    /** Explicit public GitHub archive download; no account credentials or repository code execution. */
    suspend fun installAssistantSkillFromGitHub(assistantId: String, source: GitHubSkillSource, options: SkillInstallOptions = SkillInstallOptions()): SkillInstallResult {
        onProcessStart(); return skills.install(assistantId, source, options)
    }
    suspend fun listAssistantSkillFiles(assistantId: String, directory: String): List<WorkspaceFileInfo> = skills.files(assistantId, directory)
    suspend fun readAssistantSkillFile(assistantId: String, directory: String, path: String = "SKILL.md"): SkillFile = skills.read(assistantId, directory, path)
    suspend fun writeAssistantSkillFile(assistantId: String, directory: String, path: String, text: String, expectedFile: SkillFile? = null): SkillFile =
        writeData { skills.write(assistantId, directory, path, text, expectedFile) }
    suspend fun linkAssistantSkillAsset(assistantId: String, directory: String, path: String, attachmentId: String, expectedFile: SkillFile? = null): SkillFile =
        writeData { skills.link(assistantId, directory, path, attachmentId, expectedFile) }
    suspend fun deleteAssistantSkillFile(assistantId: String, directory: String, expectedFile: SkillFile) = writeData { skills.deleteFile(assistantId, directory, expectedFile) }
    suspend fun deleteAssistantSkill(assistantId: String, directory: String, expectedSkill: SkillDefinition) = writeData { skills.delete(assistantId, directory, expectedSkill) }
    suspend fun setAssistantEnabledSkills(assistantId: String, names: Set<String>, expectedUpdatedAt: String): AssistantConfig =
        writeData { skills.enable(assistantId, names, expectedUpdatedAt) }

    suspend fun getAssistantMcpServers(assistantId: String): List<McpServerConfiguration> = mcp.servers(assistantId)
    suspend fun getAssistantMcpConfiguration(assistantId: String): McpConfiguration = mcp.view(assistantId)
    suspend fun configureAssistantMcpServers(assistantId: String, servers: List<McpServerConfiguration>, selectedIds: Set<String>, expectedUpdatedAt: String): AssistantConfig =
        writeData { mcp.configure(assistantId, servers, selectedIds, expectedUpdatedAt) }
    /** Explicit network discovery; does not call an MCP tool or run a model. */
    suspend fun listAssistantMcpTools(assistantId: String): List<McpToolDescriptor> = mcp.listTools(assistantId)
    suspend fun getAssistantMcpCapabilities(assistantId: String, serverId: String): McpServerCapabilities {
        onProcessStart(); return mcp.capabilities(assistantId, serverId)
    }
    fun observeAssistantMcpNotifications(assistantId: String, serverId: String,
        resourceUris: List<String> = emptyList()): Flow<McpNotificationEvent> = flow {
        onProcessStart()
        emitAll(mcp.observeNotifications(assistantId, serverId, resourceUris))
    }
    suspend fun listAssistantMcpResources(assistantId: String, serverId: String): List<McpResourceDescriptor> {
        onProcessStart(); return mcp.listResources(assistantId, serverId)
    }
    suspend fun listAssistantMcpResourceTemplates(assistantId: String, serverId: String): List<McpResourceTemplateDescriptor> {
        onProcessStart(); return mcp.listResourceTemplates(assistantId, serverId)
    }
    suspend fun readAssistantMcpResource(assistantId: String, serverId: String, uri: String): McpResourceResult {
        onProcessStart(); return mcp.readResource(assistantId, serverId, uri)
    }
    /** Pure expansion; URI schemes do not imply Android filesystem or network access. */
    suspend fun expandMcpResourceTemplate(uriTemplate: String, variables: kotlinx.serialization.json.JsonObject): String =
        withContext(Dispatchers.Default) { com.simpletavern.core.tools.McpUriTemplate.expand(uriTemplate, variables) }
    suspend fun readAssistantMcpResourceTemplate(assistantId: String, serverId: String, uriTemplate: String,
        variables: kotlinx.serialization.json.JsonObject): McpResourceResult =
        readAssistantMcpResource(assistantId, serverId, expandMcpResourceTemplate(uriTemplate, variables))
    suspend fun listAssistantMcpPrompts(assistantId: String, serverId: String): List<McpPromptDescriptor> {
        onProcessStart(); return mcp.listPrompts(assistantId, serverId)
    }
    /** Retrieve a selected template as a draft; does not start generation or modify chat history. */
    suspend fun getAssistantMcpPrompt(assistantId: String, serverId: String, name: String,
        arguments: Map<String, String> = emptyMap()): McpPromptResult {
        onProcessStart(); return mcp.getPrompt(assistantId, serverId, name, arguments)
    }
    /** Append the explicitly selected draft as ordinary history; never starts generation. */
    suspend fun applyAssistantMcpPrompt(chatId: String, draft: McpPromptResult, expectedUpdatedAt: String,
                                       characterId: String? = null): McpPromptApplication {
        onProcessStart()
        return mcpPromptApplications.apply(chatId, draft, expectedUpdatedAt, characterId)
    }
    suspend fun completeAssistantMcpArgument(assistantId: String, serverId: String, reference: McpCompletionReference,
        argument: String, value: String, context: Map<String, String> = emptyMap()): McpCompletion {
        onProcessStart(); return mcp.complete(assistantId, serverId, reference, argument, value, context)
    }
    suspend fun setAssistantMcpHeaderCredential(assistantId: String, serverId: String, name: String, value: String?) =
        writeData { mcp.setHeader(assistantId, serverId, name, value) }
    suspend fun setAssistantMcpEnvironmentCredential(assistantId: String, serverId: String, name: String, value: String?) =
        writeData { mcp.setEnvironment(assistantId, serverId, name, value) }

    /** Explicit metadata discovery, with no token disclosure or MCP tool invocation. */
    suspend fun discoverAssistantMcpOAuth(assistantId: String, serverId: String): McpOAuthDiscovery {
        onProcessStart(); return mcp.discoverOAuth(assistantId, serverId)
    }
    /** Returns the URL for the host to open. Complete with the full browser callback URL. */
    suspend fun startAssistantMcpOAuth(assistantId: String, serverId: String, scopes: List<String>? = null): McpBrowserLogin {
        onProcessStart(); return mcp.startOAuth(assistantId, serverId, scopes)
    }
    suspend fun completeAssistantMcpOAuth(loginId: String, callbackUrl: String): McpOAuthStatus {
        onProcessStart(); return mcp.completeOAuth(loginId, callbackUrl)
    }
    suspend fun cancelAssistantMcpOAuth(loginId: String) { onProcessStart(); mcp.cancelOAuth(loginId) }
    suspend fun getAssistantMcpOAuthStatus(assistantId: String, serverId: String): McpOAuthStatus {
        onProcessStart(); return mcp.oauthStatus(assistantId, serverId)
    }
    suspend fun refreshAssistantMcpOAuth(assistantId: String, serverId: String): McpOAuthStatus {
        onProcessStart(); return mcp.refreshOAuth(assistantId, serverId)
    }
    suspend fun logoutAssistantMcpOAuth(assistantId: String, serverId: String) {
        onProcessStart(); mcp.logoutOAuth(assistantId, serverId)
    }

    // ---- config & content ----
    suspend fun listCharacters() = content.listCharacters()
    suspend fun getCharacter(id: String) = content.getCharacter(id) ?: throw StError.NotFound(id)
    suspend fun saveCharacter(c: CharacterCard) = writeData { content.saveCharacter(c) }
    suspend fun deleteCharacter(id: String, expectedUpdatedAt: String, deleteRelatedChats: Boolean = true): List<String> =
        writeData { content.deleteCharacter(id, expectedUpdatedAt, deleteRelatedChats) }
    suspend fun listPersonas() = content.listPersonas()
    suspend fun getPersona(id: String) = content.getPersona(id) ?: throw StError.NotFound(id)
    suspend fun savePersona(p: UserPersona) = writeData { content.savePersona(p) }
    suspend fun deletePersona(id: String, expectedUpdatedAt: String) = writeData { content.deletePersona(id, expectedUpdatedAt) }

    /** Read once; preview writes no card, book or attachment. The caller owns the draft. */
    suspend fun previewCharacterCardImport(source: Uri): CharacterCardImportDraft {
        onProcessStart()
        val bytes = chatAttachments.readCharacterCardUri(source)
        return withContext(Dispatchers.Default) {
            val decoded = com.simpletavern.core.importer.SillyTavernCardDecoder.decode(bytes)
            val preview = com.simpletavern.core.importer.SillyTavernCardMapper.map(decoded)
            CharacterCardImportDraft(preview, bytes.takeIf { decoded.sourceFormat == "png" })
        }
    }
    /** Each explicit commit adds an independent local card, never overwriting Desktop/Rikka records. */
    suspend fun commitCharacterCardImport(draft: CharacterCardImportDraft,
        character: CharacterCard = draft.preview.character, worldBook: WorldBook? = draft.preview.worldBook,
        avatarFilename: String = "character.png"): CharacterCardImportResult = writeData {
        val at = Instant.now().toString()
        val book = worldBook?.copy(id = UUID.randomUUID().toString(),
            entries = worldBook.entries.map { it.copy(id = UUID.randomUUID().toString()) },
            globalActive = false, sessionChatIds = emptyList(), createdAt = at, updatedAt = at, sourceNamespace = null, sourceId = null)
        val card = character.copy(id = UUID.randomUUID().toString(), avatarRelativePath = null,
            attachedWorldBookIds = listOfNotNull(book?.id), createdAt = at, updatedAt = at, sourceNamespace = null, sourceId = null)
        val png = draft.pngBytes
        if (png != null) {
            val avatar = chatAttachments.importCharacterCardAvatar(png, avatarFilename) { ref ->
                book?.let { content.saveWorldBook(it) }
                content.saveCharacter(card.copy(avatarRelativePath = ref.relativePath))
            }
            CharacterCardImportResult(card.copy(avatarRelativePath = avatar.relativePath), book, avatar, draft.preview.warnings)
        } else content.transaction {
            book?.let { content.saveWorldBook(it) }
            content.saveCharacter(card)
            CharacterCardImportResult(card, book, warnings = draft.preview.warnings)
        }
    }
    suspend fun importCharacterCard(source: Uri, avatarFilename: String = "character.png"): CharacterCardImportResult =
        commitCharacterCardImport(previewCharacterCardImport(source), avatarFilename = avatarFilename)
    suspend fun characterOpeningMessages(characterId: String): List<String> = withContext(Dispatchers.Default) {
        content.characterOpeningMessages(getCharacter(characterId))
    }

    /** Copy a user-selected image and publish its binding in the attachment transaction. */
    suspend fun setCharacterAvatar(characterId: String, source: Uri, filename: String, mimeType: String): AttachmentRef = writeData {
        val previous = getCharacter(characterId).avatarRelativePath
        chatAttachments.importAvatarUri(source, filename, mimeType) { ref ->
            val current = getCharacter(characterId)
            if (current.avatarRelativePath != previous) throw StError.Conflict("character avatar changed while copying")
            content.saveCharacter(current.copy(avatarRelativePath = ref.relativePath, updatedAt = Instant.now().toString()))
        }
    }
    suspend fun setPersonaAvatar(personaId: String, source: Uri, filename: String, mimeType: String): AttachmentRef = writeData {
        val previous = getPersona(personaId).avatarRelativePath
        chatAttachments.importAvatarUri(source, filename, mimeType) { ref ->
            val current = getPersona(personaId)
            if (current.avatarRelativePath != previous) throw StError.Conflict("persona avatar changed while copying")
            content.savePersona(current.copy(avatarRelativePath = ref.relativePath, updatedAt = Instant.now().toString()))
        }
    }
    /** Unbind only: other cards and historical messages can still reference the same image. */
    suspend fun clearCharacterAvatar(characterId: String): CharacterCard = writeData {
        content.transaction {
            val current = getCharacter(characterId)
            val next = current.copy(avatarRelativePath = null, updatedAt = Instant.now().toString())
            content.saveCharacter(next)
            next
        }
    }
    suspend fun clearPersonaAvatar(personaId: String): UserPersona = writeData {
        content.transaction {
            val current = getPersona(personaId)
            val next = current.copy(avatarRelativePath = null, updatedAt = Instant.now().toString())
            content.savePersona(next)
            next
        }
    }
    suspend fun getAvatarAttachment(relativePath: String): AttachmentRef = writeData { chatAttachments.avatarReference(relativePath) }
    /** App-local image-loader input, not an external mount or a lifetime-pinned file handle. */
    suspend fun getAvatarFile(relativePath: String): File = writeData { chatAttachments.avatarFile(relativePath) }
    suspend fun listWorldBooks() = content.listWorldBooks()
    suspend fun getWorldBook(id: String) = content.getWorldBook(id) ?: throw StError.NotFound(id)
    suspend fun saveWorldBook(b: WorldBook) = writeData { content.saveWorldBook(b) }
    suspend fun listAssistantWorkspaceFiles(assistantId: String, afterPath: String = "", limit: Int = 100) = workspace.list(assistantId, afterPath, limit)
    suspend fun listAssistantWorkspaceAssets(assistantId: String, afterPath: String = "", limit: Int = 100) = workspace.listAssets(assistantId, afterPath, limit)
    suspend fun getAssistantWorkspaceAsset(assistantId: String, path: String) = workspace.asset(assistantId, path)
    suspend fun linkAssistantWorkspaceAsset(assistantId: String, path: String, attachmentId: String, expectedAttachmentId: String? = null) =
        writeData { workspace.linkAsset(assistantId, path, attachmentId, expectedAttachmentId) }
    suspend fun deleteAssistantWorkspaceAsset(assistantId: String, path: String, expectedAttachmentId: String) =
        writeData { workspace.deleteAsset(assistantId, path, expectedAttachmentId) }
    suspend fun readAssistantWorkspaceFile(assistantId: String, path: String) = workspace.read(assistantId, path)
    suspend fun writeAssistantWorkspaceFile(assistantId: String, path: String, text: String, expectedContent: String?) =
        writeData { workspace.write(assistantId, path, text, expectedContent) }
    suspend fun deleteAssistantWorkspaceFile(assistantId: String, path: String, expectedContent: String) =
        writeData { workspace.delete(assistantId, path, expectedContent) }
    suspend fun beginWorkspaceCharacterCard(assistantId: String, characterId: String? = null, expectedContent: String? = null) =
        writeData { workspace.beginCard(assistantId, characterId, expectedContent) }
    suspend fun patchWorkspaceCharacterCard(assistantId: String, patch: kotlinx.serialization.json.JsonObject, expectedContent: String) =
        writeData { workspace.patchCard(assistantId, patch, expectedContent) }
    suspend fun replaceWorkspaceCharacterCard(assistantId: String, card: kotlinx.serialization.json.JsonObject, expectedContent: String?) =
        writeData { workspace.replaceCard(assistantId, card, expectedContent) }
    suspend fun publishWorkspaceCharacterCard(assistantId: String, expectedContent: String, targetCharacterId: String? = null, expectedUpdatedAt: String? = null) =
        writeData { workspace.publishCard(assistantId, expectedContent, targetCharacterId, expectedUpdatedAt) }
    suspend fun deleteWorldBook(id: String, expectedUpdatedAt: String) = writeData { content.deleteWorldBook(id, expectedUpdatedAt) }
    suspend fun activeWorldBooks(chatId: String): List<WorldBook> {
        val chat = chats.getSession(chatId) ?: throw StError.NotFound(chatId)
        return content.activeWorldBooks(chat, content.getCharacter(chat.characterId))
    }
    suspend fun setWorldBookGlobalExclusion(chatId: String, worldBookId: String, excluded: Boolean, expectedUpdatedAt: String) =
        writeData { contentLibrary.setGlobalExclusion(chatId, worldBookId, excluded, expectedUpdatedAt) }
    suspend fun setWorldBookSelectionMode(chatId: String, mode: String, expectedUpdatedAt: String) =
        writeData { contentLibrary.setSelectionMode(chatId, mode, expectedUpdatedAt) }
    suspend fun attachWorldBook(chatId: String, attachment: WorldBookAttachment, expectedUpdatedAt: String) =
        writeData { contentLibrary.attach(chatId, attachment, expectedUpdatedAt) }
    suspend fun updateWorldBookAttachment(chatId: String, attachment: WorldBookAttachment, expectedUpdatedAt: String) =
        writeData { contentLibrary.updateAttachment(chatId, attachment, expectedUpdatedAt) }
    suspend fun detachWorldBook(chatId: String, worldBookId: String, expectedUpdatedAt: String) =
        writeData { contentLibrary.detach(chatId, worldBookId, expectedUpdatedAt) }
    suspend fun reorderWorldBookAttachments(chatId: String, orderedIds: List<String>, expectedUpdatedAt: String) =
        writeData { contentLibrary.reorder(chatId, orderedIds, expectedUpdatedAt) }
    suspend fun getSettingsJson() = content.getSettingsJson()
    suspend fun saveSettingsJson(json: String) = writeData { content.saveSettingsJson(json, Instant.now().toString()) }
    suspend fun getPricingRules(): PricingSnapshot { onProcessStart(); return usageLedger.pricingRules() }
    suspend fun replacePricingRules(rules: List<PricingRule>, expected: PricingVersion) = writeData {
        usageLedger.replacePricingRules(rules, expected)
    }
    suspend fun installPricingCatalog(rules: List<PricingRule>, expected: PricingVersion) = writeData {
        usageLedger.replacePricingRules(rules, expected, catalog = true)
    }
    suspend fun estimateUsageCost(tokens: UsageTokens, provider: String?, model: String?, at: String = Instant.now().toString()): CostSnapshot {
        onProcessStart(); return usageLedger.estimate(tokens, provider, model, at)
    }
    suspend fun usageEventsPage(filter: UsageFilter = UsageFilter(), cursor: UsageCursor? = null, limit: Int = 100,
                               throughSequence: Long? = null): UsagePage {
        onProcessStart(); return usageLedger.page(filter, cursor, limit, throughSequence)
    }
    suspend fun usageSummary(filter: UsageFilter = UsageFilter()): UsageSummary {
        onProcessStart(); return usageLedger.summary(filter)
    }
    suspend fun getSearchPricingRules(): SearchPricingSnapshot { onProcessStart(); return dataGate.write { searchUsage.pricing() } }
    suspend fun replaceSearchPricingRules(rules: List<SearchPricingRule>, expected: PricingVersion) = writeData { searchUsage.replacePricing(rules, expected) }
    suspend fun installSearchPricingCatalog(rules: List<SearchPricingRule>, expected: PricingVersion) = writeData { searchUsage.replacePricing(rules, expected, catalog = true) }
    suspend fun getWebSearchConfiguration(assistantId: String? = null): WebSearchConfigurationSnapshot {
        onProcessStart(); return dataGate.write { searchResolver.get(assistantId) }
    }
    suspend fun saveWebSearchConfiguration(configuration: WebSearchConfiguration, expected: WebSearchVersion, assistantId: String? = null) = writeData {
        searchResolver.save(configuration, expected, assistantId)
    }
    /** Offline by default. Explicit refresh queries configured accounts outside Room/data locks. */
    suspend fun getWebSearchStatus(assistantId: String? = null, fetchAccounts: Boolean = false): WebSearchStatus {
        onProcessStart()
        val prepared = dataGate.write { searchResolver.prepareStatus(assistantId, fetchAccounts) }
        return prepared.resolve()
    }
    /** Caller-owned explicit search; generation searches instead run under their existing task host. */
    suspend fun webSearch(query: String, assistantId: String? = null): WebSearchResult {
        onProcessStart()
        val prepared = dataGate.write { searchResolver.prepare(assistantId) }
        return prepared.search(query)
    }
    suspend fun getAssistant() = content.getAssistant()
    suspend fun previewSpeech(request: SpeechRequest): SpeechSelection {
        onProcessStart(); return dataGate.write { speech.preview(request) }
    }
    /** Offline by default; explicit catalog reads do not synthesize, select or persist anything. */
    suspend fun getSpeechDirectory(request: SpeechDirectoryRequest): SpeechDirectory {
        onProcessStart()
        val prepared = dataGate.write { speech.prepareDirectory(request) }
        return prepared.resolve()
    }
    /** Explicit reachability check; never starts a local model or synthesizes audio. */
    suspend fun getSpeechGatewayHealth(request: SpeechGatewayHealthRequest): SpeechGatewayHealth {
        onProcessStart()
        val prepared = dataGate.write { speech.prepareGatewayHealth(request) }
        return prepared.resolve()
    }
    /** Explicit remote voice creation with a retained MP3 preview; no automatic preset changes or playback. */
    suspend fun startSpeechVoiceDesign(request: SpeechVoiceDesignRequest): TaskSnapshot {
        onProcessStart(); return voiceDesignTasks.start(request)
    }
    suspend fun getSpeechVoiceDesignProgress(taskId: String): SpeechVoiceDesignProgress {
        onProcessStart(); return voiceDesignTasks.progress(taskId)
    }
    suspend fun awaitSpeechVoiceDesign(taskId: String): SpeechVoiceDesignResult {
        onProcessStart(); return voiceDesignTasks.await(taskId)
    }
    fun observeSpeechVoiceDesign(): StateFlow<SpeechVoiceDesignProgress?> = voiceDesignTasks.observe()
    suspend fun startSpeechVoiceClone(request: SpeechVoiceCloneRequest): TaskSnapshot {
        onProcessStart(); return voiceDesignTasks.startClone(request)
    }
    suspend fun getSpeechVoiceCloneProgress(taskId: String): SpeechVoiceCloneProgress {
        onProcessStart(); return voiceDesignTasks.progress(taskId, TaskKind.SPEECH_VOICE_CLONE)
    }
    suspend fun awaitSpeechVoiceClone(taskId: String): SpeechVoiceCloneResult {
        onProcessStart(); return voiceDesignTasks.await(taskId, TaskKind.SPEECH_VOICE_CLONE)
    }
    fun observeSpeechVoiceClone(): StateFlow<SpeechVoiceCloneProgress?> = voiceDesignTasks.observeClone()
    suspend fun previewSpeechPreprocessing(request: SpeechPreprocessRequest): SpeechPreprocessSelection {
        onProcessStart(); return dataGate.write { speech.preparePreprocessing(request).selection }
    }
    /** One explicit LLM call; returns a script, never synthesizes or changes the source message. */
    suspend fun preprocessSpeech(request: SpeechPreprocessRequest): SpeechPreprocessResult {
        onProcessStart()
        val prepared = dataGate.write { speech.preparePreprocessing(request) }
        return prepared.process()
    }
    suspend fun getSpeechPricingRules(): SpeechPricingSnapshot { onProcessStart(); return dataGate.write { speechUsage.pricing() } }
    suspend fun replaceSpeechPricingRules(rules: List<SpeechPricingRule>, expected: PricingVersion) = writeData { speechUsage.replacePricing(rules, expected) }
    suspend fun installSpeechPricingCatalog(rules: List<SpeechPricingRule>, expected: PricingVersion) = writeData { speechUsage.replacePricing(rules, expected, catalog = true) }
    /** Caller-owned non-streaming synthesis. No automatic generation, cache write, or old request replay. */
    suspend fun synthesizeSpeech(request: SpeechRequest): SpeechAudio {
        onProcessStart()
        val prepared = dataGate.write { speech.prepare(request) }
        return dataGate.write { prepared.synthesize() }
    }
    suspend fun startSpeechTask(request: SpeechRequest, messageCandidateId: String? = null): TaskSnapshot {
        onProcessStart(); return speechTasks.start(request, messageCandidateId)
    }
    suspend fun startSystemSpeech(request: SystemSpeechRequest, playbackSpeed: Double = 1.0): TaskSnapshot {
        onProcessStart(); return speechPlayback.startSystem(request, playbackSpeed)
    }
    suspend fun startSpeechPlayback(assetId: String, pcmFormat: PcmPlaybackFormat? = null, playbackSpeed: Double = 1.0): TaskSnapshot {
        onProcessStart(); return speechPlayback.startAsset(assetId, pcmFormat, playbackSpeed)
    }
    /** Play explicitly selected generated/imported audio through the same foreground playback task. */
    suspend fun startChatAttachmentPlayback(attachmentId: String, pcmFormat: PcmPlaybackFormat? = null,
        playbackSpeed: Double = 1.0): TaskSnapshot {
        onProcessStart()
        return speechPlayback.startAttachment(attachmentId, pcmFormat, playbackSpeed)
    }
    /** Play a completed design/clone preview from its existing local attachment or bounded download through existing playback; does not reissue synthesis or cloning. */
    suspend fun startSpeechVoicePreviewPlayback(voiceTaskId: String, playbackSpeed: Double = 1.0): TaskSnapshot {
        onProcessStart()
        return speechPlayback.startVoicePreview(voiceTaskId, playbackSpeed)
    }
    suspend fun startCloudSpeechPlayback(request: SpeechRequest, pcmFormat: PcmPlaybackFormat? = null, playbackSpeed: Double = 1.0): TaskSnapshot {
        onProcessStart(); return speechPlayback.startCloud(request, pcmFormat, playbackSpeed)
    }
    /** Play existing cached/attachment audio in order, without synthesis or billing requests. */
    suspend fun startSpeechPlaylist(items: List<SpeechPlaylistItem>, playbackSpeed: Double = 1.0): TaskSnapshot {
        onProcessStart(); return speechPlayback.startPlaylist(items, playbackSpeed)
    }
    suspend fun skipSpeechPlaybackTo(taskId: String, itemIndex: Int, positionMs: Long = 0): SpeechPlaybackProgress {
        onProcessStart(); return speechPlayback.skipTo(taskId, itemIndex, positionMs)
    }
    suspend fun nextSpeechPlaybackItem(taskId: String): SpeechPlaybackProgress { onProcessStart(); return speechPlayback.next(taskId) }
    suspend fun previousSpeechPlaybackItem(taskId: String): SpeechPlaybackProgress { onProcessStart(); return speechPlayback.previous(taskId) }
    suspend fun getSpeechPlaybackProgress(taskId: String): SpeechPlaybackProgress { onProcessStart(); return speechPlayback.progress(taskId) }
    suspend fun awaitSpeechPlayback(taskId: String): SpeechPlaybackProgress { onProcessStart(); return speechPlayback.await(taskId) }
    suspend fun pauseSpeechPlayback(taskId: String): SpeechPlaybackProgress { onProcessStart(); return speechPlayback.pause(taskId, true) }
    suspend fun resumeSpeechPlayback(taskId: String): SpeechPlaybackProgress { onProcessStart(); return speechPlayback.pause(taskId, false) }
    suspend fun seekSpeechPlayback(taskId: String, positionMs: Long): SpeechPlaybackProgress { onProcessStart(); return speechPlayback.seek(taskId, positionMs) }
    fun observeSpeechPlayback(): StateFlow<SpeechPlaybackProgress?> = speechPlayback.observe()
    suspend fun getSpeechTaskProgress(taskId: String): SpeechTaskProgress { onProcessStart(); return speechTasks.progress(taskId) }
    suspend fun awaitSpeechTask(taskId: String): SpeechAsset { onProcessStart(); return speechTasks.await(taskId) }
    fun observeSpeechTasks(): StateFlow<SpeechTaskProgress?> = speechTasks.observe()
    suspend fun getSpeechAsset(assetId: String): SpeechAsset { onProcessStart(); return dataGate.write { speechCache.asset(assetId) } }
    suspend fun readSpeechAudio(assetId: String): SpeechAudio { onProcessStart(); return dataGate.write { speechCache.read(assetId) } }
    suspend fun getMessageSpeech(chatId: String, messageCandidateId: String): SpeechAsset? {
        onProcessStart(); return dataGate.write { speechCache.forMessage(chatId, messageCandidateId) }
    }
    suspend fun getSpeechMessageBinding(chatId: String, messageCandidateId: String): SpeechMessageBinding? {
        onProcessStart(); return dataGate.write { speechCache.messageBinding(chatId, messageCandidateId) }
    }
    suspend fun bindSpeechAsset(request: SpeechBindRequest): SpeechMessageBinding = writeData { speechCache.bind(request) }
    suspend fun unbindSpeechAsset(chatId: String, messageCandidateId: String, expectedAssetId: String): Boolean = writeData {
        speechCache.unbind(chatId, messageCandidateId, expectedAssetId)
    }
    suspend fun getSpeechCacheStats(assistantId: String? = null): SpeechCacheStats {
        onProcessStart(); return dataGate.write { speechCache.stats(assistantId) }
    }
    suspend fun clearSpeechCache(assistantId: String? = null): SpeechCacheClearResult {
        onProcessStart(); return dataGate.write { speechCache.clear(assistantId) }
    }
    suspend fun saveAssistant(cfg: AssistantConfig) = writeData { content.saveAssistant(cfg) }
    suspend fun listPresets() = content.listPresets()
    suspend fun savePreset(p: ModelPreset) = writeData {
        val config = NativeJsonInput.parse(p.configJson) as? JsonObject ?: throw StError.Validation("model preset must be an object")
        val provider = (config["providerId"] as? JsonPrimitive)?.contentOrNull
        val previous = content.getPreset(p.id)
        val previousProvider = previous?.configJson?.let { (NativeJsonInput.parse(it) as? JsonObject)?.get("providerId") }
        if (previous != null && previousProvider != config["providerId"]) providerOAuth.logout(p.id)
        val selected = if (previous == null && "webSearchSource" !in config &&
            provider in setOf("deepseek", "anthropic", "openai", "openai-chatgpt", "xai-oauth"))
            p.copy(configJson = JsonObject(config + ("webSearchSource" to JsonPrimitive("native"))).toString()) else p
        content.savePreset(selected)
    }
    suspend fun getPreset(id: String) = content.getPreset(id) ?: throw StError.NotFound(id)
    suspend fun deletePreset(id: String, expectedUpdatedAt: String) = writeData {
        content.deletePreset(id, expectedUpdatedAt)
        providerOAuth.logout(id)
        credentials.remove(SubscriptionAuthService.key("openai-chatgpt", id) + ":registration")
    }
    suspend fun listAssistantConfigs() = content.listAssistantConfigurations()
    suspend fun getAssistantConfig(id: String) = content.getAssistant(id) ?: throw StError.NotFound(id)
    suspend fun saveAssistantConfig(config: AssistantConfig) = writeData { content.saveAssistant(config) }
    suspend fun deleteAssistantConfig(id: String, expectedUpdatedAt: String, deleteRelatedChats: Boolean = true): List<String> =
        writeData { content.deleteAssistant(id, expectedUpdatedAt, deleteRelatedChats) }
    suspend fun getAssistantMemoryPool(assistantId: String) = content.getAssistantMemoryPool(assistantId)
    suspend fun listRecentAssistantConversations(assistantId: String, limit: Int = 10) = assistantConversations.recent(assistantId, limit)
    suspend fun searchAssistantConversations(assistantId: String, query: String, limit: Int = 15, offset: Int = 0) =
        assistantConversations.search(assistantId, query, limit, offset)
    suspend fun readAssistantConversation(assistantId: String, chatId: String, afterOrder: Long = -1, limit: Int = 100) =
        assistantConversations.read(assistantId, chatId, afterOrder, limit)
    suspend fun listAssistantMemories(assistantId: String, afterId: String = "", limit: Int = 100) = content.listAssistantMemories(assistantId, afterId, limit)
    suspend fun getAssistantMemory(assistantId: String, memoryId: String) = content.getAssistantMemory(assistantId, memoryId)
    suspend fun createAssistantMemory(pool: AssistantMemoryPool, text: String) = writeData { content.createAssistantMemory(pool, text) }
    suspend fun updateAssistantMemory(pool: AssistantMemoryPool, memoryId: String, text: String, expectedContent: String) =
        writeData { content.updateAssistantMemory(pool, memoryId, text, expectedContent) }
    suspend fun deleteAssistantMemory(pool: AssistantMemoryPool, memoryId: String, expectedContent: String) =
        writeData { content.deleteAssistantMemory(pool, memoryId, expectedContent) }

    suspend fun createAssistantChat(binding: AssistantChatBinding = AssistantChatBinding("default"),
        title: String = "聊天助手", sandboxId: String? = null): ChatSession = writeData {
        sandboxId?.let { sandbox.requireSandbox(it) }
        assistants.create(binding, title, sandboxId)
    }
    suspend fun listAssistantChats(assistantId: String? = null): List<ChatSession> = chats.listSessions().filter {
        it.overrides.assistant?.let { binding -> assistantId == null || binding.assistantId == assistantId } == true
    }
    /** Same task/events/approval/stop API as ordinary generation; no replay or separate hidden worker. */
    suspend fun startAssistantGeneration(chatId: String, userText: String?, provider: ProviderConfig? = null,
        webSearch: WebSearchOptions = WebSearchOptions(), attachmentIds: List<String> = emptyList()): TaskSnapshot {
        onProcessStart()
        return dataGate.write {
            if (getChat(chatId).overrides.assistant == null) throw StError.Validation("not an assistant conversation")
            conversation.startGeneration(chatId, userText, provider, webSearch = webSearch, attachments = chatAttachments.references(attachmentIds))
        }
    }

    suspend fun listChats() = chats.listSessions()
    suspend fun getChat(id: String) = chats.getSession(id) ?: throw StError.NotFound(id)
    suspend fun saveChat(session: ChatSession) = writeData { chats.saveSession(session) }
    suspend fun deleteChat(id: String) = writeData { chats.deleteSession(id) }
    suspend fun createChat(characterId: String, title: String = "新对话", isGroup: Boolean = false, memberIds: List<String> = emptyList(),
        sandboxId: String? = null, openingMessageIndex: Int = 0, userPersonaId: String? = null, useDefaultPersona: Boolean = true,
        firstMessageCharacterId: String? = null, useOpeningMessage: Boolean = !isGroup || firstMessageCharacterId != null,
        pureAiMode: Boolean? = null, groupMvuPreset: String = "off", groupMvuPresetCharacterId: String? = null) =
        writeData { conversation.createChat(characterId, title, isGroup, memberIds, sandboxId, openingMessageIndex, userPersonaId,
            useDefaultPersona, firstMessageCharacterId, useOpeningMessage, pureAiMode, groupMvuPreset, groupMvuPresetCharacterId) }

    // ---- messages ----
    suspend fun pageMessages(chatId: String, limit: Int, offset: Int) = chats.pageMessages(chatId, limit, offset)
    suspend fun getMessageNode(nodeId: String): MessageNode = chats.getMessageNode(nodeId) ?: throw StError.NotFound("node $nodeId")
    suspend fun getMessageNodeForCandidate(candidateId: String): MessageNode = chats.transaction {
        val nodeId = chats.nodeIdForCandidate(candidateId) ?: throw StError.NotFound("candidate $candidateId")
        chats.getMessageNode(nodeId) ?: throw StError.NotFound("node $nodeId")
    }
    suspend fun messageCount(chatId: String) = chats.messageCount(chatId)
    /** Append a manual message without starting a model or tool task. */
    suspend fun appendMessage(chatId: String, candidate: MessageCandidate, parentNodeId: String? = null): MessageNode = writeData {
        val session = chats.getSession(chatId) ?: throw StError.NotFound("chat $chatId")
        val message = if (candidate.role == ChatRole.user && candidate.senderPersonaId == null &&
            candidate.senderName == null && candidate.senderAvatar == null) {
            val persona = contextBuilder.userPersonaFor(session)
            candidate.copy(senderPersonaId = persona?.id, senderName = persona?.name?.takeIf { it.isNotBlank() } ?: "用户",
                senderAvatar = persona?.avatarRelativePath)
        } else candidate
        chats.appendNode(chatId, managedMessageMedia(message), parentNodeId)
    }
    suspend fun selectCandidate(nodeId: String, index: Int) = writeData { chats.selectCandidate(nodeId, index) }
    suspend fun addCandidate(nodeId: String, candidate: MessageCandidate, select: Boolean = true) = writeData { chats.addCandidate(nodeId, candidate, select) }
    suspend fun editSelected(nodeId: String, content: String) = writeData { chats.editSelected(nodeId, content) }
    /** Replace editable fields of the selected candidate; request and source metadata remain unchanged. */
    suspend fun editSelected(nodeId: String, edited: MessageCandidate): MessageNode = writeData {
        val selected = chats.getMessageNode(nodeId)?.selected ?: throw StError.NotFound("selected candidate")
        chats.editSelected(nodeId, managedMessageMedia(edited, selected))
    }
    suspend fun truncateMessagesAfter(nodeId: String): Long = writeData { chats.truncateMessagesAfter(nodeId) }
    suspend fun editSelectedAndTruncate(nodeId: String, content: String): MessageNode = writeData { chats.editSelectedAndTruncate(nodeId, content) }
    suspend fun editSelectedAndTruncate(nodeId: String, edited: MessageCandidate): MessageNode = writeData {
        val selected = chats.getMessageNode(nodeId)?.selected ?: throw StError.NotFound("selected candidate")
        chats.editSelectedAndTruncate(nodeId, managedMessageMedia(edited, selected))
    }
    suspend fun deleteMessageNode(nodeId: String) = writeData { chats.deleteMessageNode(nodeId) }
    suspend fun deleteCandidate(nodeId: String, candidateId: String): MessageNode? = writeData { chats.deleteCandidate(nodeId, candidateId) }
    suspend fun searchMessages(chatId: String, query: String) = chats.search(chatId, query)
    suspend fun searchMessages(chatId: String, query: String, limit: Int, offset: Int = 0) =
        chats.search(chatId, query, limit, offset)

    private suspend fun managedMessageMedia(candidate: MessageCandidate, current: MessageCandidate? = null): MessageCandidate {
        val retained = current?.let { (it.images + it.attachments).associateBy(AttachmentRef::id) }.orEmpty()
        val newIds = (candidate.images + candidate.attachments).map(AttachmentRef::id).distinct().filterNot(retained::containsKey)
        val references = retained + chatAttachments.references(newIds).associateBy(AttachmentRef::id)
        fun resolve(refs: List<AttachmentRef>) = refs.map { references.getValue(it.id).copy(modelVisible = it.modelVisible) }
        return candidate.copy(images = resolve(candidate.images), attachments = resolve(candidate.attachments))
    }

    suspend fun forkChat(sourceChatId: String, forkAtMessageId: String, newTitle: String? = null): ChatSession {
        onProcessStart()
        return dataGate.write { conversation.fork(sourceChatId, forkAtMessageId, newTitle) }
    }
    suspend fun branchChat(sourceChatId: String, newTitle: String? = null): ChatSession {
        onProcessStart()
        return dataGate.write { conversation.branch(sourceChatId, newTitle) }
    }
    suspend fun promoteChatToGroup(sourceChatId: String, memberIds: List<String>, newTitle: String? = null,
        memberSettings: Map<String, GroupMemberSettings> = emptyMap(), pureAiMode: Boolean? = null,
        userPersonaId: String? = null, useDefaultPersona: Boolean = true,
        groupSystemInjectDepth: Int = 5, groupSystemAlwaysAtBottom: Boolean = true): ChatSession {
        onProcessStart()
        return dataGate.write { conversation.promoteToGroup(sourceChatId, memberIds, newTitle, memberSettings, pureAiMode,
            userPersonaId, useDefaultPersona, groupSystemInjectDepth, groupSystemAlwaysAtBottom) }
    }
    suspend fun forkLineage(chatId: String) = conversation.forkLineage(chatId)

    fun observeGeneration(chatId: String): Flow<StreamEvent> = conversation.observe(chatId)

    /** Read a managed attachment through the existing bounded reader; no path or remote handle. */
    suspend fun getChatAttachmentContent(attachmentId: String): ContextMedia = writeData { chatAttachments.read(attachmentId) }

    /** Copy a user-selected file into managed storage; never mount or retain its source handle. */
    suspend fun importChatAttachment(chatId: String, source: File, filename: String = source.name,
        mimeType: String = "application/octet-stream"): AttachmentRef = writeData {
        chatAttachments.import(chatId, filename, mimeType) { source.inputStream() }
    }
    suspend fun importChatAttachment(chatId: String, source: Uri, filename: String, mimeType: String): AttachmentRef = writeData {
        chatAttachments.importUri(chatId, source, filename, mimeType)
    }
    /** Explicitly select fetched bytes (for example MCP media) as a new chat input. */
    suspend fun importChatAttachment(chatId: String, bytes: ByteArray, filename: String, mimeType: String): AttachmentRef = writeData {
        chatAttachments.import(chatId, filename, mimeType) { bytes.inputStream() }
    }

    suspend fun startGeneration(chatId: String, userText: String?, provider: ProviderConfig, interjectCharacterId: String? = null,
                                webSearch: WebSearchOptions = WebSearchOptions(), attachmentIds: List<String> = emptyList()): TaskSnapshot {
        onProcessStart()
        return dataGate.write { conversation.startGeneration(chatId, userText, provider, interjectCharacterId, webSearch = webSearch,
            attachments = chatAttachments.references(attachmentIds)) }
    }

    suspend fun startGeneration(chatId: String, userText: String?, interjectCharacterId: String? = null,
                                webSearch: WebSearchOptions = WebSearchOptions(), attachmentIds: List<String> = emptyList()): TaskSnapshot {
        onProcessStart()
        return dataGate.write { conversation.startGeneration(chatId, userText, interjectCharacterId = interjectCharacterId, webSearch = webSearch,
            attachments = chatAttachments.references(attachmentIds)) }
    }

    suspend fun startGroupGeneration(chatId: String, userText: String?, options: GroupGenerationOptions = GroupGenerationOptions(),
                                     webSearch: WebSearchOptions = WebSearchOptions(), attachmentIds: List<String> = emptyList()): TaskSnapshot {
        onProcessStart()
        return dataGate.write { conversation.startGeneration(chatId, userText, groupOptions = options, webSearch = webSearch,
            attachments = chatAttachments.references(attachmentIds)) }
    }

    suspend fun startRegeneration(chatId: String, nodeId: String, webSearch: WebSearchOptions = WebSearchOptions()): TaskSnapshot =
        writeData { conversation.startGeneration(chatId, null, persistUser = false, webSearch = webSearch, regenerateNodeId = nodeId) }

    suspend fun startRegeneration(chatId: String, nodeId: String, provider: ProviderConfig,
                                  webSearch: WebSearchOptions = WebSearchOptions()): TaskSnapshot =
        writeData { conversation.startGeneration(chatId, null, provider, persistUser = false, webSearch = webSearch, regenerateNodeId = nodeId) }

    suspend fun startGroupGeneration(chatId: String, userText: String?, provider: ProviderConfig,
                                     options: GroupGenerationOptions = GroupGenerationOptions(), webSearch: WebSearchOptions = WebSearchOptions(),
                                     attachmentIds: List<String> = emptyList()): TaskSnapshot {
        onProcessStart()
        return dataGate.write { conversation.startGeneration(chatId, userText, provider, groupOptions = options, webSearch = webSearch,
            attachments = chatAttachments.references(attachmentIds)) }
    }

    suspend fun getGroupGenerationProgress(taskId: String): GroupGenerationProgress {
        onProcessStart()
        return conversation.groupProgress(taskId)
    }

    suspend fun previewProvider(chatId: String, characterId: String? = null): ProviderSelection {
        val session = getChat(chatId)
        return if (session.overrides.assistant != null) assistants.preview(session) else providers.preview(session, characterId)
    }
    /** Offline frozen catalog with adapter statuses; never chooses a model or initiates login. */
    suspend fun getProviderCatalog(): ProviderCatalogSnapshot = withContext(Dispatchers.IO) { ProviderCatalog.snapshot() }
    suspend fun getModelCatalog(providerId: String? = null, query: String? = null, offset: Int = 0, limit: Int = 100): ModelCatalogPage =
        withContext(Dispatchers.IO) { onProcessStart(); ModelCatalog.page(providerId, query, offset, limit) }
    suspend fun getModelCapabilities(model: String, providerId: String? = null): ModelCapabilitySelection =
        withContext(Dispatchers.IO) { onProcessStart(); ModelCatalog.capabilities(model, providerId) }
    suspend fun refreshModelCatalog(): ModelCatalogPage = withContext(Dispatchers.IO) {
        onProcessStart()
        val lease = dataGate.sharedLease()
        try { modelCatalogRefresh.update() } finally { lease.close() }
    }
    suspend fun previewModelPricingCatalog(selections: List<ModelCatalogPriceSelection>): List<PricingRule> =
        withContext(Dispatchers.IO) { onProcessStart(); ModelCatalog.pricingRules(selections) }
    suspend fun installModelPricingCatalog(selections: List<ModelCatalogPriceSelection>, expected: PricingVersion): PricingSnapshot = writeData {
        usageLedger.replacePricingRules(ModelCatalog.pricingRules(selections), expected, catalog = true)
    }
    suspend fun listProviderModels(presetId: String, pageToken: String? = null, pageSize: Int = 100,
        kind: ProviderModelDirectoryKind = ProviderModelDirectoryKind.MODELS): ProviderModelPage =
        withContext(Dispatchers.IO) {
            onProcessStart()
            val lease = dataGate.sharedLease()
            try {
                val provider = providers.resolveModelListing(presetId)
                providerModels.list(provider, pageToken, pageSize, kind)
            } finally {
                lease.close()
            }
        }
    suspend fun setProviderCredential(credentialId: String, apiKey: String) = writeData {
        if (credentialId.startsWith(CredentialStore.OAUTH_PREFIX)) throw StError.Validation("use the provider OAuth login API")
        if (credentialId.startsWith(CredentialStore.AWS_PREFIX)) throw StError.Validation("use the Bedrock AWS credentials API")
        credentials.putSecret(credentialId, apiKey)
    }
    suspend fun setBedrockAwsCredentials(credentialId: String, accessKeyId: String, secretAccessKey: String, sessionToken: String? = null): String {
        val actualId = if (credentialId.startsWith(CredentialStore.AWS_PREFIX)) credentialId else CredentialStore.AWS_PREFIX + credentialId
        if (actualId == CredentialStore.AWS_PREFIX || actualId.length > 1024 || actualId.any(Char::isISOControl))
            throw StError.Validation("invalid AWS credential reference")
        val credential = AwsBedrockCredentials(accessKeyId, secretAccessKey, sessionToken)
        credential.validate()
        writeData { credentials.putSecret(actualId, Jsonx.encode(credential)) }
        return actualId
    }
    suspend fun hasProviderCredential(credentialId: String) =
        if (credentialId.startsWith(CredentialStore.AWS_PREFIX)) !credentials.getSecrets(setOf(credentialId), allowAws = true)[credentialId].isNullOrBlank()
        else credentials.hasSecret(credentialId)
    suspend fun removeProviderCredential(credentialId: String) = writeData {
        if (credentialId.startsWith(CredentialStore.OAUTH_PREFIX)) throw StError.Validation("use the provider OAuth logout API")
        credentials.remove(credentialId, allowAws = credentialId.startsWith(CredentialStore.AWS_PREFIX))
    }
    suspend fun startProviderDeviceLogin(presetId: String): ProviderDeviceLogin {
        onProcessStart(); return providerOAuth.start(presetId)
    }
    suspend fun pollProviderDeviceLogin(loginId: String): ProviderDeviceLogin {
        onProcessStart(); return providerOAuth.poll(loginId)
    }
    suspend fun startProviderBrowserLogin(presetId: String): ProviderBrowserLogin {
        onProcessStart(); return providerOAuth.startBrowser(presetId)
    }
    suspend fun completeProviderBrowserLogin(loginId: String, callbackUrl: String): ProviderDeviceLogin {
        onProcessStart(); return providerOAuth.completeBrowser(loginId, callbackUrl)
    }
    suspend fun cancelProviderDeviceLogin(loginId: String): ProviderDeviceLogin {
        onProcessStart(); return providerOAuth.cancel(loginId)
    }
    suspend fun getProviderSubscriptionUsage(presetId: String): ProviderSubscriptionUsage {
        onProcessStart(); return providerOAuth.usage(presetId)
    }
    fun getChatGPTSubscriptionNotice(): String = SubscriptionAuthService.NOTICE
    /** Host opens authorizeUrl with ACTION_VIEW; pollProviderDeviceLogin observes the native loopback listener. */
    suspend fun getProviderOAuthStatus(presetId: String): ProviderOAuthStatus {
        onProcessStart(); return providerOAuth.status(presetId)
    }
    suspend fun refreshProviderOAuth(presetId: String): ProviderOAuthStatus {
        onProcessStart(); providerOAuth.connection(presetId, forceRefresh = true)
        return providerOAuth.status(presetId)
    }
    suspend fun logoutProviderOAuth(presetId: String) {
        onProcessStart(); providerOAuth.logout(presetId)
    }
    suspend fun configureGoogleVertexPreset(presetId: String, options: GoogleVertexOptions, expectedUpdatedAt: String): ModelPreset {
        onProcessStart(); return providerOAuth.configureVertex(presetId, options, expectedUpdatedAt)
    }
    suspend fun setGoogleVertexServiceAccount(presetId: String, serviceAccountJson: String): ProviderOAuthStatus {
        onProcessStart(); return providerOAuth.setVertexServiceAccount(presetId, serviceAccountJson)
    }

    /** Explicit manual operation. Source-configured generation uses its own recorded child operation; startup/import never execute it. */
    suspend fun startGeminiCacheCreate(request: GeminiCacheCreateRequest): TaskSnapshot {
        onProcessStart(); return geminiCacheTasks.create(request)
    }
    /** Select/clear a current-process cache binding. No network or cache creation. */
    suspend fun setGeminiCacheForGeneration(chatId: String, selection: GeminiCacheGenerationSelection?, characterId: String? = null): GeminiCacheGenerationSelection? {
        onProcessStart(); return geminiCacheTasks.select(chatId, selection, characterId)
    }
    suspend fun getGeminiCacheForGeneration(chatId: String, characterId: String? = null): GeminiCacheGenerationSelection? {
        onProcessStart(); return geminiCacheTasks.selection(chatId, characterId)
    }
    /** Explicit account observation only. Each page is a new task; tokens never leave the runtime service. */
    suspend fun startGeminiCacheList(chatId: String, characterId: String? = null, pageSize: Int = 100,
        previousListTaskId: String? = null): TaskSnapshot {
        onProcessStart(); return geminiCacheTasks.list(chatId, characterId, pageSize, previousListTaskId)
    }
    suspend fun startGeminiCacheOperation(chatId: String, referenceTaskId: String, action: GeminiCacheAction,
        ttlSeconds: Long? = null, expireTime: String? = null): TaskSnapshot {
        onProcessStart(); return geminiCacheTasks.manage(chatId, referenceTaskId, action, ttlSeconds, expireTime)
    }
    suspend fun getGeminiCacheOperation(taskId: String): GeminiCacheTaskProgress {
        onProcessStart(); return geminiCacheTasks.progress(taskId)
    }
    suspend fun geminiCacheOperationsPage(afterTaskId: String = "", limit: Int = 100): List<GeminiCacheTaskProgress> {
        onProcessStart(); return geminiCacheTasks.page(afterTaskId, limit)
    }
    fun observeGeminiCacheOperation(): StateFlow<GeminiCacheTaskProgress?> = geminiCacheTasks.observe()

    /** Local durable causal view, not a remote list or replay index. */
    suspend fun getGeminiCacheResource(referenceTaskId: String, at: String = Instant.now().toString()): GeminiCacheResourceState {
        onProcessStart(); return geminiCacheTasks.resource(referenceTaskId, at)
    }
    /** Read-only conditional storage estimates from durable evidence, never provider settlement. */
    suspend fun getGeminiCacheStorage(referenceTaskId: String, at: String = Instant.now().toString()): GeminiCacheStorageView {
        onProcessStart(); return geminiCacheTasks.storage(referenceTaskId, at)
    }
    suspend fun geminiCacheResourcesPage(chatId: String, afterKey: String = "", limit: Int = 100,
        at: String = Instant.now().toString()): GeminiCacheResourcePage {
        onProcessStart(); return geminiCacheTasks.resources(chatId, afterKey, limit, at)
    }
    /** Explicit conditional DELETE; stale inspection, use lease, changed credentials or uncertain state refuses it. */
    suspend fun startGeminiCacheCleanup(chatId: String, referenceTaskId: String, expectedFrontierTaskIds: List<String>): TaskSnapshot {
        if (expectedFrontierTaskIds.size !in 1..64) throw StError.Validation("invalid cache cleanup frontier")
        val expected = expectedFrontierTaskIds.toList()
        onProcessStart(); return geminiCacheTasks.manage(chatId, referenceTaskId, GeminiCacheAction.DELETE, expectedFrontier = expected)
    }

    suspend fun getGeminiCachePricingRules(): GeminiCachePricingSnapshot {
        onProcessStart(); return dataGate.write { geminiCachePrices.pricing() }
    }
    suspend fun replaceGeminiCachePricingRules(rules: List<GeminiCachePricingRule>, expected: PricingVersion) =
        writeData { geminiCachePrices.replace(rules, expected) }
    suspend fun installGeminiCachePricingCatalog(rules: List<GeminiCachePricingRule>, expected: PricingVersion) =
        writeData { geminiCachePrices.replace(rules, expected, catalog = true) }
    /** Read-only estimate from supplied public owner/quantity; no account lookup or remote request. */
    suspend fun estimateGeminiCacheCost(owner: GeminiCacheOwner, tokenCount: Long?, storageSeconds: Long = 3600,
        at: String = Instant.now().toString()): GeminiCacheCostQuote {
        onProcessStart(); return dataGate.write { geminiCachePrices.quote(owner, tokenCount, storageSeconds, at) }
    }

    suspend fun stopTask(taskId: String, reason: TaskTerminalReason = TaskTerminalReason.USER_STOP): TaskSnapshot {
        onProcessStart()
        val task = tasks.get(taskId) ?: throw StError.NotFound(taskId)
        return when (task.kind) {
            TaskKind.MEMORY_SUMMARY -> memory.stopSummary(taskId, reason)
            TaskKind.MVU_UPDATE, TaskKind.KG_UPDATE -> memory.stopStateMaintenance(taskId, reason)
            TaskKind.SANDBOX_SHELL, TaskKind.SANDBOX_FILE -> sandboxTasks.stop(taskId, reason)
            TaskKind.BACKUP -> backupTasks.stop(taskId, reason)
            TaskKind.RESTORE -> restoreTasks.stop(taskId, reason)
            TaskKind.IMPORT -> importTasks.stop(taskId, reason)
            TaskKind.FILE_COPY -> copyTasks.stop(taskId, reason)
            TaskKind.FILE_EXPORT -> exportTasks.stop(taskId, reason)
            TaskKind.SPEECH_SYNTHESIS -> speechTasks.stop(taskId, reason)
            TaskKind.SPEECH_VOICE_DESIGN, TaskKind.SPEECH_VOICE_CLONE -> voiceDesignTasks.stop(taskId, reason)
            TaskKind.SPEECH_PLAYBACK -> speechPlayback.stop(taskId, reason)
            TaskKind.GEMINI_CACHE_OPERATION, TaskKind.GEMINI_CACHE_LIST -> geminiCacheTasks.stop(taskId, reason)
            else -> conversation.stop(taskId, reason)
        }
    }

    // ---- memory ----
    suspend fun getMemory(chatId: String) = memory.get(chatId)
    suspend fun editMemory(chatId: String, content: String, expectedVersion: Long?) = writeData { memory.edit(chatId, content, expectedVersion) }
    suspend fun summarizeMemory(chatId: String, provider: ProviderConfig): MemorySummaryJob {
        onProcessStart()
        return dataGate.write { memory.summarize(chatId, provider) }
    }
    suspend fun summarizeMemory(chatId: String): MemorySummaryJob {
        onProcessStart()
        return dataGate.write { memory.summarize(chatId, providers.resolve(getChat(chatId))) }
    }
    suspend fun startMemorySummary(chatId: String, rebuild: Boolean = false): MemorySummaryJob {
        onProcessStart()
        return dataGate.write { memory.startSummary(chatId, rebuild = rebuild) }
    }
    suspend fun startMemorySummary(chatId: String, provider: ProviderConfig, rebuild: Boolean = false): MemorySummaryJob {
        onProcessStart()
        return dataGate.write { memory.startSummary(chatId, provider, rebuild) }
    }
    suspend fun getMemorySummaryJob(id: String): MemorySummaryJob {
        onProcessStart()
        return memory.getSummaryJob(id)
    }
    suspend fun getMemorySummaryProgress(id: String): MemorySummaryProgress {
        onProcessStart()
        return memory.getSummaryProgress(id)
    }
    suspend fun listMemorySummaryJobs(chatId: String): List<MemorySummaryJob> {
        onProcessStart()
        return memory.listSummaryJobs(chatId)
    }
    suspend fun pendingMemorySummary(chatId: String): MemorySummaryJob? {
        onProcessStart()
        return memory.pendingSummary(chatId)
    }
    suspend fun confirmMemorySummary(id: String): MemorySummaryJob {
        onProcessStart()
        return dataGate.write { memory.confirmSummary(id) }
    }
    suspend fun dismissMemorySummary(id: String): MemorySummaryJob {
        onProcessStart()
        return dataGate.write { memory.dismissSummary(id) }
    }
    suspend fun buildContext(chatId: String, characterIdOverride: String? = null): ContextBuildResult = withContext(Dispatchers.Default) {
        val session = getChat(chatId)
        if (session.overrides.assistant != null) assistants.context(session) else contextBuilder.build(session, characterIdOverride)
    }
    suspend fun getMvu(chatId: String) = memory.getMvu(chatId)
    suspend fun saveMvu(state: MvuState, expectedVersion: Int?) = writeData { memory.saveMvu(state, expectedVersion) }
    suspend fun getKg(chatId: String) = memory.getKg(chatId)
    suspend fun saveKg(kg: KnowledgeGraph, expectedVersion: Int?) = writeData { memory.saveKg(kg, expectedVersion) }
    suspend fun upsertKgEntity(chatId: String, entity: KgEntity, expectedVersion: Int?) = writeData { memory.upsertKgEntity(chatId, entity, expectedVersion) }
    suspend fun upsertKgRelation(chatId: String, relation: KgRelation, expectedVersion: Int?) = writeData { memory.upsertKgRelation(chatId, relation, expectedVersion) }

    suspend fun previewStateMaintenanceProvider(chatId: String) = providers.previewMaintenance(getChat(chatId))
    suspend fun startMvuUpdate(chatId: String, includeKg: Boolean = true): TaskSnapshot {
        onProcessStart()
        return dataGate.write { memory.startMvuUpdate(chatId, includeKg) }
    }
    suspend fun startMvuUpdate(chatId: String, provider: ProviderConfig, includeKg: Boolean = true): TaskSnapshot {
        onProcessStart()
        return dataGate.write { memory.startMvuUpdate(chatId, includeKg, provider) }
    }
    suspend fun startKgUpdate(chatId: String): TaskSnapshot {
        onProcessStart()
        return dataGate.write { memory.startKgUpdate(chatId) }
    }
    suspend fun startKgUpdate(chatId: String, provider: ProviderConfig): TaskSnapshot {
        onProcessStart()
        return dataGate.write { memory.startKgUpdate(chatId, provider) }
    }
    /** Explicit replacement from initial tables/empty graph after every fixed history segment succeeds. */
    suspend fun startStateHistoryRebuild(chatId: String, maintainMvu: Boolean = true, includeKg: Boolean = true): TaskSnapshot {
        onProcessStart()
        return dataGate.write { memory.startStateHistoryRebuild(chatId, maintainMvu, includeKg) }
    }
    suspend fun startStateHistoryRebuild(chatId: String, provider: ProviderConfig,
                                         maintainMvu: Boolean = true, includeKg: Boolean = true): TaskSnapshot {
        onProcessStart()
        return dataGate.write { memory.startStateHistoryRebuild(chatId, maintainMvu, includeKg, provider) }
    }
    suspend fun getStateMaintenance(taskId: String): StateMaintenanceResult {
        onProcessStart()
        return memory.getStateMaintenance(taskId)
    }
    suspend fun listStateMaintenance(chatId: String, limit: Int = 100): List<StateMaintenanceResult> {
        onProcessStart()
        return memory.listStateMaintenance(chatId, limit)
    }
    suspend fun listMvuLogs(chatId: String, limit: Int = 100): List<MvuWorkLog> {
        onProcessStart()
        return memory.listMvuLogs(chatId, limit)
    }

    // ---- body rules & extraction queue (raw messages remain unchanged) ----
    suspend fun effectiveContentRegexRules(chatId: String) = memory.effectiveContentRegexRules(chatId)
    suspend fun sessionContentRegexRules(chatId: String) = memory.sessionContentRegexRules(chatId)
    suspend fun characterContentRegexRules(characterId: String) = memory.characterContentRegexRules(characterId)
    suspend fun saveSessionContentRegexRules(chatId: String, rules: List<ContentRegexRule>, expectedRules: List<ContentRegexRule>) =
        writeData { memory.saveSessionContentRegexRules(chatId, rules, expectedRules) }
    suspend fun saveCharacterContentRegexRules(characterId: String, rules: List<ContentRegexRule>, expectedRules: List<ContentRegexRule>) =
        writeData { memory.saveCharacterContentRegexRules(characterId, rules, expectedRules) }
    suspend fun upsertSessionContentRegexRule(chatId: String, rule: ContentRegexRule, expectedRules: List<ContentRegexRule>) =
        writeData { memory.upsertSessionContentRegexRule(chatId, rule, expectedRules) }
    suspend fun deleteSessionContentRegexRule(chatId: String, ruleId: String, expectedRules: List<ContentRegexRule>) =
        writeData { memory.deleteSessionContentRegexRule(chatId, ruleId, expectedRules) }
    suspend fun previewContentRegex(chatId: String, text: String, role: ChatRole = ChatRole.assistant) = memory.previewContentRegex(chatId, text, role)
    suspend fun previewMessageContentRegex(chatId: String, messageId: String) = memory.previewMessageContentRegex(chatId, messageId)
    suspend fun scanContentRegex(chatId: String, throughMessageId: String? = null): List<ContentRegexRecord> {
        onProcessStart()
        return dataGate.write { memory.scanContentRegex(chatId, throughMessageId) }
    }
    suspend fun getContentRegexRecord(chatId: String, messageId: String) = memory.getContentRegexRecord(chatId, messageId)
    suspend fun listContentRegexRecords(chatId: String, limit: Int = 100) = memory.listContentRegexRecords(chatId, limit)
    suspend fun pendingContentRegex(chatId: String, limit: Int = 100) = memory.pendingContentRegex(chatId, limit)

    // ---- import / export ----
    suspend fun preflightImport(zip: File) = importer.preflight(zip)
    suspend fun preflightImport(archive: Uri): PreflightResult {
        onProcessStart()
        return importTasks.preflight(archive)
    }
    suspend fun runImport(zip: File, sourceHint: String? = null, sourceLibraryId: String? = null): ImportReport {
        return importTasks.await(startImport(zip, sourceHint, sourceLibraryId).id)
    }
    suspend fun startImport(zip: File, sourceHint: String? = null, sourceLibraryId: String? = null): TaskSnapshot {
        onProcessStart()
        return importTasks.start(zip, sourceHint, sourceLibraryId)
    }
    suspend fun startImport(uri: Uri, sourceHint: String? = null, sourceLibraryId: String? = null): TaskSnapshot {
        onProcessStart()
        return importTasks.start(uri, sourceHint, sourceLibraryId)
    }
    suspend fun runImport(uri: Uri, sourceHint: String? = null, sourceLibraryId: String? = null): ImportReport =
        importTasks.await(startImport(uri, sourceHint, sourceLibraryId).id)
    suspend fun getImportTaskProgress(taskId: String): ImportTaskProgress { onProcessStart(); return importTasks.progress(taskId) }
    fun observeImportTaskProgress() = importTasks.observe()
    suspend fun getImportReport(batchId: String) = importer.getReport(batchId)
    suspend fun importedSourceRevisions(namespace: String) = importer.sourceRevisions(namespace)
    suspend fun listImportedSources() = importer.sourceNamespaces()
    fun cancelImport() { importTasks.cancelActive(); importer.requestCancel() }
    fun observeImportProgress() = importer.observeProgress()

    suspend fun startBackup(destination: File, options: BackupOptions = BackupOptions(),
                            password: CharArray? = null, overwrite: Boolean = false): TaskSnapshot {
        onProcessStart()
        return backupTasks.start(destination, options, password, overwrite)
    }
    /** URI writes use wt to replace the user-authorized target; failures may leave partial bytes and are not atomic or replayed. */
    suspend fun startBackup(destination: Uri, options: BackupOptions = BackupOptions(),
                            password: CharArray? = null): TaskSnapshot {
        onProcessStart()
        return backupTasks.start(destination, options, password)
    }
    suspend fun preflightNativeBackup(archive: File): NativeBackupPreflight {
        onProcessStart()
        return NativeBackupPreflightService(db).inspect(archive)
    }
    suspend fun preflightNativeBackup(archive: Uri): NativeBackupPreflight {
        onProcessStart()
        return restoreTasks.preflight(archive)
    }
    suspend fun inspectNativeRestore(archive: File, password: CharArray? = null,
                                     mode: NativeRestoreMode = NativeRestoreMode.ADD_MISSING): NativeRestoreInspection {
        onProcessStart()
        return NativeRestoreInspectionService(db, paths, tasks, dataGate, credentials).inspect(archive, password, mode)
    }
    /** The content URI is already authorized; no persistable permission or replay is retained. */
    suspend fun inspectNativeRestore(archive: Uri, password: CharArray? = null,
                                     mode: NativeRestoreMode = NativeRestoreMode.ADD_MISSING): NativeRestoreInspection {
        onProcessStart()
        return restoreTasks.inspect(archive, password, mode)
    }
    suspend fun startNativeRestore(archive: File, password: CharArray? = null,
                                   mode: NativeRestoreMode = NativeRestoreMode.ADD_MISSING): TaskSnapshot {
        onProcessStart()
        return restoreTasks.start(archive, password, mode)
    }
    suspend fun startNativeRestore(archive: Uri, password: CharArray? = null,
                                   mode: NativeRestoreMode = NativeRestoreMode.ADD_MISSING): TaskSnapshot {
        onProcessStart()
        return restoreTasks.start(archive, password, mode)
    }
    suspend fun getNativeRestoreProgress(taskId: String): NativeRestoreProgress {
        onProcessStart()
        return restoreTasks.progress(taskId)
    }
    fun observeNativeRestoreProgress() = restoreTasks.observe()
    suspend fun restoreNativeBackup(archive: File, password: CharArray? = null,
                                    mode: NativeRestoreMode = NativeRestoreMode.ADD_MISSING): NativeRestoreProgress =
        restoreTasks.await(startNativeRestore(archive, password, mode).id)
    suspend fun restoreNativeBackup(archive: Uri, password: CharArray? = null,
                                    mode: NativeRestoreMode = NativeRestoreMode.ADD_MISSING): NativeRestoreProgress =
        restoreTasks.await(startNativeRestore(archive, password, mode).id)
    suspend fun getBackupProgress(taskId: String): BackupTaskProgress {
        onProcessStart()
        return backupTasks.progress(taskId)
    }
    fun observeBackupProgress() = backupTasks.observe()
    suspend fun exportBackup(destZip: File, options: BackupOptions, password: CharArray? = null, overwrite: Boolean = false): File {
        val task = startBackup(destZip, options, password, overwrite)
        return backupTasks.await(task.id)
    }
    /** URI writes use wt to replace the user-authorized target; failures may leave partial bytes and are not atomic or replayed. */
    suspend fun exportBackup(destination: Uri, options: BackupOptions = BackupOptions(), password: CharArray? = null): Uri {
        val task = startBackup(destination, options, password)
        return backupTasks.awaitDocument(task.id)
    }
    /** Cancel data transactions before the host waits for the Room STOPPING transaction. */
    fun signalDataTaskHostInterruption(reason: TaskTerminalReason) {
        conversation.signalHostInterruption(reason)
        memory.signalHostInterruption(reason)
        sandboxTasks.signalHostInterruption(reason)
        copyTasks.signalHostInterruption(reason)
        exportTasks.signalHostInterruption(reason)
        speechTasks.signalHostInterruption(reason)
        voiceDesignTasks.signalHostInterruption(reason)
        speechPlayback.signalHostInterruption(reason)
        geminiCacheTasks.signalHostInterruption(reason)
        backupTasks.signalHostInterruption(reason)
        restoreTasks.signalHostInterruption(reason)
        importTasks.signalHostInterruption(reason)
    }
    fun signalBackupHostInterruption(reason: TaskTerminalReason) = signalDataTaskHostInterruption(reason)

    private suspend fun <T> writeData(block: suspend () -> T): T {
        onProcessStart()
        return dataGate.write(block)
    }

    // ---- sandbox ----
    suspend fun createSandbox(name: String) = sandbox.create(name)
    suspend fun listSandboxes() = sandbox.list()
    suspend fun getSandboxRuntimeStatus(id: String) = sandbox.runtimeStatus(id)
    suspend fun initializeSandboxRuntime(id: String) = writeData { sandbox.initializeRuntime(id) }
    suspend fun deleteSandbox(id: String) = sandbox.delete(id)
    suspend fun importIntoSandbox(sandboxId: String, source: File, relativeTarget: String): File =
        copyTasks.await(startFileCopy(sandboxId, source, relativeTarget).id)
    suspend fun importIntoSandbox(sandboxId: String, source: Uri, relativeTarget: String): File =
        copyTasks.await(startFileCopy(sandboxId, source, relativeTarget).id)
    suspend fun startFileCopy(sandboxId: String, source: File, relativeTarget: String): TaskSnapshot {
        onProcessStart()
        return copyTasks.start(sandboxId, source, relativeTarget)
    }
    suspend fun startFileCopy(sandboxId: String, source: Uri, relativeTarget: String): TaskSnapshot {
        onProcessStart()
        return copyTasks.start(sandboxId, source, relativeTarget)
    }
    suspend fun startCopyAttachmentToSandbox(attachmentId: String, sandboxId: String, targetPath: String): TaskSnapshot {
        onProcessStart()
        return copyTasks.start(sandboxId, chatAttachments.copySource(attachmentId), targetPath)
    }
    suspend fun startCopyAssistantWorkspaceFileToSandbox(assistantId: String, workspacePath: String,
                                                        sandboxId: String, targetPath: String): TaskSnapshot {
        onProcessStart()
        return copyTasks.startWorkspaceText(sandboxId, workspace.read(assistantId, workspacePath).content, targetPath)
    }
    suspend fun startCopyAssistantWorkspaceAssetToSandbox(assistantId: String, workspacePath: String,
                                                         sandboxId: String, targetPath: String): TaskSnapshot {
        onProcessStart()
        val asset = workspace.asset(assistantId, workspacePath)
        return startCopyAttachmentToSandbox(asset.attachment.id, sandboxId, targetPath)
    }
    suspend fun startCopyAssistantSkillFileToSandbox(assistantId: String, directory: String, path: String,
                                                    sandboxId: String, targetPath: String): TaskSnapshot {
        onProcessStart()
        val file = skills.read(assistantId, directory, path)
        return if (file.content != null) copyTasks.startWorkspaceText(sandboxId, file.content!!, targetPath)
            else startCopyAttachmentToSandbox(file.attachment!!.id, sandboxId, targetPath)
    }
    /** Copies a coherent skill snapshot using existing FILE_COPY tasks; never runs or mounts it. */
    suspend fun copyAssistantSkillToSandbox(assistantId: String, directory: String, sandboxId: String,
                                          targetDirectory: String? = null): SkillSandboxCopyResult {
        onProcessStart()
        return skills.copy(assistantId, directory, sandboxId, targetDirectory)
    }
    suspend fun startCopyBetweenSandboxes(sourceSandboxId: String, sourcePath: String, targetSandboxId: String,
                                         targetPath: String): TaskSnapshot {
        onProcessStart()
        sandbox.requireSandbox(sourceSandboxId)
        val source = withContext(Dispatchers.IO) { sandbox.resolveInSandbox(sourceSandboxId, sourcePath) }
        return copyTasks.start(targetSandboxId, source, targetPath)
    }
    suspend fun exportSandboxTextToAssistantWorkspace(sandboxId: String, sourcePath: String, assistantId: String,
                                                      workspacePath: String, expectedContent: String? = null): AssistantWorkspaceFile = writeData {
        workspace.write(assistantId, workspacePath, sandbox.readWorkspaceText(sandboxId, sourcePath), expectedContent)
    }
    suspend fun exportSandboxFileToChatAttachment(sandboxId: String, sourcePath: String, chatId: String,
                                                 filename: String = sourcePath.substringAfterLast('/'), mimeType: String = "application/octet-stream"): AttachmentRef = writeData {
        sandbox.withExportStream(sandboxId, sourcePath) { input -> chatAttachments.import(chatId, filename, mimeType) { input } }
    }
    suspend fun exportSandboxFileToAssistantWorkspace(sandboxId: String, sourcePath: String, assistantId: String, workspacePath: String,
                                                      mimeType: String = "application/octet-stream", expectedAttachmentId: String? = null): AssistantWorkspaceAsset = writeData {
        if (content.getAssistant(assistantId) == null) throw StError.NotFound("assistant configuration")
        AssistantWorkspaceStore.normalize(workspacePath)
        lateinit var savedAsset: AssistantWorkspaceAsset
        sandbox.withExportStream(sandboxId, sourcePath) { input ->
            chatAttachments.importAsset(workspacePath.substringAfterLast('/'), mimeType, { input }) { ref ->
                savedAsset = workspace.linkAsset(assistantId, workspacePath, ref.id, expectedAttachmentId)
            }
        }
        savedAsset
    }
    suspend fun startExportSandboxTextToAssistantWorkspace(sandboxId: String, sourcePath: String, assistantId: String,
                                                           workspacePath: String, expectedContent: String? = null): TaskSnapshot {
        onProcessStart()
        return exportTasks.startText(sandboxId, sourcePath, assistantId, workspacePath, expectedContent)
    }
    suspend fun startExportSandboxFileToChatAttachment(sandboxId: String, sourcePath: String, chatId: String,
                                                       filename: String = sourcePath.substringAfterLast('/'),
                                                       mimeType: String = "application/octet-stream"): TaskSnapshot {
        onProcessStart()
        return exportTasks.startAttachment(sandboxId, sourcePath, chatId, filename, mimeType)
    }
    suspend fun startExportSandboxFileToAssistantWorkspace(sandboxId: String, sourcePath: String, assistantId: String,
                                                           workspacePath: String, mimeType: String = "application/octet-stream",
                                                           expectedAttachmentId: String? = null): TaskSnapshot {
        onProcessStart()
        return exportTasks.startAsset(sandboxId, sourcePath, assistantId, workspacePath, mimeType, expectedAttachmentId)
    }
    /** Writes to a user-authorized content document, replacing the chosen destination bytes; failure may leave a partial document. */
    suspend fun startExportSandboxFileToDocument(sandboxId: String, sourcePath: String, destination: Uri): TaskSnapshot {
        onProcessStart()
        return exportTasks.startDocument(sandboxId, sourcePath, destination)
    }
    suspend fun getSandboxExportProgress(taskId: String): SandboxExportProgress {
        onProcessStart()
        return exportTasks.progress(taskId)
    }
    fun observeSandboxExportProgress() = exportTasks.observe()
    suspend fun getFileCopyProgress(taskId: String): FileCopyProgress { onProcessStart(); return copyTasks.progress(taskId) }
    fun observeFileCopyProgress() = copyTasks.observe()
    suspend fun sandboxListDir(sandboxId: String, path: String) = sandbox.listDir(sandboxId, path)
    suspend fun sandboxRead(sandboxId: String, path: String) = sandbox.readFile(sandboxId, path)
    suspend fun sandboxWrite(sandboxId: String, taskId: String, path: String, content: String) {
        onProcessStart()
        val args = kotlinx.serialization.json.buildJsonObject {
            put("path", kotlinx.serialization.json.JsonPrimitive(path)); put("content", kotlinx.serialization.json.JsonPrimitive(content))
        }.toString()
        val task = sandboxTasks.start(sandboxId, "write_file", args, requestedId = taskId)
        sandboxTasks.await(task.id)
        val terminal = getTask(task.id)
        if (terminal.status != TaskStatus.SUCCEEDED) throw StError.Sandbox(terminal.errorMessage ?: "file task did not complete")
    }
    suspend fun runShell(sandboxId: String, taskId: String, command: String): com.simpletavern.runtime.sandbox.ShellResult {
        onProcessStart()
        val args = kotlinx.serialization.json.buildJsonObject { put("command", kotlinx.serialization.json.JsonPrimitive(command)) }.toString()
        val task = sandboxTasks.start(sandboxId, "shell", args, requestedId = taskId)
        val progress = sandboxTasks.await(task.id)
        val terminal = getTask(task.id)
        val result = Jsonx.decodeOrNull<com.simpletavern.runtime.sandbox.ShellResult>(progress.resultJson)
        val output = progress.shellOutput ?: com.simpletavern.runtime.sandbox.ShellOutput()
        if (result == null && terminal.status !in setOf(TaskStatus.STOPPED, TaskStatus.INTERRUPTED))
            throw StError.Sandbox(terminal.errorMessage ?: "shell did not execute")
        return (result ?: com.simpletavern.runtime.sandbox.ShellResult(-1, "", "", "", cancelled = true)).copy(
            stdout = output.stdout, stderr = output.stderr,
            combined = output.stdout + (if (output.stderr.isEmpty()) "" else "\n[stderr]\n" + output.stderr) + "\n[exit]=${result?.exitCode ?: -1}",
            stdoutBytes = output.stdoutBytes, stderrBytes = output.stderrBytes,
            stdoutTruncated = output.stdoutTruncated, stderrTruncated = output.stderrTruncated)
    }

    suspend fun startSandboxTask(sandboxId: String, toolName: String, argumentsJson: String, chatId: String? = null): TaskSnapshot {
        onProcessStart()
        return sandboxTasks.start(sandboxId, toolName, argumentsJson, chatId)
    }
    suspend fun startShell(sandboxId: String, command: String, timeoutSec: Long = 300, chatId: String? = null) =
        startSandboxTask(sandboxId, "shell", kotlinx.serialization.json.buildJsonObject {
            put("command", kotlinx.serialization.json.JsonPrimitive(command))
            put("timeoutSec", kotlinx.serialization.json.JsonPrimitive(timeoutSec))
        }.toString(), chatId)
    suspend fun getSandboxTaskProgress(taskId: String): com.simpletavern.core.tools.SandboxTaskProgress {
        onProcessStart()
        return sandboxTasks.progress(taskId)
    }

    // ---- approvals ----
    fun approvalPolicy(): StateFlow<ApprovalPolicy> = tools.policy()
    fun updateApprovalPolicy(policy: ApprovalPolicy) = tools.updatePolicy(policy)
    suspend fun pendingApprovals(sessionId: String) = approvals.pendingFifo(sessionId)
    suspend fun pendingApprovalCount() = approvals.pendingCount()
    fun observePendingApprovals(sessionId: String) = approvals.observePending(sessionId)
    suspend fun getUserQuestion(requestId: String): UserQuestionRequest {
        onProcessStart()
        return tools.userQuestion(requestId)
    }
    suspend fun submitUserAnswers(requestId: String, answers: Map<String, UserQuestionAnswer>) =
        writeData { tools.submitUserAnswers(requestId, answers) }
    suspend fun approvalPremiseManifest(approvalId: String, offset: Int = 0, limit: Int = 100): String =
        tools.approvalPremiseManifest(approvalId, offset, limit)
    suspend fun approvalPremiseChunk(approvalId: String, entry: Int, offset: Long = 0, limit: Int = 65_536) =
        tools.approvalPremiseChunk(approvalId, entry, offset, limit)
    suspend fun approve(approvalId: String) = writeData { tools.approve(approvalId) }
    suspend fun reject(approvalId: String) = writeData { tools.reject(approvalId) }

    // ---- tasks ----
    suspend fun getTask(id: String) = tasks.get(id) ?: throw StError.NotFound(id)
    suspend fun recentTasks(limit: Int = 100) = tasks.recent(limit)
    fun observeActiveTasks() = tasks.observeNonTerminal()
    fun observeForegroundHost() = taskHost.observeState()
    fun taskNotificationsEnabled() = taskHost.notificationsEnabled()
    suspend fun fileChangesForTask(taskId: String) = fileChanges.forTask(taskId)
    suspend fun fileChangesForSandbox(sandboxId: String, limit: Int = 200): List<FileChangeRecord> {
        require(limit in 1..2000)
        return fileChanges.forSandbox(sandboxId, limit)
    }

    suspend fun fileChangesPage(sandboxId: String, cursor: FileChangeCursor? = null, limit: Int = 100,
                                throughSequence: Long? = null): FileChangePage {
        onProcessStart()
        return fileChanges.page(sandboxId, cursor, limit, throughSequence)
    }

    suspend fun onProcessStart() {
        initialization.await()
    }

    companion object {
        @Volatile private var instance: SimpleTavernCore? = null

        fun get(context: Context): SimpleTavernCore {
            return instance ?: synchronized(this) {
                instance ?: buildInstance(context.applicationContext).also { instance = it }
            }
        }

        /** Compatibility alias: all workers and foreground stop actions must share process ownership. */
        fun create(context: Context): SimpleTavernCore = get(context)

        private fun buildInstance(context: Context): SimpleTavernCore {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val db = AppDatabase.get(context)
            val paths = StoragePaths(context)
            val dataGate = DataOperationGate()
            val chatRepo = ChatRepository(db)
            val contentRepo = ContentRepository(db)
            val memoryStore = MemoryStore(db)
            val taskHost = ForegroundTaskHost(context, scope)
            val taskStore = TaskStore(db, taskHost, dataGate)
            val approvalStore = ApprovalStore(db)
            val fileChangeStore = FileChangeStore(db)
            val sandboxStore = SandboxStore(db)
            val importStore = ImportStore(db)
            val attachmentStore = AttachmentStore(db, paths)
            val usageStore = UsageStore(db)
            val searchUsage = SearchUsageStore(db, dataGate)
            val speechUsage = SpeechUsageStore(db, dataGate)
            val credentialStore = CredentialStore(context)
            val providerOAuth = ProviderOAuthService(contentRepo,
                CopilotAuthService(contentRepo, credentialStore, dataGate), CodexAuthService(contentRepo, credentialStore, dataGate),
                VertexAuthService(contentRepo, credentialStore, dataGate, chatRepo),
                SubscriptionAuthService(contentRepo, credentialStore, dataGate))
            val providerResolver = ProviderResolver(contentRepo, credentialStore, providerOAuth)
            val geminiCachePrices = GeminiCachePriceStore(db)
            val geminiCacheTasks = GeminiCacheTaskService(chatRepo, providerResolver, taskStore, dataGate, scope, geminiCachePrices,
                MessageAttachmentReader(attachmentStore, paths))
            val llm = LlmClient(prepareGeminiCache = geminiCacheTasks::prepareUse)
            val sandboxMgr = SandboxManager(paths, sandboxStore, fileChangeStore, taskStore, dataGate,
                com.simpletavern.runtime.sandbox.SandboxRuntime(context))
            val tools = ToolRuntime(sandboxMgr, approvalStore, tasks = taskStore)
            val sandboxTasks = SandboxTaskService(taskStore, sandboxMgr, tools, chatRepo, fileChangeStore, scope)
            val searchResolver = WebSearchResolver(db, credentialStore, WebSearchService(searchUsage))
            val chatAttachments = ChatAttachmentService(attachmentStore, chatRepo, paths, context.applicationContext.contentResolver)
            val speech = SpeechResolver(db, credentialStore, SpeechService(speechUsage), SpeechPreprocessor(llm, usageStore, dataGate),
                providerResolver::resolvePreprocessing) { id ->
                val row = attachmentStore.get(id) ?: throw StError.NotFound("managed speech reference")
                com.simpletavern.core.tools.LocalSpeechReference(chatAttachments.copySource(id), row.filename,
                    AttachmentMimeTypes.normalize(row.mimeType))
            }
            val speechCache = SpeechCacheService(db, paths, taskStore)
            val speechTasks = SpeechTaskService(speech, speechCache, taskStore, dataGate, scope)
            val voiceDesignTasks = SpeechVoiceDesignTaskService(speech, db, paths, taskStore, dataGate, scope, chatAttachments)
            val speechPlayback = SpeechPlaybackService(DeviceAudioOutput(context.applicationContext), speech, speechTasks,
                speechCache, taskStore, contentRepo, chatRepo, dataGate, chatAttachments, scope)
            val nativeBackup = NativeBackupService(db, paths, taskStore, dataGate, credentialStore)
            val backupTasks = BackupTaskService(taskStore, nativeBackup, scope, paths, context.applicationContext.contentResolver)
            val nativeRestore = NativeRestoreService(db, paths, taskStore, dataGate, credentialStore)
            val restoreInputs = SourceArchiveInputs(paths, taskStore, dataGate, context.applicationContext.contentResolver,
                scope, ownerKind = TaskKind.RESTORE, root = File(paths.backups, "restore_inputs"),
                maxBytes = NativeBackupService.MAX_BYTES + 512L * 1024 * 1024)
            val restoreTasks = RestoreTaskService(taskStore, nativeRestore, scope, restoreInputs)
            val memory = MemoryService(memoryStore, chatRepo, taskStore, llm, usageStore, scope,
                { providerResolver.resolve(it, useSelectedCache = false) }, contentRepo, { providerResolver.resolveMaintenance(it) })
            val contextBuilder = ContextBuilder(chatRepo, contentRepo, memory)
            val attachmentReader = MessageAttachmentReader(attachmentStore, paths)
            val workspace = AssistantWorkspaceService(AssistantWorkspaceStore(db), contentRepo, chatRepo, attachmentStore)
            val mcp = AssistantMcpService(contentRepo, credentialStore, dataGate, sandboxMgr)
            val mcpPromptApplications = McpPromptApplicationService(contentRepo, chatRepo, attachmentStore, paths, taskStore, dataGate)
            val skillInputRoot = File(context.applicationContext.cacheDir, "skill_inputs").apply { mkdirs() }
            val skillInputs = SourceArchiveInputs(paths, taskStore, dataGate, context.applicationContext.contentResolver,
                scope, root = skillInputRoot, maxBytes = AssistantSkillInstaller.MAX_ARCHIVE_BYTES)
            val copyInputs = SourceArchiveInputs(paths, taskStore, dataGate, context.applicationContext.contentResolver,
                scope, TaskKind.FILE_COPY, paths.copyInputs)
            val copyTasks = FileCopyTaskService(paths, taskStore, fileChangeStore, sandboxMgr, copyInputs, dataGate, scope)
            val exportTasks = SandboxExportTaskService(taskStore, sandboxMgr, workspace, chatAttachments, contentRepo, chatRepo, scope, context.applicationContext.contentResolver)
            val skills = AssistantSkillService(contentRepo, attachmentStore, dataGate,
                AssistantSkillInstaller(contentRepo, attachmentStore, dataGate, paths, skillInputs, skillInputRoot),
                sandboxMgr, copyTasks, chatAttachments)
            val deviceTools = AssistantDeviceTools(context.applicationContext)
            val assistantSpeech = AssistantSpeechService(contentRepo, chatRepo, speechPlayback, dataGate)
            val assistants = AssistantService(contentRepo, chatRepo, memory, contextBuilder, providerResolver, dataGate, workspace, mcp, skills, context.applicationContext, deviceTools, assistantSpeech)
            val conversationReferences = AssistantConversationService(contentRepo, chatRepo)
            val conversation = ConversationService(
                chatRepo, contentRepo, memory, contextBuilder, llm, tools, taskStore, usageStore, scope,
                resolveSearch = { chat, protocol, options -> dataGate.write { searchResolver.forGeneration(chat, protocol, options) } },
                prepareAttachments = attachmentReader::prepare,
                saveToolResult = chatAttachments::saveToolResult,
                saveGeneratedMedia = chatAttachments::saveGeneratedMedia,
                applyExplicitModes = { session, member, provider -> dataGate.write { providerResolver.applyExplicitModes(session, member, provider) } },
                prepareAssistant = assistants::prepare,
                prepareSourceTools = { session, member ->
                    val actorId = member ?: session.characterId
                    val actor = contentRepo.getCharacter(actorId)?.takeIf { it.sourceNamespace == session.sourceNamespace }
                    val config = actor?.let { contentRepo.sourceAssistant(it) }
                    val pool = config?.let { contentRepo.assistantMemoryPool(it) }?.takeIf { it.enabled }
                    config?.let { config ->
                        val memoryTools = pool?.let { AssistantMemoryTools(contentRepo, dataGate, it, allowWrite = true, allowDelete = true) }
                        val referencesEnabled = conversationReferences.enabled(config)
                        val localTools = AssistantLocalTools(contentRepo, dataGate, config, context.applicationContext, deviceTools, assistantSpeech, session.id)
                        val skillTools = skills.tools(config, session.sandboxId)
                        val remoteTools = mcp.prepare(config)
                        val categories = memoryTools?.categories.orEmpty() + localTools.categories + skillTools.categories + remoteTools.categories +
                            (if (referencesEnabled) conversationReferences.categories else emptyMap())
                        if (categories.isEmpty()) { remoteTools.close(); null } else com.simpletavern.core.tools.AssistantToolSession(
                            UUID.randomUUID().toString(),
                            JsonArray(memoryTools?.definitions.orEmpty() + localTools.definitions + skillTools.definitions + remoteTools.definitions +
                                (if (referencesEnabled) conversationReferences.definitions else emptyList())),
                            categories, beforeCall = {
                                val current = chatRepo.getSession(session.id) ?: throw StError.NotFound("chat")
                                if (current.sourceNamespace != session.sourceNamespace || current.overrides.assistant != null || current.sandboxId != session.sandboxId ||
                                    (member == null && current.characterId != actorId) ||
                                    (member != null && (!current.isGroup || actorId !in current.memberIds)))
                                    throw StError.Conflict("source tool chat role changed")
                            }, remoteSchemaTools = remoteTools.categories.keys, forceApprovalTools = remoteTools.forceApprovalTools + localTools.forceApprovalTools,
                            beforeUserQuestion = localTools.beforeUserQuestion,
                            onClose = remoteTools::close, categoryResolver = { name, args -> memoryTools?.category(name, args) ?: localTools.category(name, args) ?: skillTools.category(name, args) }) { name, args ->
                            if (name in memoryTools?.categories.orEmpty()) memoryTools!!.dispatch(name, args)
                            else if (name in localTools.categories) localTools.dispatch(name, args)
                            else if (name in skillTools.categories) skillTools.dispatch(name, args)
                            else if (name in remoteTools.categories) remoteTools.dispatch(name, args)
                            else dataGate.write { conversationReferences.dispatch(config, name, args) }
                        }
                    }
                },
            ) { session, member -> providerResolver.resolve(session, member) }
            val importer = ImportService(paths, chatRepo, contentRepo, memoryStore, importStore, attachmentStore, dataGate)
            val sourceInputs = SourceArchiveInputs(paths, taskStore, dataGate, context.applicationContext.contentResolver, scope)
            val importTasks = ImportTaskService(taskStore, importer, scope, sourceInputs, dataGate, context.applicationContext.cacheDir)
            val core = SimpleTavernCore(
                db, paths, chatRepo, contentRepo, memoryStore, memory, contextBuilder,
                conversation, importer, sandboxMgr, tools, taskStore, approvalStore, fileChangeStore, scope,
                taskHost, sandboxTasks, credentialStore, providerResolver, dataGate, nativeBackup, backupTasks, restoreTasks, importTasks, copyTasks, exportTasks, usageStore, searchResolver, searchUsage, speechUsage, speech, speechTasks, speechCache, geminiCacheTasks, geminiCachePrices, chatAttachments, assistants, providerOAuth, workspace, mcp, skills, deviceTools, speechPlayback, mcpPromptApplications,
                AssistantSkillExporter(contentRepo, chatAttachments, dataGate, context.applicationContext.cacheDir, context.applicationContext.contentResolver),
                voiceDesignTasks,
            )
            core.initialization = scope.async(start = CoroutineStart.LAZY) {
                withContext(Dispatchers.IO) { ModelCatalog.load(context.applicationContext.cacheDir) }
                sandboxMgr.clearAbandonedPremises()
                nativeBackup.clearInterruptedStaging()
                backupTasks.onProcessStart()
                NativeRestoreInspectionService(db, paths, taskStore, dataGate, credentialStore).clearInterruptedStaging()
                nativeRestore.recoverInterrupted()
                speechCache.recover()
                voiceDesignTasks.onProcessStart()
                geminiCacheTasks.onProcessStart()
                searchUsage.interruptPending()
                speechUsage.interruptPending()
                usageStore.interruptPendingTokenRequests()
                importer.onProcessStart()
                importTasks.onProcessStart()
                copyTasks.onProcessStart()
                memory.markSummariesInterruptedOnProcessStart()
                memory.markStateMaintenanceInterruptedOnProcessStart()
                conversation.markInterruptedOnProcessStart()
            }
            core.initialization.start()
            return core
        }

    }
}
