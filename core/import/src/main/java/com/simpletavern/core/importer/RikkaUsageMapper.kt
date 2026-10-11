package com.simpletavern.core.importer

import com.simpletavern.core.model.CostSnapshot
import com.simpletavern.core.model.StError
import com.simpletavern.core.model.UsageRecordContext
import com.simpletavern.core.model.UsageTokens
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

internal object RikkaUsageMapper {
    fun map(
        message: JsonObject,
        sourceChatId: String,
        provider: String?,
        model: String?,
        protocol: String?,
        localZone: ZoneId,
    ): ImportedUsageRecord? {
        val role = message.string("role")?.lowercase()
        if (role !in setOf("assistant", "model")) return null
        val rawUsage = message["usage"]?.takeUnless { it == JsonNull } ?: return null
        val usage = rawUsage as? JsonObject ?: invalid("invalid RikkaHub usage structure")
        val messageId = message.string("id")
            ?.takeIf { it.isNotBlank() }
            ?: invalid("RikkaHub message ID is missing or invalid")
        val createdAt = (message["createdAt"] ?: message["create_at"])?.let { parseTime(it, localZone) }
            ?: invalid("RikkaHub message creation time is missing or invalid")
        val finishedAt = message.string("finishedAt")
        val tokens = UsageTokens(
            inputTokens = usage.tokenCount("promptTokens"),
            outputTokens = usage.tokenCount("completionTokens"),
            cacheReadInputTokens = usage.tokenCount("cachedTokens"),
            totalTokens = usage.tokenCount("totalTokens"),
        )

        return ImportedUsageRecord(
            eventId = "message:$messageId",
            requestId = null,
            chatId = sourceChatId,
            messageId = messageId,
            provider = provider,
            model = model,
            createdAt = createdAt,
            tokens = tokens,
            cost = CostSnapshot(source = "unknown", reason = "rikka_import_cost_unreported"),
            context = UsageRecordContext(
                protocol = protocol,
                status = if (finishedAt.isNullOrBlank()) "source_unfinished" else "source_finished",
                requestedModel = model,
                purpose = "rikka_import",
            ),
        )
    }

    private fun JsonObject.tokenCount(key: String): Long? {
        val element = this[key] ?: return null
        val primitive = element as? JsonPrimitive ?: invalid("invalid RikkaHub usage token count")
        val value = if (!primitive.isString && primitive.booleanOrNull == null) primitive.longOrNull else null
        return value?.takeIf { it >= 0 } ?: invalid("invalid RikkaHub usage token count")
    }

    private fun parseTime(element: JsonElement, localZone: ZoneId): String {
        val primitive = element as? JsonPrimitive
            ?: invalid("RikkaHub message creation time is invalid")
        if (primitive.isString) {
            val value = primitive.content
            value.toLongOrNull()?.let { return Instant.ofEpochMilli(it).toString() }
            try {
                return OffsetDateTime.parse(value).toInstant().toString()
            } catch (_: DateTimeParseException) {
                try {
                    return LocalDateTime.parse(value).atZone(localZone).toInstant().toString()
                } catch (_: DateTimeParseException) {
                    invalid("RikkaHub message creation time is invalid")
                }
            }
        }
        if (primitive.booleanOrNull != null) invalid("RikkaHub message creation time is invalid")
        val epochMillis = primitive.longOrNull
            ?: invalid("RikkaHub message creation time is invalid")
        return Instant.ofEpochMilli(epochMillis).toString()
    }

    private fun JsonObject.string(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.takeIf { it.isString }?.content
    }

    private fun invalid(message: String): Nothing = throw StError.Validation(message)
}
