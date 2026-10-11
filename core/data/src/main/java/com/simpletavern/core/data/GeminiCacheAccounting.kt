package com.simpletavern.core.data

import com.simpletavern.core.data.db.UsageEventEntity
import com.simpletavern.core.model.*
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.*

/** One independent cache-operation event, not generation tokens or a cache price/ownership certificate. */
object GeminiCacheAccounting {
    const val KIND = "gemini_cache"
    const val MAX_EVENT_BYTES = GeminiCacheTaskMetadata.MAX_LIST_BYTES + 32768
    fun eventId(taskId: String) = "$KIND:$taskId"
    fun operation(raw: String): GeminiCacheTaskProgress {
        val bytes = raw.toByteArray(Charsets.UTF_8).size
        if (bytes > GeminiCacheTaskMetadata.MAX_LIST_BYTES) invalid()
        if (NativeJsonInput.parse(raw) !is JsonObject) invalid()
        // Structural checks stay separate; streaming preserves legacy quoted/lenient boolean decoding.
        return runCatching { Jsonx.decode<GeminiCacheTaskProgress>(raw) }.getOrElse { invalid() }.also {
            if (bytes > GeminiCacheTaskMetadata.byteLimit(it.action)) invalid()
            GeminiCacheTaskMetadata.validate(it)
        }
    }
    fun event(payload: String, previousEnvelope: String? = null): UsageEventEntity {
        val value = operation(payload)
        return eventFromOperation(value, payload, previousEnvelope)
    }
    /** Internal builders reuse the operation that produced this payload; raw-input callers use event(). */
    fun eventFromOperation(value: GeminiCacheTaskProgress, payload: String, previousEnvelope: String? = null): UsageEventEntity {
        if (value.accountingVersion != 1) invalid()
        val envelope = previousEnvelope?.let { NativeJsonInput.parse(it) as? JsonObject ?: invalid() } ?: JsonObject(emptyMap())
        val raw = JsonObject(envelope + mapOf("_kind" to JsonPrimitive(KIND), "version" to JsonPrimitive(1),
            "operation" to NativeJsonInput.parse(payload))).toString()
        val id = eventId(value.taskId)
        return UsageEventEntity(id, id, value.taskId, value.owner.chatId, "gemini-cache:${value.owner.providerId}",
            value.owner.model.takeUnless { value.action == GeminiCacheAction.LIST },
            raw, value.endedAt?.let { Jsonx.encode(value.cost) }, value.createdAt).also {
                budget(it)
                if (value.action != GeminiCacheAction.LIST && raw.toByteArray(Charsets.UTF_8).size > 65536) invalid()
            }
    }
    fun parse(row: UsageEventEntity): GeminiCacheTaskProgress {
        budget(row)
        val envelope = NativeJsonInput.parse(row.usageJson) as? JsonObject ?: invalid()
        if (envelope["_kind"] != JsonPrimitive(KIND) || envelope["version"] != JsonPrimitive(1)) invalid()
        if (envelope.containsKey("_requestLifecycle")) invalid()
        val value = operation(envelope["operation"]?.toString() ?: invalid())
        if (value.action != GeminiCacheAction.LIST && row.usageJson.toByteArray(Charsets.UTF_8).size > 65_536) invalid()
        val costMatches = if (value.endedAt == null) row.costJson == null else Jsonx.decodeOrNull<CostSnapshot>(row.costJson) == value.cost
        if (value.accountingVersion != 1 || row.id != eventId(value.taskId) || row.eventId != row.id || row.requestId != value.taskId ||
            row.chatId != value.owner.chatId || row.provider != "gemini-cache:${value.owner.providerId}" ||
            row.model != value.owner.model.takeUnless { value.action == GeminiCacheAction.LIST } ||
            row.createdAt != value.createdAt || !costMatches) invalid()
        return value
    }
    /** Restore epochs invalidate runtime authority, not the meaning of historical accounting evidence. */
    fun sameEvidence(left: GeminiCacheTaskProgress, right: GeminiCacheTaskProgress) = left.copy(processEpoch = right.processEpoch) == right
    fun samePayload(payload: String, envelope: String): Boolean {
        val task = NativeJsonInput.parse(payload) as? JsonObject ?: invalid()
        val ledger = (NativeJsonInput.parse(envelope) as? JsonObject)?.get("operation") as? JsonObject ?: invalid()
        return task.filterKeys { it != "processEpoch" } == ledger.filterKeys { it != "processEpoch" }
    }
    fun identity(value: GeminiCacheTaskProgress) = value.copy(updatedAt = value.createdAt, phase = "queued",
        resourceName = if (value.action == GeminiCacheAction.CREATE) null else value.resourceName, resource = null,
        httpAttempted = false, attemptedAt = null, remoteCompleted = false, endedAt = null, errorCode = null,
        cost = CostSnapshot(reason = "request_not_sent"), projectedCost = null, listing = null,
        sourceFilesExpireTime = null, fileTransfers = emptyList())
    fun validateTransition(previous: GeminiCacheTaskProgress, next: GeminiCacheTaskProgress) {
        if (identity(previous) != identity(next) || OffsetDateTime.parse(next.updatedAt).toInstant() < OffsetDateTime.parse(previous.updatedAt).toInstant() ||
            previous.httpAttempted && (!next.httpAttempted || next.attemptedAt != previous.attemptedAt) ||
            previous.sourceFilesExpireTime != null && next.sourceFilesExpireTime != previous.sourceFilesExpireTime ||
            previous.remoteCompleted && !next.remoteCompleted || previous.endedAt != null && next != previous) invalid()
    }
    /** The same immutable archive time and operation time normalize task and ledger, preserving unknown JSON. */
    fun restoredOperation(raw: String, snapshotAt: Instant): String {
        val value = operation(raw)
        val original = NativeJsonInput.parse(raw) as JsonObject
        val changes = mutableMapOf<String, JsonElement>("processEpoch" to JsonPrimitive("native-restored"))
        if (value.endedAt == null) {
            val at = maxOf(snapshotAt, OffsetDateTime.parse(value.updatedAt).toInstant()).toString()
            changes.putAll(mapOf("phase" to JsonPrimitive("interrupted"), "updatedAt" to JsonPrimitive(at),
                "endedAt" to JsonPrimitive(at), "errorCode" to JsonPrimitive("cache_restore_interrupted")))
        }
        return JsonObject(original + changes).toString().also(::operation)
    }
    fun restoredEvent(row: UsageEventEntity, snapshotAt: Instant): UsageEventEntity {
        parse(row)
        val original = NativeJsonInput.parse(row.usageJson) as JsonObject
        val payload = restoredOperation(original["operation"]!!.toString(), snapshotAt)
        val next = event(payload, row.usageJson)
        // Existing terminal cost JSON can contain opaque fields; it is not repriced or reserialized on restore.
        return next.copy(costJson = if (row.costJson != null) row.costJson else next.costJson).also(::parse)
    }
    private fun budget(row: UsageEventEntity) {
        if (listOfNotNull(row.id, row.eventId, row.requestId, row.chatId, row.provider, row.model, row.createdAt)
                .sumOf { it.toByteArray(Charsets.UTF_8).size } > 4096 || row.provider?.toByteArray(Charsets.UTF_8)?.size?.let { it > 1024 } == true ||
            row.usageJson.toByteArray(Charsets.UTF_8).size > MAX_EVENT_BYTES || (row.costJson?.toByteArray(Charsets.UTF_8)?.size ?: 0) > 32_768) invalid()
    }
    private fun invalid(): Nothing = throw StError.Validation("invalid Gemini cache accounting record or transition")
}
