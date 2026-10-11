package com.simpletavern.core.model

import java.time.OffsetDateTime
import kotlinx.serialization.Serializable

/** Local evidence, never a remote listing or an account ownership certificate. */
@Serializable
data class GeminiCacheResourceState(val owner: GeminiCacheOwner, val name: String, val status: String,
    val metadata: GeminiCacheMetadata?, val confirmedTaskId: String?, val frontierTaskIds: List<String>,
    val latestOperationAt: String, val warnings: List<String> = listOf("local_operation_evidence_only"))
@Serializable
data class GeminiCacheResourceItem(val key: String, val resource: GeminiCacheResourceState)
@Serializable
data class GeminiCacheResourcePage(val items: List<GeminiCacheResourceItem>, val nextKey: String?, val hasMore: Boolean)

/** Causal projection of durable operations; no hash, wall-clock ordering guess, remote work or backfill. */
object GeminiCacheLifecycle {
    private fun invalid(): Nothing = throw StError.Validation("invalid Gemini cache lifecycle evidence")
    fun project(owner: GeminiCacheOwner, name: String, history: List<GeminiCacheTaskProgress>, at: String): GeminiCacheResourceState {
        val instant = at.takeIf { it.length <= 64 }?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: invalid()
        val rows = history.filter { it.owner == owner && it.resourceName == name }
        if (rows.isEmpty() || rows.size > 10000) invalid()
        val allNodes = history.associateBy { it.taskId }
        val nodes = rows.associateBy { it.taskId }
        if (nodes.size != rows.size) invalid()
        val parents = rows.associate { operation ->
            val ids = if (operation.lifecycleVersion == 1) operation.lifecyclePreviousTaskIds else listOfNotNull(operation.referenceTaskId)
            if (ids.any { it == operation.taskId }) invalid()
            // Legacy missing ancestors are not proof of current state. Known mismatches are rejected.
            ids.forEach { id -> allNodes[id]?.let {
                if (it.owner != owner || it.resourceName != name) invalid()
                if (operation.lifecycleVersion == 1 && (it.endedAt == null ||
                        OffsetDateTime.parse(operation.createdAt).toInstant() < OffsetDateTime.parse(it.updatedAt).toInstant())) invalid()
            } }
            operation.taskId to ids
        }
        val children = mutableMapOf<String, MutableList<String>>()
        val remaining = mutableMapOf<String, Int>()
        rows.forEach { operation ->
            val known = parents.getValue(operation.taskId).filter(nodes::containsKey)
            remaining[operation.taskId] = known.size
            known.forEach { children.getOrPut(it) { mutableListOf() }.add(operation.taskId) }
        }
        data class Evidence(val status: String, val metadata: GeminiCacheMetadata? = null, val confirmation: String? = null)
        val result = mutableMapOf<String, Evidence>()
        val ready = java.util.ArrayDeque<String>()
        remaining.filterValues { it == 0 }.keys.forEach(ready::addLast)
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst(); val operation = nodes.getValue(id)
            val priorIds = parents.getValue(id)
            val knownPrior = priorIds.mapNotNull(result::get)
            val prior = if (priorIds.isNotEmpty() && knownPrior.size == priorIds.size && knownPrior.distinct().size == 1)
                knownPrior.singleOrNull() ?: knownPrior.first() else Evidence("unknown")
            result[id] = when {
                operation.endedAt == null -> Evidence("pending", prior.metadata, prior.confirmation)
                operation.remoteCompleted && operation.action == GeminiCacheAction.DELETE -> Evidence("deleted", prior.metadata)
                operation.remoteCompleted -> Evidence("available", operation.resource ?: invalid(), id)
                operation.httpAttempted && operation.action != GeminiCacheAction.GET -> Evidence("unknown", prior.metadata)
                else -> prior // Unsent/failed GET cannot prove a mutation of the resource.
            }
            children[id].orEmpty().forEach { child ->
                remaining[child] = remaining.getValue(child) - 1
                if (remaining[child] == 0) ready.addLast(child)
            }
        }
        if (result.size != rows.size) invalid() // Cycles never become a usable resource.
        val frontier = nodes.keys.filter { children[it].isNullOrEmpty() }.sorted()
        if (frontier.isEmpty() || frontier.size > 64) throw StError.Validation("cache lifecycle frontier exceeds budget")
        val evidence = if (frontier.size == 1) result.getValue(frontier.single()) else Evidence("ambiguous")
        val status = if (evidence.status == "available" && evidence.metadata?.let {
                !instant.isBefore(OffsetDateTime.parse(it.expireTime).toInstant()) } == true) "expired" else evidence.status
        val warnings = listOf("local_operation_evidence_only") +
            (if (parents.values.any { ids -> ids.any { it !in nodes } }) listOf("cache_lifecycle_ancestor_missing") else emptyList()) +
            (if (rows.any { it.lifecycleVersion == null }) listOf("legacy_lifecycle_links") else emptyList()) +
            (if (frontier.size > 1) listOf("cache_lifecycle_ambiguous") else emptyList())
        return GeminiCacheResourceState(owner, name, status, evidence.metadata, evidence.confirmation, frontier,
            rows.maxBy { OffsetDateTime.parse(it.updatedAt).toInstant() }.updatedAt, warnings)
    }
}
