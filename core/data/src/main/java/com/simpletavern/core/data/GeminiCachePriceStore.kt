package com.simpletavern.core.data

import androidx.room.withTransaction
import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.data.db.AppSettingsEntity
import com.simpletavern.core.model.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dedicated cache quote rules. Edits do not rewrite existing events or install catalog prices automatically. */
class GeminiCachePriceStore(private val db: AppDatabase) {
    private val instanceEpoch = UUID.randomUUID().toString()
    private suspend fun epoch() = "$instanceEpoch:${db.fileChanges().restoreEpoch().orEmpty()}"
    private suspend fun rules(): GeminiCachePricingRuleSet {
        if ((db.config().settingsBytes(SETTINGS_ID) ?: 0) > 262_144) throw StError.Validation("cache pricing exceeds 256 KiB")
        val raw = db.config().getSettings(SETTINGS_ID)?.settingsJson ?: return GeminiCachePricingRuleSet()
        NativeJsonInput.parse(raw)
        return (Jsonx.decodeOrNull<GeminiCachePricingRuleSet>(raw) ?: throw StError.Validation("invalid cache pricing"))
            .also(GeminiCachePricing::validateRules)
    }
    suspend fun pricing() = withContext(Dispatchers.IO) { db.withTransaction {
        val value = rules(); GeminiCachePricingSnapshot(value, PricingVersion(epoch(), value.revision))
    } }
    suspend fun replace(incoming: List<GeminiCachePricingRule>, expected: PricingVersion, catalog: Boolean = false): GeminiCachePricingSnapshot {
        if (incoming.size > 200) throw StError.Validation("cache pricing exceeds rule budget")
        val proposed = incoming.toList()
        return withContext(Dispatchers.IO) { db.withTransaction {
            val previous = rules(); val currentEpoch = epoch()
            if (expected != PricingVersion(currentEpoch, previous.revision)) throw StError.Conflict("cache pricing changed; refresh before editing")
            if (previous.revision == Long.MAX_VALUE) throw StError.Validation("cache pricing revision exhausted")
            val source = if (catalog) "catalog" else "user"
            if (proposed.any { it.source != source }) throw StError.Validation("cache pricing ownership mismatch")
            val value = GeminiCachePricingRuleSet(revision = previous.revision + 1,
                rules = previous.rules.filter { it.source != source } + proposed, updatedAt = Instant.now().toString())
            GeminiCachePricing.validateRules(value)
            val raw = Jsonx.encode(value)
            if (raw.toByteArray(Charsets.UTF_8).size > 262_144) throw StError.Validation("cache pricing exceeds 256 KiB")
            db.config().upsertSettings(AppSettingsEntity(SETTINGS_ID, raw, value.updatedAt!!))
            GeminiCachePricingSnapshot(value, PricingVersion(currentEpoch, value.revision))
        } }
    }
    suspend fun freeze(owner: GeminiCacheOwner, at: String): GeminiCacheFrozenPrice = withContext(Dispatchers.IO) { db.withTransaction {
        try { GeminiCachePricing.select(rules(), owner, at) } catch (_: StError.Validation) {
            GeminiCacheFrozenPrice(reason = "pricing_configuration_invalid")
        }
    } }
    suspend fun quote(owner: GeminiCacheOwner, tokenCount: Long?, storageSeconds: Long, at: String): GeminiCacheCostQuote {
        // Read-only caller input: no credential resolution, resource creation, task or accounting write.
        GeminiCacheTaskMetadata.validateOwner(owner)
        return GeminiCachePricing.quote(freeze(owner, at), owner, at, tokenCount, storageSeconds)
    }
    companion object { const val SETTINGS_ID = "gemini_cache_pricing_v1" }
}
