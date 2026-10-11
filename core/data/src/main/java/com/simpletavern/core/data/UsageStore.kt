package com.simpletavern.core.data

import androidx.room.withTransaction
import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.data.db.AppSettingsEntity
import com.simpletavern.core.data.db.UsageEventEntity
import com.simpletavern.core.model.*
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Frozen rules for one new caller-owned token request; no repricing on completion. */
class UsageCostPlan internal constructor(private val estimate: (UsageTokens) -> CostSnapshot) {
    fun cost(tokens: UsageTokens): CostSnapshot = estimate(tokens)
}

/** Immutable event-time cost snapshots. Existing events are never implicitly repriced or replayed. */
class UsageStore(private val db: AppDatabase) {
    private val instanceEpoch = UUID.randomUUID().toString()
    @Volatile private var cachedRules: Pair<String, PricingEngine.Prepared>? = null
    private suspend fun epoch() = "$instanceEpoch:${db.fileChanges().restoreEpoch().orEmpty()}"

    private suspend fun rules(): PricingEngine.Prepared {
        if ((db.config().settingsBytes(PRICING_ID) ?: 0) > 262_144) throw StError.Validation("pricing configuration exceeds 256 KiB")
        val raw = db.config().getSettings(PRICING_ID)?.settingsJson ?: return PricingEngine.prepare(PricingRuleSet())
        cachedRules?.takeIf { it.first == raw }?.let { return it.second } // exact text comparison, no content hash
        val value = try { Jsonx.decode<PricingRuleSet>(raw) } catch (_: Exception) { throw StError.Validation("pricing configuration is invalid") }
        val prepared = PricingEngine.prepare(value)
        cachedRules = raw to prepared
        return prepared
    }

    suspend fun pricingRules(): PricingSnapshot = withContext(Dispatchers.IO) { db.withTransaction {
        val value = rules().value
        PricingSnapshot(value, PricingVersion(epoch(), value.revision))
    } }

    /** User edits never erase the read-only catalog half, and catalog installation never erases users. */
    suspend fun replacePricingRules(incoming: List<PricingRule>, expected: PricingVersion, catalog: Boolean = false): PricingSnapshot =
        withContext(Dispatchers.IO) { db.withTransaction {
            val previous = rules().value
            val currentEpoch = epoch()
            if (expected != PricingVersion(currentEpoch, previous.revision)) throw StError.Conflict("pricing rules changed; refresh before editing")
            val source = if (catalog) "catalog" else "user"
            if (incoming.any { it.source != source || (catalog && !it.id.startsWith("catalog:")) })
                throw StError.Validation("pricing rule ownership differs from this operation")
            if (previous.revision == Long.MAX_VALUE) throw StError.Validation("pricing revision exhausted")
            val value = PricingRuleSet(revision = previous.revision + 1,
                rules = previous.rules.filter { it.source != source } + incoming, updatedAt = Instant.now().toString())
            val prepared = PricingEngine.prepare(value)
            val raw = Jsonx.encode(value)
            if (raw.toByteArray(Charsets.UTF_8).size > 262_144) throw StError.Validation("pricing configuration exceeds 256 KiB")
            db.config().upsertSettings(AppSettingsEntity(PRICING_ID, raw, value.updatedAt!!))
            cachedRules = raw to prepared
            PricingSnapshot(value, PricingVersion(currentEpoch, value.revision))
        } }

    suspend fun estimate(tokens: UsageTokens, provider: String?, model: String?, at: String = Instant.now().toString()): CostSnapshot =
        withContext(Dispatchers.IO) { db.withTransaction { PricingEngine.estimate(rules(), tokens, provider, model, at) } }

    suspend fun prepareCost(provider: String?, model: String?, at: String): UsageCostPlan = withContext(Dispatchers.IO) { db.withTransaction {
        PricingEngine.instant(at)
        val prepared = try { rules() } catch (_: StError.Validation) { null }
        val frozen = prepared ?: PricingEngine.prepare(PricingRuleSet())
        UsageCostPlan { tokens ->
            val value = PricingEngine.estimate(frozen, tokens, provider, model, at)
            if (prepared == null) value.copy(reason = "pricing_configuration_invalid") else value
        }
    } }

    suspend fun record(e: UsageEventEntity, context: UsageRecordContext? = null): Boolean = withContext(Dispatchers.IO) { db.withTransaction {
        if (db.usage().exists(e.eventId)) return@withTransaction false
        if (listOfNotNull(e.id, e.eventId, e.requestId, e.chatId, e.provider, e.model, e.createdAt).sumOf { it.toByteArray(Charsets.UTF_8).size } > 4096 ||
            e.usageJson.toByteArray(Charsets.UTF_8).size > 65_536 || (e.costJson?.toByteArray(Charsets.UTF_8)?.size ?: 0) > 32_768)
            throw StError.Validation("usage event exceeds storage budget")
        PricingEngine.instant(e.createdAt)
        val envelope = NativeJsonInput.parse(e.usageJson) as? JsonObject ?: throw StError.Validation("invalid usage envelope")
        if (envelope.containsKey("_kind") && envelope["_kind"] != JsonPrimitive("llm"))
            throw StError.Validation("non-token usage must use its dedicated accounting service")
        if (envelope.containsKey("_requestLifecycle")) throw StError.Validation("durable token requests must use their lifecycle service")
        val tokens = Jsonx.decodeOrNull<UsageTokens>(e.usageJson) ?: throw StError.Validation("invalid usage token snapshot")
        val at = context?.startedAt ?: e.createdAt
        val cost = if (e.costJson != null) e.costJson else {
            val prepared = try { rules() } catch (_: StError.Validation) { null }
            val snapshot = PricingEngine.estimate(prepared ?: PricingEngine.prepare(PricingRuleSet()), tokens, e.provider, e.model, at)
            Jsonx.encode(if (prepared == null) snapshot.copy(reason = "pricing_configuration_invalid") else snapshot)
        }
        val raw = if (context == null) e.usageJson else {
            val original = Jsonx.json.parseToJsonElement(e.usageJson).jsonObject
            JsonObject(original + ("_recordContext" to Jsonx.json.parseToJsonElement(Jsonx.encode(context)))).toString()
        }
        if (raw.toByteArray(Charsets.UTF_8).size > 65_536 || cost.toByteArray(Charsets.UTF_8).size > 32_768)
            throw StError.Validation("usage accounting snapshot exceeds storage budget")
        db.usage().insertIgnore(e.copy(usageJson = raw, costJson = cost)) != -1L
    } }

    suspend fun beginTokenRequest(requestId: String, chatId: String?, provider: String, model: String,
        context: UsageRecordContext, prices: UsageCostPlan, credentialMaterial: String = "",
        credentialMaterials: List<String> = emptyList()): TokenUsageRequest = withContext(Dispatchers.IO) { db.withTransaction {
        val at = context.startedAt ?: throw StError.Validation("token request start time missing")
        val row = UsageEventEntity(requestId, requestId, requestId, chatId, provider, model,
            TokenRequestAccounting.encode(UsageTokens(), context.copy(status = "prepared"), TokenRequestLifecycle()), null, at)
        TokenRequestAccounting.parse(row) ?: throw StError.Validation("token request lifecycle missing")
        val secrets = (listOf(credentialMaterial) + credentialMaterials).filter(String::isNotBlank).distinct()
        if (listOfNotNull(row.id, row.chatId, row.provider, row.model).any { value -> secrets.any(value::contains) } ||
            TokenRequestAccounting.containsCredential(Jsonx.json.parseToJsonElement(row.usageJson), "", secrets))
            throw StError.Validation("token request metadata contains credential material")
        if (db.usage().insertIgnore(row) == -1L) throw StError.Conflict("token request identity already exists")
        TokenUsageRequest(db, row, prices, credentialMaterial, secrets)
    } }

    /** Vendor accounting closes outside the caller's later message/memory/state commit. */
    suspend fun <T> executeTokenRequest(requestId: String, chatId: String?, provider: String, model: String,
        context: UsageRecordContext, credentialMaterial: String, outcome: suspend () -> TokenRequestOutcome,
        recorded: suspend (CostSnapshot) -> Unit = {}, credentialMaterials: List<String> = emptyList(),
        run: suspend (suspend () -> Unit) -> T): T {
        var request: TokenUsageRequest? = null
        var failure: Throwable? = null
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                val prices = prepareCost(provider, model, context.startedAt ?: throw StError.Validation("token request time missing"))
                request = beginTokenRequest(requestId, chatId, provider, model, context, prices, credentialMaterial, credentialMaterials)
            }
            currentCoroutineContext().ensureActive()
            return run { request!!.beforeExecute() }
        } catch (error: Throwable) { failure = error; throw error }
        finally { request?.let { ledger -> withContext(NonCancellable + Dispatchers.IO) {
            try {
                val info = outcome()
                val status = if (info.completed) "completed" else if (failure is CancellationException) "cancelled" else "failed"
                val cost = ledger.finish(info.tokens, status, info.firstTokenLatencyMs, info.totalDurationMs,
                    if (info.completed) null else if (status == "cancelled") "cancelled" else "request_failed")
                recorded(cost)
            } catch (_: Exception) {
                val accounting = StError.Accounting("request accounting could not be finalized; do not automatically retry", mapOf("requestId" to requestId))
                val original = failure
                if (original != null) original.addSuppressed(accounting) else throw accounting
            }
        } } }
    }

    /** Startup only; historical token rows without the new marker are never guessed or replayed. */
    suspend fun interruptPendingTokenRequests() = withContext(Dispatchers.IO) {
        var after = 0L; var scanned = 0; var bytes = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val rows = db.withTransaction {
                val budgets = db.usage().pendingTokenBudgets(after, 200)
                for (budget in budgets) {
                    if (++scanned > 100_000 || budget.identityBytes > 4096 || budget.usageBytes > 65_536 || budget.costBytes > 32_768)
                        throw StError.Validation("pending token request exceeds row budget")
                    bytes += budget.identityBytes + budget.usageBytes + budget.costBytes
                    if (bytes > 67_108_864) throw StError.Validation("pending token requests exceed scan budget")
                }
                db.usage().pendingTokenPage(after, 200).also { rows -> rows.forEach { item ->
                    val next = TokenRequestAccounting.interrupt(item.event, Instant.now().toString(), "process_interrupted")
                    if (next != item.event && db.usage().finishTokenRequest(item.event.id, item.event.chatId, item.event.provider,
                            item.event.model, item.event.createdAt, item.event.usageJson, next.usageJson, next.costJson) != 1)
                        throw StError.Conflict("pending token request changed")
                } }
            }
            if (rows.isEmpty()) break
            after = rows.last().sequence
        }
    }

    private suspend fun validateReadBudget(budget: com.simpletavern.core.data.db.UsageRowBudget): Long {
        if (budget.identityBytes > 4096 || budget.costBytes > 32768 || budget.usageBytes > GeminiCacheAccounting.MAX_EVENT_BYTES ||
            budget.usageBytes > 65536 && !db.usage().cacheBudgetCandidate(budget.sequence))
            throw StError.Validation("historical usage row exceeds read budget; no rows were silently skipped")
        return budget.identityBytes + budget.costBytes + budget.usageBytes
    }
    private fun validateLargeCacheEvent(event: UsageEventEntity) {
        if (event.usageJson.toByteArray(Charsets.UTF_8).size > 65536) GeminiCacheAccounting.parse(event)
    }
    suspend fun forChat(chatId: String, limit: Int = 100): List<UsageEventEntity> = withContext(Dispatchers.IO) { db.withTransaction {
        if (limit !in 1..200 || chatId.toByteArray(Charsets.UTF_8).size > 1024)
            throw StError.Validation("usage chat filter or limit exceeds budget")
        var bytes = 0L
        db.usage().forChatBudgets(chatId, limit).forEach { bytes += validateReadBudget(it) }
        if (bytes > 8L * 1024 * 1024) throw StError.Validation("historical usage chat page exceeds 8 MiB; use usage pagination")
        db.usage().forChat(chatId, limit).also { it.forEach(::validateLargeCacheEvent) }
    } }

    suspend fun costForEvent(eventId: String): CostSnapshot? = withContext(Dispatchers.IO) { db.withTransaction {
        if ((db.usage().costBytes(eventId) ?: 0) > 32_768) throw StError.Validation("historical usage cost exceeds row budget")
        val raw = db.usage().cost(eventId) ?: return@withTransaction null
        val cost = Jsonx.decodeOrNull<CostSnapshot>(raw)
        if (cost == null || cost.version != 1) throw StError.Validation("historical usage cost version or JSON is invalid")
        cost
    } }

    suspend fun page(filter: UsageFilter = UsageFilter(), cursor: UsageCursor? = null, limit: Int = 100,
                     throughSequence: Long? = null): UsagePage = withContext(Dispatchers.IO) { db.withTransaction {
        if (limit !in 1..200 || listOfNotNull(filter.chatId, filter.provider, filter.model).any { it.toByteArray(Charsets.UTF_8).size > 1024 })
            throw StError.Validation("usage page filter or limit exceeds budget")
        if (filter.kind != null && filter.kind !in SUPPORTED_KINDS) throw StError.Validation("invalid usage event kind filter")
        val since = filter.since?.let(PricingEngine::instant)
        val until = filter.until?.let(PricingEngine::instant)
        if (since != null && until != null && since >= until) throw StError.Validation("usage time interval must be nonempty")
        val currentEpoch = epoch()
        if (cursor != null && (cursor.epoch != currentEpoch || cursor.filter != filter))
            throw StError.Conflict("usage stream reset or filter changed; request a fresh page")
        val after = cursor?.afterSequence ?: 0
        val through = throughSequence ?: db.usage().latestSequence()
        if (after < 0 || through < after) throw StError.Validation("invalid usage sequence range")
        val budgets = db.usage().pageBudgets(after, through, filter.chatId, filter.provider, filter.model, limit + 1)
        var bytes = 0L; var rawLimit = 0; var full = false
        for (budget in budgets) {
            val rowBytes = validateReadBudget(budget)
            if (!full && bytes + rowBytes <= 8L * 1024 * 1024) { bytes += rowBytes; rawLimit++ } else full = true
        }
        val rows = db.usage().page(after, through, filter.chatId, filter.provider, filter.model, rawLimit)
        rows.forEach { validateLargeCacheEvent(it.event) }
        var invalidTime = 0
        val items = rows.take(limit).mapNotNull { row ->
            val e = row.event
            val at = runCatching { PricingEngine.instant(e.createdAt) }.getOrNull()
            if ((since != null || until != null) && at == null) { invalidTime++; return@mapNotNull null }
            if (at != null && (since != null && at < since || until != null && at >= until)) return@mapNotNull null
            val issues = mutableListOf<String>()
            val envelope = runCatching { NativeJsonInput.parse(e.usageJson) as? JsonObject }.getOrNull()
            val kind = if (envelope?.containsKey("_kind") == true) (envelope["_kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "unknown" else "llm"
            if (filter.kind != null && kind != filter.kind) return@mapNotNull null
            val search = if (kind == "web_search") Jsonx.decodeOrNull<SearchUsageSnapshot>(e.usageJson)?.takeIf {
                runCatching { SearchUsageStore.validateSnapshot(it) }.isSuccess && e.provider == "web-search:${it.provider}" && e.chatId == it.context.chatId } else null
            val speech = if (kind == "tts") Jsonx.decodeOrNull<SpeechUsageSnapshot>(e.usageJson)?.takeIf {
                runCatching { SpeechUsageStore.validateSnapshot(it) }.isSuccess && SpeechUsageStore.consistent(e, it) } else null
            val voiceDesign = if (kind == "speech_voice_design") Jsonx.decodeOrNull<SpeechVoiceDesignUsage>(e.usageJson)?.takeIf {
                validVoiceDesignUsage(e, it) } else null
            val voiceClone = if (kind == "speech_voice_clone") Jsonx.decodeOrNull<SpeechVoiceCloneUsage>(e.usageJson)?.takeIf {
                validVoiceCloneUsage(e, it) } else null
            val cache = if (kind == GeminiCacheAccounting.KIND) runCatching { GeminiCacheAccounting.parse(e) }.getOrNull() else null
            val usage = if (kind == "llm" && envelope != null) Jsonx.decodeOrNull<UsageTokens>(e.usageJson) else null
            val tokenRequest = if (envelope?.containsKey("_requestLifecycle") == true)
                runCatching { TokenRequestAccounting.parse(e) }.getOrNull() else null
            if (envelope?.containsKey("_requestLifecycle") == true && tokenRequest == null) issues.add("invalid_token_request_lifecycle")
            if (kind !in SUPPORTED_KINDS) issues.add("unsupported_usage_kind")
            if (kind == GeminiCacheAccounting.KIND && cache == null) issues.add("invalid_cache_usage")
            if (kind == "tts" && speech == null) issues.add("invalid_speech_usage")
            if (kind == "speech_voice_design" && voiceDesign == null) issues.add("invalid_voice_design_usage")
            if (kind == "speech_voice_clone" && voiceClone == null) issues.add("invalid_voice_clone_usage")
            if (kind == "web_search" && (search == null || e.provider != "web-search:${search.provider}" || e.chatId != search.context.chatId)) issues.add("invalid_search_usage")
            if (kind == "llm" && usage == null) issues.add("invalid_usage_json")
            if (usage != null && listOf(usage.inputTokens, usage.outputTokens, usage.totalTokens, usage.reasoningTokens,
                    usage.cacheReadInputTokens, usage.cacheWriteInputTokens, usage.cacheWriteShortTokens,
                    usage.cacheWriteLongTokens).filterNotNull().any { it < 0 }) issues.add("invalid_token_counts")
            if (at == null) issues.add("invalid_event_time")
            val context = if (search != null) UsageRecordContext(protocol = "WEB_SEARCH", status = search.status, startedAt = search.startedAt)
                else if (voiceDesign != null) UsageRecordContext(protocol = "VOICE_DESIGN", status = voiceDesign.status,
                    startedAt = voiceDesign.startedAt, purpose = "voice_design", sourceNamespace = voiceDesign.sourceNamespace,
                    taskId = voiceDesign.taskId)
                else if (voiceClone != null) UsageRecordContext(protocol = "VOICE_CLONE", status = voiceClone.status,
                    startedAt = voiceClone.startedAt, purpose = "voice_clone", sourceNamespace = voiceClone.sourceNamespace,
                    taskId = voiceClone.taskId)
                else if (cache != null) UsageRecordContext(protocol = "GEMINI_CACHE", status = when (cache.phase) {
                    "queued" -> "prepared"; "executing" -> "running"; else -> cache.phase }, startedAt = cache.createdAt,
                    requestedModel = cache.owner.model.takeUnless { cache.action == GeminiCacheAction.LIST }, purpose = "cache_${cache.action.name.lowercase()}",
                    scope = "chat:${cache.owner.chatId}", sourceNamespace = cache.owner.sourceNamespace, taskId = cache.taskId)
                else if (speech != null) UsageRecordContext(protocol = "TTS", status = speech.status, startedAt = speech.startedAt,
                    requestedModel = speech.model, totalDurationMs = speech.endedAt?.let { ended -> runCatching { java.time.Duration.between(
                        PricingEngine.instant(speech.startedAt), PricingEngine.instant(ended)).toMillis() }.getOrNull() })
                else envelope?.get("_recordContext")?.let { Jsonx.decodeOrNull<UsageRecordContext>(it.toString()) }
            val decodedCost = Jsonx.decodeOrNull<CostSnapshot>(e.costJson)
            val cost = decodedCost?.takeIf { it.version == 1 }
            if (e.costJson != null && cost == null) issues.add("invalid_cost_json")
            if (search != null && ((search.status in setOf("prepared", "running") && e.costJson != null) ||
                    (search.status !in setOf("prepared", "running") && cost != SearchUsageStore.cost(search)))) issues.add("search_cost_mismatch")
            if (speech != null && ((speech.status in setOf("prepared", "running") && e.costJson != null) ||
                    (speech.status !in setOf("prepared", "running") && cost != SpeechUsageStore.cost(speech)))) issues.add("speech_cost_mismatch")
            if (cost?.source in setOf("provider", "estimated") &&
                (cost?.currency?.let(PricingEngine::currency) != true || cost.amount?.let {
                    runCatching { PricingEngine.decimal(it) }.isSuccess } != true)) issues.add("invalid_cost_values")
            UsageLedgerItem(row.sequence, e.id, e.eventId, e.requestId, e.chatId, e.provider, e.model, e.createdAt, usage, context, cost,
                issues, kind, search, speech, tokenRequest?.lifecycle, cache, voiceDesign, voiceClone)
        }
        UsagePage(items, UsageCursor(currentEpoch, filter, rows.take(limit).lastOrNull()?.sequence ?: after), through,
            rows.size > limit || budgets.size > rawLimit, rows.take(limit).size, invalidTime)
    } }

    /** Bounded batches, one fixed upper boundary, no long writer transaction or whole-ledger list. */
    suspend fun summary(filter: UsageFilter = UsageFilter()): UsageSummary = withContext(Dispatchers.IO) {
        var cursor: UsageCursor? = null
        var through: Long? = null
        var events = 0L; var unknownInput = 0L; var unknownOutput = 0L; var unknownCost = 0L; var invalid = 0L; var invalidTime = 0L
        var input = BigInteger.ZERO; var output = BigInteger.ZERO; var read = BigInteger.ZERO; var write = BigInteger.ZERO
        var unknownRead = 0L; var unknownWrite = 0L
        var searchEvents = 0L; var unknownCredits = 0L
        val credits = sortedMapOf<String, BigDecimal>()
        var speechEvents = 0L; var unknownSpeechCharacters = 0L; var speechInput = BigInteger.ZERO
        val speechCharacters = sortedMapOf<String, BigInteger>()
        var voiceDesignEvents = 0L; var voiceDesignInput = BigInteger.ZERO
        var voiceCloneEvents = 0L; var voiceCloneInput = BigInteger.ZERO
        var cacheOperations = 0L; var cacheCreations = 0L; var unknownCacheCreated = 0L; var cacheCreatedTokens = BigInteger.ZERO
        var cacheListings = 0L; var unknownListings = 0L; var listedResources = BigInteger.ZERO
        var unknownCacheCreationQuote = 0L; var unknownCacheStorageQuote = 0L
        data class CacheQuotes(var creation: BigDecimal = BigDecimal.ZERO, var storage: BigDecimal = BigDecimal.ZERO,
            var creationCount: Long = 0, var storageCount: Long = 0)
        val cacheQuotes = sortedMapOf<String, CacheQuotes>()
        val statuses = linkedMapOf<String, Long>()
        data class Totals(var provider: BigDecimal = BigDecimal.ZERO, var estimated: BigDecimal = BigDecimal.ZERO,
                          var comparison: BigDecimal = BigDecimal.ZERO)
        val currencies = sortedMapOf<String, Totals>()
        fun count(value: Long?) = value?.takeIf { it >= 0 }?.let(BigInteger::valueOf)
        fun amount(value: String?) = value?.let { runCatching { PricingEngine.decimal(it) }.getOrNull() }
        fun bucket(currency: String): Totals {
            if (currency !in currencies && currencies.size >= 64) throw StError.Validation("usage summary currency budget exceeded")
            return currencies.getOrPut(currency) { Totals() }
        }
        do {
            currentCoroutineContext().ensureActive()
            val page = page(filter, cursor, 200, through)
            through = page.throughSequence
            cursor = page.nextCursor
            invalidTime += page.excludedInvalidTime
            for (e in page.items) {
                events++
                if (e.issues.isNotEmpty()) invalid++
                val status = e.context?.status?.takeIf { it in setOf("prepared", "running", "completed", "succeeded", "failed", "stopped", "interrupted", "cancelled") } ?: "unknown"
                statuses[status] = (statuses[status] ?: 0) + 1
                if (e.kind == "llm") {
                    count(e.usage?.inputTokens)?.let { input += it } ?: run { unknownInput++ }
                    count(e.usage?.outputTokens)?.let { output += it } ?: run { unknownOutput++ }
                    count(e.usage?.cacheReadInputTokens)?.let { read += it } ?: run { unknownRead++ }
                    count(e.usage?.cacheWriteInputTokens)?.let { write += it } ?: run { unknownWrite++ }
                } else if (e.kind == "web_search") {
                    searchEvents++
                    val reported = amount(e.search?.credits)
                    if (reported == null) unknownCredits++ else e.search?.provider?.let { provider ->
                        credits[provider] = (credits[provider] ?: BigDecimal.ZERO) + reported }
                } else if (e.kind == "tts") {
                    speechEvents++
                    e.speech?.inputCharacters?.let { speechInput += BigInteger.valueOf(it) }
                    val reported = count(e.speech?.reportedCharacters)
                    if (reported == null) unknownSpeechCharacters++ else e.speech?.provider?.let { provider ->
                        speechCharacters[provider] = (speechCharacters[provider] ?: BigInteger.ZERO) + reported }
                } else if (e.kind == "speech_voice_design") {
                    voiceDesignEvents++
                    if ("invalid_voice_design_usage" !in e.issues)
                        e.voiceDesign?.inputCharacters?.let { voiceDesignInput += BigInteger.valueOf(it) }
                } else if (e.kind == "speech_voice_clone") {
                    voiceCloneEvents++
                    if ("invalid_voice_clone_usage" !in e.issues)
                        e.voiceClone?.inputCharacters?.let { voiceCloneInput += BigInteger.valueOf(it) }
                } else if (e.kind == GeminiCacheAccounting.KIND) {
                    cacheOperations++
                    val cache = e.geminiCache
                    if (cache?.action == GeminiCacheAction.LIST) {
                        cacheListings++
                        val listing = cache.listing
                        if (cache.remoteCompleted && listing != null) listedResources += BigInteger.valueOf(listing.caches.size.toLong())
                        else unknownListings++
                    }
                    if (cache != null && cache.action == GeminiCacheAction.CREATE) {
                        cacheCreations++
                        val reported = cache.takeIf { it.remoteCompleted }?.resource?.totalTokenCount
                        count(reported)?.let { cacheCreatedTokens += it } ?: run { unknownCacheCreated++ }
                        if (cache.remoteCompleted) {
                            val quote = cache.projectedCost
                            val quoteCurrency = quote?.currency?.takeIf(PricingEngine::currency)
                            val creationAmount = amount(quote?.creationAmount)
                            val storageAmount = amount(quote?.projectedStorageAmount)
                            if (quoteCurrency == null || creationAmount == null) unknownCacheCreationQuote++
                            if (quoteCurrency == null || storageAmount == null) unknownCacheStorageQuote++
                            if (quoteCurrency != null && (creationAmount != null || storageAmount != null)) {
                                if (quoteCurrency !in cacheQuotes && cacheQuotes.size >= 64)
                                    throw StError.Validation("cache quote summary currency budget exceeded")
                                val total = cacheQuotes.getOrPut(quoteCurrency) { CacheQuotes() }
                                creationAmount?.let { total.creation += it; total.creationCount++ }
                                storageAmount?.let { total.storage += it; total.storageCount++ }
                            }
                        }
                    }
                }
                val cost = e.cost.takeUnless { e.kind !in SUPPORTED_KINDS ||
                    e.kind in setOf("speech_voice_design", "speech_voice_clone") || e.issues.any { it in setOf("invalid_search_usage", "search_cost_mismatch", "invalid_speech_usage", "speech_cost_mismatch", "invalid_voice_design_usage", "invalid_voice_clone_usage", "invalid_token_request_lifecycle", "invalid_cache_usage") } }
                val known = amount(cost?.amount)
                val currency = cost?.currency?.takeIf(PricingEngine::currency)
                if (cost == null || known == null || currency == null || cost.source !in setOf("provider", "estimated")) unknownCost++
                else if (cost.source == "provider") {
                    bucket(currency).provider += known
                    val estimateCurrency = cost.estimatedCurrency?.takeIf(PricingEngine::currency)
                    val estimate = amount(cost.estimatedAmount)
                    if (estimate != null && estimateCurrency != null) bucket(estimateCurrency).comparison += estimate
                } else bucket(currency).estimated += known
            }
        } while (page.hasMore)
        UsageSummary(events, statuses, input.toString(), output.toString(), read.toString(), write.toString(), unknownInput,
            unknownOutput, unknownCost, currencies.map { (currency, total) -> CurrencyCostTotal(currency,
                PricingEngine.money(total.provider), PricingEngine.money(total.estimated), PricingEngine.money(total.comparison),
                PricingEngine.money(total.provider + total.estimated)) }, invalid, through!!, cursor!!.epoch, invalidTime,
                unknownRead, unknownWrite, searchEvents, credits.mapValues { PricingEngine.money(it.value) }, unknownCredits,
                speechEvents, speechInput.toString(), speechCharacters.mapValues { it.value.toString() }, unknownSpeechCharacters,
                cacheOperations, cacheCreations, cacheCreatedTokens.toString(), unknownCacheCreated,
                cacheQuotes.map { (currency, quote) -> GeminiCacheQuoteTotal(currency, PricingEngine.money(quote.creation),
                    PricingEngine.money(quote.storage), quote.creationCount, quote.storageCount) },
                unknownCacheCreationQuote, unknownCacheStorageQuote, cacheListings, listedResources.toString(), unknownListings,
                voiceDesignEvents, voiceDesignInput.toString(), voiceCloneEvents, voiceCloneInput.toString())
    }

    companion object {
        private const val PRICING_ID = "pricing_rules_v1"
        private val SUPPORTED_KINDS = setOf("llm", "web_search", "tts", GeminiCacheAccounting.KIND, "speech_voice_design", "speech_voice_clone")

        fun validVoiceDesignUsage(row: UsageEventEntity, value: SpeechVoiceDesignUsage): Boolean =
            value.version == 1 && value._kind == "speech_voice_design" && value.provider == "minimax" &&
                value.inputCharacters in 1L..500L && value.status in setOf("prepared", "running", "completed", "failed", "stopped", "interrupted") &&
                value.taskId.matches(Regex("[0-9a-f]{32}")) &&
                row.provider == "tts-voice-design:${value.provider}" && row.id == "voice-design:${value.taskId}" &&
                row.eventId == "voice-design:${value.taskId}" && row.requestId == value.taskId &&
                row.model == null && row.createdAt == value.startedAt &&
                runCatching { PricingEngine.instant(value.startedAt) }.isSuccess &&
                (value.endedAt?.let { at -> runCatching { PricingEngine.instant(at) }.isSuccess } != false)

        fun validVoiceCloneUsage(row: UsageEventEntity, value: SpeechVoiceCloneUsage): Boolean =
            value.version == 1 && value._kind == "speech_voice_clone" && value.provider in setOf("minimax", "glm", "siliconflow") &&
                value.inputCharacters in 0L..10_000L && value.status in setOf("prepared", "running", "completed", "failed", "stopped", "interrupted") &&
                value.taskId.matches(Regex("[0-9a-f]{32}")) &&
                row.provider == "tts-voice-clone:${value.provider}" && row.id == "voice-clone:${value.taskId}" &&
                row.eventId == "voice-clone:${value.taskId}" && row.requestId == value.taskId &&
                row.model == null && row.createdAt == value.startedAt &&
                runCatching { PricingEngine.instant(value.startedAt) }.isSuccess &&
                (value.endedAt?.let { at -> runCatching { PricingEngine.instant(at) }.isSuccess } != false)
    }
}
