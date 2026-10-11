package com.simpletavern.core.importer

import com.simpletavern.core.model.CostSnapshot
import com.simpletavern.core.model.ProviderReportedCost
import com.simpletavern.core.model.StError
import com.simpletavern.core.model.UsageRecordContext
import com.simpletavern.core.model.UsageTokens
import java.math.BigDecimal
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull

internal data class ImportedUsageRecord(
    val eventId: String,
    val requestId: String?,
    val chatId: String?,
    val messageId: String?,
    val provider: String?,
    val model: String?,
    val createdAt: String,
    val tokens: UsageTokens,
    val cost: CostSnapshot,
    val context: UsageRecordContext,
)

internal object DesktopUsageMapper {
    fun map(obj: JsonObject): ImportedUsageRecord {
        val eventId = obj.requiredText("eventId")
        if (obj.long("version") != 1L) invalid("unsupported desktop usage event version")

        val timestamp = obj.optionalText("ts")?.let(::parseTime)
            ?: obj.optionalText("startedAt")?.let(::parseTime)
            ?: invalid("desktop usage event time is invalid")
        val startedAt = obj.optionalText("startedAt")?.let(::parseTime)

        val requestId = obj.optionalText("requestId")
        val chatId = obj.optionalText("chatId")
        val messageId = obj.optionalText("messageId")
        val provider = obj.optionalText("provider")
        val requestedModel = obj.optionalText("requestedModel")
        val resolvedModel = obj.optionalText("resolvedModel")
        val usage = obj.objectValue("usage")
        val tokens = (usage?.let(::tokens) ?: UsageTokens()).copy(resolvedModel = resolvedModel)
        val cost = obj.objectValue("cost")?.let(::cost) ?: unknownCost()

        return ImportedUsageRecord(
            eventId = eventId,
            requestId = requestId,
            chatId = chatId,
            messageId = messageId,
            provider = provider,
            model = resolvedModel ?: requestedModel,
            createdAt = timestamp,
            tokens = tokens,
            cost = cost,
            context = UsageRecordContext(
                protocol = obj.optionalText("protocol"),
                status = obj.optionalText("status"),
                startedAt = startedAt,
                requestedModel = requestedModel,
                resolvedModel = resolvedModel,
                firstTokenLatencyMs = obj.optionalLong("firstTokenLatencyMs"),
                totalDurationMs = obj.optionalLong("totalDurationMs"),
            ),
        )
    }

    private fun tokens(obj: JsonObject): UsageTokens = UsageTokens(
        inputTokens = obj.tokenCount("inputTokens", "input_tokens"),
        outputTokens = obj.tokenCount("outputTokens", "output_tokens"),
        totalTokens = obj.tokenCount("totalTokens", "total_tokens"),
        cacheReadInputTokens = obj.tokenCount("cacheReadInputTokens", "cache_read_input_tokens"),
        cacheWriteInputTokens = obj.tokenCount("cacheWriteInputTokens", "cache_write_input_tokens"),
        reasoningTokens = obj.tokenCount("reasoningTokens", "reasoning_tokens"),
        serviceTier = obj.optionalText("serviceTier") ?: obj.optionalText("service_tier"),
        fastRequested = obj["fastRequested"]?.takeUnless { it.isNull }?.let {
            (it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.booleanOrNull
                ?: invalid("invalid desktop usage fast flag")
        },
    )

    private fun cost(obj: JsonObject): CostSnapshot {
        val source = obj.optionalText("source") ?: "unknown"
        if (source == "unknown") return unknownCost()
        if (source !in setOf("provider", "estimated")) return unknownCost()

        return try {
            val amount = obj.decimalText("amount")
                ?: (if (source == "estimated") obj.decimalText("estimatedAmount") else null)
                ?: return unknownCost()
            val currency = obj.optionalText("currency")
                ?.takeIf { CURRENCY.matches(it) }
                ?: return unknownCost()
            val providerAmount = obj.decimalText("providerAmount")
            val estimatedAmount = obj.decimalText("estimatedAmount")
            val providerReported = if (source == "provider") {
                ProviderReportedCost(providerAmount ?: amount, currency)
            } else null

            CostSnapshot(
                source = source,
                amount = amount,
                currency = currency,
                providerReported = providerReported,
                estimatedAmount = estimatedAmount ?: if (source == "estimated") amount else null,
                // Desktop v1 does not state a separate estimate currency for provider costs.
                estimatedCurrency = if (source == "estimated") currency else null,
                pricingRuleId = obj.optionalText("pricingRuleId"),
            )
        } catch (_: IllegalArgumentException) {
            unknownCost()
        }
    }

    private fun unknownCost() = CostSnapshot(source = "unknown", reason = UNKNOWN_COST_REASON)

    private fun JsonObject.tokenCount(camel: String, snake: String): Long? {
        val element = this[camel]?.takeUnless { it.isNull } ?: this[snake]?.takeUnless { it.isNull } ?: return null
        val primitive = element as? JsonPrimitive ?: invalid("invalid desktop usage token count")
        val value = if (!primitive.isString && primitive.booleanOrNull == null) primitive.longOrNull else null
        return value?.takeIf { it >= 0 } ?: invalid("invalid desktop usage token count")
    }

    private fun JsonObject.optionalLong(key: String): Long? {
        val element = this[key]?.takeUnless { it.isNull } ?: return null
        val primitive = element as? JsonPrimitive ?: invalid("invalid desktop usage duration")
        val value = if (!primitive.isString && primitive.booleanOrNull == null) primitive.longOrNull else null
        return value?.takeIf { it >= 0 } ?: invalid("invalid desktop usage duration")
    }

    private fun JsonObject.decimalText(key: String): String? {
        val element = this[key]?.takeUnless { it.isNull } ?: return null
        val primitive = element as? JsonPrimitive ?: throw IllegalArgumentException()
        if (!primitive.isString && primitive.booleanOrNull != null) throw IllegalArgumentException()
        val text = primitive.content
        if (text.length > 64) throw IllegalArgumentException()
        val decimal = BigDecimal(text)
        if (decimal.signum() < 0 || decimal.precision() > 64 || decimal.scale() !in -12..18) {
            throw IllegalArgumentException()
        }
        return text
    }

    private fun JsonObject.requiredText(key: String): String = optionalText(key)
        ?: invalid("invalid desktop usage event structure")

    private fun JsonObject.long(key: String): Long? {
        val element = this[key]?.takeUnless { it.isNull } ?: return null
        val primitive = element as? JsonPrimitive ?: return null
        return if (!primitive.isString && primitive.booleanOrNull == null) primitive.longOrNull else null
    }

    private fun JsonObject.optionalText(key: String): String? {
        val element = this[key]?.takeUnless { it.isNull } ?: return null
        val primitive = element as? JsonPrimitive ?: invalid("invalid desktop usage event structure")
        if (!primitive.isString) invalid("invalid desktop usage event structure")
        return primitive.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.objectValue(key: String): JsonObject? {
        val element = this[key]?.takeUnless { it.isNull } ?: return null
        return element as? JsonObject ?: invalid("invalid desktop usage event structure")
    }

    private fun parseTime(value: String): String = try {
        OffsetDateTime.parse(value).toInstant().toString()
    } catch (_: java.time.format.DateTimeParseException) {
        invalid("desktop usage event time is invalid")
    }

    private val JsonElement.isNull get() = this == kotlinx.serialization.json.JsonNull

    private fun invalid(message: String): Nothing = throw StError.Validation(message)

    private const val UNKNOWN_COST_REASON = "desktop_import_cost_unreported"
    private val CURRENCY = Regex("[A-Z]{3}")
}
