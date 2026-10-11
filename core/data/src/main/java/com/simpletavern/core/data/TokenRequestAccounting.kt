package com.simpletavern.core.data

import androidx.room.withTransaction
import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.data.db.UsageEventEntity
import com.simpletavern.core.model.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** A single prepared -> running -> terminal row; contains no request text, credential or replay handle. */
class TokenUsageRequest internal constructor(private val db: AppDatabase, private var row: UsageEventEntity,
    private val prices: UsageCostPlan, private val credentialMaterial: String, credentialMaterials: List<String> = emptyList()) {
    private val credentialMaterials = credentialMaterials.toList()
    private val lock = Mutex()
    suspend fun beforeExecute() = withContext(NonCancellable + Dispatchers.IO) { lock.withLock {
        val before = TokenRequestAccounting.parse(row) ?: throw StError.Validation("token request lifecycle missing")
        if (before.context.status != "prepared") throw StError.Conflict("token request cannot execute twice")
        val at = maxOf(Instant.now(), PricingEngine.instant(row.createdAt)).toString()
        save(row.copy(usageJson = TokenRequestAccounting.encode(before.tokens, before.context.copy(status = "running"),
            before.lifecycle.copy(httpAttempted = true, attemptedAt = at))))
    } }
    suspend fun finish(tokens: UsageTokens, status: String, firstTokenLatencyMs: Long?, totalDurationMs: Long,
        errorCode: String?): CostSnapshot = withContext(NonCancellable + Dispatchers.IO) { lock.withLock {
        val before = TokenRequestAccounting.parse(row) ?: throw StError.Validation("token request lifecycle missing")
        if (before.context.status !in setOf("prepared", "running")) throw StError.Conflict("token request is already terminal")
        val at = maxOf(Instant.now(), PricingEngine.instant(row.createdAt),
            before.lifecycle.attemptedAt?.let(PricingEngine::instant) ?: Instant.MIN).toString()
        val safeTokens = TokenRequestAccounting.safeTokens(tokens, credentialMaterial, credentialMaterials)
        val computed = if (before.lifecycle.httpAttempted) prices.cost(safeTokens) else CostSnapshot(reason = "request_not_sent")
        val cost = if (TokenRequestAccounting.containsCredential(Jsonx.json.parseToJsonElement(Jsonx.encode(computed)), credentialMaterial, credentialMaterials))
            CostSnapshot(reason = "cost_metadata_omitted") else computed
        val context = before.context.copy(status = status, resolvedModel = safeTokens.resolvedModel,
            firstTokenLatencyMs = firstTokenLatencyMs, totalDurationMs = totalDurationMs)
        val next = row.copy(usageJson = TokenRequestAccounting.encode(safeTokens, context, before.lifecycle.copy(endedAt = at, errorCode = errorCode)),
            costJson = Jsonx.encode(cost))
        save(next)
        cost
    } }
    private suspend fun save(next: UsageEventEntity) {
        TokenRequestAccounting.parse(next) ?: throw StError.Validation("token request lifecycle missing")
        db.withTransaction {
            if (db.usage().finishTokenRequest(row.id, row.chatId, row.provider, row.model, row.createdAt,
                    row.usageJson, next.usageJson, next.costJson) != 1) throw StError.Conflict("token request changed before checkpoint")
        }
        row = next // assignment shares the short non-cancellable commit boundary.
    }
}

/** Shared live/native contract. Old ordinary token rows without a lifecycle stay historical. */
object TokenRequestAccounting {
    private val PURPOSES = setOf("tts_preprocess", "chat_generation", "memory_summary", "state_maintenance")
    data class Snapshot(val tokens: UsageTokens, val context: UsageRecordContext, val lifecycle: TokenRequestLifecycle)
    fun containsCredential(value: JsonElement, secret: String): Boolean = secret.isNotBlank() && when (value) {
        is JsonPrimitive -> value.isString && value.content.contains(secret)
        is JsonObject -> value.any { (key, child) -> key.contains(secret) || containsCredential(child, secret) }
        is JsonArray -> value.any { containsCredential(it, secret) }
    }
    fun containsCredential(value: JsonElement, secret: String, additional: List<String>): Boolean =
        containsCredential(value, secret) || additional.any { containsCredential(value, it) }
    fun safeTokens(tokens: UsageTokens, secrets: List<String>): UsageTokens = safeTokens(tokens, "", secrets)
    fun safeTokens(tokens: UsageTokens, secret: String, additional: List<String> = emptyList()): UsageTokens = try {
        val raw = Jsonx.encode(tokens)
        if (raw.toByteArray(Charsets.UTF_8).size > 32_768 || containsCredential(NativeJsonInput.parse(raw), secret, additional)) invalid()
        listOfNotNull(tokens.resolvedModel, tokens.serviceTier, tokens.requestedServiceTier).forEach {
            SpeechText.validate(it, 2048)
            if (it.isBlank() || it.length > 512 || it.any(Char::isISOControl)) invalid()
        }
        if (listOfNotNull(tokens.inputTokens, tokens.outputTokens, tokens.totalTokens, tokens.reasoningTokens, tokens.cacheReadInputTokens,
                tokens.cacheWriteInputTokens, tokens.cacheWriteShortTokens, tokens.cacheWriteLongTokens).any { it < 0 }) invalid()
        tokens.providerCost?.let {
            if (it.amount.length > 64 || it.amount.any(Char::isISOControl) ||
                it.currency?.let { value -> value.length > 16 || value.any(Char::isISOControl) } == true) invalid()
        }
        tokens
    } catch (_: Exception) { UsageTokens(pricingWarnings = listOf("usage_metadata_omitted")) }
    fun encode(tokens: UsageTokens, context: UsageRecordContext, lifecycle: TokenRequestLifecycle): String =
        // Canonical header indexes our own unfinished rows without scanning unrelated old token events.
        JsonObject(mapOf("_requestLifecycle" to Jsonx.json.parseToJsonElement(Jsonx.encode(lifecycle))) +
            Jsonx.json.parseToJsonElement(Jsonx.encode(tokens)).jsonObject +
            ("_recordContext" to Jsonx.json.parseToJsonElement(Jsonx.encode(context)))).toString()

    fun fromColumns(columns: JsonObject): UsageEventEntity {
        fun text(name: String): String? = columns[name]?.takeIf { it != JsonNull }?.let {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw StError.Validation("token request column must be text")
        }
        fun required(name: String) = text(name) ?: throw StError.Validation("token request column missing")
        return UsageEventEntity(required("id"), required("eventId"), required("requestId"), text("chatId"), text("provider"),
            text("model"), required("usageJson"), text("costJson"), required("createdAt"))
    }
    fun parse(row: UsageEventEntity): Snapshot? {
        if (row.usageJson.toByteArray(Charsets.UTF_8).size > 65_536) throw StError.Validation("token request exceeds usage budget")
        val envelope = NativeJsonInput.parse(row.usageJson) as? JsonObject ?: throw StError.Validation("token request envelope must be an object")
        if (!envelope.containsKey("_requestLifecycle")) return null
        val tokens = Jsonx.decodeOrNull<UsageTokens>(row.usageJson) ?: throw StError.Validation("invalid token request usage")
        val context = envelope["_recordContext"]?.let { Jsonx.decodeOrNull<UsageRecordContext>(it.toString()) }
            ?: throw StError.Validation("invalid token request context")
        val lifecycle = envelope["_requestLifecycle"]?.let { Jsonx.decodeOrNull<TokenRequestLifecycle>(it.toString()) }
            ?: throw StError.Validation("invalid token request lifecycle")
        if (envelope["_kind"]?.let { it != JsonPrimitive("llm") } == true ||
            context.purpose == "tts_preprocess" && !SpeechPreprocessMetadata.validRequestId(row.requestId) ||
            row.id != row.requestId || row.eventId != row.id || context.purpose !in PURPOSES ||
            context.protocol !in LlmProtocol.entries.map { it.name } || context.requestedModel != row.model ||
            context.startedAt != row.createdAt || context.status !in setOf("prepared", "running", "completed", "failed", "cancelled", "interrupted") ||
            lifecycle.version != 1 || lifecycle.httpAttempted != (lifecycle.attemptedAt != null)) invalid()
        fun identity(value: String) { SpeechText.validate(value, 1024); if (value.isBlank() || value.any(Char::isISOControl)) invalid() }
        listOfNotNull(row.requestId, row.provider, row.chatId, context.sourceNamespace, context.taskId, context.messageCandidateId).forEach(::identity)
        if (row.provider == null || context.messageCandidateId != null && row.chatId == null) invalid()
        val scope = context.scope ?: throw StError.Validation("token request scope missing")
        if (scope != "global") {
            when {
                scope.startsWith("assistant:") -> identity(scope.removePrefix("assistant:"))
                scope.startsWith("chat:") -> { identity(scope.removePrefix("chat:")); if (scope != "chat:${row.chatId}") invalid() }
                else -> invalid()
            }
        }
        val roundLimit = if (context.purpose == "chat_generation") 64 else 8
        if (context.segmentIndex?.let { it !in 1..100_000 } == true || context.roundIndex?.let { it !in 1..roundLimit } == true ||
            context.turnIndex?.let { it !in 0..10_000 } == true) invalid()
        listOfNotNull(row.model, context.resolvedModel, tokens.resolvedModel, tokens.serviceTier, tokens.requestedServiceTier).forEach {
            SpeechText.validate(it, 2048); if (it.isBlank() || it.length > 512 || it.any(Char::isISOControl)) invalid()
        }
        if (row.model == null || listOfNotNull(tokens.inputTokens, tokens.outputTokens, tokens.totalTokens, tokens.reasoningTokens,
                tokens.cacheReadInputTokens, tokens.cacheWriteInputTokens, tokens.cacheWriteShortTokens, tokens.cacheWriteLongTokens).any { it < 0 } ||
            listOfNotNull(context.firstTokenLatencyMs, context.totalDurationMs).any { it < 0 }) invalid()
        if (listOfNotNull(row.id, row.eventId, row.requestId, row.chatId, row.provider, row.model, row.createdAt)
                .sumOf { it.toByteArray(Charsets.UTF_8).size } > 4096 || (row.costJson?.toByteArray(Charsets.UTF_8)?.size ?: 0) > 32_768) invalid()
        if (row.createdAt.length > 64 || listOfNotNull(lifecycle.attemptedAt, lifecycle.endedAt).any { it.length > 64 }) invalid()
        val start = PricingEngine.instant(row.createdAt)
        val attempt = lifecycle.attemptedAt?.let(PricingEngine::instant)
        val end = lifecycle.endedAt?.let(PricingEngine::instant)
        val pending = context.status in setOf("prepared", "running")
        if (attempt?.let { it < start } == true || end?.let { it < start || attempt != null && it < attempt } == true ||
            pending != (end == null) || pending && row.costJson != null || !pending && row.costJson == null ||
            context.status == "prepared" && lifecycle.httpAttempted || context.status in setOf("running", "completed") && !lifecycle.httpAttempted ||
            pending && lifecycle.errorCode != null || lifecycle.errorCode?.matches(Regex("[a-z][a-z0-9_]{0,127}")) == false) invalid()
        row.costJson?.let { raw ->
            NativeJsonInput.parse(raw)
            val cost = Jsonx.decodeOrNull<CostSnapshot>(raw) ?: throw StError.Validation("invalid token request cost")
            if (cost.version != 1 || cost.source !in setOf("unknown", "provider", "estimated") || cost.source != "unknown" &&
                (cost.currency?.let(PricingEngine::currency) != true || cost.amount?.let { runCatching { PricingEngine.decimal(it) }.isSuccess } != true)) invalid()
            if (!lifecycle.httpAttempted && cost.source != "unknown") invalid()
        }
        return Snapshot(tokens, context, lifecycle)
    }
    fun interrupt(row: UsageEventEntity, at: String, reason: String): UsageEventEntity {
        val before = parse(row) ?: return row
        if (before.context.status !in setOf("prepared", "running")) return row
        val ended = maxOf(PricingEngine.instant(at), PricingEngine.instant(row.createdAt),
            before.lifecycle.attemptedAt?.let(PricingEngine::instant) ?: Instant.MIN).toString()
        return row.copy(usageJson = encode(before.tokens, before.context.copy(status = "interrupted"),
            before.lifecycle.copy(endedAt = ended, errorCode = reason)), costJson = Jsonx.encode(CostSnapshot(reason = reason)))
            .also { parse(it) }
    }
    private fun invalid(): Nothing = throw StError.Validation("inconsistent token request lifecycle")
}
