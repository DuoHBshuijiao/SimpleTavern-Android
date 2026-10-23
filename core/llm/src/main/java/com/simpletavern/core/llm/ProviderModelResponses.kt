package com.simpletavern.core.llm

import com.simpletavern.core.model.LlmProtocol
import com.simpletavern.core.model.ProviderModelDirectoryKind
import com.simpletavern.core.model.ProviderModelInfo
import com.simpletavern.core.model.StError
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object ProviderModelResponses {
    internal data class Parsed(val models: List<ProviderModelInfo>, val nextPageToken: String? = null)

    fun parse(protocol: LlmProtocol, root: JsonObject): Parsed {
        if (root["error"]?.let { it != JsonNull } == true) invalid()
        return when (protocol) {
            LlmProtocol.OPENAI_COMPATIBLE, LlmProtocol.OPENAI_RESPONSES -> parseOpenAi(root)
            LlmProtocol.ANTHROPIC_MESSAGES -> parseAnthropic(root)
            LlmProtocol.GEMINI_GENERATE_CONTENT -> parseGemini(root)
            else -> throw StError.Unsupported("model listing is unsupported for this protocol")
        }
    }

    fun bedrock(root: JsonObject, kind: ProviderModelDirectoryKind): Parsed {
        rejectProviderError(root)
        return when (kind) {
            ProviderModelDirectoryKind.MODELS -> {
                val rows = optionalArray(root, "modelSummaries")
                val models = rows.map { value ->
                    val row = value as? JsonObject ?: invalid()
                    val lifecycle = optionalObject(row, "modelLifecycle")
                    ProviderModelInfo(
                        id = requiredText(row, "modelId", MAX_MODEL_ID_LENGTH),
                        name = optionalText(row, "modelName"),
                        ownedBy = optionalText(row, "providerName"),
                        resourceName = optionalText(row, "modelArn"),
                        status = lifecycle?.let { optionalText(it, "status") },
                        inputModalities = optionalTextArray(row, "inputModalities"),
                        outputModalities = optionalTextArray(row, "outputModalities"),
                        inferenceTypes = optionalTextArray(row, "inferenceTypesSupported"),
                        streamingSupported = optionalBoolean(row, "responseStreamingSupported"),
                    )
                }
                Parsed(models)
            }
            ProviderModelDirectoryKind.BEDROCK_INFERENCE_PROFILES -> {
                val rows = optionalArray(root, "inferenceProfileSummaries")
                val models = rows.map { value ->
                    val row = value as? JsonObject ?: invalid()
                    val referencedModels = optionalArray(row, "models").map { model ->
                        val modelObject = model as? JsonObject ?: invalid()
                        requiredText(modelObject, "modelArn", MAX_MODEL_ID_LENGTH)
                    }
                    ProviderModelInfo(
                        id = requiredText(row, "inferenceProfileId", MAX_MODEL_ID_LENGTH),
                        name = optionalText(row, "inferenceProfileName"),
                        createdAt = optionalAwsCreatedAt(row, "createdAt"),
                        resourceName = optionalText(row, "inferenceProfileArn"),
                        status = optionalText(row, "status"),
                        referencedModels = referencedModels,
                        profileType = optionalText(row, "type"),
                    )
                }
                Parsed(models, optionalText(root, "nextToken", MAX_PAGE_TOKEN_LENGTH)?.let(::checkedPageToken))
            }
        }
    }

    fun vertex(root: JsonObject): Parsed {
        rejectProviderError(root)
        val models = optionalArray(root, "publisherModels").map { value ->
            val row = value as? JsonObject ?: invalid()
            val resourceName = requiredText(row, "name", MAX_TEXT_LENGTH)
            if (!resourceName.startsWith(VERTEX_MODEL_PREFIX)) invalid()
            val id = resourceName.removePrefix(VERTEX_MODEL_PREFIX)
            if ('/' in id) invalid()
            checkedText(id, MAX_MODEL_ID_LENGTH)
            ProviderModelInfo(
                id = id,
                name = optionalText(row, "displayName"),
                resourceName = resourceName,
                version = optionalText(row, "versionId"),
                status = optionalText(row, "versionState"),
            )
        }
        val next = optionalText(root, "nextPageToken", MAX_PAGE_TOKEN_LENGTH)?.let(::checkedPageToken)
        return Parsed(models, next)
    }

    fun chatgpt(root: JsonObject): Parsed {
        val rows = root["models"] as? JsonArray ?: invalid()
        return Parsed(rows.mapNotNull { value ->
            val row = value as? JsonObject ?: invalid()
            if (optionalText(row, "visibility") != "list") null else ProviderModelInfo(
                id = requiredText(row, "slug", MAX_MODEL_ID_LENGTH), name = optionalText(row, "display_name"))
        })
    }

    private fun parseOpenAi(root: JsonObject): Parsed {
        val rows = requiredArray(root, "data")
        val models = rows.map { value ->
            val row = value as? JsonObject ?: invalid()
            ProviderModelInfo(
                id = requiredText(row, "id", MAX_MODEL_ID_LENGTH),
                name = optionalText(row, "name") ?: optionalText(row, "display_name"),
                createdAt = optionalCreatedAt(row, "created"),
                ownedBy = optionalText(row, "owned_by"),
            )
        }
        return Parsed(models)
    }

    private fun parseAnthropic(root: JsonObject): Parsed {
        val rows = requiredArray(root, "data")
        val models = rows.map { value ->
            val row = value as? JsonObject ?: invalid()
            ProviderModelInfo(
                id = requiredText(row, "id", MAX_MODEL_ID_LENGTH),
                name = optionalText(row, "display_name"),
                createdAt = optionalText(row, "created_at"),
                inputTokenLimit = optionalNonNegativeLong(row, "max_input_tokens"),
                outputTokenLimit = optionalNonNegativeLong(row, "max_tokens"),
            )
        }
        val hasMore = optionalBoolean(root, "has_more") ?: false
        if (!hasMore) return Parsed(models)

        val lastId = optionalText(root, "last_id", MAX_PAGE_TOKEN_LENGTH)
            ?: models.lastOrNull()?.id
            ?: invalid()
        return Parsed(models, checkedPageToken(lastId))
    }

    private fun parseGemini(root: JsonObject): Parsed {
        val rows = when (val value = root["models"]) {
            null -> JsonArray(emptyList())
            is JsonArray -> value
            else -> invalid()
        }
        if (rows.size > MAX_MODELS) invalid()
        val models = rows.map { value ->
            val row = value as? JsonObject ?: invalid()
            val resourceName = requiredText(row, "name", MAX_MODEL_ID_LENGTH + MODEL_PREFIX.length)
            if (!resourceName.startsWith(MODEL_PREFIX)) invalid()
            val id = resourceName.removePrefix(MODEL_PREFIX)
            checkedText(id, MAX_MODEL_ID_LENGTH)
            ProviderModelInfo(
                id = id,
                name = optionalText(row, "displayName"),
                inputTokenLimit = optionalNonNegativeLong(row, "inputTokenLimit"),
                outputTokenLimit = optionalNonNegativeLong(row, "outputTokenLimit"),
                supportedGenerationMethods = optionalTextArray(row, "supportedGenerationMethods"),
            )
        }
        val next = optionalText(root, "nextPageToken", MAX_PAGE_TOKEN_LENGTH)?.let(::checkedPageToken)
        return Parsed(models, next)
    }

    private fun requiredArray(root: JsonObject, key: String): JsonArray =
        (root[key] as? JsonArray)?.also { if (it.size > MAX_MODELS) invalid() } ?: invalid()

    private fun optionalArray(root: JsonObject, key: String): JsonArray {
        val value = root[key] ?: return JsonArray(emptyList())
        if (value == JsonNull) return JsonArray(emptyList())
        return (value as? JsonArray)?.also { if (it.size > MAX_MODELS) invalid() } ?: invalid()
    }

    private fun optionalObject(root: JsonObject, key: String): JsonObject? {
        val value = root[key] ?: return null
        if (value == JsonNull) return null
        return value as? JsonObject ?: invalid()
    }

    private fun rejectProviderError(root: JsonObject) {
        if (root["error"]?.let { it != JsonNull } == true ||
            root["__type"]?.let { it != JsonNull } == true
        ) {
            invalid()
        }
    }

    private fun requiredText(obj: JsonObject, key: String, maxLength: Int = MAX_TEXT_LENGTH): String =
        optionalText(obj, key, maxLength) ?: invalid()

    private fun optionalText(obj: JsonObject, key: String, maxLength: Int = MAX_TEXT_LENGTH): String? {
        val value = obj[key] ?: return null
        if (value == JsonNull) return null
        val primitive = value as? JsonPrimitive ?: invalid()
        if (!primitive.isString) invalid()
        if (primitive.content.isBlank()) return null
        return checkedText(primitive.content, maxLength)
    }

    private fun checkedText(value: String, maxLength: Int): String {
        if (value.isBlank() || value.length > maxLength || value.any(Char::isISOControl)) invalid()
        return value
    }

    private fun optionalNonNegativeLong(obj: JsonObject, key: String): Long? {
        val value = obj[key] ?: return null
        if (value == JsonNull) return null
        val primitive = value as? JsonPrimitive ?: invalid()
        if (primitive.isString) invalid()
        return primitive.content.toLongOrNull()?.takeIf { it >= 0 } ?: invalid()
    }

    private fun optionalCreatedAt(obj: JsonObject, key: String): String? {
        val value = obj[key] ?: return null
        if (value == JsonNull) return null
        val primitive = value as? JsonPrimitive ?: invalid()
        if (primitive.isString) invalid()
        val seconds = primitive.content.toLongOrNull()?.takeIf { it >= 0 } ?: invalid()
        return try {
            Instant.ofEpochSecond(seconds).toString()
        } catch (_: DateTimeException) {
            invalid()
        }
    }

    private fun optionalAwsCreatedAt(obj: JsonObject, key: String): String? {
        val value = obj[key] ?: return null
        if (value == JsonNull) return null
        val primitive = value as? JsonPrimitive ?: invalid()
        if (primitive.isString) return checkedText(primitive.content, MAX_TEXT_LENGTH)
        // AWS REST JSON timestamps may include fractional epoch seconds.
        val seconds = primitive.content.takeIf { it.length <= 64 }?.toBigDecimalOrNull() ?: invalid()
        if (seconds.signum() < 0 || seconds.scale() !in -12..9) invalid()
        return try {
            val whole = seconds.toBigInteger().longValueExact()
            val nanos = seconds.remainder(BigDecimal.ONE).movePointRight(9).longValueExact()
            Instant.ofEpochSecond(whole, nanos).toString()
        } catch (_: ArithmeticException) {
            invalid()
        } catch (_: DateTimeException) {
            invalid()
        }
    }

    private fun optionalBoolean(obj: JsonObject, key: String): Boolean? {
        val value = obj[key] ?: return null
        if (value == JsonNull) return null
        val primitive = value as? JsonPrimitive ?: invalid()
        if (primitive.isString) invalid()
        return when (primitive.content) {
            "true" -> true
            "false" -> false
            else -> invalid()
        }
    }

    private fun optionalTextArray(obj: JsonObject, key: String): List<String> {
        val value = obj[key] ?: return emptyList()
        if (value == JsonNull) return emptyList()
        val array = value as? JsonArray ?: invalid()
        if (array.size > MAX_METHODS) invalid()
        return array.map { item ->
            val primitive = item as? JsonPrimitive ?: invalid()
            if (!primitive.isString) invalid()
            checkedText(primitive.content, MAX_TEXT_LENGTH)
        }
    }

    private fun checkedPageToken(value: String): String {
        if (value.isEmpty() || value.length > MAX_PAGE_TOKEN_LENGTH || value.any(Char::isISOControl)) invalid()
        return value
    }

    private fun invalid(): Nothing = throw StError.Provider("invalid provider model listing response")

    private const val MAX_MODELS = 10_000
    private const val MAX_METHODS = 256
    private const val MAX_MODEL_ID_LENGTH = 2_048
    private const val MAX_PAGE_TOKEN_LENGTH = 8_192
    private const val MAX_TEXT_LENGTH = 8_192
    private const val MODEL_PREFIX = "models/"
    private const val VERTEX_MODEL_PREFIX = "publishers/google/models/"
}
