package com.simpletavern.core.api

import android.util.AtomicFile
import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.model.StError
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ModelCatalogProvider(val id: String, val name: String, val docsUrl: String?, val apiUrl: String?, val modelCount: Int)

/** Frozen source prices are decimal text, not installed pricing rules or a live quote. */
@Serializable
data class ModelCatalogMode(val body: JsonObject, val headers: Map<String, String>, val costPerMillion: Map<String, String>,
    val extraPricing: JsonObject = JsonObject(emptyMap()))

@Serializable
data class ModelCatalogEntry(
    val providerId: String, val modelId: String, val name: String, val family: String?,
    val reasoning: Boolean?, val temperature: Boolean?, val toolCall: Boolean?, val reasoningToggle: Boolean?,
    val efforts: List<String>, val costPerMillion: Map<String, String>, val limits: Map<String, Long>,
    val modes: Map<String, ModelCatalogMode>, val supportedParameters: List<String>,
    val openRouterCachePricePerToken: Map<String, String>, val releaseDate: String?, val source: String,
    val extraPricing: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class ModelCatalogPage(val generatedAt: String?, val sources: Map<String, String>,
    val providers: List<ModelCatalogProvider>, val total: Int, val offset: Int, val nextOffset: Int?,
    val models: List<ModelCatalogEntry>, val warnings: List<String> = listOf("frozen_catalog_not_runtime_capability_proof", "reference_prices_not_installed"))

@Serializable
data class ModelCapabilitySelection(val requestedModel: String, val requestedProviderId: String?,
    val matchedModel: ModelCatalogEntry?, val generatedAt: String?, val warnings: List<String>)

/** Bundled fallback plus an explicitly refreshed reference cache; never changes user models or installed prices. */
internal object ModelCatalog {
    private data class Data(val generatedAt: String?, val sources: Map<String, String>,
        val providers: List<ModelCatalogProvider>, val entries: List<ModelCatalogEntry>, val online: Boolean = false)
    @Volatile private var refreshed: Data? = null
    @Volatile private var cacheWarning: String? = null
    private var cache: AtomicFile? = null
    private val data get() = refreshed ?: bundled
    internal const val MAX_BYTES = 33_554_432
    private val bundled: Data by lazy {
        val bytes = (ModelCatalog::class.java.getResourceAsStream("/simpletavern/catalog/models.generated.json")
            ?: throw StError.Validation("bundled model catalog missing")).use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count > 2_097_152 - out.size()) invalid()
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        val root = NativeJsonInput.parse(NativeJsonInput.utf8(bytes)) as? JsonObject ?: invalid()
        parse(root, false)
    }

    /** Downloaded reference data is rebuildable cache, not a Room row or native user-data snapshot. */
    @Synchronized fun load(cacheDirectory: File) {
        val file = AtomicFile(cacheDirectory.resolve("model-catalog-v1.json"))
        cache = file
        refreshed = null
        cacheWarning = null
        try {
            val root = file.openRead().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > MAX_BYTES - out.size()) invalid()
                    out.write(buffer, 0, count)
                }
                Json.parseToJsonElement(NativeJsonInput.utf8(out.toByteArray())) as? JsonObject ?: invalid()
            }
            refreshed = parse(root, true)
        } catch (_: FileNotFoundException) { /* First launch or an evicted reference cache uses bundled data. */ }
        catch (_: Exception) { cacheWarning = "online_catalog_cache_unavailable" }
    }

    @Synchronized fun install(root: JsonObject): ModelCatalogPage {
        val parsed = parse(root, true)
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw StError.Provider("model catalog exceeds cache budget")
        val file = cache ?: throw StError.Conflict("model catalog cache is not initialized")
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
        refreshed = parsed
        cacheWarning = null
        return page(null, null, 0, 100)
    }

    @Synchronized fun pricingRules(selections: List<ModelCatalogPriceSelection>) = ModelCatalogPricing.rules(selections)

    private fun parse(root: JsonObject, online: Boolean): Data {
        val providers = root.obj("providers") ?: invalid()
        val router = root.obj("openrouter") ?: invalid()
        if (providers.size !in 1..512 || router.size > 10_000) invalid()
        val entries = mutableListOf<ModelCatalogEntry>()
        val descriptions = providers.map { (id, value) ->
            identity(id)
            val provider = value as? JsonObject ?: invalid()
            val models = provider.obj("models") ?: invalid()
            if (models.size > 50_000 - entries.size) invalid()
            models.forEach { (modelId, raw) ->
                identity(modelId)
                val model = raw as? JsonObject ?: invalid()
                val extra = if (id == "openrouter") router.obj(modelId) else null
                entries += entry(id, modelId, model, extra, if (online && id == "openrouter" && extra != null) "openrouter" else "models.dev")
            }
            ModelCatalogProvider(id, provider.text("name") ?: id, provider.text("doc"), provider.text("api"), models.size)
        }.toMutableList()
        // OpenRouter's independent metadata may contain IDs absent from models.dev's frozen list.
        val existingRouter = entries.filter { it.providerId == "openrouter" }.mapTo(hashSetOf()) { it.modelId }
        router.forEach { (id, value) ->
            identity(id)
            if (id !in existingRouter) entries += entry("openrouter", id, JsonObject(emptyMap()), value as? JsonObject ?: invalid(), "openrouter_metadata")
        }
        if (entries.size > 50_000 || entries.map { it.providerId to key(it.modelId) }.distinct().size != entries.size) invalid()
        val routerIndex = descriptions.indexOfFirst { it.id == "openrouter" }
        if (routerIndex >= 0) descriptions[routerIndex] = descriptions[routerIndex].copy(modelCount = entries.count { it.providerId == "openrouter" })
        return Data(root.text("generatedAt"), root.stringMap("sources"), descriptions.toList(),
            entries.sortedWith(compareBy({ it.providerId }, { it.modelId })), online)
    }

    fun page(providerId: String?, query: String?, offset: Int, limit: Int): ModelCatalogPage {
        if (offset !in 0..50_000 || limit !in 1..200 || (query?.length ?: 0) > 256) throw StError.Validation("invalid model catalog page")
        val data = data
        val provider = providerId?.let { providerKey(it, data) }
        val search = query?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val matching = data.entries.filter { (provider == null || it.providerId == provider) &&
            (search.isEmpty() || search in key(it.modelId) || search in it.name.lowercase(Locale.ROOT)) }
        val slice = matching.drop(offset).take(limit)
        return ModelCatalogPage(data.generatedAt, data.sources.toMap(), data.providers.toList(), matching.size, offset,
            (offset + slice.size).takeIf { it < matching.size }, slice.map(::copyEntry), warnings(data))
    }

    fun capabilities(model: String, providerId: String?): ModelCapabilitySelection {
        identity(model)
        val data = data
        val provider = providerId?.let { providerKey(it, data) }
        val requested = key(model)
        val candidates = data.entries.filter { (provider == null || it.providerId == provider) && key(it.modelId) == requested }
        // Do not borrow another provider's limits, Fast support or prices on an ambiguous/unknown identity.
        val matched = candidates.singleOrNull()
        return ModelCapabilitySelection(model, providerId, matched?.let(::copyEntry), data.generatedAt, buildList {
            addAll(warnings(data))
            if (matched == null) add(if (candidates.isEmpty()) "model_not_in_catalog" else "provider_required_for_ambiguous_model")
        })
    }

    private fun warnings(data: Data) = listOf(if (data.online) "reference_catalog_not_runtime_capability_proof"
        else "frozen_catalog_not_runtime_capability_proof", "reference_prices_not_installed") + listOfNotNull(cacheWarning)

    private fun providerKey(id: String, data: Data): String {
        identity(id)
        val normalized = id.trim().lowercase(Locale.ROOT)
        if (data.providers.any { it.id == normalized }) return normalized
        val entry = ProviderCatalog.find(normalized)
        val selected = entry?.modelsProviderId ?: entry?.piId ?: normalized
        if (data.providers.any { it.id == selected }) return selected
        return when (selected) {
            "fireworks" -> "fireworks-ai"; "together" -> "togetherai"; "kimi-coding" -> "kimi-for-coding"
            "zai-coding-cn" -> "zai-coding-plan"; "qwen-token-plan", "qwen-token-plan-individual" -> "alibaba-token-plan"
            "qwen-token-plan-cn" -> "alibaba-token-plan-cn"; "ant-ling" -> "bailing"
            "azure-openai-responses" -> "azure"; "openai-codex" -> "openai"; else -> selected
        }
    }
    private fun key(id: String) = id.trim().removePrefix("models/").lowercase(Locale.ROOT)
    private fun identity(id: String) { if (id.isBlank() || id.length > 512 || id.any { it.code < 32 || it.code == 127 }) invalid() }
    private fun entry(provider: String, id: String, model: JsonObject, router: JsonObject?, source: String) = ModelCatalogEntry(
        provider, id, model.text("name") ?: id, model.text("family"), model.bool("reasoning"), model.bool("temperature"),
        model.bool("toolCall"), model.bool("reasoningToggle"), router?.strings("supportedEfforts")?.takeIf { it.isNotEmpty() } ?: model.strings("efforts"),
        model.obj("cost")?.decimals().orEmpty(), model.obj("limit")?.mapValues { (_, v) ->
            (v as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 } ?: invalid() }.orEmpty(),
        model.obj("modes")?.mapValues { (_, value) ->
            val mode = value as? JsonObject ?: invalid()
            ModelCatalogMode(mode.obj("body") ?: JsonObject(emptyMap()), mode.stringMap("headers"), mode.obj("cost")?.decimals().orEmpty(),
                mode.obj("extraPricing") ?: JsonObject(emptyMap())) }.orEmpty(),
        router?.strings("supportedParameters").orEmpty(), router?.obj("cachePricing")?.decimals().orEmpty(), model.text("releaseDate"), source,
        model.obj("extraPricing") ?: JsonObject(emptyMap()))
    private fun copyEntry(entry: ModelCatalogEntry) = entry.copy(efforts = entry.efforts.toList(), costPerMillion = entry.costPerMillion.toMap(),
        limits = entry.limits.toMap(), modes = entry.modes.mapValues { (_, m) -> m.copy(body = copyJson(m.body) as JsonObject, headers = m.headers.toMap(),
            costPerMillion = m.costPerMillion.toMap(), extraPricing = copyJson(m.extraPricing) as JsonObject) },
        supportedParameters = entry.supportedParameters.toList(), openRouterCachePricePerToken = entry.openRouterCachePricePerToken.toMap(),
        extraPricing = copyJson(entry.extraPricing) as JsonObject)
    private fun copyJson(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (_, v) -> copyJson(v) })
        is JsonArray -> JsonArray(value.map(::copyJson))
        else -> value
    }
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it != JsonNull }?.let { it as? JsonObject ?: invalid() }
    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it != JsonNull }?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid() }
    private fun JsonObject.bool(key: String): Boolean? = get(key)?.takeIf { it != JsonNull }?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull ?: invalid() }
    private fun JsonObject.strings(key: String): List<String> = get(key)?.takeIf { it != JsonNull }?.let { value ->
        (value as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid() } ?: invalid() }.orEmpty()
    private fun JsonObject.stringMap(key: String): Map<String, String> = obj(key)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid() }.orEmpty()
    private fun JsonObject.decimals(): Map<String, String> = mapValues { (_, v) ->
        val raw = (v as? JsonPrimitive)?.content ?: invalid()
        val number = raw.takeIf { it.length <= 64 }?.toBigDecimalOrNull() ?: invalid()
        if (number.signum() < 0 || number.precision() > 64 || number.scale() !in -12..18) invalid()
        raw // Preserve the source decimal, including per-token OpenRouter values, without floating point conversion.
    }
    private fun invalid(): Nothing = throw StError.Validation("invalid model catalog or identity")
}
