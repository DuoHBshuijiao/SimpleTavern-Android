package com.simpletavern.core.api

import android.content.Context
import com.simpletavern.core.conversation.ConversationService
import com.simpletavern.core.data.*
import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.importer.ImportService
import com.simpletavern.core.llm.LlmClient
import com.simpletavern.core.llm.ProviderConfig
import com.simpletavern.core.memory.ContextBuilder
import com.simpletavern.core.memory.MemoryService
import com.simpletavern.core.model.*
import com.simpletavern.core.tools.ToolRuntime
import com.simpletavern.runtime.sandbox.SandboxManager
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Public business facade for future Compose UI (Codex).
 * UI must call this API only — never Room tables or sandbox paths directly.
 *
 * Threading: suspend functions are main-safe; heavy IO uses Dispatchers.IO internally.
 * Streaming does not depend on UI subscribers; events are buffered per chat.
 */
class SimpleTavernCore private constructor(
    val db: AppDatabase,
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
) {
    // ---- config & content ----
    suspend fun listCharacters() = content.listCharacters()
    suspend fun getCharacter(id: String) = content.getCharacter(id) ?: throw StError.NotFound(id)
    suspend fun saveCharacter(c: CharacterCard) = content.saveCharacter(c)
    suspend fun listPersonas() = content.listPersonas()
    suspend fun savePersona(p: UserPersona) = content.savePersona(p)
    suspend fun listWorldBooks() = content.listWorldBooks()
    suspend fun saveWorldBook(b: WorldBook) = content.saveWorldBook(b)
    suspend fun getSettingsJson() = content.getSettingsJson()
    suspend fun saveSettingsJson(json: String) = content.saveSettingsJson(json, Instant.now().toString())
    suspend fun getAssistant() = content.getAssistant()
    suspend fun saveAssistant(cfg: AssistantConfig) = content.saveAssistant(cfg)
    suspend fun listPresets() = content.listPresets()
    suspend fun savePreset(p: ModelPreset) = content.savePreset(p)

    suspend fun listChats() = chats.listSessions()
    suspend fun getChat(id: String) = chats.getSession(id) ?: throw StError.NotFound(id)
    suspend fun saveChat(session: ChatSession) = chats.saveSession(session)
    suspend fun deleteChat(id: String) = chats.deleteSession(id)
    suspend fun createChat(characterId: String, title: String = "新对话", isGroup: Boolean = false, memberIds: List<String> = emptyList(), sandboxId: String? = null) =
        conversation.createChat(characterId, title, isGroup, memberIds, sandboxId)

    // ---- messages ----
    suspend fun pageMessages(chatId: String, limit: Int, offset: Int) = chats.pageMessages(chatId, limit, offset)
    suspend fun messageCount(chatId: String) = chats.messageCount(chatId)
    suspend fun selectCandidate(nodeId: String, index: Int) = chats.selectCandidate(nodeId, index)
    suspend fun addCandidate(nodeId: String, candidate: MessageCandidate, select: Boolean = true) = chats.addCandidate(nodeId, candidate, select)
    suspend fun editSelected(nodeId: String, content: String) = chats.editSelected(nodeId, content)
    suspend fun searchMessages(chatId: String, query: String) = chats.search(chatId, query)
    suspend fun forkChat(sourceChatId: String, forkAtMessageId: String, newTitle: String? = null) =
        conversation.fork(sourceChatId, forkAtMessageId, newTitle)
    suspend fun forkLineage(chatId: String) = conversation.forkLineage(chatId)

    fun observeGeneration(chatId: String): Flow<StreamEvent> = conversation.observe(chatId)

    suspend fun startGeneration(chatId: String, userText: String?, provider: ProviderConfig, interjectCharacterId: String? = null) =
        conversation.startGeneration(chatId, userText, provider, interjectCharacterId)

    suspend fun stopTask(taskId: String) = conversation.stop(taskId)

    // ---- memory ----
    suspend fun getMemory(chatId: String) = memory.get(chatId)
    suspend fun editMemory(chatId: String, content: String, expectedVersion: Long?) = memory.edit(chatId, content, expectedVersion)
    suspend fun summarizeMemory(chatId: String, provider: ProviderConfig) = memory.summarize(chatId, provider)
    suspend fun buildContext(chatId: String): ContextBuildResult {
        val session = getChat(chatId)
        return contextBuilder.build(session)
    }
    suspend fun getMvu(chatId: String) = memory.getMvu(chatId)
    suspend fun saveMvu(state: MvuState, expectedVersion: Int?) = memory.saveMvu(state, expectedVersion)
    suspend fun getKg(chatId: String) = memory.getKg(chatId)
    suspend fun saveKg(kg: KnowledgeGraph, expectedVersion: Int?) = memory.saveKg(kg, expectedVersion)
    suspend fun upsertKgEntity(chatId: String, entity: KgEntity, expectedVersion: Int?) = memory.upsertKgEntity(chatId, entity, expectedVersion)
    suspend fun upsertKgRelation(chatId: String, relation: KgRelation, expectedVersion: Int?) = memory.upsertKgRelation(chatId, relation, expectedVersion)

    // ---- import / export ----
    suspend fun preflightImport(zip: File) = importer.preflight(zip)
    suspend fun runImport(zip: File, sourceHint: String? = null) = importer.execute(zip, sourceHint)
    fun cancelImport() = importer.requestCancel()
    fun observeImportProgress() = importer.observeProgress()

    suspend fun exportBackup(destZip: File, options: BackupOptions): File = withContext(Dispatchers.IO) {
        // Consistency: refuse when non-terminal tasks exist unless caller accepts incomplete (we fail closed).
        val active = tasks.nonTerminal()
        if (active.isNotEmpty()) {
            throw StError.Conflict("active tasks prevent consistent backup", mapOf("count" to active.size.toString()))
        }
        destZip.parentFile?.mkdirs()
        ZipOutputStream(destZip.outputStream()).use { zip ->
            if (options.includeConfig) {
                content.getSettingsJson()?.let { putText(zip, "settings.json", it) }
                content.getAssistant()?.let { putText(zip, "assistant_settings.json", it.settingsJson) }
            }
            if (options.includeDatabase) {
                // Export structured JSON snapshot instead of live DB file copy
                val sessions = chats.listSessions()
                sessions.forEach { s ->
                    val nodes = chats.pageMessages(s.id, Int.MAX_VALUE, 0)
                    val payload = Jsonx.encode(mapOf("session" to Jsonx.encode(s), "nodes" to Jsonx.encode(nodes)))
                    putText(zip, "chats/${s.id}/export.json", payload)
                    if (options.includeMemory) {
                        memory.get(s.id)?.let { putText(zip, "chats/${s.id}/long_term_memory.txt", it.content) }
                        memory.getKg(s.id)?.let { putText(zip, "chats/${s.id}/knowledge_graph.json", Jsonx.encode(it)) }
                        memory.getMvu(s.id)?.let { putText(zip, "chats/${s.id}/mvu_state.json", Jsonx.encode(it)) }
                    }
                }
                content.listCharacters().forEach { putText(zip, "characters/${it.id}.json", Jsonx.encode(it)) }
                content.listPersonas().forEach { putText(zip, "personas/${it.id}.json", Jsonx.encode(it)) }
                content.listWorldBooks().forEach { putText(zip, "world_books/${it.id}.json", Jsonx.encode(it)) }
            }
            if (options.includeAttachments) {
                paths.attachments.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = "attachments/" + f.relativeTo(paths.attachments).path.replace('\\', '/')
                    zip.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            if (options.includeSandboxUserFiles) {
                paths.sandboxes.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = "sandboxes/" + f.relativeTo(paths.sandboxes).path.replace('\\', '/')
                    zip.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            putText(
                zip,
                "BACKUP_META.json",
                Jsonx.encode(
                    mapOf(
                        "createdAt" to Instant.now().toString(),
                        "options" to Jsonx.encode(options),
                        "credentialsIncluded" to options.includeCredentials.toString(),
                        "runtimeNote" to "Rebuildable runtimes not included unless includeRebuildableRuntimes; offline limits apply",
                    ),
                ),
            )
        }
        destZip
    }

    // ---- sandbox ----
    suspend fun createSandbox(name: String) = sandbox.create(name)
    suspend fun listSandboxes() = sandbox.list()
    suspend fun deleteSandbox(id: String) = sandbox.delete(id)
    suspend fun importIntoSandbox(sandboxId: String, source: File, relativeTarget: String) =
        sandbox.importFile(sandboxId, source, relativeTarget)
    suspend fun sandboxListDir(sandboxId: String, path: String) = sandbox.listDir(sandboxId, path)
    suspend fun sandboxRead(sandboxId: String, path: String) = sandbox.readFile(sandboxId, path)
    suspend fun sandboxWrite(sandboxId: String, taskId: String, path: String, content: String) =
        sandbox.writeFile(sandboxId, taskId, path, content)
    suspend fun runShell(sandboxId: String, taskId: String, command: String) = sandbox.runShell(sandboxId, taskId, command)

    // ---- approvals ----
    fun approvalPolicy(): StateFlow<ApprovalPolicy> = tools.policy()
    fun updateApprovalPolicy(policy: ApprovalPolicy) = tools.updatePolicy(policy)
    suspend fun pendingApprovals(sessionId: String) = approvals.pendingFifo(sessionId)
    suspend fun pendingApprovalCount() = approvals.pendingCount()
    suspend fun approve(approvalId: String) = tools.approve(approvalId)
    suspend fun reject(approvalId: String) = tools.reject(approvalId)

    // ---- tasks ----
    suspend fun getTask(id: String) = tasks.get(id) ?: throw StError.NotFound(id)
    suspend fun recentTasks(limit: Int = 100) = tasks.recent(limit)
    suspend fun fileChangesForTask(taskId: String) = fileChanges.forTask(taskId)

    suspend fun onProcessStart() {
        conversation.markInterruptedOnProcessStart()
    }

    companion object {
        @Volatile private var instance: SimpleTavernCore? = null

        fun get(context: Context): SimpleTavernCore {
            return instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }
        }

        fun create(context: Context): SimpleTavernCore {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val db = AppDatabase.get(context)
            val paths = StoragePaths(context)
            val chatRepo = ChatRepository(db)
            val contentRepo = ContentRepository(db)
            val memoryStore = MemoryStore(db)
            val taskStore = TaskStore(db)
            val approvalStore = ApprovalStore(db)
            val fileChangeStore = FileChangeStore(db)
            val sandboxStore = SandboxStore(db)
            val importStore = ImportStore(db)
            val attachmentStore = AttachmentStore(db, paths)
            val usageStore = UsageStore(db)
            val llm = LlmClient()
            val sandboxMgr = SandboxManager(paths, sandboxStore, fileChangeStore)
            val tools = ToolRuntime(sandboxMgr, approvalStore)
            val memory = MemoryService(memoryStore, chatRepo, taskStore, llm)
            val contextBuilder = ContextBuilder(chatRepo, contentRepo, memory)
            val conversation = ConversationService(
                chatRepo, contentRepo, memory, contextBuilder, llm, tools, taskStore, usageStore, scope,
            )
            val importer = ImportService(paths, chatRepo, contentRepo, memoryStore, importStore, attachmentStore)
            val core = SimpleTavernCore(
                db, paths, chatRepo, contentRepo, memoryStore, memory, contextBuilder,
                conversation, importer, sandboxMgr, tools, taskStore, approvalStore, fileChangeStore, scope,
            )
            scope.launch { core.onProcessStart() }
            return core
        }

        private fun putText(zip: ZipOutputStream, name: String, text: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
