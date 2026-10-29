package com.simpletavern.core.importer

import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.model.*
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** A standalone SimpleTavern JSON book or SillyTavern lorebook, never an archive or executable. */
object WorldBookFileDecoder {
    const val MAX_INPUT_BYTES = 4 * 1024 * 1024
    fun decode(text: String, filename: String = "世界书.json"): WorldBookImportResult = try {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_INPUT_BYTES) throw StError.Validation("worldbook exceeds 4 MiB")
        val raw = NativeJsonInput.parse(text.removePrefix("\uFEFF")) as? JsonObject ?: invalid()
        val entries = when (val value = raw["entries"]) {
            is JsonArray -> value.map { it as? JsonObject ?: invalid() }
            is JsonObject -> value.values.map { it as? JsonObject ?: invalid() }
            else -> invalid()
        }
        if (entries.size > 10_000) throw StError.Validation("worldbook exceeds 10000 entries")
        val st = raw["entries"] is JsonObject || entries.any { entry ->
            entry.keys.any { it in setOf("key", "keys", "comment", "constant", "insertion_order", "disable") }
        }
        val warnings = linkedSetOf<String>()
        val mapped = entries.mapIndexed { index, entry ->
            if (!st) Jsonx.json.decodeFromJsonElement<WorldBookEntry>(JsonObject(entry + ("id" to JsonPrimitive(id()))))
            else {
                fun keys(name: String): List<String> = when (val value = entry[name]) {
                    null, JsonNull -> emptyList()
                    is JsonArray -> value.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid() }
                    else -> invalid()
                }
                val constant = entry.flag("constant") ?: false
                val primary = (keys("keys") + keys("key")).filter(String::isNotBlank).distinct()
                val secondary = keys("keysecondary") + keys("secondary_keys")
                val useRegex = entry.flag("use_regex") ?: false
                fun pattern(keys: List<String>) = keys.joinToString("|") { if (useRegex) it else Regex.escape(it) }
                var regex = if (constant) ".*" else pattern(primary)
                if (!constant && primary.isNotEmpty() && secondary.isNotEmpty() && entry.flag("selective") == true) {
                    if (entry.number("selectiveLogic") !in setOf(null, 0))
                        throw StError.Unsupported("unsupported worldbook secondary-key matching mode")
                    regex = "(?s)(?=.*(?:$regex))(?=.*(?:${pattern(secondary)})).*"
                }
                if (!useRegex && entry.flag("caseSensitive") != true && regex.isNotEmpty()) regex = "(?i:$regex)"
                val position = when (entry.number("position")) {
                    null -> null
                    0 -> "before_system_prompt"
                    1 -> "after_system_prompt"
                    4 -> "at_depth"
                    else -> { warnings += "unsupported_position_retained_default_insertion"; null }
                }
                WorldBookEntry(id(), entry.text("comment") ?: entry.text("name") ?: "条目 ${index + 1}",
                    regex, entry.text("content") ?: "", entry.flag("enabled") ?: !(entry.flag("disable") ?: false),
                    entry.number("insertion_order") ?: entry.number("order") ?: index,
                    scanDepth = entry.number("scanDepth"), constantActive = constant,
                    position = position, injectDepth = entry.number("depth") ?: 4,
                    role = when (entry.number("role")) { null, 0 -> ChatRole.system; 1 -> ChatRole.user; 2 -> ChatRole.assistant; else -> invalid() })
            }.also { value ->
                if (value.scanDepth?.let { it < 0 || it > 500 } == true || value.injectDepth !in 0..500 ||
                    value.role !in setOf(ChatRole.system, ChatRole.user, ChatRole.assistant)) invalid()
                if (value.regex.isNotEmpty()) try { Regex(value.regex) } catch (_: Exception) { invalid() }
            }
        }
        val at = Instant.now().toString()
        WorldBookImportResult(WorldBook(id(), raw.text("name")?.takeIf(String::isNotBlank)
            ?: filename.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').ifBlank { "世界书" },
            mapped, createdAt = at, updatedAt = at, extraJson = raw.toString()), if (st) "sillytavern" else "simpletavern", warnings.toList())
    } catch (error: StError) { throw error }
    catch (_: Exception) { throw StError.Validation("invalid worldbook JSON") }

    private fun id() = UUID.randomUUID().toString()
    private fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it == JsonNull }?.let {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid()
    }
    private fun JsonObject.flag(key: String): Boolean? = get(key)?.takeUnless { it == JsonNull }?.let {
        (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull ?: invalid()
    }
    private fun JsonObject.number(key: String): Int? = get(key)?.takeUnless { it == JsonNull }?.let {
        (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.intOrNull ?: invalid()
    }
    private fun invalid(): Nothing = throw StError.Validation("invalid worldbook entries or controls")
}
