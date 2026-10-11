package com.simpletavern.core.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.Serializable

/** Conditional ranges, NOT bounds on actual charges or certified resource lifetime. */
@Serializable
data class GeminiCacheStorageBoundary(val earliest: String, val latest: String, val source: String)
@Serializable
data class GeminiCacheStorageSegment(val phase: String, val sourceTaskId: String, val closingTaskId: String?,
    val start: GeminiCacheStorageBoundary, val end: GeminiCacheStorageBoundary, val expireTime: String,
    val tokenCount: Long?, val pricing: GeminiCacheFrozenPrice?, val minimumSeconds: String, val maximumSeconds: String,
    val estimatedMinimumAmount: String?, val estimatedMaximumAmount: String?, val reason: String?)
@Serializable
data class GeminiCacheStorageGap(val from: String?, val through: String, val reason: String)
@Serializable
data class GeminiCacheStorageTotal(val currency: String, val phase: String, val estimatedMinimumAmount: String,
    val estimatedMaximumAmount: String, val contributingSegments: Int)
@Serializable
data class GeminiCacheStorageView(val owner: GeminiCacheOwner, val name: String, val asOf: String, val status: String,
    val creationTaskId: String?, val frontierTaskIds: List<String>, val segments: List<GeminiCacheStorageSegment>,
    val totals: List<GeminiCacheStorageTotal>, val unpricedSegmentCount: Int, val gaps: List<GeminiCacheStorageGap>,
    val warnings: List<String>)

/** No HTTP, current-price lookup, account inference, history rewriting or elapsed/future double counting. */
object GeminiCacheStorage {
    private fun invalid(): Nothing = throw StError.Validation("invalid Gemini cache storage evidence")
    private fun time(raw: String): Instant = raw.takeIf { it.length <= 64 }?.let {
        runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: invalid()
    private fun seconds(start: Instant, end: Instant): BigDecimal {
        if (end <= start) return BigDecimal.ZERO
        val duration = Duration.between(start, end)
        return BigDecimal.valueOf(duration.seconds).add(BigDecimal.valueOf(duration.nano.toLong(), 9))
    }
    private fun window(operation: GeminiCacheTaskProgress) = GeminiCacheStorageBoundary(
        operation.attemptedAt ?: invalid(), operation.endedAt ?: invalid(), "local_http_window")
    private fun point(at: Instant, source: String) = GeminiCacheStorageBoundary(at.toString(), at.toString(), source)
    private fun sameTime(left: String?, right: String?) = if (left == null || right == null) left == right else time(left) == time(right)

    fun project(owner: GeminiCacheOwner, name: String, history: List<GeminiCacheTaskProgress>, at: String): GeminiCacheStorageView {
        history.filter { it.owner == owner && it.resourceName == name }.forEach(GeminiCacheTaskMetadata::validate)
        return projectFromValidatedHistory(owner, name, history, at)
    }

    /** Repository history has already passed metadata checks; retain only the calculation's causal/time checks here. */
    fun projectFromValidatedHistory(owner: GeminiCacheOwner, name: String, history: List<GeminiCacheTaskProgress>, at: String): GeminiCacheStorageView {
        val asOf = time(at)
        val state = GeminiCacheLifecycle.project(owner, name, history, at)
        val rows = history.filter { it.owner == owner && it.resourceName == name }
        if (asOf < time(state.latestOperationAt)) throw StError.Validation("storage asOf precedes current operation evidence; refresh")
        val warnings = state.warnings + listOf("not_provider_billed_cost", "continuous_storage_between_observations_assumed",
            "local_http_windows_not_server_billing_timestamps", "server_and_local_clock_alignment_assumed",
            "frozen_linear_price_not_refreshed", "public_owner_not_account_identity")
        fun unknown(reason: String) = GeminiCacheStorageView(owner, name, at, "unknown", null, state.frontierTaskIds,
            emptyList(), emptyList(), 0, listOf(GeminiCacheStorageGap(null, at, reason)), warnings)
        if (state.frontierTaskIds.size != 1) return unknown("cache_storage_branching_history")
        if (rows.any { it.lifecycleVersion != 1 }) return unknown("cache_storage_legacy_links")
        // Only a complete single causal chain is priceable. Never order by timestamps or choose one branch.
        val nodes = rows.associateBy { it.taskId }
        val reverse = mutableListOf<GeminiCacheTaskProgress>()
        val visited = mutableSetOf<String>()
        var id: String? = state.frontierTaskIds.single()
        while (id != null) {
            if (!visited.add(id)) invalid()
            val operation = nodes[id] ?: return unknown("cache_storage_ancestor_missing")
            reverse.add(operation)
            if (operation.lifecyclePreviousTaskIds.size > 1) return unknown("cache_storage_branching_history")
            id = operation.lifecyclePreviousTaskIds.singleOrNull()
        }
        if (visited.size != rows.size) return unknown("cache_storage_branching_history")
        val chain = reverse.asReversed()
        val creation = chain.first()
        if (creation.action != GeminiCacheAction.CREATE || !creation.remoteCompleted)
            return unknown("cache_storage_confirmed_creation_missing")
        val segments = mutableListOf<GeminiCacheStorageSegment>()
        val gaps = mutableListOf<GeminiCacheStorageGap>()
        var active: GeminiCacheTaskProgress? = creation
        var observed = creation.resource ?: invalid()
        fun append(source: GeminiCacheTaskProgress, end: GeminiCacheStorageBoundary, closingId: String?) {
            val metadata = source.resource ?: invalid()
            val expire = time(metadata.expireTime)
            val start = window(source)
            val startEarly = time(start.earliest); val startLate = time(start.latest)
            val endEarly = minOf(time(end.earliest), expire); val endLate = minOf(time(end.latest), expire)
            val frozen = if (source.action == GeminiCacheAction.CREATE) source.pricing else source.storagePricing
            val rate = frozen?.rule?.storagePerMillionTokenHours?.let(GeminiCachePricing::decimal)
            val count = metadata.totalTokenCount?.let(BigDecimal::valueOf)
            fun amount(duration: BigDecimal, rounding: RoundingMode): String? {
                if (rate == null) return null
                if (rate.signum() == 0) return "0" // Explicit zero, never infer missing price/quantity as free.
                return count?.multiply(rate)?.multiply(duration)?.divide(BigDecimal("3600000000"), 18, rounding)
                    ?.let(GeminiCachePricing::money)
            }
            fun part(phase: String, low: Instant, high: Instant, partStart: GeminiCacheStorageBoundary) {
                if (high <= time(partStart.earliest)) return
                if (segments.size >= 1000) throw StError.Validation("cache storage segment budget exceeded")
                val minimum = seconds(time(partStart.latest), low)
                val maximum = seconds(time(partStart.earliest), high)
                val minimumAmount = amount(minimum, RoundingMode.FLOOR)
                val maximumAmount = amount(maximum, RoundingMode.CEILING)
                val reason = frozen?.reason ?: when {
                    frozen == null -> "cache_storage_frozen_price_missing"
                    rate == null -> "cache_storage_rate_missing"
                    minimumAmount == null -> "cache_storage_token_count_missing"
                    else -> null
                }
                segments.add(GeminiCacheStorageSegment(phase, source.taskId, closingId, partStart,
                    GeminiCacheStorageBoundary(low.toString(), high.toString(), end.source), metadata.expireTime,
                    metadata.totalTokenCount, frozen, GeminiCachePricing.money(minimum), GeminiCachePricing.money(maximum),
                    minimumAmount, maximumAmount, reason))
            }
            part("elapsed", minOf(endEarly, asOf), minOf(endLate, asOf), start)
            // Future is only the part after asOf; it cannot include elapsed storage again.
            if (endLate > asOf) part("future", maxOf(endEarly, asOf), endLate,
                GeminiCacheStorageBoundary(maxOf(startEarly, asOf).toString(), maxOf(startLate, asOf).toString(), "as_of_split"))
        }
        fun gap(from: String?, reason: String) {
            gaps.add(GeminiCacheStorageGap(from, at, reason)); active = null
        }
        for (operation in chain.drop(1)) {
            val source = active ?: break // Later GET cannot reconstruct an uncertain storage/rate interval.
            if (!operation.httpAttempted) continue // Unsent intent is not a mutation.
            if (!operation.remoteCompleted) {
                if (operation.action != GeminiCacheAction.GET) {
                    append(source, point(time(operation.attemptedAt ?: invalid()), "uncertain_mutation_start"), operation.taskId)
                    gap(operation.attemptedAt, "cache_storage_mutation_outcome_unknown")
                }
                continue
            }
            when (operation.action) {
                GeminiCacheAction.DELETE -> {
                    append(source, window(operation), operation.taskId); active = null
                }
                GeminiCacheAction.GET -> {
                    val current = operation.resource ?: invalid()
                    if (!sameTime(current.expireTime, observed.expireTime) || current.totalTokenCount != observed.totalTokenCount ||
                        !sameTime(current.createTime, observed.createTime) || !sameTime(current.updateTime, observed.updateTime))
                        gap(source.attemptedAt, "cache_storage_observation_changed")
                    else observed = current
                }
                GeminiCacheAction.UPDATE_TTL, GeminiCacheAction.UPDATE_EXPIRY -> {
                    val current = operation.resource ?: invalid()
                    if (current.totalTokenCount != observed.totalTokenCount || !sameTime(current.createTime, observed.createTime) ||
                        current.updateTime?.let { newer -> observed.updateTime?.let { time(newer) < time(it) } } == true ||
                        time(operation.attemptedAt ?: invalid()) >= time(observed.expireTime)) {
                        gap(source.attemptedAt, "cache_storage_continuity_unproven")
                    } else {
                        append(source, window(operation), operation.taskId)
                        active = operation; observed = current
                    }
                }
                else -> invalid()
            }
        }
        active?.let { append(it, point(time(observed.expireTime), "reported_expiration"), null) }
        val totals = segments.filter { it.estimatedMinimumAmount != null && it.estimatedMaximumAmount != null }
            .groupBy { (it.pricing?.rule?.currency ?: invalid()) to it.phase }
            .entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).map { (key, parts) ->
                GeminiCacheStorageTotal(key.first, key.second,
                    GeminiCachePricing.money(parts.fold(BigDecimal.ZERO) { sum, part -> sum + part.estimatedMinimumAmount!!.toBigDecimal() }),
                    GeminiCachePricing.money(parts.fold(BigDecimal.ZERO) { sum, part -> sum + part.estimatedMaximumAmount!!.toBigDecimal() }), parts.size)
            }
        val unpriced = segments.count { it.estimatedMinimumAmount == null || it.estimatedMaximumAmount == null }
        return GeminiCacheStorageView(owner, name, at, if (gaps.isEmpty() && unpriced == 0) "estimated" else "partial",
            creation.taskId, state.frontierTaskIds, segments, totals, unpriced, gaps, warnings)
    }
}
