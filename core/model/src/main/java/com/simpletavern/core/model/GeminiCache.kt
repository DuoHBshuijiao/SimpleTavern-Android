package com.simpletavern.core.model

import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/** Remote resource metadata only, never cached prompt text, headers, keys or a replay instruction. */
@Serializable
data class GeminiCacheMetadata(val name: String, val model: String, val expireTime: String,
    val totalTokenCount: Long?, val createTime: String? = null, val updateTime: String? = null,
    val warnings: List<String> = listOf("cache_storage_cost_not_in_generation_usage"))

@Serializable
data class GeminiCachePage(val caches: List<GeminiCacheMetadata>, val nextPageToken: String?)

/** A confirmed account observation, not local ownership. Pagination tokens remain runtime-only. */
@Serializable
data class GeminiCacheListing(val caches: List<GeminiCacheMetadata>, val hasNextPage: Boolean,
    val warnings: List<String> = listOf("account_listing_not_local_ownership_proof"))

@Serializable
data class GeminiCacheCreateRequest(val chatId: String, val system: String, val ttlSeconds: Long = 3600,
    val characterId: String? = null, val toolsJson: String? = null, val contents: List<ContextMessage> = emptyList(),
    val nativeWebSearch: Boolean = false) {
    override fun toString() = "GeminiCacheCreateRequest(<private>)"
}

@Serializable
enum class GeminiCacheAction { CREATE, GET, UPDATE_TTL, UPDATE_EXPIRY, DELETE, LIST }

@Serializable
data class GeminiCacheOwner(val chatId: String, val characterId: String?, val sourceNamespace: String?,
    val providerId: String, val model: String, val baseUrl: String, val authStyle: String?, val presetId: String?)

/** Explicit intent attached to a newly resolved request, not a durable prompt or replay instruction. */
data class GeminiCachePlan(val owner: GeminiCacheOwner, val ttlSeconds: Long = 3600, val historyTail: Boolean = false,
    val selection: GeminiCacheGenerationSelection? = null)

@Serializable
enum class GeminiCacheContentMode { MATCH_PREFIX, PREPEND }

/** A process-local selection of a locally created binding, not a persisted remote replay handle. */
@Serializable
data class GeminiCacheGenerationSelection(val taskId: String, val contentMode: GeminiCacheContentMode = GeminiCacheContentMode.MATCH_PREFIX)

/** Durable operation evidence, not a request, remote ownership certificate, cache price or replay handle. */
@Serializable
data class GeminiCacheTaskProgress(val taskId: String, val owner: GeminiCacheOwner, val action: GeminiCacheAction,
    val createdAt: String, val updatedAt: String, val processEpoch: String, val phase: String = "queued",
    val referenceTaskId: String? = null, val resourceName: String? = null, val resource: GeminiCacheMetadata? = null,
    val ttlSeconds: Long? = null, val requestedExpireTime: String? = null, val httpAttempted: Boolean = false,
    val attemptedAt: String? = null, val remoteCompleted: Boolean = false, val endedAt: String? = null,
    val errorCode: String? = null, val cost: CostSnapshot = CostSnapshot(reason = "request_not_sent"), val version: Int = 1,
    val generationRequestId: String? = null, val automatic: Boolean = false, val historyTail: Boolean = false,
    val generationTaskId: String? = null, val accountingVersion: Int? = null,
    val pricing: GeminiCacheFrozenPrice? = null, val projectedCost: GeminiCacheCostQuote? = null,
    val lifecycleVersion: Int? = null, val lifecyclePreviousTaskIds: List<String> = emptyList(),
    val listingPageSize: Int? = null, val listing: GeminiCacheListing? = null,
    val storagePricing: GeminiCacheFrozenPrice? = null, val sourceFilesExpireTime: String? = null,
    val fileTransfers: List<ProviderFileTransfer> = emptyList())

object GeminiCacheTaskMetadata {
    const val MAX_BYTES = 32768
    const val MAX_LIST_BYTES = 1_048_576
    private val AUTH_STYLES = setOf("bearer", "x-api-key", "api-key", "x-goog-api-key", "query_key", "none")
    private val ALLOWED_PHASES = setOf("queued", "preparing", "executing", "completed", "failed", "stopped", "interrupted")
    private val TERMINAL_PHASES = setOf("completed", "failed", "stopped", "interrupted")
    private val PREPARING_PHASES = setOf("queued", "preparing")
    private val COST_REASONS = setOf("request_not_sent", "cache_cost_not_priced", "cache_remote_outcome_unknown")
    private val ERROR_CODE_PATTERN = Regex("[a-z][a-z0-9_]{0,127}")
    fun byteLimit(action: GeminiCacheAction) = if (action == GeminiCacheAction.LIST) MAX_LIST_BYTES else MAX_BYTES
    fun kind(action: GeminiCacheAction) = if (action == GeminiCacheAction.LIST) TaskKind.GEMINI_CACHE_LIST else TaskKind.GEMINI_CACHE_OPERATION
    fun validateTask(value: GeminiCacheTaskProgress, id: String, chatId: String?, createdAt: String, status: TaskStatus) {
        validate(value)
        validateTaskBinding(value, id, chatId, createdAt, status)
    }
    /** Binding is for parsed results that have already passed full metadata validation. */
    fun validateTaskBinding(value: GeminiCacheTaskProgress, id: String, chatId: String?, createdAt: String, status: TaskStatus) {
        if (value.taskId != id || value.owner.chatId != chatId || value.createdAt != createdAt ||
            status.isTerminal != (value.endedAt != null) || status.isTerminal && value.phase !=
                (if (status == TaskStatus.SUCCEEDED) "completed" else status.name.lowercase()))
            throw StError.Validation("cache task and operation record differ")
    }
    /** Owner checks are an input boundary; they do not establish remote ownership. */
    fun validateOwner(owner: GeminiCacheOwner): GeminiCacheRoutes.Route {
        fun invalid(): Nothing = throw StError.Validation("invalid Gemini cache operation record")
        fun identity(text: String) { if (text.isBlank() || text.length > 1024 || text.any(Char::isISOControl)) invalid() }
        listOfNotNull(owner.chatId, owner.characterId, owner.sourceNamespace, owner.providerId, owner.presetId).forEach(::identity)
        val route = runCatching { GeminiCacheRoutes.select(owner.baseUrl) }.getOrNull() ?: invalid()
        runCatching { route.requestModel(owner.model) }.getOrElse { invalid() }
        if (route.isVertex) {
            if (owner.authStyle != "bearer") invalid()
        } else owner.authStyle?.let { if (it !in AUTH_STYLES) invalid() }
        return route
    }
    fun validate(value: GeminiCacheTaskProgress) {
        fun invalid(): Nothing = throw StError.Validation("invalid Gemini cache operation record")
        if (value.accountingVersion != null && value.accountingVersion != 1) invalid()
        val isList = value.action == GeminiCacheAction.LIST
        if (isList) {
            if (value.accountingVersion != 1 || value.listingPageSize?.let { it in 1..1000 } != true || value.lifecycleVersion != null ||
                value.lifecyclePreviousTaskIds.isNotEmpty() || value.resourceName != null || value.resource != null ||
                value.referenceTaskId == value.taskId || value.remoteCompleted != (value.listing != null)) invalid()
        } else if (value.listingPageSize != null || value.listing != null) invalid()
        if (value.lifecycleVersion != null && (value.lifecycleVersion != 1 || value.accountingVersion != 1) ||
            value.lifecyclePreviousTaskIds.size > 64 || value.lifecyclePreviousTaskIds.distinct().size != value.lifecyclePreviousTaskIds.size ||
            value.lifecyclePreviousTaskIds.any { it == value.taskId || it.isBlank() || it.length > 1024 || it.any(Char::isISOControl) } ||
            value.lifecycleVersion == null && value.lifecyclePreviousTaskIds.isNotEmpty() ||
            value.action == GeminiCacheAction.CREATE && value.lifecyclePreviousTaskIds.isNotEmpty() ||
            value.lifecycleVersion == 1 && value.action != GeminiCacheAction.CREATE && value.lifecyclePreviousTaskIds.isEmpty()) invalid()
        value.pricing?.let {
            if (value.action != GeminiCacheAction.CREATE || value.accountingVersion != 1) invalid()
            GeminiCachePricing.validateFrozen(it, value.owner, value.createdAt)
        }
        value.storagePricing?.let {
            if (value.action != GeminiCacheAction.UPDATE_TTL && value.action != GeminiCacheAction.UPDATE_EXPIRY ||
                value.accountingVersion != 1 || value.lifecycleVersion != 1) invalid()
            GeminiCachePricing.validateFrozen(it, value.owner, value.createdAt)
        }
        fun identity(text: String) { if (text.isBlank() || text.length > 1024 || text.any(Char::isISOControl)) invalid() }
        listOfNotNull(value.taskId, value.referenceTaskId, value.processEpoch, value.generationRequestId, value.generationTaskId).forEach(::identity)
        if (value.automatic != (value.generationRequestId != null) || value.automatic && value.action != GeminiCacheAction.CREATE ||
            (value.historyTail || value.generationTaskId != null) && !value.automatic) invalid()
        fun time(raw: String) = raw.takeIf { it.length <= 64 }?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: invalid()
        val created = time(value.createdAt)
        val updated = time(value.updatedAt)
        val attempted = value.attemptedAt?.let(::time)
        val ended = value.endedAt?.let(::time)
        value.sourceFilesExpireTime?.let(::time)
        if (value.version != 1 || updated < created || value.httpAttempted != (attempted != null) ||
            attempted?.let { it < created || it > updated } == true || ended?.let { it < (attempted ?: created) || it > updated } == true ||
            value.remoteCompleted && (!value.httpAttempted || ended == null) || value.resource != null && !value.remoteCompleted ||
            value.phase !in ALLOWED_PHASES ||
            (value.phase in TERMINAL_PHASES) != (ended != null) ||
            value.phase == "completed" && !value.remoteCompleted || value.phase in PREPARING_PHASES && value.httpAttempted ||
            value.phase == "executing" && !value.httpAttempted) invalid()
        val route = validateOwner(value.owner)
        if (value.action == GeminiCacheAction.CREATE && value.referenceTaskId != null || value.action != GeminiCacheAction.CREATE && value.action != GeminiCacheAction.LIST &&
            (value.referenceTaskId == null || value.resourceName == null)) invalid()
        if (value.action == GeminiCacheAction.CREATE || value.action == GeminiCacheAction.UPDATE_TTL) {
            if (value.ttlSeconds?.let { it in 1..604800 } != true || value.requestedExpireTime != null) invalid()
        } else if (value.ttlSeconds != null) invalid()
        if (value.action == GeminiCacheAction.UPDATE_EXPIRY) { time(value.requestedExpireTime ?: invalid()) }
        else if (value.requestedExpireTime != null) invalid()
        value.resourceName?.let { if (!route.validResource(it)) invalid() }
        value.resource?.let { resource ->
            if (resource.name != value.resourceName || !route.matchesModel(resource.model, value.owner.model) ||
                resource.totalTokenCount?.let { it < 0 } == true) invalid()
            time(resource.expireTime); resource.createTime?.let(::time); resource.updateTime?.let(::time)
        }
        value.listing?.let { page ->
            if (page.caches.size > value.listingPageSize!! || page.caches.map { it.name }.distinct().size != page.caches.size) invalid()
            page.caches.forEach { resource ->
                if (!route.validResource(resource.name) || !route.validModel(resource.model) || resource.totalTokenCount?.let { it < 0 } == true) invalid()
                time(resource.expireTime); resource.createTime?.let(::time); resource.updateTime?.let(::time)
            }
        }
        if (value.remoteCompleted && value.action != GeminiCacheAction.DELETE && value.action != GeminiCacheAction.LIST && value.resource == null) invalid()
        if (value.projectedCost != null) {
            if (!value.remoteCompleted || value.action != GeminiCacheAction.CREATE || value.pricing == null ||
                value.projectedCost != GeminiCachePricing.quote(value.pricing, value.owner, value.createdAt, value.resource?.totalTokenCount, value.ttlSeconds ?: invalid())) invalid()
        } else if (value.pricing != null && value.remoteCompleted && value.action == GeminiCacheAction.CREATE) invalid()
        if (value.errorCode?.matches(ERROR_CODE_PATTERN) == false || value.cost.source != "unknown" ||
            value.cost.amount != null || value.cost.currency != null || value.cost.providerReported != null ||
            value.cost.reason !in COST_REASONS ||
            value.cost != CostSnapshot(reason = value.cost.reason)) invalid()
        val expectedCost = if (value.remoteCompleted) "cache_cost_not_priced" else if (value.httpAttempted) "cache_remote_outcome_unknown" else "request_not_sent"
        if (value.cost.reason != expectedCost) invalid()
    }
}
