package com.simpletavern.core.api

import com.simpletavern.core.model.PricingRule
import com.simpletavern.core.model.StError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.Locale

@Serializable
data class ModelCatalogPriceSelection(
    val providerId: String,
    val modelId: String,
    val usageProvider: String,
    val mode: String? = null,
    val serviceTier: String = "standard",
)

/** Converts explicitly selected frozen catalog prices into rules; it never installs them. */
internal object ModelCatalogPricing {
    fun rules(selections: List<ModelCatalogPriceSelection>): List<PricingRule> {
        if (selections.isEmpty() || selections.size > 200)
            throw StError.Validation("model catalog pricing selection count must be 1..200")

        val seen = mutableSetOf<Triple<String, String, String>>()
        return selections.mapIndexed { index, selection ->
            validateText(selection.usageProvider, 512, "usage provider")
            validateText(selection.serviceTier, 64, "service tier")
            if (selection.mode != null && selection.mode.isBlank())
                throw StError.Validation("mode must not be blank")

            val tuple = Triple(
                norm(selection.usageProvider),
                norm(selection.modelId),
                norm(selection.serviceTier),
            )
            if (!seen.add(tuple)) throw StError.Validation("duplicate model catalog pricing selection")

            val entry = ModelCatalog.capabilities(selection.modelId, selection.providerId).matchedModel
                ?: throw StError.NotFound("model catalog entry")
            val mode = selection.mode?.let { requested ->
                entry.modes[requested] ?: throw StError.Unsupported("selected model catalog mode is unavailable")
            }
            if (!entry.extraPricing.isEmpty() || mode?.extraPricing?.isNotEmpty() == true)
                throw StError.Unsupported("selected model catalog price has unsupported dimensions")

            val rates = mode?.costPerMillion ?: entry.costPerMillion
            val input = rates["input"]
            val output = rates["output"]
            val cacheRead = rates["cache_read"]
            val cacheWrite = rates["cache_write"]
            // The catalog normalizer provides this rate in USD per million tokens when known.
            val cacheWriteLong = rates["cache_write_1h"]
            if (listOf(input, output, cacheRead, cacheWrite, cacheWriteLong).all { it == null })
                throw StError.Unsupported("selected model catalog entry has no usable prices")

            PricingRule(
                id = "catalog:models-$index",
                provider = selection.usageProvider,
                canonicalModelId = selection.modelId,
                currency = "USD",
                inputPerMillion = input,
                outputPerMillion = output,
                cacheReadPerMillion = cacheRead,
                cacheWritePerMillion = cacheWrite,
                cacheWriteLongPerMillion = cacheWriteLong,
                serviceTier = selection.serviceTier,
                sourceUrl = if (mode == null && entry.source.contains("openrouter", ignoreCase = true))
                    "https://openrouter.ai/api/v1/models" else "https://models.dev/api.json",
                source = "catalog",
                coversMixedModalities = false,
            )
        }
    }

    private fun norm(value: String) = value.trim().lowercase(Locale.ROOT)

    private fun validateText(value: String, maxLength: Int, label: String) {
        if (value.isBlank() || value.length > maxLength || value.any(Char::isISOControl))
            throw StError.Validation("invalid $label")
    }
}
