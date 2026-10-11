package com.simpletavern.core.importer

import com.simpletavern.core.model.CharacterCard
import com.simpletavern.core.model.CharacterCardImportPreview
import com.simpletavern.core.model.WorldBook
import com.simpletavern.core.model.WorldBookEntry
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull

public object SillyTavernCardMapper {
    public fun map(decoded: DecodedSillyTavernCard): CharacterCardImportPreview {
        val raw = decoded.raw
        val nestedData = raw["data"] as? JsonObject
        val merged = if (nestedData == null) raw else JsonObject(raw + nestedData)

        val description = merged.text("description")
        val scenario = merged.text("scenario")
        val personalityValue = merged.text("personality")
        val personality = if (personalityValue.isBlank() && scenario.isBlank()) description else personalityValue
        val name = merged.text("name").ifBlank { "新角色" }
        val firstMessage = merged.text("first_mes").ifBlank { merged.text("firstMessage") }
        val alternateGreetings = extraFirstMessageEntries(merged["alternate_greetings"])
        val now = Instant.now().toString()
        val worldBook = buildWorldBook(merged["character_book"], name, now)
        val characterId = newId()

        val character = CharacterCard(
            id = characterId,
            name = name,
            description = description,
            personality = personality,
            scenario = scenario,
            firstMessage = firstMessage,
            exampleDialogue = merged.text("mes_example").ifBlank { merged.text("exampleDialogue") },
            systemPrompt = merged.text("system_prompt").ifBlank { merged.text("systemPrompt") },
            attachedWorldBookIds = listOfNotNull(worldBook?.id),
            createdAt = now,
            updatedAt = now,
            extraJson = JsonObject(raw + ("extraFirstMessageEntries" to JsonArray(alternateGreetings))).toString(),
        )

        val warnings = buildList {
            addAll(decoded.warnings)
            if (hasExtension(merged, "regex_scripts")) add("unsupported_extension_retained")
            if (hasExtension(merged, "tavern_helper")) add("unsupported_extension_retained")
        }

        return CharacterCardImportPreview(
            character = character,
            worldBook = worldBook,
            openingMessages = listOf(firstMessage) + alternateGreetings.mapNotNull { it.text("text").takeIf(String::isNotBlank) },
            sourceFormat = decoded.sourceFormat,
            hasEmbeddedAvatar = decoded.sourceFormat == "png",
            warnings = warnings,
        )
    }

    private fun buildWorldBook(rawBook: JsonElement?, cardName: String, now: String): WorldBook? {
        val source = rawBook as? JsonObject ?: return null
        val sourceEntries = source["entries"] as? JsonArray ?: return null
        val entries = sourceEntries.mapIndexedNotNull { index, element ->
            val rawEntry = element as? JsonObject ?: return@mapIndexedNotNull null
            val content = rawEntry.text("content").trim()
            val comment = rawEntry.text("comment").trim()
            val insertionOrder = (rawEntry["insertion_order"] as? JsonPrimitive)?.intOrNull ?: index
            WorldBookEntry(
                id = newId(),
                title = comment.ifBlank { "条目 ${index + 1}" },
                regex = stEntryRegex(rawEntry),
                content = content,
                enabled = (rawEntry["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                orderIndex = insertionOrder,
                constantActive = (rawEntry["constant"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
        if (entries.isEmpty()) return null

        return WorldBook(
            id = newId(),
            name = source.text("name").ifBlank { "$cardName 世界书" },
            entries = entries,
            createdAt = now,
            updatedAt = now,
            extraJson = source.toString(),
        )
    }

    private fun stEntryRegex(entry: JsonObject): String {
        if ((entry["constant"] as? JsonPrimitive)?.booleanOrNull == true) return ".*"
        val keys = (entry["keys"] as? JsonArray)
            ?.mapNotNull { it.asText()?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty()
        if (keys.isEmpty()) return ""
        if ((entry["use_regex"] as? JsonPrimitive)?.booleanOrNull == true) return keys.joinToString("|")
        return keys.joinToString("|") { it.escapeRegexLiteral() }
    }

    private fun extraFirstMessageEntries(value: JsonElement?): List<JsonObject> {
        val greetings = value as? JsonArray ?: return emptyList()
        return greetings.mapNotNull { item ->
            val text = when (item) {
                is JsonObject -> item.text("text").trim()
                else -> item.asText()?.trim().orEmpty()
            }
            text.takeIf(String::isNotEmpty)?.let {
                JsonObject(mapOf("text" to JsonPrimitive(it), "chip" to JsonPrimitive(true)))
            }
        }
    }

    private fun hasExtension(data: JsonObject, key: String): Boolean {
        val extensions = data["extensions"] as? JsonObject
        val value = extensions?.get(key) ?: data[key] ?: return false
        return when (value) {
            is JsonObject -> value.isNotEmpty()
            is JsonArray -> value.isNotEmpty()
            is JsonPrimitive -> value.contentOrNull?.isNotBlank() == true && value.booleanOrNull != false
            else -> true
        }
    }

    private fun JsonObject.text(key: String): String = this[key]?.asText()?.trim().orEmpty()

    private fun JsonElement.asText(): String? = when (this) {
        is JsonPrimitive -> contentOrNull
        else -> null
    }

    private fun String.escapeRegexLiteral(): String = buildString {
        for (character in this@escapeRegexLiteral) {
            if (character in "\\.+*?()|[]{}^$") append('\\')
            append(character)
        }
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "")
}
