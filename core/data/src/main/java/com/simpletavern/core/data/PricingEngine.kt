package com.simpletavern.core.data

import com.google.re2j.Pattern
import com.simpletavern.core.model.*
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

/** Decimal accounting; no embedded live prices, currency conversion, or unknown-as-zero. */
internal object PricingEngine {
    data class Prepared(val value: PricingRuleSet, val patterns: Map<String, List<Pattern>>)
    private fun norm(value: String) = value.trim().lowercase(Locale.ROOT)
    fun decimal(value: String, scaleLimit: Int = 18): BigDecimal {
        if (value.length > 64) throw StError.Validation("accounting decimal exceeds budget")
        val n = value.toBigDecimalOrNull() ?: throw StError.Validation("accounting decimal is not finite")
        if (n.signum() < 0 || n.precision() > 64 || n.scale() !in -12..scaleLimit)
            throw StError.Validation("accounting decimal must be nonnegative and bounded")
        return n
    }
    fun money(value: BigDecimal) = value.stripTrailingZeros().toPlainString()
    fun currency(value: String) = value.matches(Regex("[A-Z]{3}"))
    fun instant(value: String): Instant = try { OffsetDateTime.parse(value).toInstant() }
        catch (_: Exception) { throw StError.Validation("accounting timestamp must have an offset") }
    private fun bound(value: String?, end: Boolean): Instant? = value?.let {
        if (it.length > 64) throw StError.Validation("pricing effective time exceeds budget")
        if (it.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            val date = try { LocalDate.parse(it) } catch (_: Exception) { throw StError.Validation("invalid pricing date") }
            (if (end) date.plusDays(1) else date).atStartOfDay().toInstant(ZoneOffset.UTC)
        } else instant(it)
    }
    fun prepare(set: PricingRuleSet): Prepared {
        if (set.version != 1 || set.revision < 0 || set.rules.size > 200) throw StError.Validation("unsupported pricing version or rule budget")
        val ids = mutableSetOf<String>()
        val patterns = mutableMapOf<String, List<Pattern>>()
        for (r in set.rules) {
            if (!r.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{2,127}")) || !ids.add(r.id))
                throw StError.Validation("invalid or duplicate pricing rule identity")
            if (r.source !in setOf("user", "catalog") || (r.source == "user" && r.id.startsWith("catalog:")))
                throw StError.Validation("invalid pricing rule source")
            if (!currency(r.currency) || (r.provider?.length ?: 0) > 512 || (r.canonicalModelId?.length ?: 0) > 512 ||
                r.serviceTier.isBlank() || r.serviceTier.length > 64 || (r.sourceUrl?.length ?: 0) > 2048)
                throw StError.Validation("pricing identity, currency or tier exceeds budget")
            if (r.aliases.size > 32 || r.aliases.any { it.trim().length !in 3..512 } || r.regexAliases.size > 8)
                throw StError.Validation("pricing aliases exceed budget or are too short")
            val rates = listOf(r.inputPerMillion, r.outputPerMillion, r.cacheReadPerMillion,
                r.cacheWritePerMillion, r.cacheWriteLongPerMillion)
            if (rates.all { it == null }) throw StError.Validation("pricing rule has no rates")
            rates.filterNotNull().forEach { if (decimal(it, 12) > BigDecimal("1000000000000")) throw StError.Validation("pricing rate too large") }
            val from = bound(r.effectiveFrom, false)
            val to = bound(r.effectiveTo, true)
            if (from != null && to != null && from >= to) throw StError.Validation("invalid pricing effective interval")
            if (r.canonicalModelId.isNullOrBlank() && r.aliases.isEmpty() && r.regexAliases.isEmpty())
                throw StError.Validation("pricing rule has no model selector")
            patterns[r.id] = r.regexAliases.map { pattern ->
                if (pattern.length !in 3..512 || pattern.replace(Regex("[\\\\^$.*+?()\\[\\]{}|]"), "").trim().length < 3)
                    throw StError.Validation("pricing regex alias too broad")
                try { Pattern.compile(pattern, Pattern.CASE_INSENSITIVE) }
                catch (_: Exception) { throw StError.Validation("pricing regex not supported by RE2") }
            }
        }
        return Prepared(set, patterns)
    }

    fun estimate(prepared: Prepared, tokens: UsageTokens, provider: String?, requestedModel: String?, at: String): CostSnapshot {
        val reported = tokens.providerCost
        val trusted = reported?.let { runCatching { decimal(it.amount) }.getOrNull() }
            ?.takeIf { reported.currency?.let(::currency) == true }
        fun finish(estimate: CostSnapshot): CostSnapshot = if (trusted != null) estimate.copy(source = "provider",
            amount = money(trusted), currency = reported!!.currency, providerReported = reported)
            else estimate.copy(providerReported = reported)
        fun unknown(reason: String) = finish(CostSnapshot(reason = reason, pricedAt = at, pricingRevision = prepared.value.revision))
        val now = runCatching { instant(at) }.getOrNull() ?: return unknown("invalid_pricing_time")
        if (tokens.resolvedModel == "<redacted>") return unknown("redacted_model_identity")
        val models = listOfNotNull(tokens.resolvedModel, requestedModel).map(::norm).filter { it.isNotEmpty() }.distinct()
        if (tokens.serviceTier == null && (tokens.fastRequested == true || tokens.requestedServiceTier != null))
            return unknown("missing_service_tier")
        val tier = norm(tokens.serviceTier ?: "standard")
            .let { if (it == "default") "standard" else it }
        val eligible = prepared.value.rules.filter { r -> r.enabled &&
            (r.provider.isNullOrBlank() || r.provider?.let(::norm) == provider?.let(::norm)) && norm(r.serviceTier) == tier &&
            (bound(r.effectiveFrom, false)?.let { now >= it } != false) &&
            (bound(r.effectiveTo, true)?.let { now < it } != false) }
        fun select(predicate: (PricingRule) -> Boolean) = eligible.sortedBy { if (it.source == "user") 0 else 1 }.firstOrNull(predicate)
        // Resolved identity outranks requested identity; exact outranks aliases/RE2, then user before catalog.
        val rule = models.firstNotNullOfOrNull { model -> select { it.canonicalModelId?.let(::norm) == model } }
            ?: select { r -> r.aliases.any { norm(it) in models } }
            ?: select { r -> prepared.patterns[r.id].orEmpty().any { p -> models.any { p.matcher(it).find() } } }
            ?: return unknown("no_matching_price")
        if (tokens.pricingWarnings.any { it != "mixed_modalities" } ||
            ("mixed_modalities" in tokens.pricingWarnings && !rule.coversMixedModalities))
            return unknown("unsupported_usage_dimensions")
        val numbers = listOf(tokens.inputTokens, tokens.outputTokens, tokens.totalTokens, tokens.reasoningTokens, tokens.cacheReadInputTokens,
            tokens.cacheWriteInputTokens, tokens.cacheWriteShortTokens, tokens.cacheWriteLongTokens)
        if (numbers.filterNotNull().any { it < 0 }) return unknown("invalid_token_counts")
        if (tokens.inputTokensIncludeCache == null || tokens.outputTokensIncludeReasoning == null)
            return unknown("unknown_token_accounting")
        val read = tokens.cacheReadInputTokens
        val write = tokens.cacheWriteInputTokens
        val short = tokens.cacheWriteShortTokens
        val long = tokens.cacheWriteLongTokens
        val reportedInput = tokens.inputTokens
        val reportedOutput = tokens.outputTokens
        val reportedTotal = tokens.totalTokens
        fun sum(vararg counts: Long): BigInteger = counts.fold(BigInteger.ZERO) { total, count -> total + BigInteger.valueOf(count) }
        if (tokens.inputTokensIncludeCache == true && reportedInput != null &&
            sum(read ?: 0, write ?: 0) > BigInteger.valueOf(reportedInput))
            return unknown("inconsistent_input_cache_counts")
        val reportedReasoning = tokens.reasoningTokens
        if (tokens.outputTokensIncludeReasoning == true && reportedOutput != null && reportedReasoning != null && reportedReasoning > reportedOutput)
            return unknown("inconsistent_reasoning_counts")
        // Derive omitted thoughts only for inclusive-input/exclusive-output accounting (Gemini).
        val reasoning = reportedReasoning ?: if (tokens.inputTokensIncludeCache == true &&
            tokens.outputTokensIncludeReasoning == false && reportedTotal != null && reportedInput != null && reportedOutput != null) {
            val derived = BigInteger.valueOf(reportedTotal) - sum(reportedInput, reportedOutput)
            if (derived.signum() < 0) return unknown("inconsistent_total_tokens")
            derived.longValueExact()
        } else null
        val fullInput = if (reportedInput == null) null else if (tokens.inputTokensIncludeCache == true)
            BigInteger.valueOf(reportedInput) else if (read != null && write != null) sum(reportedInput, read, write) else null
        val fullOutput = if (reportedOutput == null) null else if (tokens.outputTokensIncludeReasoning == true)
            BigInteger.valueOf(reportedOutput) else if (reasoning != null) sum(reportedOutput, reasoning) else null
        if (reportedTotal != null && fullInput != null && fullOutput != null &&
            BigInteger.valueOf(reportedTotal) != fullInput + fullOutput)
            return unknown("inconsistent_total_tokens")
        if ((short != null || long != null) && short != null && long != null && write != null && sum(short, long) != BigInteger.valueOf(write))
            return unknown("inconsistent_cache_write_breakdown")
        val components = mutableListOf<CostComponent>()
        val reasons = mutableListOf<String>()
        fun component(name: String, count: Long?, rate: String?) {
            val amount = if (count != null && (count == 0L || rate != null))
                BigDecimal.valueOf(count).multiply(rate?.let(::decimal) ?: BigDecimal.ZERO).movePointLeft(6) else null
            if (amount == null) reasons.add("missing_$name")
            components.add(CostComponent(name, count, rate, amount?.let(::money)))
        }
        val input = if (tokens.inputTokensIncludeCache == true) {
            if (reportedInput == null || read == null || write == null) null
            else runCatching { Math.subtractExact(Math.subtractExact(reportedInput, read), write) }.getOrNull()?.takeIf { it >= 0 }
        } else reportedInput
        val output = if (tokens.outputTokensIncludeReasoning == true) reportedOutput
            else if (reportedOutput != null && reasoning != null)
                runCatching { Math.addExact(reportedOutput, reasoning) }.getOrNull() else null
        component("input", input, rule.inputPerMillion)
        component("output", output, rule.outputPerMillion)
        component("cache_read", read, rule.cacheReadPerMillion)
        if (short != null || long != null) {
            if (short == null || long == null || write == null || runCatching { Math.addExact(short, long) }.getOrNull() != write)
                reasons.add("inconsistent_cache_write_breakdown")
            component("cache_write_short", short, rule.cacheWritePerMillion)
            component("cache_write_long", long, rule.cacheWriteLongPerMillion)
        } else component("cache_write", write, rule.cacheWritePerMillion)
        val subtotal = components.mapNotNull { it.amount?.toBigDecimal() }.fold(BigDecimal.ZERO, BigDecimal::add)
        val complete = reasons.isEmpty()
        return finish(CostSnapshot(source = if (complete) "estimated" else "unknown",
            amount = if (complete) money(subtotal) else null, currency = if (complete) rule.currency else null,
            estimatedAmount = if (complete) money(subtotal) else null, estimatedCurrency = rule.currency,
            partialEstimatedAmount = if (!complete) money(subtotal) else null, pricingRuleId = rule.id,
            pricingRevision = prepared.value.revision, pricedAt = at, reason = reasons.takeIf { it.isNotEmpty() }?.joinToString(","),
            components = components))
    }
}
