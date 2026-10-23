package com.simpletavern.core.api

import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.model.StError
import java.net.URI
import java.io.ByteArrayOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ProviderCatalogEntry(
    val id: String, val label: String, val name: String, val piId: String?, val region: String, val group: String,
    val baseUrlTemplate: String?, val placeholders: List<Map<String, String>>, val authStyle: String,
    val defaultProtocol: String, val supportedProtocols: List<String>, val protocolVariant: String?,
    val protocolPaths: Map<String, String>, val cacheStrategy: String, val explicitCacheMarkers: Boolean,
    val fastMode: String?, val docsUrl: String?, val keywords: List<String>, val legacyIds: List<String>,
    val requiresOAuth: Boolean, val hint: String?, val modelsProviderId: String?, val envKeys: List<String>,
    val unsupportedApis: List<String>, val suggestedModels: List<String>, val adapterStatus: String,
)

@Serializable
data class ProviderCatalogSnapshot(val generatedAt: String?, val source: Map<String, String>,
    val providers: List<ProviderCatalogEntry>, val warnings: List<String> = listOf("frozen_catalog_not_runtime_capability_proof"))

/** Bundled reference data only: no remote fetch, credential, preferences write or model selection. */
internal object ProviderCatalog {
    private val data: ProviderCatalogSnapshot by lazy {
        fun resource(name: String): JsonObject {
            val input = ProviderCatalog::class.java.getResourceAsStream("/simpletavern/catalog/$name")
                ?: throw StError.Validation("bundled provider catalog missing")
            val bytes = input.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count > 131_072 - output.size()) throw StError.Validation("bundled provider catalog exceeds budget")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return NativeJsonInput.parse(NativeJsonInput.utf8(bytes)) as? JsonObject
                ?: throw StError.Validation("bundled provider catalog must be an object")
        }
        val generated = resource("providers.generated.json")
        val overlay = resource("providers.overlay.json")
        val sourceRows = generated.objects("providers")
        val overlayRows = overlay.objects("providers")
        if (sourceRows.isEmpty() || overlayRows.isEmpty()) invalid()
        if (sourceRows.map { it.required("id") }.distinct().size != sourceRows.size) invalid()
        val byId = sourceRows.associateBy { it.required("id") }
        val seen = hashSetOf<String>()
        val entries = overlayRows.map { obj ->
            val piId = obj.text("piId")
            if (piId != null) seen += piId
            merge(piId?.let(byId::get), obj)
        } + byId.filterKeys { it !in seen }.map { (id, obj) -> merge(obj, JsonObject(mapOf("id" to JsonPrimitive(id), "piId" to JsonPrimitive(id)))) }
        if (entries.map { it.id }.distinct().size != entries.size || entries.size > 256) invalid()
        ProviderCatalogSnapshot(generated.text("generatedAt"), generated.stringMap("source"), entries)
    }

    fun snapshot(): ProviderCatalogSnapshot = data.copy(providers = data.providers.map { it.copy(
        placeholders = it.placeholders.map { map -> map.toMap() }, protocolPaths = it.protocolPaths.toMap(),
        supportedProtocols = it.supportedProtocols.toList(), keywords = it.keywords.toList(), legacyIds = it.legacyIds.toList(),
        envKeys = it.envKeys.toList(), unsupportedApis = it.unsupportedApis.toList(), suggestedModels = it.suggestedModels.toList()) },
        source = data.source.toMap(), warnings = data.warnings.toList())
    fun find(id: String): ProviderCatalogEntry? = data.providers.firstOrNull { it.id == id }
        ?: data.providers.firstOrNull { id in it.legacyIds }

    /** Most-specific matching path; templates cannot match an unrelated host or port. */
    fun match(base: String): ProviderCatalogEntry? {
        val target = URI(base)
        var best: ProviderCatalogEntry? = null
        var specificity = -1
        for (entry in data.providers) for (template in listOfNotNull(entry.baseUrlTemplate) + entry.protocolPaths.values) {
            if (matches(template, target)) {
                val score = URI(template.replace(Regex("\\{[A-Za-z0-9_]+}"), "zzplaceholderzz")).path.length
                if (score > specificity) { best = entry; specificity = score }
            }
        }
        return best
    }

    private fun matches(template: String, target: URI): Boolean {
        val marked = template.replace(Regex("\\{[A-Za-z0-9_]+}"), "zzplaceholderzz")
        val uri = runCatching { URI(marked) }.getOrNull() ?: return false
        fun port(value: URI) = if (value.port != -1) value.port else if (value.scheme == "https") 443 else 80
        if (uri.scheme != target.scheme || port(uri) != port(target)) return false
        fun pattern(raw: String, segment: String) = Regex(raw.split("zzplaceholderzz").joinToString(segment) { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        if (!pattern(uri.host ?: return false, "[A-Za-z0-9-]+").matches(target.host ?: return false)) return false
        val path = uri.path.trimEnd('/')
        return pattern(path, "[A-Za-z0-9._-]+?").let { expression ->
            Regex("^(?:${expression.pattern})(?:/|$)", RegexOption.IGNORE_CASE).containsMatchIn(target.path.trimEnd('/'))
        }
    }

    private fun merge(pi: JsonObject?, overlay: JsonObject): ProviderCatalogEntry {
        val generated = pi ?: JsonObject(emptyMap())
        val id = overlay.text("id") ?: generated.required("id")
        val protocols = (overlay.strings("supportedProtocols").ifEmpty { generated.strings("protocols") }
            .ifEmpty { listOf("openai_compatible_chat") }).toMutableList()
        if (id == "amazon-bedrock" && "bedrock_converse" !in protocols) protocols += "bedrock_converse"
        val default = overlay.text("defaultProtocol") ?: protocols.first()
        if (default !in protocols) protocols.add(0, default)
        val oauth = overlay.boolean("requiresOAuth") || generated.boolean("oauthOnly")
        val variant = overlay.text("protocolVariant")
        val base = overlay.text("baseUrlTemplate") ?: generated.text("baseUrl")
        val status = when {
            id == "github-copilot" -> "oauth_device_github_com"
            id == "openai-codex" -> "oauth_codex_compatibility"
            id == "openai-chatgpt" -> "oauth_chatgpt_official"
            id == "xai-oauth" -> "oauth_xai_device_compatibility"
            id == "amazon-bedrock" && variant in setOf("bedrock", "bedrock_converse") -> "bedrock_bearer"
            oauth -> "unsupported_oauth"
            variant != null -> "unsupported_protocol_variant"
            base == null -> "missing_template"
            else -> "standard_http"
        }
        return ProviderCatalogEntry(id, overlay.text("label") ?: generated.text("name") ?: id,
            overlay.text("name") ?: generated.text("name") ?: id, overlay.text("piId") ?: generated.text("id"),
            overlay.text("region") ?: "global", overlay.text("group") ?: "global", base,
            overlay.objects("placeholders").map { obj -> obj.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid() } },
            overlay.text("authStyle") ?: "bearer", default, protocols.toList(), variant, overlay.stringMap("protocolPaths"),
            overlay.text("cacheStrategy") ?: "best_effort", overlay.boolean("explicitCacheMarkers"), overlay.text("fastMode"),
            overlay.text("docsUrl"), overlay.strings("keywords"), overlay.strings("legacyIds"), oauth,
            if (id == "amazon-bedrock") "Bedrock API key (Bearer); supports native Anthropic InvokeModel and explicit Converse requests." else overlay.text("hint"),
            overlay.text("modelsProviderId"), generated.strings("envKeys"), generated.strings("unsupportedApis"), overlay.strings("suggestedModels"), status)
    }
    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it != JsonNull }?.let {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid()
    }
    private fun JsonObject.required(key: String) = text(key)?.takeIf(String::isNotBlank) ?: invalid()
    private fun JsonObject.objects(key: String): List<JsonObject> = get(key)?.takeIf { it != JsonNull }?.let { value ->
        val array = value as? JsonArray ?: invalid()
        if (array.size > 256) invalid()
        array.map { it as? JsonObject ?: invalid() }
    }.orEmpty()
    private fun JsonObject.strings(key: String): List<String> = get(key)?.takeIf { it != JsonNull }?.let { value ->
        (value as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid() } ?: invalid()
    }.orEmpty()
    private fun JsonObject.boolean(key: String): Boolean = get(key)?.takeIf { it != JsonNull }?.let {
        (it as? JsonPrimitive)?.booleanOrNull ?: invalid()
    } ?: false
    private fun JsonObject.stringMap(key: String): Map<String, String> = get(key)?.takeIf { it != JsonNull }?.let { value ->
        (value as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid() } ?: invalid()
    }.orEmpty()
    private fun invalid(): Nothing = throw StError.Validation("invalid bundled provider catalog")
}
