package com.simpletavern.core.data

import com.simpletavern.core.data.db.UsageEventEntity
import com.simpletavern.core.model.*
import kotlinx.serialization.json.*

/** Preserve previously verifiable cache evidence retained outside an explicit partial replacement. */
internal class NativeGeminiCacheRestoreGuard(
    private val prior: suspend (String, String, String, Int) -> JsonElement?,
    private val combined: suspend (String, String, String, Int) -> JsonElement?,
    private val lookupUsage: suspend (String, Boolean) -> UsageEventEntity?,
    private val included: (String, String) -> Boolean,
    private val issue: (NativeRestoreIssue) -> Unit,
) {
    private var readBytes = 0L
    fun requireBudget() { if (readBytes > 67_108_864) throw StError.Validation("retained cache references exceed 64 MiB read budget") }
    private fun bytes(value: String) { readBytes += value.toByteArray(Charsets.UTF_8).size; requireBudget() }
    private suspend fun text(table: String, id: String, column: String, limit: Int = 1024, incoming: Boolean = false): String? {
        val value = (if (incoming) combined else prior)(table, id, column, limit)?.jsonPrimitive?.contentOrNull
        value?.let(::bytes)
        return value
    }
    private suspend fun ledger(taskId: String, incoming: Boolean): UsageEventEntity? {
        val row = lookupUsage(GeminiCacheAccounting.eventId(taskId), incoming) ?: return null
        listOfNotNull(row.id, row.eventId, row.requestId, row.chatId, row.provider, row.model, row.createdAt, row.usageJson, row.costJson).forEach(::bytes)
        return row
    }
    private suspend fun task(taskId: String, incoming: Boolean): Pair<GeminiCacheTaskProgress, String>? {
        val kind = text("tasks", taskId, "kind", 128, incoming)
        if (kind !in setOf(TaskKind.GEMINI_CACHE_OPERATION.name, TaskKind.GEMINI_CACHE_LIST.name)) return null
        val raw = text("tasks", taskId, "payloadJson", GeminiCacheTaskMetadata.MAX_LIST_BYTES, incoming) ?: return null
        val operation = GeminiCacheAccounting.operation(raw)
        if (kind != GeminiCacheTaskMetadata.kind(operation.action).name) throw StError.Validation("cache task kind mismatch")
        val rawStatus = text("tasks", taskId, "status", 128, incoming) ?: return null
        val status = TaskStatus.entries.find { it.name == rawStatus } ?: throw StError.Validation("invalid cache task status")
        GeminiCacheTaskMetadata.validateTask(operation, taskId, text("tasks", taskId, "chatId", incoming = incoming),
            text("tasks", taskId, "createdAt", 64, incoming) ?: return null, status)
        return operation to raw
    }
    private fun operationJson(raw: String, ledger: Boolean): JsonObject {
        val root = NativeJsonInput.parse(raw) as? JsonObject ?: throw StError.Validation("invalid cache evidence JSON")
        val operation = if (ledger) root["operation"] as? JsonObject ?: throw StError.Validation("cache ledger operation missing") else root
        return JsonObject(operation.filterKeys { it != "processEpoch" })
    }
    private fun report(id: String, code: String, blocking: Boolean = true) = issue(NativeRestoreIssue(code, "tasks",
        buildJsonObject { put("id", id) }.toString().take(2048), column = "payloadJson", blocking = blocking))

    suspend fun inspect(id: String) {
        bytes(id)
        var verified = false
        try {
            val original = task(id, false) ?: return
            if (original.first.accountingVersion != 1) return // Legacy task-only history is not fabricated into a ledger.
            val originalLedger = ledger(id, false)
            if (originalLedger == null || !GeminiCacheAccounting.sameEvidence(GeminiCacheAccounting.parse(originalLedger), original.first) ||
                operationJson(original.second, false) != operationJson(originalLedger.usageJson, true)) {
                report(id, "PRESERVED_CACHE_UNVERIFIED", false); return
            }
            verified = true
            val taskIncluded = included("tasks", id)
            val ledgerIncluded = included("usage_events", originalLedger.id)
            if (!taskIncluded || !ledgerIncluded) {
                val nextTask = task(id, true)
                val nextLedger = ledger(id, true)
                if (nextTask == null || nextLedger == null ||
                    !GeminiCacheAccounting.sameEvidence(nextTask.first, GeminiCacheAccounting.parse(nextLedger)) ||
                    operationJson(nextTask.second, false) != operationJson(nextLedger.usageJson, true) ||
                    (!taskIncluded && operationJson(nextTask.second, false) != operationJson(original.second, false)) ||
                    (!ledgerIncluded && (nextLedger.usageJson != originalLedger.usageJson || nextLedger.costJson != originalLedger.costJson)))
                    report(id, "PRESERVED_CACHE_ACCOUNTING_CHANGED")
            }
            // No retained cache row means the archive explicitly owns both replacements.
            if (taskIncluded && ledgerIncluded) return
            val operation = original.first
            val owner = operation.owner
            if (text("chats", owner.chatId, "id") != null && text("chats", owner.chatId, "sourceNamespace") == owner.sourceNamespace &&
                (text("chats", owner.chatId, "id", incoming = true) == null ||
                    text("chats", owner.chatId, "sourceNamespace", incoming = true) != owner.sourceNamespace))
                report(id, "PRESERVED_CACHE_SOURCE_CHANGED")
            operation.referenceTaskId?.let { reference ->
                val beforePair = task(reference, false) ?: return@let
                fun matches(value: GeminiCacheTaskProgress) = value.owner == owner && value.remoteCompleted &&
                    if (operation.action == GeminiCacheAction.LIST) value.action == GeminiCacheAction.LIST && value.phase == "completed" &&
                        value.listingPageSize == operation.listingPageSize && value.listing?.hasNextPage == true
                    else value.resourceName == operation.resourceName && value.action != GeminiCacheAction.DELETE
                if (matches(beforePair.first)) {
                    val afterPair = task(reference, true)
                    if (afterPair == null || !matches(afterPair.first))
                        report(id, "PRESERVED_CACHE_REFERENCE_CHANGED")
                    else if (operation.action == GeminiCacheAction.LIST &&
                        operationJson(beforePair.second, false) != operationJson(afterPair.second, false))
                        report(id, "PRESERVED_CACHE_LIST_REFERENCE_CHANGED")
                }
            }
            for (ancestorId in operation.lifecyclePreviousTaskIds) {
                val before = task(ancestorId, false)
                if (before != null && before.first.owner == owner && before.first.resourceName == operation.resourceName && before.first.endedAt != null) {
                    val after = task(ancestorId, true)
                    if (after == null || operationJson(after.second, false) != operationJson(before.second, false))
                        report(id, "PRESERVED_CACHE_LIFECYCLE_ANCESTOR_CHANGED")
                }
            }
        } catch (_: StError.Validation) { requireBudget(); report(id, if (verified) "PRESERVED_CACHE_ACCOUNTING_CHANGED" else "PRESERVED_CACHE_UNREADABLE", verified) }
        catch (_: StError.Unsupported) { requireBudget(); report(id, if (verified) "PRESERVED_CACHE_ACCOUNTING_CHANGED" else "PRESERVED_CACHE_UNREADABLE", verified) }
    }
}
