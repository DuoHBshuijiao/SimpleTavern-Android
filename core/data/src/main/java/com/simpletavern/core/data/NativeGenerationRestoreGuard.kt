package com.simpletavern.core.data

import com.simpletavern.core.data.db.UsageEventEntity
import com.simpletavern.core.model.*
import kotlinx.serialization.json.*

/** Historical request/cost relationships. Does not interpret opaque provider turns or replay requests. */
internal object NativeGenerationUsage {
    const val MAX_METADATA_BYTES = 32_000_000
    private val FIELDS = setOf("version", "requestId", "chatId", "messageId", "provider", "protocol", "requestedModel",
        "resolvedModel", "startedAt", "status", "usage", "cost")
    private val TERMINAL = setOf("completed", "succeeded", "failed", "stopped", "interrupted", "cancelled")
    data class Frame(val root: JsonObject, val metadata: GenerationMetadata)
    data class Ledger(val row: UsageEventEntity, val usage: JsonObject, val cost: JsonObject,
        val tokens: UsageTokens, val context: UsageRecordContext, val snapshot: CostSnapshot, val durable: Boolean)
    data class Proof(val identity: List<String?>, val usage: JsonObject, val cost: JsonObject, val sourceNamespace: String?)
    fun frame(raw: String): Frame? {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_METADATA_BYTES) throw StError.Validation("generation metadata exceeds native budget")
        val root = NativeJsonInput.parse(raw) as? JsonObject ?: return null
        // Only billing fields are decoded. Unknown search/opaque protocol fields remain untouched.
        val value = Jsonx.decodeOrNull<GenerationMetadata>(JsonObject(root.filterKeys { it in FIELDS }).toString()) ?: return null
        return Frame(root, value)
    }
    private fun identity(value: String) { SpeechText.validate(value, 1024); if (value.isBlank() || value.any(Char::isISOControl)) invalid() }
    private fun model(value: String) { SpeechText.validate(value, 2048); if (value.isBlank() || value.length > 512 || value.any(Char::isISOControl)) invalid() }
    fun known(frame: Frame): Boolean {
        val meta = frame.metadata
        val provider = meta.provider ?: return false
        val requestedModel = meta.requestedModel ?: return false
        val startedAt = meta.startedAt ?: return false
        if (meta.version != 1 || meta.protocol !in LlmProtocol.entries.map { it.name } || meta.status !in TERMINAL || meta.cost == null) return false
        identity(meta.requestId); identity(provider); model(requestedModel)
        listOfNotNull(meta.chatId, meta.messageId).forEach(::identity)
        meta.resolvedModel?.let(::model)
        if (startedAt.length > 64) invalid()
        PricingEngine.instant(startedAt)
        return true
    }
    fun ledger(row: UsageEventEntity): Ledger? {
        if (row.usageJson.toByteArray(Charsets.UTF_8).size > 65_536 || (row.costJson?.toByteArray(Charsets.UTF_8)?.size ?: 0) > 32_768) invalid()
        val usage = NativeJsonInput.parse(row.usageJson) as? JsonObject ?: return null
        if (usage["_kind"]?.let { it != JsonPrimitive("llm") } == true || row.eventId != row.requestId || row.chatId == null ||
            row.provider == null || row.model == null || row.costJson == null) return null
        listOf(row.id, row.eventId, row.requestId, row.chatId, row.provider).forEach(::identity); model(row.model)
        if (listOfNotNull(row.id, row.eventId, row.requestId, row.chatId, row.provider, row.model, row.createdAt)
                .sumOf { it.toByteArray(Charsets.UTF_8).size } > 4096 || row.createdAt.length > 64) invalid()
        PricingEngine.instant(row.createdAt)
        val tokens = Jsonx.decodeOrNull<UsageTokens>(row.usageJson) ?: return null
        val context = usage["_recordContext"]?.let { Jsonx.decodeOrNull<UsageRecordContext>(it.toString()) } ?: return null
        val startedAt = context.startedAt ?: return null
        if (context.protocol !in LlmProtocol.entries.map { it.name } || context.status !in TERMINAL ||
            context.requestedModel != row.model || context.purpose !in setOf(null, "chat_generation")) return null
        if (startedAt.length > 64) invalid()
        PricingEngine.instant(startedAt)
        listOfNotNull(context.sourceNamespace, context.taskId, context.messageCandidateId).forEach(::identity)
        if (TokenRequestAccounting.safeTokens(tokens, "") != tokens) return null
        val cost = NativeJsonInput.parse(row.costJson) as? JsonObject ?: return null
        val snapshot = Jsonx.decodeOrNull<CostSnapshot>(row.costJson) ?: return null
        if (snapshot.version != 1 || snapshot.source !in setOf("unknown", "provider", "estimated") || snapshot.source != "unknown" &&
            (snapshot.currency?.let(PricingEngine::currency) != true || snapshot.amount?.let { runCatching { PricingEngine.decimal(it) }.isSuccess } != true)) return null
        val durable = usage.containsKey("_requestLifecycle")
        if (durable && TokenRequestAccounting.parse(row)?.context?.purpose != "chat_generation") return null
        if (durable && context.resolvedModel != tokens.resolvedModel) return null
        return Ledger(row, usage, cost, tokens, context, snapshot, durable)
    }
    suspend fun proof(frame: Frame, id: String, chatId: String, role: String?, duplicateUsage: String?, ledger: Ledger,
        cell: suspend (String, String, String, Int) -> JsonElement?): Proof? {
        if (!known(frame)) return null
        val meta = frame.metadata
        val row = ledger.row
        if (role != ChatRole.assistant.name || meta.chatId?.let { it != chatId } == true || meta.messageId?.let { it != id } == true ||
            meta.requestId != row.requestId || meta.provider != row.provider || meta.protocol != ledger.context.protocol ||
            meta.requestedModel != row.model || meta.startedAt != ledger.context.startedAt || meta.cost != ledger.snapshot ||
            (meta.usage ?: UsageTokens()) != ledger.tokens || meta.resolvedModel != ledger.tokens.resolvedModel) return null
        duplicateUsage?.let { raw ->
            if (raw.toByteArray(Charsets.UTF_8).size > 65_536) invalid()
            NativeJsonInput.parse(raw)
            if (Jsonx.decodeOrNull<UsageTokens>(raw) != ledger.tokens) return null
        }
        suspend fun text(table: String, owner: String, column: String, limit: Int = 1024): String? =
            cell(table, owner, column, limit)?.takeIf { it != JsonNull }?.let {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw StError.Validation("generation owner column must be text")
            }
        if (text("chats", chatId, "id") == null) return null
        val source = text("chats", chatId, "sourceNamespace")
        val expected = if (ledger.durable) ledger.context.sourceNamespace else {
            val origin = row.chatId ?: return null
            if (text("chats", origin, "id") == null) return null
            text("chats", origin, "sourceNamespace")
        }
        if (source != expected) return null // same-source historical sharing/forks are allowed, not silently moved sources.
        row.chatId?.let { origin ->
            if (text("chats", origin, "id") != null && text("chats", origin, "sourceNamespace") != expected) return null
        }
        ledger.context.taskId?.let { task ->
            if (text("tasks", task, "id") != null && (text("tasks", task, "chatId") != row.chatId ||
                    text("tasks", task, "kind", 128) !in setOf(TaskKind.CHAT_GENERATION.name, TaskKind.GROUP_GENERATION.name))) return null
        }
        ledger.context.messageCandidateId?.let { original ->
            if (text("message_candidates", original, "id") != null && (text("message_candidates", original, "chatId") != row.chatId ||
                    text("message_candidates", original, "role", 128) != ChatRole.assistant.name)) return null
        }
        return Proof(listOf(row.id, row.eventId, row.requestId, row.chatId, row.provider, row.model, row.createdAt), ledger.usage, ledger.cost, expected)
    }
    fun normalize(raw: String): String {
        val root = try {
            if (raw.toByteArray(Charsets.UTF_8).size > MAX_METADATA_BYTES) invalid()
            NativeJsonInput.parse(raw) as? JsonObject
        } catch (_: StError.Validation) { null }
            catch (_: StError.Unsupported) { null } ?: return raw
        fun string(name: String) = (root[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val version = root["version"]?.let { (it as? JsonPrimitive)?.takeIf { value -> !value.isString }?.intOrNull } ?: if ("version" !in root) 1 else null
        if (version != 1 || string("protocol") !in LlmProtocol.entries.map { it.name } ||
            string("status") !in setOf("queued", "running", "awaiting_tools", "stopping")) return raw
        return JsonObject(root + ("status" to JsonPrimitive("interrupted"))).toString()
    }
    private fun invalid(): Nothing = throw StError.Validation("invalid historical generation accounting metadata")
}

/** Protect only previously verifiable references retained outside an explicit replacement. */
internal class NativeGenerationRestoreGuard(
    private val prior: suspend (String, String, String, Int) -> JsonElement?,
    private val combined: suspend (String, String, String, Int) -> JsonElement?,
    private val lookupUsage: suspend (String, Boolean) -> UsageEventEntity?,
    private val included: (String, String) -> Boolean,
    private val issue: (NativeRestoreIssue) -> Unit,
) {
    private var readBytes = 0L
    private suspend fun read(table: String, id: String, column: String, limit: Int, incoming: Boolean): JsonElement? {
        val value = (if (incoming) combined else prior)(table, id, column, limit)
        if (value is JsonPrimitive && value.isString) readBytes += value.content.toByteArray(Charsets.UTF_8).size
        return value
    }
    private suspend fun text(table: String, id: String, column: String, limit: Int = 1024, incoming: Boolean = false) =
        read(table, id, column, limit, incoming)?.jsonPrimitive?.contentOrNull
    fun requireBudget() { if (readBytes > 67_108_864) throw StError.Validation("retained generation references exceed 64 MiB read budget") }
    private fun report(id: String, code: String, blocking: Boolean = true) = issue(NativeRestoreIssue(code, "message_candidates",
        buildJsonObject { put("id", id) }.toString().take(2048), column = "generationMetadataJson", blocking = blocking))
    private suspend fun ledger(requestId: String, incoming: Boolean): NativeGenerationUsage.Ledger? {
        val row = lookupUsage(requestId, incoming) ?: return null
        readBytes += listOfNotNull(row.id, row.eventId, row.requestId, row.chatId, row.provider, row.model,
            row.createdAt, row.usageJson, row.costJson).sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
        return NativeGenerationUsage.ledger(row)
    }
    suspend fun inspect(id: String) {
        if (included("message_candidates", id)) return
        readBytes += id.toByteArray(Charsets.UTF_8).size
        try {
            val raw = text("message_candidates", id, "generationMetadataJson", NativeGenerationUsage.MAX_METADATA_BYTES) ?: return
            val frame = NativeGenerationUsage.frame(raw)
            if (frame == null || !NativeGenerationUsage.known(frame)) { report(id, "PRESERVED_GENERATION_UNVERIFIED", false); return }
            val chat = text("message_candidates", id, "chatId") ?: return
            val role = text("message_candidates", id, "role", 128)
            val duplicate = text("message_candidates", id, "usageJson", 65_536)
            val before = ledger(frame.metadata.requestId, false)
            if (before == null) { report(id, "PRESERVED_GENERATION_USAGE_UNVERIFIED", false); return }
            val proof = NativeGenerationUsage.proof(frame, id, chat, role, duplicate, before) { table, owner, column, limit -> read(table, owner, column, limit, false) }
            if (proof == null) { report(id, "PRESERVED_GENERATION_USAGE_UNVERIFIED", false); return }
            val affected = included("usage_events", before.row.id) || included("chats", chat) ||
                before.row.chatId?.let { included("chats", it) } == true || before.context.taskId?.let { included("tasks", it) } == true ||
                before.context.messageCandidateId?.let { included("message_candidates", it) } == true
            if (!affected) return
            try {
                val after = ledger(frame.metadata.requestId, true)
                val next = after?.let { NativeGenerationUsage.proof(frame, id, chat, role, duplicate, it) { table, owner, column, limit -> read(table, owner, column, limit, true) } }
                if (next != proof) report(id, "PRESERVED_GENERATION_USAGE_CHANGED")
            } catch (_: StError.Validation) { report(id, "PRESERVED_GENERATION_USAGE_CHANGED") }
            catch (_: StError.Unsupported) { report(id, "PRESERVED_GENERATION_USAGE_CHANGED") }
        } catch (_: StError.Validation) { report(id, "PRESERVED_GENERATION_UNREADABLE", false) }
        catch (_: StError.Unsupported) { report(id, "PRESERVED_GENERATION_UNREADABLE", false) }
    }
}
