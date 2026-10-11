package com.simpletavern.core.api

import com.simpletavern.core.llm.ProviderHttpOptions
import com.simpletavern.core.model.StError
import java.util.Locale
import kotlinx.serialization.json.*

/** Configuration contains either public text or encrypted-store references, never inline header secrets. */
internal class ProviderHeaderSettings private constructor(private val entries: List<Entry>) {
    private data class Entry(val name: String, val value: String?, val credentialId: String?, val prefix: String, val suffix: String)
    val credentialIds: Set<String> get() = entries.mapNotNull { it.credentialId }.toSet()
    val hasAuthenticationReference: Boolean get() = entries.any { it.credentialId != null && ProviderHttpOptions.sensitive(it.name) }

    fun resolve(secrets: Map<String, String>, authStyle: String?, apiVersion: String? = null): ProviderHttpOptions {
        val materials = arrayListOf<String>()
        val headers = entries.associate { entry ->
            val value = if (entry.credentialId == null) entry.value!! else {
                val secret = secrets[entry.credentialId]?.takeIf(String::isNotBlank)
                    ?: throw StError.Validation("custom header credential missing; configure encrypted credential storage")
                ProviderHttpOptions.validateSecret(secret)
                materials += secret
                entry.prefix + secret + entry.suffix
            }
            ProviderHttpOptions.validateHeader(entry.name, value)
            entry.name to value
        }
        return ProviderHttpOptions(authStyle, headers, materials, apiVersion)
    }

    companion object {
        fun parse(layers: List<JsonObject>): ProviderHeaderSettings {
            val entries = linkedMapOf<String, Entry>()
            var count = 0
            fun text(obj: JsonObject, key: String): String? = obj[key]?.takeIf { it != JsonNull }?.let {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid()
            }
            fun add(name: String, value: JsonElement) {
                if (++count > 256) invalid()
                val descriptor = when (value) {
                    is JsonPrimitive -> if (value.isString) JsonObject(mapOf("value" to value)) else invalid()
                    is JsonObject -> value
                    else -> invalid()
                }
                if (descriptor.keys.any { it !in setOf("name", "key", "value", "credentialId", "prefix", "suffix") }) invalid()
                val literal = text(descriptor, "value")
                val id = text(descriptor, "credentialId")
                if (id?.startsWith(com.simpletavern.core.data.CredentialStore.OAUTH_PREFIX) == true ||
                    id?.startsWith(com.simpletavern.core.data.CredentialStore.AWS_PREFIX) == true)
                    throw StError.Validation("structured provider records cannot be used as custom header credentials")
                val prefix = text(descriptor, "prefix").orEmpty()
                val suffix = text(descriptor, "suffix").orEmpty()
                if ((literal != null) == (id != null) || id != null && (id.isBlank() || id.length > 1024 || id.any(Char::isISOControl)) ||
                    id == null && (prefix.isNotEmpty() || suffix.isNotEmpty())) invalid()
                ProviderHttpOptions.validateHeader(name, literal ?: prefix + suffix)
                if (ProviderHttpOptions.sensitive(name) && literal != null)
                    throw StError.Validation("sensitive headers require encrypted credential references")
                entries[name.lowercase(Locale.ROOT)] = Entry(name, literal, id, prefix, suffix)
            }
            for (layer in layers) for (key in listOf("headers", "customHeaders")) {
                val raw = layer[key]?.takeIf { it != JsonNull } ?: continue
                if (raw.toString().toByteArray(Charsets.UTF_8).size > 65_536) invalid()
                when (raw) {
                    is JsonObject -> raw.forEach { (name, value) -> add(name, value) }
                    is JsonArray -> raw.forEach { element ->
                        val item = element as? JsonObject ?: invalid()
                        val name = text(item, "name") ?: text(item, "key") ?: invalid()
                        add(name, item)
                    }
                    else -> invalid()
                }
            }
            if (entries.size > 64) invalid()
            return ProviderHeaderSettings(entries.values.toList())
        }
        private fun invalid(): Nothing = throw StError.Validation("invalid custom header configuration; removed sensitive values need encrypted references")
    }
}
