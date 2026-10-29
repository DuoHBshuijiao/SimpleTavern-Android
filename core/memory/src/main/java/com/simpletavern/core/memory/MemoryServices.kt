package com.simpletavern.core.memory

import com.simpletavern.core.data.ChatRepository
import com.simpletavern.core.data.ContentRepository
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.data.MemoryStore
import com.simpletavern.core.data.UsageStore
import com.simpletavern.core.data.TaskStore
import com.simpletavern.core.llm.LlmClient
import com.simpletavern.core.llm.ProviderConfig
import com.simpletavern.core.model.*
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.*

class MemoryService(
    private val store: MemoryStore,
    private val chats: ChatRepository,
    private val tasks: TaskStore,
    private val llm: LlmClient,
    usage: UsageStore,
    scope: CoroutineScope,
    resolveProvider: suspend (ChatSession) -> ProviderConfig,
    content: ContentRepository,
    resolveMaintenanceProvider: suspend (ChatSession) -> ProviderConfig,
) {
    suspend fun get(chatId: String) = store.getMemory(chatId)

    suspend fun copySnapshot(
        sourceChatId: String,
        targetChatId: String,
        nodeIds: Map<String, String>,
        messageIds: Map<String, String>,
    ): Unit = chats.transaction {
        val updatedAt = Instant.now().toString()
        fun remapKnownMessageReference(id: String?): String? = id?.let { messageIds[it] ?: nodeIds[it] ?: it }

        store.getMemory(sourceChatId)?.let { source ->
            store.saveMemory(source.copy(
                chatId = targetChatId,
                version = 1,
                anchorMessageId = source.anchorMessageId?.let { messageIds[it] ?: nodeIds[it] },
                updatedAt = updatedAt,
                source = "chat_branch",
            ))
        }
        store.getMvu(sourceChatId)?.let { source ->
            store.saveMvu(source.copy(
                chatId = targetChatId,
                version = 1,
                updatedAt = updatedAt,
                source = "chat_branch",
            ))
        }
        store.getKg(sourceChatId)?.let { source ->
            store.saveKg(source.copy(
                chatId = targetChatId,
                entities = source.entities.map { it.copy(firstMentionedAt = remapKnownMessageReference(it.firstMentionedAt)) },
                relations = source.relations.map { it.copy(establishedAt = remapKnownMessageReference(it.establishedAt)) },
                version = 1,
                updatedAt = updatedAt,
                source = "chat_branch",
            ))
        }
        Unit
    }

    private val summaries = MemorySummaryService(store, chats, tasks, llm, usage, scope, resolveProvider)
    private val regex = ContentRegexService(store, chats, content)
    private val maintenance = StateMaintenanceService(store, chats, content, tasks, llm, usage, scope, resolveMaintenanceProvider, regex)
    fun signalHostInterruption(reason: TaskTerminalReason) {
        summaries.signalHostInterruption(reason)
        maintenance.signalHostInterruption(reason)
    }

    suspend fun startMvuUpdate(chatId: String, includeKg: Boolean = true, provider: ProviderConfig? = null): TaskSnapshot {
        regex.scan(chatId)
        return maintenance.start(chatId, true, includeKg, provider)
    }
    suspend fun startKgUpdate(chatId: String, provider: ProviderConfig? = null) = maintenance.start(chatId, false, true, provider)
    suspend fun startStateHistoryRebuild(chatId: String, maintainMvu: Boolean = true, includeKg: Boolean = true,
                                        provider: ProviderConfig? = null) = maintenance.rebuild(chatId, maintainMvu, includeKg, provider)
    suspend fun getStateMaintenance(id: String) = maintenance.get(id)
    suspend fun listStateMaintenance(chatId: String, limit: Int = 100) = maintenance.list(chatId, limit)
    suspend fun listMvuLogs(chatId: String, limit: Int = 100) = store.listMvuLogs(chatId, limit)
    suspend fun stopStateMaintenance(id: String, reason: TaskTerminalReason = TaskTerminalReason.USER_STOP) = maintenance.stop(id, reason)
    suspend fun markStateMaintenanceInterruptedOnProcessStart() = maintenance.recover()
    suspend fun maybeAutoMaintain(chatId: String, throughMessageId: String): TaskSnapshot? {
        regex.scan(chatId, throughMessageId)
        return maintenance.maybeAuto(chatId, throughMessageId)
    }

    suspend fun effectiveContentRegexRules(chatId: String) = regex.listRules(chatId)
    suspend fun sessionContentRegexRules(chatId: String) = regex.sessionRules(chatId)
    suspend fun characterContentRegexRules(characterId: String) = regex.characterRules(characterId)
    suspend fun saveSessionContentRegexRules(chatId: String, rules: List<ContentRegexRule>, expected: List<ContentRegexRule>) = regex.saveSessionRules(chatId, rules, expected)
    suspend fun saveCharacterContentRegexRules(characterId: String, rules: List<ContentRegexRule>, expected: List<ContentRegexRule>) = regex.saveCharacterRules(characterId, rules, expected)
    suspend fun upsertSessionContentRegexRule(chatId: String, rule: ContentRegexRule, expected: List<ContentRegexRule>) = regex.upsert(chatId, rule, expected)
    suspend fun deleteSessionContentRegexRule(chatId: String, id: String, expected: List<ContentRegexRule>) = regex.delete(chatId, id, expected)
    suspend fun previewContentRegex(chatId: String, text: String, role: ChatRole = ChatRole.assistant) = regex.preview(chatId, text, role)
    suspend fun previewMessageContentRegex(chatId: String, messageId: String) = regex.previewMessage(chatId, messageId)
    suspend fun scanContentRegex(chatId: String, throughMessageId: String? = null) = regex.scan(chatId, throughMessageId)
    suspend fun getContentRegexRecord(chatId: String, messageId: String) = regex.getRecord(chatId, messageId)
    suspend fun listContentRegexRecords(chatId: String, limit: Int = 100) = regex.records(chatId, limit)
    suspend fun pendingContentRegex(chatId: String, limit: Int = 100) = regex.pending(chatId, limit = limit)
    suspend fun projectRegexContent(session: ChatSession, candidates: List<MessageCandidate>) = regex.project(session, candidates)
    suspend fun logAutoMaintenanceFailure(chatId: String, error: Exception) = store.appendMvuLog(
        java.util.UUID.randomUUID().toString(), chatId, Instant.now().toString(), "auto_signal_failed",
        "automatic maintenance was not queued (${(error as? StError)?.code ?: "error"})", null)
    suspend fun logAutoSummaryFailure(chatId: String, error: Exception) = store.appendMvuLog(
        java.util.UUID.randomUUID().toString(), chatId, Instant.now().toString(), "auto_summary_failed",
        "automatic summary was not queued (${(error as? StError)?.code ?: "error"})", null)

    suspend fun edit(chatId: String, content: String, expectedVersion: Long?): LongTermMemory = chats.transaction {
        val current = store.getMemory(chatId)
        if (chats.getSession(chatId) == null) throw StError.NotFound(chatId)
        store.saveMemory(LongTermMemory(chatId, content, version = current?.version ?: 0,
            anchorMessageId = current?.anchorMessageId, updatedAt = Instant.now().toString(), source = "user"), expectedVersion)
    }

    suspend fun summarize(chatId: String, provider: ProviderConfig, manual: Boolean = true): MemorySummaryJob =
        summaries.await(summaries.start(chatId, provider, manual = manual).id)

    suspend fun startSummary(chatId: String, provider: ProviderConfig? = null, rebuild: Boolean = false) =
        summaries.start(chatId, provider, rebuild = rebuild)
    suspend fun getSummaryJob(id: String) = summaries.get(id)
    suspend fun getSummaryProgress(id: String) = summaries.progress(id)
    suspend fun listSummaryJobs(chatId: String) = summaries.list(chatId)
    suspend fun pendingSummary(chatId: String) = summaries.pending(chatId)
    suspend fun confirmSummary(id: String) = summaries.confirm(id)
    suspend fun dismissSummary(id: String) = summaries.dismiss(id)
    suspend fun stopSummary(id: String, reason: TaskTerminalReason = TaskTerminalReason.USER_STOP) = summaries.stop(id, reason)
    suspend fun markSummariesInterruptedOnProcessStart() = summaries.recover()

    suspend fun maybeAutoSummarize(chatId: String, throughMessageId: String? = null) =
        summaries.maybeAuto(chatId, throughMessageId)
    suspend fun maybeAutoSummarize(session: ChatSession) = summaries.maybeAuto(session.id, null)

    suspend fun bootstrapMvuFromCharacter(chatId: String, tablesJson: String) {
        val tables = Jsonx.decodeOrNull<List<StatusTableDef>>(tablesJson)?.takeIf { it.isNotEmpty() } ?: return
        store.saveMvu(
            MvuState(chatId = chatId, tables = tables, updatedAt = Instant.now().toString(), source = "chat_assistant"),
        )
    }

    suspend fun getMvu(chatId: String) = store.getMvu(chatId)
    suspend fun saveMvu(state: MvuState, expected: Int?) = chats.transaction {
        if (chats.getSession(state.chatId) == null) throw StError.NotFound(state.chatId)
        store.saveMvu(state, expected)
    }
    suspend fun getKg(chatId: String) = store.getKg(chatId)
    suspend fun saveKg(kg: KnowledgeGraph, expected: Int?) = chats.transaction {
        if (chats.getSession(kg.chatId) == null) throw StError.NotFound(kg.chatId)
        store.saveKg(kg, expected)
    }

    suspend fun upsertKgEntity(chatId: String, entity: KgEntity, expectedVersion: Int?): KnowledgeGraph = chats.transaction {
        if (chats.getSession(chatId) == null) throw StError.NotFound(chatId)
        if (entity.id.isBlank() || entity.name.isBlank() || entity.type !in StateMaintenanceDraft.entityTypes) throw StError.Validation("invalid graph entity")
        val current = store.getKg(chatId) ?: KnowledgeGraph(chatId = chatId)
        val byId = current.entities.firstOrNull { it.id == entity.id }
        val byName = current.entities.firstOrNull { it.name == entity.name && it.type == entity.type && !it.deleted }
        if (byId != null && byName != null && byId.id != byName.id) throw StError.Conflict("entity identity conflicts with an existing name and type")
        val existing = byId ?: byName
        val next = entity.copy(id = existing?.id ?: entity.id, properties = existing?.properties.orEmpty() + entity.properties,
            firstMentionedAt = existing?.firstMentionedAt ?: entity.firstMentionedAt)
        val relations = if (next.deleted) current.relations.filterNot { it.subject == next.id || it.`object` == next.id } else current.relations
        store.saveKg(current.copy(entities = current.entities.filterNot { it.id == next.id } + next,
            relations = relations, updatedAt = Instant.now().toString(), source = "user"), expectedVersion)
    }

    suspend fun upsertKgRelation(chatId: String, relation: KgRelation, expectedVersion: Int?): KnowledgeGraph = chats.transaction {
        if (chats.getSession(chatId) == null) throw StError.NotFound(chatId)
        if (relation.predicate.isBlank() || relation.`object`.isBlank() || !relation.confidence.isFinite() || relation.confidence !in 0.0..1.0) throw StError.Validation("invalid graph relation")
        val current = store.getKg(chatId) ?: KnowledgeGraph(chatId = chatId)
        val entityIds = current.entities.filter { !it.deleted }.map { it.id }.toSet()
        if (relation.subject !in entityIds) throw StError.Validation("subject missing")
        if (current.entities.any { it.id == relation.`object` && it.deleted }) throw StError.Validation("object entity is deleted")
        val others = current.relations.filterNot {
            it.subject == relation.subject && it.predicate == relation.predicate && it.`object` == relation.`object`
        }
        val existing = current.relations.firstOrNull {
            it.subject == relation.subject && it.predicate == relation.predicate && it.`object` == relation.`object`
        }
        store.saveKg(current.copy(relations = others + relation.copy(establishedAt = existing?.establishedAt ?: relation.establishedAt),
            updatedAt = Instant.now().toString(), source = "user"), expectedVersion)
    }

    fun renderKgForPrompt(kg: KnowledgeGraph): String {
        val alive = kg.entities.filter { !it.deleted }
        val byType = alive.groupBy { it.type }
        return buildString {
            appendLine("<KnowledgeGraph>")
            byType.forEach { (type, ents) ->
                appendLine("[$type]")
                ents.forEach { e ->
                    val props = e.properties.entries.joinToString("，") { "${it.key}${it.value}" }
                    appendLine("- ${e.name}${if (props.isNotBlank()) "：$props" else ""}")
                }
            }
            appendLine("[关系]")
            val activeIds = alive.map { it.id }.toSet()
            val deletedIds = kg.entities.filter { it.deleted }.map { it.id }.toSet()
            kg.relations.filter { it.subject in activeIds && it.`object` !in deletedIds }.forEach { r ->
                val s = alive.find { it.id == r.subject }?.name ?: r.subject
                val o = alive.find { it.id == r.`object` }?.name ?: r.`object`
                appendLine("- $s ${r.predicate} $o（置信度: ${r.confidence}）")
            }
            appendLine("</KnowledgeGraph>")
        }
    }

    fun renderMvuForPrompt(state: MvuState): String = buildString {
        appendLine("<StateVariables>")
        state.tables.forEach { table ->
            appendLine("[${table.name}]")
            val columns = table.columns.filterNot { it == "field" }
            appendLine((listOf("field") + columns).joinToString(" | "))
            table.rows.forEach { row ->
                appendLine((listOf(row.field) + columns.map { row.cells[it].orEmpty() }).joinToString(" | "))
            }
        }
        appendLine("</StateVariables>")
    }
}

class ContextBuilder(
    private val chats: ChatRepository,
    private val content: ContentRepository,
    private val memory: MemoryService,
    private val budgetTokens: Int = 0,
) {
    suspend fun contextBudgetFor(session: ChatSession, characterIdOverride: String? = null): Int =
        resolveContextBudget(session, if (session.isGroup) session.memberSettings[characterIdOverride ?: session.characterId] else null,
            settingsFor(session.sourceNamespace))

    suspend fun pureAiFor(session: ChatSession): Boolean =
        session.overrides.pureAiMode ?: ((settingsFor(session.sourceNamespace)["pureAiMode"] as? JsonPrimitive)?.booleanOrNull == true)

    /** Use the same persona and pure-AI selection for new input snapshots and prompt construction. */
    suspend fun userPersonaFor(session: ChatSession, pureAi: Boolean? = null): UserPersona? {
        val personaId = session.userPersonaId ?: return null
        val isPureAi = pureAi ?: pureAiFor(session)
        return if (isPureAi) null else content.getPersona(personaId)
    }

    suspend fun build(session: ChatSession, characterIdOverride: String? = null,
        throughOrder: Long = Long.MAX_VALUE, outputTokens: Int = 0, toolsJson: String? = null): ContextBuildResult {
        val notes = mutableListOf<String>()
        val characterId = characterIdOverride ?: session.characterId
        val character = content.getCharacter(characterId)
        val memberSettings = if (session.isGroup) session.memberSettings[characterId] else null
        val settings = settingsFor(session.sourceNamespace)
        val prompts = settings["prompts"] as? JsonObject ?: JsonObject(emptyMap())
        val pureAi = session.overrides.pureAiMode ?: ((settings["pureAiMode"] as? JsonPrimitive)?.booleanOrNull == true)
        val persona = userPersonaFor(session, pureAi)
        val charName = character?.name?.takeIf { it.isNotBlank() } ?: "角色"
        val userName = persona?.name?.takeIf { it.isNotBlank() } ?: "用户"
        fun render(text: String) = renderPlaceholders(text, charName, userName)
        val contextBudget = resolveContextBudget(session, memberSettings, settings)
        val requestReserve = outputTokens.coerceAtLeast(0).toLong() + ContextTokens.tools(toolsJson)
        val template = messageTemplate(session)
        val messages = mutableListOf<ContextMessage>()
        var injectedMemory = false
        var injectedMvu = false
        var injectedKg = false
        val worldBookIds = mutableListOf<String>()

        val systemParts = mutableListOf<String>()
        val sessionPrompt = session.overrides.prompt?.takeIf { session.overrides.sessionSystemPromptEnabled && it.isNotBlank() }
        val promptMode = session.overrides.sessionSystemPromptMode
        val replacing = sessionPrompt != null && promptMode in setOf("override", "override_global")
        if (!replacing) (prompts["globalSystem"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { systemParts += it }
        character?.takeUnless { sessionPrompt != null && promptMode == "override" }?.let { c ->
            if (c.name.isNotBlank()) systemParts += "角色：${c.name}"
            if (c.systemPrompt.isNotBlank()) systemParts += c.systemPrompt
            if (c.description.isNotBlank()) systemParts += "描述：${c.description}"
            if (memberSettings?.includePersonality != false && c.personality.isNotBlank()) systemParts += "性格：${c.personality}"
            if (memberSettings?.includeScenario != false && c.scenario.isNotBlank()) systemParts += "场景：${c.scenario}"
            if (c.exampleDialogue.isNotBlank()) systemParts += "<ExampleDialogue>\n${c.exampleDialogue}\n</ExampleDialogue>"
        }
        sessionPrompt?.let { systemParts += it }

        val memberNames = if (session.isGroup) session.memberIds.associateWith { id ->
            content.getCharacter(id)?.name ?: id
        } else emptyMap()
        if (session.isGroup) {
            systemParts += "群聊成员：${memberNames.values.joinToString("、")}。你当前扮演${character?.name ?: characterId}，只输出该角色的回复，不代替其他成员或用户发言。历史中【成员：…】标记表示其他角色的发言。"
        }

        persona?.let { systemParts += "用户身份：${it.name}\n${it.description}" }

        val sourceAssistant = character?.takeIf { it.sourceNamespace == session.sourceNamespace }?.let { content.sourceAssistant(it) }
        val sourceMemory = sourceAssistant?.let { content.sourceAssistantMemory(it) }
        sourceAssistant?.let { content.skills.prompt(it) }?.takeIf { it.isNotBlank() }?.let { systemParts += it }
        if (!sourceMemory.isNullOrBlank()) {
            systemParts += "<AssistantMemory>\n$sourceMemory\n</AssistantMemory>"
            injectedMemory = true
        }
        val mem = memory.get(session.id)
        val memoryText = mem?.content ?: session.overrides.longTermMemory
        if (!memoryText.isNullOrBlank()) {
            systemParts += "<LongTermMemory>\n$memoryText\n</LongTermMemory>"
            injectedMemory = true
        }

        val carrier = if (characterId == session.characterId) character else content.getCharacter(session.characterId)
        val mvuRuntimeEnabled = if (session.isGroup) session.overrides.groupMvuEnabled ?: (carrier?.mvuEnabled == true) else carrier?.mvuEnabled == true
        val mvu = if (mvuRuntimeEnabled) memory.getMvu(session.id) else null
        var directiveState: String? = null
        if (mvu != null && mvu.tables.isNotEmpty()) {
            val mvuMode = session.overrides.mvuMode ?: if (session.isGroup) {
                val templateId = session.overrides.groupMvuTemplateCharacterId ?: session.overrides.groupMvuAnchorCharacterId
                val template = if (templateId == null || templateId == carrier?.id) carrier else
                    content.getCharacter(templateId) ?: throw StError.NotFound("MVU template character $templateId")
                template?.mvuMode
            } else carrier?.mvuMode
            val stateText = memory.renderMvuForPrompt(mvu)
            if (mvuMode == "directive") directiveState = stateText
            else { systemParts += stateText; injectedMvu = true }
        }
        val kgEnabled = mvuRuntimeEnabled && session.overrides.knowledgeGraphEnabled != false
        val kg = if (kgEnabled) memory.getKg(session.id) else null
        val graphText = kg?.takeIf { it.entities.isNotEmpty() || it.relations.isNotEmpty() }?.let { memory.renderKgForPrompt(it) }
        val identityGuard = if (session.isGroup) "[仅允许使用${charName}的身份输出下一条回复。]" else null
        val prefill = if ((prompts["globalPrefillEnabled"] as? JsonPrimitive)?.booleanOrNull != false)
            (prompts["globalPrefill"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { render(it.trim()) } else null

        // World books are separate history injections, not permanently prepended role instructions.
        val startId = session.overrides.contextStartMessageId
        val keepBefore = anchorKeepBefore(session.overrides.contextStartKeepBeforeMessages)
        val messageLimit = session.overrides.contextMessageLimit ?: 0
        if (messageLimit < 0) throw StError.Validation("context message limit must be nonnegative")
        val historyWindow = if (messageLimit > 0) chats.transaction {
            countLimitedHistory(session.id, messageLimit, notes, throughOrder,
                anchorOrder = startId?.let { chats.contextAnchorOrder(session.id, it) }, keepBefore = keepBefore)
        } else {
            val (startOrder, recent) = if (startId == null) {
                null to chats.selectedMessages(session.id, throughOrder = throughOrder)
            } else chats.transaction {
                val order = chats.contextAnchorOrder(session.id, startId)
                val after = order?.takeIf { it > 0 }?.minus(1) ?: -1L
                val tail = chats.selectedMessages(session.id, afterOrder = after, throughOrder = throughOrder)
                val selected = when {
                    // Main-chat anchors beyond the requested history retain the original all-history fallback.
                    order != null && tail.isEmpty() -> chats.selectedMessages(session.id, throughOrder = throughOrder)
                    after >= 0 && keepBefore > 0 -> chats.selectedWindow(session.id, after, keepBefore)
                        .filter { it.orderIndex >= 0 } + tail
                    else -> tail
                }
                order to selected
            }
            val startIndex = startOrder?.let { order -> recent.indexOfFirst { it.orderIndex >= order } }?.takeIf { it >= 0 } ?: 0
            val from = (startIndex - keepBefore).coerceAtLeast(0)
            recent.drop(from).map { it.candidate }
        }
        val books = content.activeWorldBooks(session, character)
        val defaultScanDepth = (settings["worldBookEntryScanDepthDefault"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: 2
        notes += if (throughOrder == Long.MAX_VALUE) "history_source=all_selected_messages"
            else "history_source=selected_messages_through_order:$throughOrder"
        notes += "context_size=$contextBudget"

        if (systemParts.isNotEmpty()) {
            messages += ContextMessage(ChatRole.system, systemParts.joinToString("\n\n") { render(it) })
        }

        val rawWindow = memory.projectRegexContent(session, historyWindow)
        val historyNames = mutableMapOf<String, String>().apply { putAll(memberNames); put(characterId, charName) }
        // Resolve historical names only for bodies that use them, before content-sensitive grouping.
        val window = rawWindow.map { message ->
            if (message.role == ChatRole.tool) return@map message
            val needsCharacterName = message.content.contains("{{char}}")
            if (!needsCharacterName && !message.content.contains("{{user}}")) return@map message
            val historicalName = if (needsCharacterName) message.characterId?.let { id ->
                historyNames.getOrPut(id) { content.getCharacter(id)?.name ?: charName }
            } ?: charName else charName
            val rendered = renderPlaceholders(message.content, historicalName,
                message.senderName?.takeIf { it.isNotBlank() } ?: userName)
            if (rendered == message.content) message else message.copy(content = rendered)
        }

        // Entry configuration is frozen for this build; only history-dependent activation is rematched below.
        val preparedBooks = books.map { book -> book to book.entries.filter { it.enabled }.sortedBy { it.orderIndex } }
        val hasWorldBooks = books.isNotEmpty()
        // Reuse a bounded set across both passes; entries beyond it still compile and match normally.
        val compiledBookPatterns = mutableMapOf<Pair<String, Boolean>, Regex>()
        val attachmentsByBook = buildMap {
            // Duplicate attachments retain the original first-match override.
            for (attachment in session.overrides.worldBookAttachments) {
                if (attachment.worldBookId !in this) put(attachment.worldBookId, attachment)
            }
        }
        fun injections(history: List<ContextMessage>): List<WorldBookInjection> {
            var explicitScanHistory: List<ContextMessage>? = null
            // Keep only the latest window text; the final, budget-trimmed pass starts with fresh state.
            var previousDepth = -1
            var previousExplicit = false
            var scanText = ""
            return preparedBooks.flatMap { (book, enabledEntries) ->
                val attachment = attachmentsByBook[book.id]
                val bookScanDepth = attachment?.scanDepth?.takeIf { it >= 1 } ?: defaultScanDepth
                val entries = enabledEntries.filter { entry ->
                    val scanDepth = entry.scanDepth?.coerceAtLeast(0) ?: bookScanDepth
                    if (entry.constantActive || entry.scanDepth == null && scanDepth == 0) return@filter true
                    if (entry.regex.isBlank()) return@filter false
                    val explicit = entry.scanDepth != null
                    val scanned = if (!explicit) history else explicitScanHistory ?: history
                        .filter { it.role != ChatRole.system && it.role != ChatRole.tool }.also { explicitScanHistory = it }
                    val depth = scanDepth.coerceAtMost(scanned.size)
                    if (depth != previousDepth || explicit != previousExplicit) {
                        scanText = scanned.takeLast(depth).joinToString("\n") { it.content }
                        previousDepth = depth
                        previousExplicit = explicit
                    }
                    runCatching {
                        val multiline = entry.position == null
                        val key = entry.regex to multiline
                        val pattern = compiledBookPatterns[key] ?: Regex(entry.regex,
                            if (multiline) setOf(RegexOption.MULTILINE) else emptySet()).also {
                            if (compiledBookPatterns.size < 64) compiledBookPatterns[key] = it
                        }
                        pattern.containsMatchIn(scanText)
                    }.getOrElse { notes += "invalid_worldbook_regex_skipped"; false }
                }
                val text = entries.filter { it.position == null && it.content.isNotBlank() }
                    .joinToString("\n\n") { render("【${it.title}】\n${it.content.trim()}") }
                val desktop = if (text.isBlank()) emptyList() else listOf(
                    WorldBookInjection(book.id, attachment?.insertDepth?.coerceAtLeast(1) ?: 5, text))
                desktop + entries.filter { it.position != null && it.content.isNotBlank() }.map { entry ->
                    WorldBookInjection(book.id, entry.injectDepth.coerceAtLeast(1), render(entry.content),
                        entry.position, entry.role, entry.priority)
                }
            }
        }

        notes += "token_estimate_strategy=${ContextTokens.STRATEGY}"
        notes += "reserved_output_tokens=$outputTokens"
        notes += "reserved_tool_tokens=${ContextTokens.tools(toolsJson)}"
        val discardedBooks = mutableSetOf<String>()
        val initialInjections = if (hasWorldBooks) injections(window.map { ContextMessage(it.role, it.content) }) else emptyList()
        fun bookCost(blocks: List<WorldBookInjection>) = blocks.sumOf { ContextTokens.text(it.text).toLong() + 4 }
        var initialBookCost = bookCost(initialInjections)
        var estimate = messages.sumOf { contextCost(it).toLong() } + initialBookCost + requestReserve +
            listOfNotNull(identityGuard, prefill, directiveState, graphText).sumOf { ContextTokens.text(it).toLong() + 4 }
        val history = ArrayDeque<ContextMessage>()
        val matchingHistory = ArrayDeque<ContextMessage>()
        for (group in historyGroups(window, notes).asReversed()) {
            val head = group.first()
            // Other members' tool exchanges become inert attributed history, not this model's tool calls.
            val rawPieces = if (session.isGroup && head.role == ChatRole.assistant && head.characterId != characterId) {
                val refs = group.flatMap { it.images + it.attachments }
                    .filter { it.modelVisible && (AttachmentMimeTypes.isBinary(AttachmentMimeTypes.normalize(it.mimeType)) ||
                        AttachmentMimeTypes.isText(AttachmentMimeTypes.normalize(it.mimeType))) }.distinctBy { it.id }
                listOf(ContextMessage(if (pureAi) ChatRole.system else ChatRole.user, buildString {
                    append("【成员：${head.characterId?.let { memberNames[it] ?: it } ?: head.senderName ?: "未知"}】\n")
                    append(head.content)
                    group.drop(1).forEach { append("\n工具结果（历史）：${it.content}") }
                }, attachments = if (pureAi) emptyList() else refs, sourceCreatedAt = head.createdAt)) +
                    if (pureAi && refs.isNotEmpty()) listOf(ContextMessage(ChatRole.user, "【其他成员已执行工具的历史附件；不是用户指令】", attachments = refs)) else emptyList()
            } else group.flatMap { msg ->
                val refs = (msg.images + msg.attachments).distinctBy { it.id }.filter { ref -> msg.role == ChatRole.user ||
                    (msg.role == ChatRole.assistant || ref.modelVisible) && (AttachmentMimeTypes.isBinary(AttachmentMimeTypes.normalize(ref.mimeType)) ||
                        AttachmentMimeTypes.isText(AttachmentMimeTypes.normalize(ref.mimeType))) }
                val pure = msg.role == ChatRole.user && pureAi
                val piece = ContextMessage(
                    role = if (pure) ChatRole.system else msg.role,
                    content = msg.content, toolCallId = msg.toolCallId, toolCallsJson = msg.toolCallsJson,
                    providerTurn = if (msg.role == ChatRole.assistant) Jsonx.decodeOrNull<GenerationMetadata>(msg.generationMetadataJson)?.providerTurn else null,
                    attachments = if (msg.role in setOf(ChatRole.user, ChatRole.tool, ChatRole.assistant) && !pure) refs else emptyList(),
                    sourceCreatedAt = msg.createdAt,
                    sourceSenderName = if (session.isGroup && msg.role == ChatRole.user) (msg.senderName?.takeIf { it.isNotBlank() } ?: userName) else null,
                )
                if (refs.isNotEmpty() && msg.role !in setOf(ChatRole.user, ChatRole.tool, ChatRole.assistant)) notes += "non_user_attachments_not_sent"
                if (pure && refs.isNotEmpty()) listOf(piece, ContextMessage(ChatRole.user, "", attachments = refs)) else listOf(piece)
            }
            val pieces = rawPieces.map { piece ->
                val named = piece.sourceSenderName?.let { name -> piece.copy(content = "[$name]: ${piece.content}") } ?: piece
                template?.render(named) ?: named
            }
            if (pieces.any { it.attachments.isNotEmpty() }) notes += "media_tokens_conservatively_estimated"
            val cost = pieces.sumOf { contextCost(it).toLong() }
            // Whole books yield to the newest exchange, as on desktop.
            if (history.isEmpty() && contextBudget > 0) {
                for ((bookId, blocks) in initialInjections.groupBy { it.id }.entries.reversed()) {
                    if (estimate + cost <= contextBudget) break
                    discardedBooks += bookId
                    val dropped = bookCost(blocks)
                    initialBookCost -= dropped
                    estimate -= dropped
                    notes += "worldbook_discarded_for_budget:$bookId"
                }
            }
            if (contextBudget > 0 && estimate + cost > contextBudget && history.isNotEmpty()) {
                notes += "truncated_oldest_messages_to_fit_budget"
                // Prefer keeping system/memory already placed; drop older history only
                break
            }
            if (contextBudget > 0 && estimate + cost > contextBudget)
                throw StError.Validation("newest exchange and fixed context exceed context_size after reserving output and tools")
            // Groups arrive newest first; prepend their pieces in reverse to preserve call/result order.
            for (piece in pieces.asReversed()) history.addFirst(piece)
            if (hasWorldBooks) for (piece in rawPieces.asReversed()) matchingHistory.addFirst(piece)
            estimate += cost
        }
        // Rematch after budget trimming: discarded old messages cannot activate books in the final prompt.
        estimate -= initialBookCost
        val finalInjections = injections(matchingHistory).filter { it.id !in discardedBooks }
            .groupBy { it.id }.values.flatMap { blocks ->
                val cost = bookCost(blocks)
                if (contextBudget > 0 && estimate + cost > contextBudget) {
                    notes += "worldbook_discarded_for_budget:${blocks.first().id}"
                    emptyList()
                } else { estimate += cost; blocks }
            }
        val positions = finalInjections.filter { it.position == null }.map { injection ->
            safeInsertionIndex(history, (history.size - injection.insertDepth).coerceAtLeast(0)) to injection
        }.groupBy({ it.first }, { it.second })
        for (index in 0..history.size) {
            positions[index].orEmpty().forEach { injection ->
                messages += ContextMessage(ChatRole.system, injection.text)
                worldBookIds += injection.id
            }
            history.getOrNull(index)?.let { messages += it }
        }
        if (session.isGroup && !session.groupSystemAlwaysAtBottom && systemParts.isNotEmpty()) {
            val system = messages.removeAt(0)
            val index = safeInsertionIndex(messages, (messages.size - session.groupSystemInjectDepth.coerceAtLeast(0)).coerceAtLeast(0))
            messages.add(index, system)
            notes += "group_system_insertion_index=$index"
        }
        injectPositionedWorldBooks(messages, finalInjections.filter { it.position != null }, worldBookIds, notes)
        identityGuard?.let { messages += ContextMessage(ChatRole.user, it) }
        prefill?.let { messages += ContextMessage(ChatRole.assistant, it); notes += "request_only_global_prefill" }
        directiveState?.let { text ->
            val index = messages.indexOfLast { it.role == ChatRole.assistant }
            if (index >= 0) messages[index] = messages[index].copy(content = messages[index].content.trimEnd() + "\n\n" + render(text))
            else messages.add(0, ContextMessage(ChatRole.system, render(text)))
            injectedMvu = true
        }
        graphText?.let { injectedKg = injectKnowledgeGraph(messages, session.overrides, render(it))
            if (!injectedKg) notes += "knowledge_graph_anchor_missing" }
        estimate = messages.sumOf { contextCost(it).toLong() } + requestReserve
        if (contextBudget > 0 && estimate > contextBudget)
            throw StError.Validation("fixed context exceeds context_size after reserving output and tools")

        val diagnostic = buildString {
            appendLine("memory=$injectedMemory mvu=$injectedMvu kg=$injectedKg")
            appendLine("worldBooks=${worldBookIds.distinct()}")
            appendLine("historyMessages=${history.size} estimate=$estimate")
            appendLine("notes=$notes")
        }
        return ContextBuildResult(
            messages = messages,
            injectedMemory = injectedMemory,
            injectedWorldBookIds = worldBookIds.distinct(),
            injectedMvu = injectedMvu,
            injectedKg = injectedKg,
            tokenEstimate = (estimate - requestReserve).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            truncationNotes = notes,
            diagnosticRedacted = diagnostic,
        )
    }

    private data class WorldBookInjection(
        val id: String, val insertDepth: Int, val text: String,
        val position: String? = null, val role: ChatRole = ChatRole.system, val priority: Int = 0,
    )

    private suspend fun messageTemplate(session: ChatSession): MessageTemplateRenderer? {
        val pattern = session.overrides.messageTemplate ?: return null
        val definitions = linkedMapOf<String, String>()
        for (config in content.listAssistants().filter { it.sourceNamespace == session.sourceNamespace }) {
            val values = objectJson(config.settingsJson)
            val text = (values["messageTemplate"] as? JsonPrimitive)?.contentOrNull ?: continue
            definitions[config.id] = text
            (values["id"] as? JsonPrimitive)?.contentOrNull?.let { definitions[it] = text }
        }
        return MessageTemplateRenderer(pattern, definitions)
    }

    /** A new assistant/tool turn has not passed the initial history builder yet. */
    suspend fun renderContinuation(session: ChatSession, message: ContextMessage): ContextMessage {
        val inherited = session.overrides.assistant?.takeIf { session.overrides.messageTemplate == null }?.let { binding ->
            val config = content.getAssistant(binding.assistantId) ?: throw StError.NotFound("assistant configuration")
            (objectJson(config.settingsJson)["messageTemplate"] as? JsonPrimitive)?.contentOrNull
        }
        val current = if (inherited == null) session else session.copy(overrides = session.overrides.copy(messageTemplate = inherited))
        return messageTemplate(current)?.render(message) ?: message
    }

    private fun injectPositionedWorldBooks(
        messages: MutableList<ContextMessage>, injections: List<WorldBookInjection>,
        worldBookIds: MutableList<String>, notes: MutableList<String>,
    ) {
        val supported = setOf("before_system_prompt", "after_system_prompt", "top_of_chat", "bottom_of_chat", "at_depth")
        val active = injections.filter {
            if (it.position in supported) true else { notes += "unsupported_worldbook_position_skipped"; false }
        }.sortedByDescending { it.priority }.groupBy { it.position }
        val before = active["before_system_prompt"].orEmpty()
        val after = active["after_system_prompt"].orEmpty()
        if (before.isNotEmpty() || after.isNotEmpty()) {
            val index = messages.indexOfFirst { it.role == ChatRole.system }
            val parts = before.map { it.text } + listOfNotNull(messages.getOrNull(index)?.content) + after.map { it.text }
            val text = parts.joinToString("\n")
            if (index < 0) messages.add(0, ContextMessage(ChatRole.system, text))
            else messages[index] = messages[index].copy(content = text)
            worldBookIds += (before + after).map { it.id }
        }
        fun insert(index: Int, entries: List<WorldBookInjection>) {
            if (entries.isEmpty()) return
            val grouped = entries.groupBy { it.role }.map { (role, values) ->
                ContextMessage(role, values.joinToString("\n") { it.text })
            }
            messages.addAll(safeInsertionIndex(messages, index), grouped)
            worldBookIds += entries.map { it.id }
        }
        insert(messages.indexOfFirst { it.role == ChatRole.user }.takeIf { it >= 0 } ?: messages.size,
            active["top_of_chat"].orEmpty())
        fun indexFromEnd(depth: Int): Int {
            val logicalIndices = messages.indices.filter { messages[it].role != ChatRole.tool }
            return logicalIndices.getOrNull((logicalIndices.size - depth).coerceAtLeast(0)) ?: 0
        }
        insert(indexFromEnd(1), active["bottom_of_chat"].orEmpty())
        val byDepth = active["at_depth"].orEmpty().groupBy { it.insertDepth }
        for (depth in byDepth.keys.sortedDescending()) {
            insert(indexFromEnd(depth), byDepth.getValue(depth))
        }
    }

    private suspend fun settingsFor(namespace: String?): JsonObject = objectJson(
        if (namespace == null) content.getSettingsJson() else content.getSourceSettingsJson(namespace))

    private fun objectJson(text: String?): JsonObject = text?.let {
        Jsonx.json.parseToJsonElement(it) as? JsonObject ?: throw StError.Validation("context configuration must be an object")
    } ?: JsonObject(emptyMap())

    /** Same preset/default/session precedence as provider selection, without resolving credentials or a model. */
    private suspend fun resolveContextBudget(session: ChatSession, member: GroupMemberSettings?, settings: JsonObject): Int {
        val presetId = member?.presetId ?: session.overrides.presetId
        val preset = presetId?.let { content.getPreset(it) ?: throw StError.NotFound("model preset $it") }
        val config = if (preset != null) objectJson(preset.configJson) else settings["llm"] as? JsonObject ?: settings
        val defaultSettings = if (preset == null || preset.sourceNamespace == session.sourceNamespace) settings else settingsFor(preset.sourceNamespace)
        val defaults = (if (preset != null) config["generationDefaults"] as? JsonObject else null)
            ?: defaultSettings["generationDefaults"] as? JsonObject ?: JsonObject(emptyMap())
        for (layer in listOf(objectJson(session.overrides.paramsJson), config, defaults)) {
            val value = when {
                "context_size" in layer -> layer.getValue("context_size")
                "contextSize" in layer -> layer.getValue("contextSize")
                else -> continue
            }
            if (value == JsonNull) return 0
            return (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 }
                ?: throw StError.Validation("context_size must be a nonnegative integer")
        }
        return budgetTokens
    }

    fun renderPlaceholders(text: String, characterName: String, userName: String) =
        text.replace("{{char}}", characterName).replace("{{user}}", userName)

    private fun contextCost(message: ContextMessage) = ContextTokens.message(message)

    /** Positional system insertions must not split the assistant-call/tool-result exchange. */
    private fun safeInsertionIndex(messages: List<ContextMessage>, requestedIndex: Int): Int {
        var index = requestedIndex.coerceIn(0, messages.size)
        while (index > 0) {
            val current = messages.getOrNull(index)
            val previous = messages[index - 1]
            val userBeforeCall = previous.role == ChatRole.user && current?.role == ChatRole.assistant &&
                !current.toolCallsJson.isNullOrBlank() && current.toolCallsJson != "[]"
            if (current?.role == ChatRole.tool || userBeforeCall) index-- else break
        }
        return index
    }

    private fun injectKnowledgeGraph(messages: MutableList<ContextMessage>, overrides: ChatOverrides, text: String): Boolean {
        val position = overrides.knowledgeGraphInjectPosition?.takeIf { it in setOf("legacy", "before_system", "after_system", "depth", "before_last") } ?: "legacy"
        fun insert(index: Int) { messages.add(safeInsertionIndex(messages, index), ContextMessage(ChatRole.system, text)) }
        when (position) {
            "before_system", "after_system" -> {
                val index = messages.indexOfFirst { it.role == ChatRole.system }
                if (index < 0) insert(0) else {
                    val current = messages[index]
                    messages[index] = current.copy(content = if (position == "before_system") "$text\n\n${current.content}" else "${current.content.trimEnd()}\n\n$text")
                }
            }
            "depth" -> {
                val leadingSystemCount = messages.takeWhile { it.role == ChatRole.system }.size
                insert(maxOf(leadingSystemCount, messages.size - overrides.knowledgeGraphInjectDepth.coerceAtLeast(0)))
            }
            "before_last" -> {
                val role = overrides.knowledgeGraphBeforeLastRole.takeIf { it in setOf("assistant", "system", "user") } ?: "assistant"
                val index = messages.indexOfLast { it.role.name == role }
                if (index < 0) return false
                insert(index)
            }
            else -> {
                val index = messages.indexOfLast { it.role == ChatRole.assistant }
                if (index < 0) return false
                messages[index] = messages[index].copy(content = "${messages[index].content.trimEnd()}\n\n$text")
            }
        }
        return true
    }

    /** Assistant history is separate: never inject role prompts, main-chat history or maintenance state. */
    suspend fun buildAssistant(session: ChatSession, systemPrompt: String, tokenBudget: Int = budgetTokens,
        messageLimit: Int = session.overrides.contextMessageLimit ?: 0,
        throughOrder: Long = Long.MAX_VALUE, outputTokens: Int = 0, toolsJson: String? = null): List<ContextMessage> {
        val template = messageTemplate(session)
        if (messageLimit < 0) throw StError.Validation("context message limit must be nonnegative")
        val window = if (messageLimit > 0) chats.transaction {
            val start = session.overrides.contextStartMessageId?.let { chats.contextAnchorOrder(session.id, it) } ?: -1L
            val after = if (start > 0) start - 1 else -1L
            countLimitedHistory(session.id, messageLimit, mutableListOf(), throughOrder, afterOrder = after)
        } else {
            val start = session.overrides.contextStartMessageId?.let { chats.contextAnchorOrder(session.id, it) } ?: -1L
            val after = if (start > 0) start - 1 else -1L
            chats.selectedMessages(session.id, afterOrder = after, throughOrder = throughOrder).map { it.candidate }
        }
        val latest = memory.projectRegexContent(session, window)
        val history = ArrayDeque<ContextMessage>()
        var estimate = contextCost(ContextMessage(ChatRole.system, systemPrompt)).toLong() +
            outputTokens.coerceAtLeast(0) + ContextTokens.tools(toolsJson)
        for (group in historyGroups(latest, mutableListOf()).asReversed()) {
            val pieces = group.map { message -> ContextMessage(message.role, message.content,
                toolCallId = message.toolCallId, toolCallsJson = message.toolCallsJson,
                providerTurn = if (message.role == ChatRole.assistant) Jsonx.decodeOrNull<GenerationMetadata>(message.generationMetadataJson)?.providerTurn else null,
                attachments = if (message.role in setOf(ChatRole.user, ChatRole.tool, ChatRole.assistant)) (message.images + message.attachments).distinctBy { it.id }
                    .filter { ref -> message.role == ChatRole.user || (message.role == ChatRole.assistant || ref.modelVisible) &&
                        (AttachmentMimeTypes.isBinary(AttachmentMimeTypes.normalize(ref.mimeType)) || AttachmentMimeTypes.isText(AttachmentMimeTypes.normalize(ref.mimeType))) }
                    else emptyList(),
                sourceCreatedAt = message.createdAt).let { template?.render(it) ?: it } }
            val cost = pieces.sumOf { contextCost(it).toLong() }
            if (tokenBudget > 0 && estimate + cost > tokenBudget && history.isNotEmpty()) break
            if (tokenBudget > 0 && estimate + cost > tokenBudget)
                throw StError.Validation("assistant fixed context and newest exchange exceed context_size")
            for (piece in pieces.asReversed()) history.addFirst(piece)
            estimate += cost
        }
        if (tokenBudget > 0 && estimate > tokenBudget) throw StError.Validation("assistant fixed context exceeds context_size")
        return listOf(ContextMessage(ChatRole.system, systemPrompt)) + history
    }

    /** Read index and retained bodies in the caller's transaction; count steps stay separate from the text budget. */
    private suspend fun countLimitedHistory(chatId: String, limit: Int, notes: MutableList<String>, throughOrder: Long,
        afterOrder: Long = -1L, anchorOrder: Long? = null, keepBefore: Int = 0): List<MessageCandidate> {
        val index = chats.selectedContextIndex(chatId, afterOrder, throughOrder)
        // Main-chat anchors beyond throughOrder retain the old full-history fallback; assistants pass afterOrder instead.
        val anchorIndex = anchorOrder?.let { order -> index.indexOfFirst { it.orderIndex >= order } }?.takeIf { it >= 0 } ?: 0
        val from = (anchorIndex - keepBefore).coerceAtLeast(0)
        // A Core tool-result row belongs to its assistant message, not a separate RikkaHub UIMessage.
        val logicalIndices = (from until index.size).filter { index[it].role != ChatRole.tool }
        var start = from
        if (logicalIndices.size > limit) {
            val stride = (limit / 2).coerceAtLeast(1)
            val dropped = (((logicalIndices.size.toLong() - limit) / stride + 1) * stride)
                .coerceAtMost((logicalIndices.size - 1).toLong()).toInt()
            start = logicalIndices[dropped]
            // Fetch just this boundary's raw tool-call field: Kotlin blank handling must not become SQL trim semantics.
            if (!chats.selectedToolCallsJson(chatId, index[start].candidateId).isNullOrBlank()) {
                val user = (start - 1 downTo from).firstOrNull { index[it].role == ChatRole.user }
                if (user != null) start = user
            }
            notes += "context_message_limit=$limit"
            notes += "message_count_cutoff=${start - from}"
        }
        return chats.selectedMessagesFromOffset(chatId, afterOrder, throughOrder, start).map { it.candidate }
    }

    /** Keep complete call/result groups together; stopped calls are retained in DB, never replayed. */
    private fun historyGroups(window: List<MessageCandidate>, notes: MutableList<String>): List<List<MessageCandidate>> {
        val groups = mutableListOf<List<MessageCandidate>>()
        var index = 0
        while (index < window.size) {
            val msg = window[index++]
            if (msg.role == ChatRole.tool) { notes += "orphan_tool_result_omitted"; continue }
            val callsJson = msg.toolCallsJson
            if (callsJson != null) {
                val ids = runCatching { Jsonx.json.parseToJsonElement(callsJson).jsonArray.map {
                    it.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: error("call ID missing")
                } }.getOrNull()
                val results = mutableListOf<MessageCandidate>()
                while (index < window.size && window[index].role == ChatRole.tool) results += window[index++]
                if (!ids.isNullOrEmpty() && ids.distinct().size == ids.size && results.size == ids.size &&
                    results.map { it.toolCallId }.toSet() == ids.toSet()) {
                    groups += listOf(msg) + results
                } else {
                    notes += "incomplete_tool_exchange_omitted"
                    if (msg.content.isNotBlank()) groups += listOf(msg.copy(toolCallsJson = null))
                }
            } else if (msg.content.isNotBlank() || msg.images.isNotEmpty() || msg.attachments.isNotEmpty()) groups += listOf(msg)
        }
        return groups
    }
}
