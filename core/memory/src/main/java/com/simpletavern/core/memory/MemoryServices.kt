package com.simpletavern.core.memory

import com.simpletavern.core.data.ChatRepository
import com.simpletavern.core.data.ContentRepository
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.data.MemoryStore
import com.simpletavern.core.data.TaskStore
import com.simpletavern.core.llm.LlmClient
import com.simpletavern.core.llm.LlmRequest
import com.simpletavern.core.llm.ProviderConfig
import com.simpletavern.core.model.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.toList

class MemoryService(
    private val store: MemoryStore,
    private val chats: ChatRepository,
    private val tasks: TaskStore,
    private val llm: LlmClient,
) {
    suspend fun get(chatId: String) = store.getMemory(chatId)

    suspend fun edit(chatId: String, content: String, expectedVersion: Long?): LongTermMemory {
        val now = Instant.now().toString()
        return store.saveMemory(
            LongTermMemory(chatId, content, version = expectedVersion ?: 0, updatedAt = now, source = "user"),
            expectedVersion = expectedVersion,
        )
    }

    suspend fun summarize(
        chatId: String,
        provider: ProviderConfig,
        manual: Boolean = true,
    ): MemorySummaryJob {
        val mem = store.getMemory(chatId)
        val nodes = chats.pageMessages(chatId, limit = 500, offset = 0)
        val sourceIds = nodes.mapNotNull { it.selected?.id }
        val job = MemorySummaryJob(
            id = UUID.randomUUID().toString().replace("-", ""),
            chatId = chatId,
            status = TaskStatus.RUNNING,
            sourceMessageIds = sourceIds,
            expectedMemoryVersion = mem?.version,
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString(),
        )
        store.upsertSummaryJob(job)
        tasks.upsert(
            TaskSnapshot(
                id = job.id,
                kind = TaskKind.MEMORY_SUMMARY,
                status = TaskStatus.RUNNING,
                chatId = chatId,
                createdAt = job.createdAt,
                updatedAt = job.updatedAt,
            ),
        )
        return try {
            val transcript = nodes.mapNotNull { n ->
                val c = n.selected ?: return@mapNotNull null
                "${c.role}: ${c.content}"
            }.joinToString("\n")
            val prompt = buildString {
                appendLine("请总结以下对话为长期记忆要点，保留关键设定与未决情节。不要编造。")
                if (!mem?.content.isNullOrBlank()) {
                    appendLine("现有记忆：")
                    appendLine(mem!!.content)
                }
                appendLine("对话：")
                appendLine(transcript.take(100_000))
            }
            val events = llm.stream(
                provider,
                LlmRequest(
                    requestId = job.id,
                    protocol = provider.protocol,
                    model = provider.model,
                    messages = listOf(ContextMessage(ChatRole.user, prompt)),
                ),
            ).toList()
            val text = events.filterIsInstance<StreamEvent.Delta>().filter { !it.reasoning }.joinToString("") { it.text }
            if (text.isBlank()) throw StError.Provider("empty summary")
            val lastMsg = sourceIds.lastOrNull()
            val saved = store.saveMemory(
                LongTermMemory(
                    chatId = chatId,
                    content = text,
                    version = mem?.version ?: 0,
                    anchorMessageId = lastMsg,
                    updatedAt = Instant.now().toString(),
                    source = if (manual) "manual_summary" else "auto_summary",
                ),
                expectedVersion = mem?.version,
            )
            // Only advance coverage after successful save
            val session = chats.getSession(chatId)
            if (session != null && lastMsg != null) {
                chats.saveSession(
                    session.copy(
                        overrides = session.overrides.copy(lastAutoMemorySummaryAfterMessageId = lastMsg),
                        updatedAt = Instant.now().toString(),
                    ),
                )
            }
            val done = job.copy(
                status = TaskStatus.SUCCEEDED,
                resultText = saved.content,
                coveredThroughMessageId = lastMsg,
                updatedAt = Instant.now().toString(),
            )
            store.upsertSummaryJob(done)
            tasks.upsert(
                TaskSnapshot(
                    id = job.id, kind = TaskKind.MEMORY_SUMMARY, status = TaskStatus.SUCCEEDED,
                    chatId = chatId, terminalReason = TaskTerminalReason.COMPLETED,
                    createdAt = job.createdAt, updatedAt = done.updatedAt, resultSummary = "memory v${saved.version}",
                ),
            )
            done
        } catch (t: Throwable) {
            val failed = job.copy(
                status = TaskStatus.FAILED,
                failureReason = t.message,
                updatedAt = Instant.now().toString(),
            )
            store.upsertSummaryJob(failed)
            tasks.upsert(
                TaskSnapshot(
                    id = job.id, kind = TaskKind.MEMORY_SUMMARY, status = TaskStatus.FAILED,
                    chatId = chatId, terminalReason = TaskTerminalReason.ERROR, errorMessage = t.message,
                    createdAt = job.createdAt, updatedAt = failed.updatedAt,
                ),
            )
            failed
        }
    }

    suspend fun maybeAutoSummarize(session: ChatSession) {
        val every = session.overrides.autoMemorySummaryEveryN ?: return
        if (every <= 0) return
        // Auto summarize requires provider from settings; left as hook for host wiring.
        // Coverage boundary only advances inside summarize() after successful save.
    }

    suspend fun bootstrapMvuFromCharacter(chatId: String, tablesJson: String) {
        val tables = Jsonx.decodeOrNull<List<StatusTableDef>>(tablesJson) ?: return
        store.saveMvu(
            MvuState(chatId = chatId, tables = tables, updatedAt = Instant.now().toString(), source = "chat_assistant"),
        )
    }

    suspend fun getMvu(chatId: String) = store.getMvu(chatId)
    suspend fun saveMvu(state: MvuState, expected: Int?) = store.saveMvu(state, expected)
    suspend fun getKg(chatId: String) = store.getKg(chatId)
    suspend fun saveKg(kg: KnowledgeGraph, expected: Int?) = store.saveKg(kg, expected)

    suspend fun upsertKgEntity(chatId: String, entity: KgEntity, expectedVersion: Int?): KnowledgeGraph {
        val current = store.getKg(chatId) ?: KnowledgeGraph(chatId = chatId)
        val others = current.entities.filterNot { it.name == entity.name && it.type == entity.type && !it.deleted }
        val merged = others + entity
        return store.saveKg(current.copy(entities = merged, updatedAt = Instant.now().toString()), expectedVersion)
    }

    suspend fun upsertKgRelation(chatId: String, relation: KgRelation, expectedVersion: Int?): KnowledgeGraph {
        val current = store.getKg(chatId) ?: KnowledgeGraph(chatId = chatId)
        val entityIds = current.entities.filter { !it.deleted }.map { it.id }.toSet()
        if (relation.subject !in entityIds) throw StError.Validation("subject missing")
        if (relation.`object` !in entityIds) throw StError.Validation("object missing")
        val others = current.relations.filterNot {
            it.subject == relation.subject && it.predicate == relation.predicate && it.`object` == relation.`object`
        }
        return store.saveKg(current.copy(relations = others + relation, updatedAt = Instant.now().toString()), expectedVersion)
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
            kg.relations.forEach { r ->
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
            appendLine(table.columns.joinToString(" | "))
            table.rows.forEach { row ->
                appendLine(table.columns.joinToString(" | ") { col -> if (col == "field") row.field else row.cells[col] ?: "" })
            }
        }
        appendLine("</StateVariables>")
    }
}

class ContextBuilder(
    private val chats: ChatRepository,
    private val content: ContentRepository,
    private val memory: MemoryService,
    private val budgetTokens: Int = 24_000,
) {
    suspend fun build(session: ChatSession, characterIdOverride: String? = null): ContextBuildResult {
        val notes = mutableListOf<String>()
        val characterId = characterIdOverride ?: session.characterId
        val character = content.getCharacter(characterId)
        val messages = mutableListOf<ContextMessage>()
        var injectedMemory = false
        var injectedMvu = false
        var injectedKg = false
        val worldBookIds = mutableListOf<String>()

        val systemParts = mutableListOf<String>()
        character?.let { c ->
            if (c.systemPrompt.isNotBlank()) systemParts += c.systemPrompt
            if (c.description.isNotBlank()) systemParts += "描述：${c.description}"
            if (c.personality.isNotBlank()) systemParts += "性格：${c.personality}"
            if (c.scenario.isNotBlank()) systemParts += "场景：${c.scenario}"
        }
        session.overrides.prompt?.takeIf { it.isNotBlank() }?.let {
            when (session.overrides.sessionSystemPromptMode) {
                "override" -> {
                    systemParts.clear()
                    systemParts += it
                }
                else -> systemParts += it
            }
        }

        val mem = memory.get(session.id)
        if (!mem?.content.isNullOrBlank()) {
            systemParts += "<LongTermMemory>\n${mem!!.content}\n</LongTermMemory>"
            injectedMemory = true
        }

        val mvu = memory.getMvu(session.id)
        if (mvu != null && mvu.tables.isNotEmpty()) {
            systemParts += memory.renderMvuForPrompt(mvu)
            injectedMvu = true
        }
        val kgEnabled = session.overrides.knowledgeGraphEnabled != false
        val kg = if (kgEnabled) memory.getKg(session.id) else null
        if (kg != null && (kg.entities.isNotEmpty() || kg.relations.isNotEmpty())) {
            systemParts += memory.renderKgForPrompt(kg)
            injectedKg = true
        }

        // World book trigger
        val recent = chats.pageMessages(session.id, limit = 80, offset = 0)
        val scanText = recent.takeLast(20).joinToString("\n") { it.selected?.content ?: "" }
        val books = content.listWorldBooks()
        val attached = session.overrides.worldBookAttachments.map { it.worldBookId }.toSet()
        books.filter { it.globalActive || it.id in attached || session.id in it.sessionChatIds }
            .filter { it.id !in session.overrides.worldBookGlobalExclusions }
            .sortedBy { b -> session.overrides.worldBookAttachments.find { it.worldBookId == b.id }?.insertDepth ?: 5 }
            .forEach { book ->
                book.entries.filter { it.enabled }.sortedBy { it.orderIndex }.forEach { entry ->
                    val hit = if (entry.regex.isBlank()) false else runCatching {
                        Regex(entry.regex, RegexOption.IGNORE_CASE).containsMatchIn(scanText)
                    }.getOrDefault(false)
                    if (hit || entry.regex.isBlank()) {
                        systemParts += "【${entry.title}】\n${entry.content}"
                        worldBookIds += book.id
                    }
                }
            }

        if (systemParts.isNotEmpty()) {
            messages += ContextMessage(ChatRole.system, systemParts.joinToString("\n\n"))
        }

        // Anchor / window
        val startId = session.overrides.contextStartMessageId
        val keepBefore = session.overrides.contextStartKeepBeforeMessages ?: 0
        val selected = recent.mapNotNull { it.selected }
        val startIndex = startId?.let { id -> selected.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
        val from = (startIndex - keepBefore).coerceAtLeast(0)
        val window = selected.drop(from)

        // Rough char/4 token estimate; record strategy when crude
        notes += "token_estimate_strategy=chars/4"
        var estimate = messages.sumOf { it.content.length / 4 }
        val history = mutableListOf<ContextMessage>()
        for (msg in window.asReversed()) {
            val piece = ContextMessage(
                role = msg.role,
                content = msg.content,
                toolCallId = msg.toolCallId,
                toolCallsJson = msg.toolCallsJson,
            )
            val cost = piece.content.length / 4
            if (estimate + cost > budgetTokens) {
                notes += "truncated_oldest_messages_to_fit_budget"
                // Prefer keeping system/memory already placed; drop older history only
                break
            }
            history.add(0, piece)
            estimate += cost
        }
        messages += history

        if (injectedMemory.not() && systemParts.any { it.contains("LongTermMemory") }.not()) {
            // ok
        }
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
            tokenEstimate = estimate,
            truncationNotes = notes,
            diagnosticRedacted = diagnostic,
        )
    }
}
