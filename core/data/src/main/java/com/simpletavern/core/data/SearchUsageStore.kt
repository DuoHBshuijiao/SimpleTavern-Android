package com.simpletavern.core.data

import androidx.room.withTransaction
import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.data.db.AppSettingsEntity
import com.simpletavern.core.data.db.UsageEventEntity
import com.simpletavern.core.model.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Search usage shares the generic usage table, but never fabricates token counts or paid invoices. */
class SearchUsageStore(private val db: AppDatabase, private val gate: DataOperationGate) {
    private val processEpoch = UUID.randomUUID().toString()
    private suspend fun epoch() = "$processEpoch:${db.fileChanges().restoreEpoch().orEmpty()}"
    private suspend fun rules(): SearchPricingRuleSet {
        if ((db.config().settingsBytes(PRICING_ID) ?: 0) > 262_144) throw StError.Validation("search pricing exceeds 256 KiB")
        val raw = db.config().getSettings(PRICING_ID)?.settingsJson ?: return SearchPricingRuleSet()
        NativeJsonInput.parse(raw)
        return (Jsonx.decodeOrNull<SearchPricingRuleSet>(raw) ?: throw StError.Validation("invalid search pricing")).also(::validateRules)
    }
    suspend fun pricing() = withContext(Dispatchers.IO) { db.withTransaction {
        val rules = rules(); SearchPricingSnapshot(rules, PricingVersion(epoch(), rules.revision))
    } }
    suspend fun replacePricing(incoming: List<SearchPricingRule>, expected: PricingVersion, catalog: Boolean = false) = withContext(Dispatchers.IO) { db.withTransaction {
        val previous = rules()
        val currentEpoch = epoch()
        if (expected != PricingVersion(currentEpoch, previous.revision)) throw StError.Conflict("search pricing changed; refresh before saving")
        val source = if (catalog) "catalog" else "user"
        if (incoming.any { it.source != source }) throw StError.Validation("search pricing ownership mismatch")
        if (previous.revision == Long.MAX_VALUE) throw StError.Validation("search pricing revision exhausted")
        val value = SearchPricingRuleSet(revision = previous.revision + 1,
            rules = previous.rules.filter { it.source != source } + incoming, updatedAt = Instant.now().toString())
        validateRules(value)
        val raw = Jsonx.encode(value)
        if (raw.toByteArray(Charsets.UTF_8).size > 262_144) throw StError.Validation("search pricing exceeds 256 KiB")
        db.config().upsertSettings(AppSettingsEntity(PRICING_ID, raw, value.updatedAt!!))
        SearchPricingSnapshot(value, PricingVersion(currentEpoch, value.revision))
    } }

    suspend fun execute(provider: String, scope: String, parameters: Map<String, String>, context: SearchCallContext,
        run: suspend (beforeExecute: suspend () -> Unit) -> WebSearchResult): WebSearchResult = withContext(Dispatchers.IO) {
        val lease = gate.sharedLease() // not a Room/network mutex; prevents a native snapshot splitting this event.
        try {
            currentCoroutineContext().ensureActive()
            val requestId = UUID.randomUUID().toString()
            val eventId = "web_search:$requestId"
            var snapshot = withContext(NonCancellable) { db.withTransaction {
                val at = Instant.now().toString()
                val prices = try { rules() } catch (_: StError.Validation) { null }
                val matching = prices?.rules.orEmpty().filter { it.enabled && it.provider == provider &&
                    it.matchParameters.all { (key, value) -> parameters[key] == value } &&
                    (it.effectiveFrom?.let { from -> PricingEngine.instant(at) >= PricingEngine.instant(from) } != false) &&
                    (it.effectiveTo?.let { to -> PricingEngine.instant(at) < PricingEngine.instant(to) } != false) }
                val preferred = matching.filter { it.source == "user" }.ifEmpty { matching }
                val specificity = preferred.maxOfOrNull { it.matchParameters.size }
                val selected = preferred.filter { it.matchParameters.size == specificity }.singleOrNull()
                val reason = when { prices == null -> "pricing_configuration_invalid"; preferred.isEmpty() -> "no_matching_price";
                    selected == null -> "ambiguous_search_price"; else -> null }
                val value = SearchUsageSnapshot(provider = provider, scope = scope, context = context, startedAt = at,
                    parameters = parameters, pricingRevision = prices?.revision, pricingRule = selected, pricingReason = reason)
                validateSnapshot(value)
                val row = UsageEventEntity(eventId, eventId, requestId, context.chatId, "web-search:$provider", null, Jsonx.encode(value), null, at)
                if (db.usage().insertIgnore(row) == -1L) throw StError.Conflict("search usage identity collision")
                value
            } }
            suspend fun save(next: SearchUsageSnapshot, cost: CostSnapshot? = null) {
                validateSnapshot(next)
                val raw = Jsonx.encode(next)
                val costRaw = cost?.let { Jsonx.encode(it) }
                withContext(NonCancellable) {
                    if (db.usage().finishSearch(eventId, Jsonx.encode(snapshot), raw, costRaw) != 1)
                        throw StError.Conflict("search usage changed before finalization")
                    snapshot = next
                }
            }
            var response: WebSearchResult? = null
            var failure: Throwable? = null
            try {
                currentCoroutineContext().ensureActive()
                response = run {
                    save(snapshot.copy(status = "running", httpAttempted = true))
                    currentCoroutineContext().ensureActive()
                }
                currentCoroutineContext().ensureActive()
            } catch (e: Throwable) { failure = e }
            val status = when (failure) { null -> "completed"; is CancellationException -> "cancelled"; else -> "failed" }
            val error = when (val e = failure) { null -> null; is CancellationException -> "cancelled";
                is StError.Search -> e.code.takeIf { it in SAFE_ERRORS } ?: "web_search_failed"; else -> "web_search_failed" }
            // If a response arrived just before cancellation, retain its reported credits without claiming completion.
            val ended = maxOf(Instant.now(), PricingEngine.instant(snapshot.startedAt)).toString()
            val final = snapshot.copy(status = status, endedAt = ended, credits = response?.credits, errorCode = error)
            try { withContext(NonCancellable) { save(final, cost(final)) } }
            catch (_: Exception) {
                val accountingError = StError.Search("web_search_accounting_failed", "search usage finalization failed", mapOf("requestId" to requestId))
                if (failure != null) failure.addSuppressed(accountingError) else throw accountingError
            }
            failure?.let { throw it }
            response!!.copy(requestId = requestId)
        } finally { lease.close() }
    }

    /** Startup only marks unfinished records; it never creates or retries a search request. */
    suspend fun interruptPending() = withContext(Dispatchers.IO) { gate.write {
        var after = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val rows = db.withTransaction {
                if (db.usage().pendingSearchBudgets(after, 200).any { it.identityBytes > 4096 || it.usageBytes > 65_536 })
                    throw StError.Validation("pending search usage exceeds row budget")
                db.usage().pendingSearchPage(after, 200).also { rows -> rows.forEach { row ->
                    val envelope = NativeJsonInput.parse(row.event.usageJson) as? JsonObject ?: throw StError.Validation("invalid pending search envelope")
                    if (envelope["_kind"] != JsonPrimitive("web_search")) return@forEach // do not rewrite a legacy token event by provider name alone
                    val before = Jsonx.decode<SearchUsageSnapshot>(row.event.usageJson).also(::validateSnapshot)
                    if (before.status !in setOf("prepared", "running")) throw StError.Validation("invalid unfinished search usage")
                    val ended = maxOf(Instant.now(), PricingEngine.instant(before.startedAt)).toString()
                    val final = before.copy(status = "interrupted", endedAt = ended, errorCode = "process_interrupted")
                    if (db.usage().finishSearch(row.event.id, row.event.usageJson, Jsonx.encode(final), Jsonx.encode(cost(final))) != 1)
                        throw StError.Conflict("pending search usage changed")
                } }
            }
            if (rows.isEmpty()) break
            after = rows.last().sequence
        }
    } }

    companion object {
        private const val PRICING_ID = "search_pricing_v1"
        val PARAMETERS = setOf("search_depth", "topic", "max_results", "count", "freshness", "summary")
        private val SAFE_ERRORS = setOf("web_search_auth_failed", "web_search_quota", "web_search_redirect_refused",
            "web_search_http_error", "web_search_invalid_response", "web_search_response_too_large", "web_search_timeout",
            "web_search_network_error", "web_search_provider_error")
        fun validateRules(value: SearchPricingRuleSet) {
            if (value.version != 1 || value.revision < 0 || value.rules.size > 200) throw StError.Validation("invalid search pricing version/budget")
            val ids = mutableSetOf<String>()
            value.rules.forEach { rule ->
                if (!rule.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{2,127}")) || !ids.add(rule.id) ||
                    rule.provider !in setOf("tavily", "bocha", "brave") || rule.unit !in setOf("request", "credit") ||
                    !PricingEngine.currency(rule.currency) || rule.source !in setOf("user", "catalog") ||
                    (rule.source == "catalog") != rule.id.startsWith("catalog:")) throw StError.Validation("invalid search pricing identity/unit")
                if (PricingEngine.decimal(rule.amountPerUnit, 12) > BigDecimal("1000000000000")) throw StError.Validation("search pricing rate exceeds budget")
                if (rule.matchParameters.any { (key, text) -> key !in PARAMETERS || text.length !in 1..1024 || text.any(Char::isISOControl) })
                    throw StError.Validation("invalid search pricing conditions")
                val from = rule.effectiveFrom?.also { if (it.length > 64) throw StError.Validation("search pricing time exceeds budget") }?.let(PricingEngine::instant)
                val to = rule.effectiveTo?.also { if (it.length > 64) throw StError.Validation("search pricing time exceeds budget") }?.let(PricingEngine::instant)
                if (from != null && to != null && from >= to) throw StError.Validation("invalid search pricing interval")
            }
        }
        fun validateSnapshot(value: SearchUsageSnapshot) {
            if (value.version != 1 || value._kind != "web_search" || value.provider !in setOf("tavily", "bocha", "brave") ||
                value.status !in setOf("prepared", "running", "completed", "failed", "cancelled", "interrupted"))
                throw StError.Validation("invalid search usage version/status")
            val identities = listOfNotNull(value.context.chatId, value.context.taskId, value.context.callId)
            if (identities.any { it.isBlank() || it.toByteArray(Charsets.UTF_8).size > 1024 || it.any(Char::isISOControl) } ||
                value.scope.toByteArray(Charsets.UTF_8).size > 1050 || value.scope.any(Char::isISOControl) ||
                value.parameters.any { (key, text) -> key !in PARAMETERS || text.length > 1024 || text.any(Char::isISOControl) } ||
                (value.errorCode?.matches(Regex("[a-z_]{1,64}")) == false) || (value.pricingRevision ?: 0) < 0 ||
                (value.pricingReason?.matches(Regex("[a-z_]{1,64}")) == false) ||
                (value.scope != "global" && (!value.scope.startsWith("assistant:") || value.scope.removePrefix("assistant:").isBlank())))
                throw StError.Validation("search usage identity/parameters exceed budget")
            val pending = value.status in setOf("prepared", "running")
            if ((pending && (value.endedAt != null || value.credits != null || value.errorCode != null)) ||
                (!pending && value.endedAt == null) || (value.status == "prepared" && value.httpAttempted) ||
                (value.status in setOf("running", "completed") && !value.httpAttempted) ||
                (value.status == "completed" && value.errorCode != null) || (value.credits != null && !value.httpAttempted))
                throw StError.Validation("invalid search usage lifecycle")
            PricingEngine.instant(value.startedAt)
            value.endedAt?.let { if (PricingEngine.instant(it) < PricingEngine.instant(value.startedAt)) throw StError.Validation("invalid search usage lifecycle") }
            value.credits?.let { PricingEngine.decimal(it) }
            value.pricingRule?.let {
                validateRules(SearchPricingRuleSet(rules = listOf(it)))
                if (it.provider != value.provider || value.pricingRevision == null ||
                    !it.matchParameters.all { (key, text) -> value.parameters[key] == text }) throw StError.Validation("search pricing snapshot mismatch")
            }
            if (Jsonx.encode(value).toByteArray(Charsets.UTF_8).size > 65_536) throw StError.Validation("search usage exceeds 64 KiB")
        }
        fun cost(value: SearchUsageSnapshot): CostSnapshot {
            val base = CostSnapshot(pricedAt = value.startedAt, pricingRevision = value.pricingRevision, pricingRuleId = value.pricingRule?.id)
            if (!value.httpAttempted) return base.copy(reason = "search_request_not_sent")
            val rule = value.pricingRule ?: return base.copy(reason = value.pricingReason ?: "no_matching_price")
            val quantity = when (rule.unit) {
                "credit" -> value.credits?.let { runCatching { PricingEngine.decimal(it) }.getOrNull() }
                else -> BigDecimal.ONE.takeIf { value.status == "completed" }
            } ?: return base.copy(reason = if (value.status == "completed") "search_credits_not_reported" else "search_charge_unknown")
            val amount = PricingEngine.money(quantity.multiply(PricingEngine.decimal(rule.amountPerUnit)))
            if (runCatching { PricingEngine.decimal(amount) }.isFailure) return base.copy(reason = "search_cost_exceeds_budget")
            return base.copy(source = "estimated", amount = amount, currency = rule.currency,
                estimatedAmount = amount, estimatedCurrency = rule.currency)
        }
    }
}
