package com.simpletavern.core.llm

import com.simpletavern.core.model.*
import java.time.Instant
import kotlinx.serialization.json.*

/** Identity of an already resolved, managed attachment; no file reads or content digests. */
internal data class ProviderFileIdentity(val id: String?, val path: String, val mime: String,
    val bytes: Int, val modifiedAt: Long) {
    override fun toString() = "ProviderFileIdentity(<private>)"
}

internal class GeminiCacheFile(val identity: ProviderFileIdentity, val expiresAt: Instant)

/** Request-local ownership capability. Neither remote URIs nor paths enter durable metadata. */
class GeminiCacheFiles internal constructor(internal val entries: Map<String, GeminiCacheFile>,
    private val retainFiles: suspend (Set<String>) -> Unit) {
    val sourceFilesExpireTime: String? get() = entries.values.minOfOrNull { it.expiresAt }?.toString()

    fun select(contents: List<ContextMessage>): GeminiCacheFiles {
        val used = contents.flatMap { it.media }.mapNotNull { it.providerFile }.toSet()
        return GeminiCacheFiles(entries.filterKeys { it in used }, retainFiles)
    }

    fun requireLifetime(ttlSeconds: Long) {
        val requested = Instant.now().plusSeconds(ttlSeconds + 60)
        if (entries.values.any { requested.isAfter(it.expiresAt) })
            throw StError.Validation("cache TTL exceeds the uploaded source file lifetime")
    }

    suspend fun retain() { if (entries.isNotEmpty()) retainFiles(entries.keys) }
    internal fun detached() = GeminiCacheFiles(entries) {}
    internal fun available() = entries.values.all { Instant.now().isBefore(it.expiresAt) }

    /** Only substitute an equivalent managed file for prefix comparison, never rewrite prompt text. */
    internal fun matchContents(contents: JsonArray, current: GeminiCacheFiles?): JsonArray {
        if (entries.isEmpty() || current == null) return contents
        val originals = entries.entries.associate { it.value.identity to it.key }
        val aliases = current.entries.mapNotNull { (uri, file) -> originals[file.identity]?.let { uri to it } }.toMap()
        return JsonArray(contents.map content@{ content ->
            val value = content as? JsonObject ?: return@content content
            val parts = value["parts"] as? JsonArray ?: return@content content
            JsonObject(value + ("parts" to JsonArray(parts.map part@{ part ->
                val block = part as? JsonObject ?: return@part part
                val file = block["fileData"] as? JsonObject ?: return@part part
                val uri = (file["fileUri"] as? JsonPrimitive)?.content ?: return@part part
                aliases[uri]?.let { JsonObject(block + ("fileData" to JsonObject(file + ("fileUri" to JsonPrimitive(it))))) } ?: part
            })))
        })
    }

    override fun toString() = "GeminiCacheFiles(<private>)"
}
