package com.simpletavern.core.api

import com.simpletavern.core.model.StError
import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Normalizes the two public catalog responses into the bundled desktop catalog shape. */
internal object ModelCatalogSources {
    private const val MODELS_DEV_URL = "https://models.dev/api.json"
    private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/models"
    private const val MAX_PROVIDERS = 512
    private const val MAX_MODELS = 50_000
    private const val MAX_OPENROUTER_MODELS = 10_000

    fun normalize(modelsDev: JsonObject, openRouter: JsonObject, fetchedAt: String): JsonObject {
        if (modelsDev.size > MAX_PROVIDERS) invalid()

        val providers = linkedMapOf<String, JsonElement>()
        val modelIndexes = mutableMapOf<String, LinkedHashMap<String, JsonElement>>()
        var modelCount = 0

        modelsDev.forEach { (providerId, rawProvider) ->
            val source = rawProvider as? JsonObject ?: invalid()
            val sourceModels = source["models"] as? JsonObject ?: invalid()
            modelCount += sourceModels.size
            if (modelCount > MAX_MODELS) invalid()

            val models = linkedMapOf<String, JsonElement>()
            sourceModels.forEach { (modelId, rawModel) ->
                val model = rawModel as? JsonObject ?: invalid()
                models[modelId] = compactModel(modelId, model)
            }
            modelIndexes[providerId] = models
            providers[providerId] = buildJsonObject {
                put("modelsDevId", JsonPrimitive(providerId))
                put("name", source["name"] ?: JsonPrimitive(providerId))
                put("doc", source["doc"] ?: JsonNull)
                put("api", source["api"] ?: JsonNull)
                put("models", JsonObject(models))
            }
        }

        val rawRouterModels = openRouter["data"] as? JsonArray ?: invalid()
        if (rawRouterModels.size > MAX_OPENROUTER_MODELS) invalid()

        val routerMetadata = linkedMapOf<String, JsonElement>()
        rawRouterModels.forEach { rawItem ->
            val item = rawItem as? JsonObject ?: invalid()
            val id = (item["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotBlank() } ?: invalid()
            val pricing = item["pricing"] as? JsonObject
            val routerEntry = compactRouterMetadata(item, pricing)
            routerMetadata[id] = routerEntry

            val routerModels = modelIndexes.getOrPut("openrouter") {
                linkedMapOf<String, JsonElement>().also { models ->
                    providers["openrouter"] = buildJsonObject {
                        put("modelsDevId", JsonPrimitive("openrouter"))
                        put("name", JsonPrimitive("openrouter"))
                        put("doc", JsonNull)
                        put("api", JsonNull)
                        put("models", JsonObject(models))
                    }
                }
            }
            val existing = routerModels[id] as? JsonObject
            val model = if (existing != null) mergeRouterModel(existing, item, pricing)
            else minimumRouterModel(id, item, pricing)
            if (existing == null) {
                modelCount++
                if (modelCount > MAX_MODELS) invalid()
            }
            routerModels[id] = model
            providers["openrouter"] = (providers["openrouter"] as JsonObject).let { provider ->
                JsonObject(provider + ("models" to JsonObject(routerModels)))
            }
        }

        return buildJsonObject {
            put("generatedAt", JsonPrimitive(fetchedAt))
            put("sources", buildJsonObject {
                put("modelsDev", JsonPrimitive(MODELS_DEV_URL))
                put("openrouter", JsonPrimitive(OPENROUTER_URL))
            })
            put("providers", JsonObject(providers))
            put("openrouter", JsonObject(routerMetadata))
        }
    }

    private fun compactModel(id: String, raw: JsonObject): JsonObject {
        val efforts = mutableListOf<JsonElement>()
        var toggle = false
        (raw["reasoning_options"] as? JsonArray)?.forEach { optionElement ->
            val option = optionElement as? JsonObject ?: return@forEach
            when ((option["type"] as? JsonPrimitive)?.content) {
                "effort" -> (option["values"] as? JsonArray)?.forEach { value ->
                    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (!text.isNullOrEmpty()) efforts += JsonPrimitive(text)
                }
                "toggle" -> toggle = true
            }
        }

        val cost = raw["cost"] as? JsonObject
        val commonCost = linkedMapOf<String, JsonElement>()
        val extraPricing = linkedMapOf<String, JsonElement>()
        cost?.forEach { (key, value) ->
            if (key in MODEL_COST_FIELDS) commonCost[key] = value else extraPricing[key] = value
        }

        val modes = linkedMapOf<String, JsonElement>()
        val sourceModes = ((raw["experimental"] as? JsonObject)?.get("modes") as? JsonObject)
        sourceModes?.forEach { (modeName, rawMode) ->
            val mode = rawMode as? JsonObject ?: return@forEach
            val entry = linkedMapOf<String, JsonElement>()
            val modeProvider = mode["provider"] as? JsonObject
            modeProvider?.get("body")?.let { entry["body"] = it }
            modeProvider?.get("headers")?.let { entry["headers"] = it }

            val modeCost = mode["cost"] as? JsonObject
            val commonModeCost = linkedMapOf<String, JsonElement>()
            val modeExtraPricing = linkedMapOf<String, JsonElement>()
            modeCost?.forEach { (key, value) ->
                if (key in MODEL_COST_FIELDS) commonModeCost[key] = value else modeExtraPricing[key] = value
            }
            if (commonModeCost.isNotEmpty()) entry["cost"] = JsonObject(commonModeCost)
            if (modeExtraPricing.isNotEmpty()) entry["extraPricing"] = JsonObject(modeExtraPricing)
            modes[modeName] = JsonObject(entry)
        }

        return buildJsonObject {
            put("name", raw["name"] ?: JsonPrimitive(id))
            put("family", raw["family"] ?: JsonNull)
            put("reasoning", raw["reasoning"] ?: JsonNull)
            put("temperature", raw["temperature"] ?: JsonNull)
            put("toolCall", raw["tool_call"] ?: JsonNull)
            if (efforts.isNotEmpty()) put("efforts", JsonArray(efforts))
            if (toggle) put("reasoningToggle", JsonPrimitive(true))
            if (commonCost.isNotEmpty()) put("cost", JsonObject(commonCost))
            if (extraPricing.isNotEmpty()) put("extraPricing", JsonObject(extraPricing))

            val limit = raw["limit"] as? JsonObject
            if (limit != null) {
                val retained = limit.filterKeys { it in MODEL_LIMIT_FIELDS }
                if (retained.isNotEmpty()) put("limit", JsonObject(retained))
            }
            if (modes.isNotEmpty()) put("modes", JsonObject(modes))
            raw["release_date"]?.let { put("releaseDate", it) }
        }
    }

    private fun compactRouterMetadata(item: JsonObject, pricing: JsonObject?): JsonObject {
        val metadata = linkedMapOf<String, JsonElement>()
        (item["supported_parameters"] as? JsonArray)?.let { metadata["supportedParameters"] = it }
        ((item["reasoning"] as? JsonObject)?.get("supported_efforts") as? JsonArray)
            ?.let { metadata["supportedEfforts"] = it }

        val cache = linkedMapOf<String, JsonElement>()
        pricing?.forEach { (key, value) ->
            if (key == "input_cache_read" || key == "input_cache_write" || key == "input_cache_write_1h") {
                val decimal = nonNegativeDecimal(value)
                if (decimal != null) cache[key] = JsonPrimitive(decimal.first)
            }
        }
        if (cache.isNotEmpty()) metadata["cachePricing"] = JsonObject(cache)
        return JsonObject(metadata)
    }

    private fun mergeRouterModel(existing: JsonObject, item: JsonObject, pricing: JsonObject?): JsonObject {
        val model = existing.toMutableMap()
        val cost = (existing["cost"] as? JsonObject)?.toMutableMap() ?: linkedMapOf()
        val extraPricing = (existing["extraPricing"] as? JsonObject)?.toMutableMap() ?: linkedMapOf()
        applyRouterPricing(pricing, cost, extraPricing)
        if (cost.isNotEmpty()) model["cost"] = JsonObject(cost)
        else model.remove("cost")
        if (extraPricing.isNotEmpty()) model["extraPricing"] = JsonObject(extraPricing)
        else model.remove("extraPricing")
        item["name"]?.takeIf { it != JsonNull }?.let { model["name"] = it }
        val context = item["context_length"]?.takeIf { it != JsonNull }
        val maxCompletion = ((item["top_provider"] as? JsonObject)?.get("max_completion_tokens"))
            ?.takeIf { it != JsonNull }
        if (context != null || maxCompletion != null) {
            val limit = (model["limit"] as? JsonObject)?.toMutableMap() ?: linkedMapOf()
            context?.let { limit["context"] = it }
            maxCompletion?.let { limit["output"] = it }
            model["limit"] = JsonObject(limit)
        }
        return JsonObject(model)
    }

    private fun minimumRouterModel(id: String, item: JsonObject, pricing: JsonObject?): JsonObject {
        val cost = linkedMapOf<String, JsonElement>()
        val extraPricing = linkedMapOf<String, JsonElement>()
        applyRouterPricing(pricing, cost, extraPricing)
        val limit = linkedMapOf<String, JsonElement>()
        item["context_length"]?.takeIf { it != JsonNull }?.let { limit["context"] = it }
        (((item["top_provider"] as? JsonObject)?.get("max_completion_tokens")))
            ?.takeIf { it != JsonNull }?.let { limit["output"] = it }
        return buildJsonObject {
            put("name", item["name"] ?: JsonPrimitive(id))
            if (cost.isNotEmpty()) put("cost", JsonObject(cost))
            if (extraPricing.isNotEmpty()) put("extraPricing", JsonObject(extraPricing))
            if (limit.isNotEmpty()) put("limit", JsonObject(limit))
        }
    }

    private fun applyRouterPricing(
        pricing: JsonObject?,
        cost: MutableMap<String, JsonElement>,
        extraPricing: MutableMap<String, JsonElement>,
    ) {
        if (pricing == null) return
        val mapped = linkedMapOf(
            "prompt" to "input",
            "completion" to "output",
            "input_cache_read" to "cache_read",
            "input_cache_write" to "cache_write",
            "input_cache_write_1h" to "cache_write_1h",
        )
        pricing.forEach { (sourceKey, value) ->
            val targetKey = mapped[sourceKey]
            if (targetKey != null) {
                val decimal = nonNegativeDecimal(value)
                if (decimal == null) {
                    cost.remove(targetKey)
                    extraPricing[sourceKey] = value
                } else {
                    cost[targetKey] = JsonPrimitive(decimal.second)
                }
            } else if (!isExactlyZero(value)) {
                extraPricing[sourceKey] = value
            }
        }
    }

    /** Returns the original decimal text and its per-million form when the quote is usable. */
    private fun nonNegativeDecimal(value: JsonElement): Pair<String, String>? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString.not() && primitive.content.toBigDecimalOrNull() == null) return null
        val text = primitive.content
        if (text.length > MAX_DECIMAL_TEXT_LENGTH) return null
        val decimal = text.toBigDecimalOrNull() ?: return null
        if (decimal.signum() < 0 || decimal.precision() > MAX_DECIMAL_PRECISION ||
            decimal.scale() !in MIN_DECIMAL_SCALE..MAX_DECIMAL_SCALE
        ) return null
        val perMillion = decimal.movePointRight(6)
        val perMillionText = perMillion.toPlainString()
        if (perMillion.precision() > MAX_DECIMAL_PRECISION ||
            perMillion.scale() !in MIN_DECIMAL_SCALE..MAX_DECIMAL_SCALE ||
            perMillionText.length > MAX_DECIMAL_TEXT_LENGTH
        ) return null
        return text to perMillionText
    }

    private fun isExactlyZero(value: JsonElement): Boolean {
        val primitive = value as? JsonPrimitive ?: return false
        val decimal = primitive.content.toBigDecimalOrNull() ?: return false
        return decimal.signum() == 0
    }

    private fun invalid(): Nothing = throw StError.Provider("invalid public model catalog response")

    private val MODEL_COST_FIELDS = setOf("input", "output", "cache_read", "cache_write")
    private val MODEL_LIMIT_FIELDS = setOf("context", "input", "output")
    private const val MAX_DECIMAL_TEXT_LENGTH = 64
    private const val MAX_DECIMAL_PRECISION = 64
    private const val MIN_DECIMAL_SCALE = -12
    private const val MAX_DECIMAL_SCALE = 18
}
