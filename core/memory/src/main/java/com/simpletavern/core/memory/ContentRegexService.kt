package com.simpletavern.core.memory

import com.simpletavern.core.data.*
import com.simpletavern.core.model.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Raw candidate bodies are never rewritten. All validity checks compare actual data, without hashes. */
internal class ContentRegexService(private val store: MemoryStore, private val chats: ChatRepository,
                                   private val content: ContentRepository) {
    data class Rules(val rules: List<ContentRegexRule>, val depth: Int, val globalRuleIds: Set<String> = emptySet()) {
        val json: String get() = buildJsonObject {
            put("rules", Jsonx.json.encodeToJsonElement(rules)); put("depth", depth)
        }.toString()
    }
    private fun parse(raw: String?): JsonObject = raw?.let { Jsonx.json.parseToJsonElement(it) as? JsonObject }
        ?: JsonObject(emptyMap())
    private fun decode(raw: String?): List<ContentRegexRule> = raw?.let { Jsonx.decode(it) } ?: emptyList()

    suspend fun effective(session: ChatSession, carrier: CharacterCard? = null): Rules {
        val namespace = session.sourceNamespace
        val settings = if (session.overrides.assistant != null) JsonObject(emptyMap()) else parse(
            if (namespace == null) content.getSettingsJson() else content.getSourceSettingsJson(namespace))
        // Keep typed config decoding on the streaming path for lenient booleans such as "TRUE".
        val global = decode(settings["contentRegexRuleLibrary"]?.toString())
        val assistantRules = session.overrides.assistant?.let { binding ->
            val config = content.getAssistant(binding.assistantId) ?: throw StError.NotFound("assistant configuration")
            if (config.sourceNamespace != session.sourceNamespace) throw StError.Conflict("assistant rule source differs from conversation")
            parse(config.settingsJson)["contentRegexRules"]?.toString()
        }
        val local = decode(session.overrides.contentRegexRulesJson ?: assistantRules
            ?: (carrier ?: content.getCharacter(session.characterId))?.contentRegexRulesJson)
        if (listOf(global, local).any { items -> items.any { it.id.isBlank() } || items.map { it.id }.distinct().size != items.size }) {
            throw StError.Validation("content regex library contains missing or duplicate IDs")
        }
        val byId = linkedMapOf<String, ContentRegexRule>()
        // Global library wins duplicate IDs, matching Desktop. Role rules are a fallback, not an extra copy.
        (global + local).forEach { rule -> if (rule.id !in byId) byId[rule.id] = rule }
        if (byId.size > 100) throw StError.Validation("effective content regex rule limit is 100")
        val rules = byId.values.map { rule -> rule.copy(enabled = session.overrides.contentRegexEnabledByRuleId[rule.id] ?: rule.enabled) }
            .sortedWith(compareBy({ it.order }, { it.id }))
        if (session.overrides.contentRegexScanDepthDefault !in 1..500 || rules.any { it.scanDepthOverride?.let { n -> n !in 1..500 } == true }) {
            throw StError.Validation("content regex scan depths must be 1..500")
        }
        return Rules(rules, session.overrides.contentRegexScanDepthDefault, global.map { it.id }.toSet())
    }

    suspend fun listRules(chatId: String): List<ContentRegexRule> = effective(session(chatId)).rules
    suspend fun sessionRules(chatId: String): List<ContentRegexRule> = decode(session(chatId).overrides.contentRegexRulesJson)
    suspend fun characterRules(characterId: String): List<ContentRegexRule> = decode(
        (content.getCharacter(characterId) ?: throw StError.NotFound(characterId)).contentRegexRulesJson)

    private fun validate(rules: List<ContentRegexRule>) {
        if (rules.size > 100 || rules.map { it.id }.distinct().size != rules.size) throw StError.Validation("rules require unique IDs and at most 100 entries")
        rules.forEach(ContentRegexEngine::validate)
    }
    suspend fun saveSessionRules(chatId: String, rules: List<ContentRegexRule>, expectedRules: List<ContentRegexRule>) = chats.transaction {
        validate(rules)
        val current = session(chatId)
        if (decode(current.overrides.contentRegexRulesJson) != expectedRules) throw StError.Conflict("session regex rules changed")
        chats.saveSession(current.copy(overrides = current.overrides.copy(contentRegexRulesJson = Jsonx.encode(rules)), updatedAt = now()))
        rules
    }
    suspend fun saveCharacterRules(characterId: String, rules: List<ContentRegexRule>, expectedRules: List<ContentRegexRule>) = chats.transaction {
        validate(rules)
        val current = content.getCharacter(characterId) ?: throw StError.NotFound(characterId)
        if (decode(current.contentRegexRulesJson) != expectedRules) throw StError.Conflict("character regex rules changed")
        content.saveCharacter(current.copy(contentRegexRulesJson = Jsonx.encode(rules), updatedAt = now()))
        rules
    }
    suspend fun upsert(chatId: String, rule: ContentRegexRule, expectedRules: List<ContentRegexRule>): List<ContentRegexRule> {
        val next = if (rule.id.isBlank()) rule.copy(id = UUID.randomUUID().toString()) else rule
        return saveSessionRules(chatId, expectedRules.filterNot { it.id == next.id } + next, expectedRules)
    }
    suspend fun delete(chatId: String, ruleId: String, expectedRules: List<ContentRegexRule>): List<ContentRegexRule> {
        if (expectedRules.none { it.id == ruleId }) throw StError.NotFound(ruleId)
        return saveSessionRules(chatId, expectedRules.filterNot { it.id == ruleId }, expectedRules)
    }

    private fun eligible(row: SelectedMessage) = row.candidate.role in setOf(ChatRole.user, ChatRole.assistant) &&
        row.candidate.content.isNotBlank() && Jsonx.decodeOrNull<GenerationMetadata>(row.candidate.generationMetadataJson)
            ?.status?.lowercase() !in setOf("queued", "running", "awaiting_tools", "stopping", "stopped", "failed", "interrupted")

    suspend fun preview(chatId: String, text: String, role: ChatRole = ChatRole.assistant) =
        ContentRegexEngine.apply(text, effective(session(chatId)).rules, role)

    /** Imported history already passed the source pipeline. An edited body no longer matches that source. */
    private fun sourceProcessed(candidate: MessageCandidate, reasoning: Boolean = false): Boolean {
        val source = parse(candidate.extraJson)
        if ((source["rikkaRegexProcessed"] as? JsonPrimitive)?.booleanOrNull != true) return false
        val parts = (source["parts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun text(value: JsonObject, key: String) = (value[key] as? JsonPrimitive)?.contentOrNull
        val original = if (reasoning) parts.filter { text(it, "type") == "reasoning" }.joinToString("\n") { text(it, "reasoning").orEmpty() }
            .ifBlank { text(source, "reasoning") ?: text(source, "reasoningContent").orEmpty() }
        else text(source, "content") ?: parts.filter { text(it, "type") == "text" }.joinToString("\n") { text(it, "text").orEmpty() }
        return original == if (reasoning) candidate.reasoningContent.orEmpty() else candidate.content
    }

    suspend fun apply(candidate: MessageCandidate, rules: List<ContentRegexRule>) = ContentRegexEngine.apply(
        candidate.content, rules, candidate.role, rules.any { it.engine == "kotlin" } && sourceProcessed(candidate))

    suspend fun previewMessage(chatId: String, messageId: String): MessageContentProjection {
        val rules = effective(session(chatId)).rules
        val candidate = chats.getCandidate(chatId, messageId) ?: throw StError.NotFound("message candidate")
        val output = apply(candidate, rules)
        val reasoning = candidate.reasoningContent?.let { ContentRegexEngine.apply(it, rules, candidate.role,
            sourceProcessed(candidate, reasoning = true), reasoning = true) }
        return MessageContentProjection(messageId, output.persistedText, output.displayText,
            reasoning?.persistedText, reasoning?.displayText, output.errors + reasoning?.errors.orEmpty())
    }

    suspend fun scan(chatId: String, throughMessageId: String? = null): List<ContentRegexRecord> {
        val snapshot = chats.transaction {
            val session = session(chatId)
            val rules = effective(session)
            val throughOrder = throughMessageId?.let { chats.selectedMessageOrder(chatId, it) } ?: Long.MAX_VALUE
            val depth = maxOf(rules.depth, rules.rules.filter { it.enabled }.maxOfOrNull { it.scanDepthOverride ?: rules.depth } ?: rules.depth)
            val rows = chats.selectedWindow(chatId, throughOrder, depth).filter(::eligible)
            Triple(rules, rows, throughOrder)
        }
        val (rules, rows, throughOrder) = snapshot
        val results = mutableListOf<ContentRegexRecord>()
        for ((index, row) in rows.withIndex()) {
            currentCoroutineContext().ensureActive()
            val applicable = rules.rules.map { rule -> rule.copy(enabled = rule.enabled &&
                (rule.scanAllHistory || rows.size - index <= (rule.scanDepthOverride ?: rules.depth)) &&
                !(rule.skipOpeningMessage && row.orderIndex == 0L && row.candidate.role == ChatRole.assistant)) }
            val old = store.getRegexRecord(row.candidate.id)
            if (old != null && valid(old, rules)) {
                results += old; continue
            }
            val output = apply(row.candidate, applicable)
            val saved = chats.transaction {
                val current = session(chatId)
                if (effective(current) != rules || chats.getCandidate(chatId, row.candidate.id) != row.candidate ||
                    chats.selectedMessageOrder(chatId, row.candidate.id) != row.orderIndex) throw StError.Conflict("regex source or rules changed during scan")
                val prior = store.getRegexRecord(row.candidate.id)
                if (prior != old) throw StError.Conflict("regex record changed during scan")
                val record = ContentRegexRecord(row.candidate.id, chatId, row.nodeId, row.orderIndex,
                    (prior?.revision ?: 0) + 1, row.candidate.content, rules.json, output,
                    if (output.extractedItems.isNotEmpty()) "pending" else "empty", updatedAt = now())
                store.saveRegex(record)
                record
            }
            results += saved
        }
        // Invalid old queue entries are retained for diagnostics, but cannot be consumed.
        pending(chatId, throughOrder)
        return results
    }

    suspend fun valid(record: ContentRegexRecord, suppliedRules: Rules? = null): Boolean {
        if (record.queueStatus == "stale") return false
        val rules = suppliedRules ?: effective(session(record.chatId))
        val candidate = chats.getCandidate(record.chatId, record.messageId) ?: return false
        if (candidate.content != record.sourceText || record.rulesJson != rules.json ||
            !eligible(SelectedMessage(record.nodeId, record.orderIndex, candidate))) return false
        if (record.output.messageRole != null && record.output.messageRole != candidate.role ||
            record.output.sourceProcessed != (rules.rules.any { it.engine == "kotlin" } && sourceProcessed(candidate))) return false
        return try { chats.selectedMessageOrder(record.chatId, record.messageId) == record.orderIndex }
        catch (_: StError) { false }
    }
    suspend fun pending(chatId: String, throughOrder: Long = Long.MAX_VALUE, limit: Int = 100): List<ContentRegexRecord> = chats.transaction {
        require(limit in 1..500)
        val rules = effective(session(chatId))
        val result = mutableListOf<ContentRegexRecord>()
        // Page by repeatedly retiring invalid head entries. Never silently skip valid work beyond the batch.
        while (result.isEmpty()) {
            val records = store.pendingRegex(chatId, throughOrder, limit)
            for (record in records) {
                if (valid(record, rules)) result += record
                else store.saveRegex(record.copy(queueStatus = "stale", revision = record.revision + 1, updatedAt = now()))
            }
            if (records.size < limit) break
        }
        result
    }
    suspend fun consume(records: List<ContentRegexRecord>, taskId: String) {
        for (record in records) {
            if (store.getRegexRecord(record.messageId) != record || record.queueStatus != "pending" || !valid(record)) {
                throw StError.Conflict("regex queue input changed; state draft not applied")
            }
            store.saveRegex(record.copy(queueStatus = "consumed", consumedByTaskId = taskId,
                revision = record.revision + 1, updatedAt = now()))
        }
    }
    suspend fun getRecord(chatId: String, messageId: String): ContentRegexRecord? = chats.transaction {
        session(chatId)
        store.getRegexRecord(messageId)?.takeIf { it.chatId == chatId && valid(it) }
    }
    suspend fun records(chatId: String, limit: Int): List<ContentRegexRecord> = chats.transaction {
        session(chatId)
        store.listRegex(chatId, limit)
    }
    suspend fun project(session: ChatSession, candidates: List<MessageCandidate>): List<MessageCandidate> {
        val rules = effective(session)
        if (rules.rules.none { it.enabled && (it.pattern.isNotBlank() || it.engine == "kotlin") }) return candidates
        // Evaluate eligibility once; a rank gives the same newest-relative depth without repeated list searches.
        val eligibleRanks = IntArray(candidates.size) { -1 }
        var eligibleCount = 0
        candidates.forEachIndexed { index, candidate ->
            if (eligible(SelectedMessage("", 0, candidate))) eligibleRanks[index] = eligibleCount++
        }
        return candidates.mapIndexed { index, candidate ->
            val rank = eligibleRanks[index]
            if (rank < 0) return@mapIndexed candidate
            val opening = candidate.role == ChatRole.assistant && chats.selectedMessageOrder(session.id, candidate.id) == 0L
            val distance = eligibleCount - rank
            val applicable = rules.rules.map { it.copy(enabled = it.enabled &&
                (it.scanAllHistory || distance <= (it.scanDepthOverride ?: rules.depth)) && !(it.skipOpeningMessage && opening)) }
            // Queue records describe the scan window; the current request may have a different depth/anchor.
            val text = apply(candidate, applicable).persistedText
            val reasoning = candidate.reasoningContent?.let { ContentRegexEngine.apply(it, applicable, candidate.role,
                sourceProcessed(candidate, reasoning = true), reasoning = true).persistedText }
            candidate.copy(content = text, reasoningContent = reasoning)
        }
    }
    private suspend fun session(id: String) = chats.getSession(id) ?: throw StError.NotFound(id)
    private fun now() = Instant.now().toString()
}
