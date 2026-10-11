package com.simpletavern.core.memory

import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.model.*
import java.util.UUID
import kotlinx.serialization.json.*

/** Fixed chat/carrier scope. Methods only change an immutable task draft, never repositories. */
internal class StateRegexTools(private val initial: StateRegexDraft, private val globalRuleIds: Set<String>) {
    var state = initial
        private set

    fun restore(snapshot: StateRegexDraft) { state = snapshot }

    private fun rules(raw: String?): List<JsonObject> {
        if (raw == null) return emptyList()
        val array = Jsonx.json.parseToJsonElement(raw) as? JsonArray ?: throw StError.Validation("rules must be an array")
        if (array.size > 100) throw StError.Validation("rule limit is 100")
        val objects = array.map { it as? JsonObject ?: throw StError.Validation("rule must be an object") }
        val ids = objects.map { Jsonx.decode<ContentRegexRule>(it.toString()).id }
        if (ids.any { it.isBlank() } || ids.distinct().size != ids.size) throw StError.Validation("rules contain missing or duplicate IDs")
        return objects
    }

    fun manage(character: Boolean, args: JsonObject): JsonObject {
        if (args.keys.any { it !in setOf("operation", "rule_id", "rule") }) throw StError.Validation("unknown rule tool argument")
        val operation = args.string("operation").trim().lowercase()
        val raw = if (character) state.characterRulesJson else state.sessionRulesJson
        val previous = rules(raw)
        val next: List<JsonObject>
        var deletedId: String? = null
        when (operation) {
            "list" -> {
                if ("rule_id" in args || "rule" in args) throw StError.Validation("list only accepts operation")
                next = previous
            }
            "delete" -> {
                if ("rule" in args) throw StError.Validation("delete does not accept rule")
                deletedId = args.string("rule_id").trim()
                if (deletedId.isEmpty() || previous.none { it.id() == deletedId }) throw StError.NotFound("rule not found")
                next = renumber(previous.filterNot { it.id() == deletedId })
            }
            "upsert" -> {
                if ("rule_id" in args) throw StError.Validation("upsert takes the ID inside rule")
                val patch = args["rule"] as? JsonObject ?: throw StError.Validation("rule must be an object")
                validatePatch(patch)
                val requestedId = patch["id"]?.let { (it as JsonPrimitive).content.trim() }.orEmpty()
                val existing = if (requestedId.isNotEmpty()) previous.firstOrNull { it.id() == requestedId }
                    ?: throw StError.NotFound("rule ID not found for upsert") else null
                if (existing == null && previous.size >= 100) throw StError.Validation("rule limit is 100")
                val ruleId = existing?.id() ?: UUID.randomUUID().toString().replace("-", "")
                val merged = JsonObject(existing.orEmpty() + patch + ("id" to JsonPrimitive(ruleId)))
                val typed = Jsonx.decode<ContentRegexRule>(merged.toString())
                ContentRegexEngine.validate(typed)
                // Defaults are materialized for model reads. Unknown imported fields survive partial edits.
                val normalized = JsonObject(merged + Jsonx.json.parseToJsonElement(Jsonx.encode(typed)).jsonObject)
                next = renumber(previous.filterNot { it.id() == ruleId } + normalized)
            }
            else -> throw StError.Validation("operation must be list, upsert or delete")
        }
        val nextRaw = if (operation == "list") raw else JsonArray(next).toString()
        if ((nextRaw?.length ?: 0) > 80_000) throw StError.Validation("rule draft exceeds budget")
        state = if (character) state.copy(characterRulesJson = nextRaw, characterRead = true,
            characterChanged = nextRaw != initial.characterRulesJson) else state.copy(sessionRulesJson = nextRaw,
            sessionRead = true, sessionChanged = nextRaw != initial.sessionRulesJson)
        return buildJsonObject {
            if (character) put("characterId", state.characterId)
            put("rules", Jsonx.json.parseToJsonElement(Jsonx.encode(next.map { Jsonx.decode<ContentRegexRule>(it.toString()) })))
            put("count", next.size)
            put("shadowedByGlobalRuleIds", JsonArray(next.map { it.id() }.filter { it in globalRuleIds }.map { JsonPrimitive(it) }))
            put("scope", if (character) "carrier_character" else "current_chat")
            if (!character) put("inheritsCharacterRules", nextRaw == null)
            deletedId?.let { put("deletedId", it) }
            put("draftOnly", true)
        }
    }

    private fun JsonObject.id() = Jsonx.decode<ContentRegexRule>(toString()).id
    private fun renumber(items: List<JsonObject>): List<JsonObject> = items.sortedWith(compareBy(
        { Jsonx.decode<ContentRegexRule>(it.toString()).order }, { it.id() })).mapIndexed { index, rule ->
        JsonObject(rule + ("order" to JsonPrimitive(index)))
    }
    private fun JsonObject.string(name: String) = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw StError.Validation("$name must be a string")

    private fun validatePatch(patch: JsonObject) {
        val strings = setOf("id", "name", "pattern", "action", "replacement", "matchMode", "extractSource", "engine")
        val integers = setOf("order", "scanDepthOverride", "extractGroupIndex")
        val booleans = setOf("enabled", "visualOnly", "applyToReasoning", "scanAllHistory", "skipOpeningMessage")
        if (patch.keys.any { it !in strings + integers + booleans + "roles" }) throw StError.Validation("unknown rule field")
        patch.forEach { (key, value) ->
            if (key == "roles") {
                val roles = value as? JsonArray ?: throw StError.Validation("roles must be an array")
                if (roles.any { (it as? JsonPrimitive)?.contentOrNull !in setOf("user", "assistant") })
                    throw StError.Validation("roles must contain user or assistant")
                return@forEach
            }
            val nullable = key in setOf("name", "replacement", "scanDepthOverride", "extractGroupIndex")
            if (value == JsonNull && nullable) return@forEach
            val p = value as? JsonPrimitive ?: throw StError.Validation("invalid $key")
            when {
                key in strings -> if (!p.isString) throw StError.Validation("$key must be a string")
                key in integers -> if (p.isString || p.intOrNull == null) throw StError.Validation("$key must be an integer")
                key in booleans -> if (p.isString || p.booleanOrNull == null) throw StError.Validation("$key must be a boolean")
            }
        }
    }

    companion object {
        fun parameters(): JsonObject = buildJsonObject {
            put("operation", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("list", "upsert", "delete").map { JsonPrimitive(it) })) })
            put("rule_id", buildJsonObject { put("type", "string"); put("description", "Required for delete; obtain ID from list.") })
            put("rule", buildJsonObject {
                put("type", "object"); put("additionalProperties", false)
                put("description", "Partial rule update: include existing id. Default engine re2; kotlin uses full Kotlin replacement syntax with replace/global only.")
                put("properties", buildJsonObject {
                    listOf("id", "pattern").forEach { put(it, buildJsonObject { put("type", "string") }) }
                    listOf("name", "replacement").forEach { put(it, buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null")))) }) }
                    put("enabled", buildJsonObject { put("type", "boolean") })
                    listOf("visualOnly", "applyToReasoning", "scanAllHistory", "skipOpeningMessage").forEach {
                        put(it, buildJsonObject { put("type", "boolean") })
                    }
                    put("engine", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("re2", "kotlin").map(::JsonPrimitive))) })
                    put("roles", buildJsonObject { put("type", "array"); put("items", buildJsonObject {
                        put("type", "string"); put("enum", JsonArray(listOf("user", "assistant").map(::JsonPrimitive)))
                    }) })
                    put("order", buildJsonObject { put("type", "integer") })
                    listOf("action" to listOf("remove", "replace", "extract", "extract_and_replace"),
                        "matchMode" to listOf("first", "global"), "extractSource" to listOf("whole_match", "capture_group")).forEach { (key, values) ->
                        put(key, buildJsonObject { put("type", "string"); put("enum", JsonArray(values.map { JsonPrimitive(it) })) })
                    }
                    put("scanDepthOverride", buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("integer"), JsonPrimitive("null")))); put("minimum", 1); put("maximum", 500) })
                    put("extractGroupIndex", buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("integer"), JsonPrimitive("null")))); put("minimum", 0) })
                })
            })
        }
    }
}
