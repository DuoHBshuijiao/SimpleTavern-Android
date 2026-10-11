package com.simpletavern.core.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import kotlinx.serialization.Serializable

@Serializable
data class GeminiCachePricingRule(val id: String, val currency: String,
    val creationPerOperation: String? = null, val creationPerMillionTokens: String? = null,
    val storagePerMillionTokenHours: String? = null, val providerId: String? = null, val model: String? = null,
    val chatId: String? = null, val sourceNamespace: String? = null, val enabled: Boolean = true,
    val effectiveFrom: String? = null, val effectiveTo: String? = null, val source: String = "user")
@Serializable
data class GeminiCachePricingRuleSet(val version: Int = 1, val revision: Long = 0,
    val rules: List<GeminiCachePricingRule> = emptyList(), val updatedAt: String? = null)
@Serializable
data class GeminiCachePricingSnapshot(val value: GeminiCachePricingRuleSet, val version: PricingVersion)
/** Frozen at operation start; not an account price assertion or a reason to reprice old events. */
@Serializable
data class GeminiCacheFrozenPrice(val version: Int = 1, val revision: Long? = null,
    val rule: GeminiCachePricingRule? = null, val reason: String? = null)
@Serializable
data class GeminiCacheCostQuote(val version: Int = 1, val pricedAt: String, val pricingRevision: Long?, val pricingRuleId: String?,
    val currency: String?, val tokenCount: Long?, val storageSeconds: Long,
    val creationFixedAmount: String? = null, val creationTokenAmount: String? = null, val creationAmount: String? = null,
    val projectedStorageAmount: String? = null, val projectedTotalAmount: String? = null, val reason: String? = null,
    val warnings: List<String> = listOf("requested_ttl_storage_projection", "not_provider_billed_cost"))

/** Decimal-only explicit quotes; no embedded prices, time rounding up, automatic free tiers or currency conversion. */
object GeminiCachePricing {
    private fun invalid(): Nothing = throw StError.Validation("invalid Gemini cache pricing snapshot")
    private fun identity(value: String) { if (value.isBlank() || value.toByteArray(Charsets.UTF_8).size > 1024 || value.any(Char::isISOControl)) invalid() }
    private fun time(value: String) = value.takeIf { it.length <= 64 }?.let {
        runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: invalid()
    fun decimal(value: String): BigDecimal {
        val number = value.takeIf { it.length <= 64 }?.toBigDecimalOrNull() ?: invalid()
        if (number.signum() < 0 || number.precision() > 64 || number.scale() !in -12..12 || number > BigDecimal("1000000000000")) invalid()
        return number
    }
    fun money(value: BigDecimal) = value.stripTrailingZeros().toPlainString()
    fun validateRules(value: GeminiCachePricingRuleSet) {
        if (value.version != 1 || value.revision < 0 || value.rules.size > 200) invalid()
        value.updatedAt?.let(::time)
        val ids = mutableSetOf<String>()
        for (rule in value.rules) {
            if (!rule.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{2,127}")) || !ids.add(rule.id) ||
                !rule.currency.matches(Regex("[A-Z]{3}")) || rule.source !in setOf("user", "catalog") ||
                (rule.source == "catalog") != rule.id.startsWith("catalog:")) invalid()
            listOfNotNull(rule.providerId, rule.model, rule.chatId, rule.sourceNamespace).forEach(::identity)
            val rates = listOfNotNull(rule.creationPerOperation, rule.creationPerMillionTokens, rule.storagePerMillionTokenHours)
            if (rates.isEmpty()) invalid()
            rates.forEach(::decimal)
            val from = rule.effectiveFrom?.let(::time); val to = rule.effectiveTo?.let(::time)
            if (from != null && to != null && from >= to) invalid()
        }
    }
    private fun matches(rule: GeminiCachePricingRule, owner: GeminiCacheOwner, at: String) = rule.enabled &&
        (rule.providerId == null || rule.providerId == owner.providerId) && (rule.model == null || rule.model == owner.model) &&
        (rule.chatId == null || rule.chatId == owner.chatId) && (rule.sourceNamespace == null || rule.sourceNamespace == owner.sourceNamespace) &&
        (rule.effectiveFrom?.let { time(at) >= time(it) } ?: true) && (rule.effectiveTo?.let { time(at) < time(it) } ?: true)
    fun select(value: GeminiCachePricingRuleSet, owner: GeminiCacheOwner, at: String): GeminiCacheFrozenPrice {
        validateRules(value); time(at)
        val matches = value.rules.filter { matches(it, owner, at) }
        val preferred = matches.filter { it.source == "user" }.ifEmpty { matches }
        fun specificity(rule: GeminiCachePricingRule) = listOfNotNull(rule.providerId, rule.model, rule.chatId, rule.sourceNamespace).size
        val highest = preferred.maxOfOrNull(::specificity)
        val selected = preferred.filter { specificity(it) == highest }.singleOrNull()
        return GeminiCacheFrozenPrice(revision = value.revision, rule = selected,
            reason = if (preferred.isEmpty()) "no_matching_cache_price" else if (selected == null) "ambiguous_cache_price" else null)
    }
    fun validateFrozen(value: GeminiCacheFrozenPrice, owner: GeminiCacheOwner, at: String) {
        time(at)
        if (value.version != 1 || value.revision?.let { it < 0 } == true || value.reason !in setOf(null,
                "pricing_configuration_invalid", "no_matching_cache_price", "ambiguous_cache_price")) invalid()
        if (value.rule == null) {
            if (value.reason == null || (value.revision == null) != (value.reason == "pricing_configuration_invalid")) invalid()
        } else {
            validateRules(GeminiCachePricingRuleSet(rules = listOf(value.rule)))
            if (value.revision == null || value.reason != null || !matches(value.rule, owner, at)) invalid()
        }
    }
    fun quote(value: GeminiCacheFrozenPrice, owner: GeminiCacheOwner, at: String, tokens: Long?, storageSeconds: Long): GeminiCacheCostQuote {
        validateFrozen(value, owner, at)
        if (tokens?.let { it < 0 } == true || storageSeconds !in 1..604800) invalid()
        val rule = value.rule
        fun proportional(rate: String?, seconds: Long? = null): BigDecimal? {
            val price = rate?.let(::decimal) ?: return null
            if (price.signum() == 0) return BigDecimal.ZERO // Explicit zero, never a missing quantity/rate guessed as zero.
            val count = tokens?.let(BigDecimal::valueOf) ?: return null
            val numerator = count.multiply(price).multiply(BigDecimal.valueOf(seconds ?: 1))
            return numerator.divide(BigDecimal.valueOf(if (seconds == null) 1_000_000 else 3_600_000_000), 18, RoundingMode.HALF_EVEN)
        }
        val fixed = rule?.creationPerOperation?.let(::decimal)
        val write = proportional(rule?.creationPerMillionTokens)
        val creation = if (fixed != null && write != null) fixed + write else null
        val storage = proportional(rule?.storagePerMillionTokenHours, storageSeconds)
        val total = if (creation != null && storage != null) creation + storage else null
        return GeminiCacheCostQuote(pricedAt = at, pricingRevision = value.revision, pricingRuleId = rule?.id, currency = rule?.currency,
            tokenCount = tokens, storageSeconds = storageSeconds, creationFixedAmount = fixed?.let(::money), creationTokenAmount = write?.let(::money),
            creationAmount = creation?.let(::money), projectedStorageAmount = storage?.let(::money), projectedTotalAmount = total?.let(::money),
            reason = value.reason ?: if (total == null) "cache_quote_incomplete" else null)
    }
}
