package com.simpletavern.core.tools

import com.simpletavern.core.model.ApprovalCategory
import com.simpletavern.core.model.StError
import kotlinx.serialization.json.*

/** Request-local definitions and dispatch, subject to the ordinary tool approval queue. */
class AssistantToolSession(
    val identity: String,
    val definitions: JsonArray,
    val categories: Map<String, ApprovalCategory>,
    private val beforeCall: suspend () -> Unit,
    private val remoteSchemaTools: Set<String> = emptySet(),
    private val forceApprovalTools: Set<String> = emptySet(),
    private val onClose: suspend () -> Unit = {},
    private val beforeUserQuestion: (suspend () -> Unit)? = null,
    private val categoryResolver: ((String, JsonObject) -> ApprovalCategory?)? = null,
    private val dispatch: suspend (String, JsonObject) -> ToolResult,
) {
    fun approvalCategory(name: String, arguments: JsonObject): ApprovalCategory? =
        categoryResolver?.invoke(name, arguments) ?: categories[name]
    private val schemas = definitions.associate { spec ->
        val fn = spec.jsonObject["function"]!!.jsonObject
        fn["name"]!!.jsonPrimitive.content to fn["parameters"]!!.jsonObject
    }
    internal fun validateArguments(name: String, args: JsonObject) {
        if (name in remoteSchemaTools) return // Preserve full remote JSON Schema; the MCP server validates it.
        val schema = schemas[name] ?: return // Separately permission-bound web_search retains its own parser.
        val fields = schema["properties"]!!.jsonObject
        if (args.keys.any { it !in fields } || schema["required"]!!.jsonArray.any { args[it.jsonPrimitive.content] in setOf(null, JsonNull) })
            throw StError.Validation("assistant tool arguments do not match declared fields")
        for ((key, value) in args) {
            val primitive = value as? JsonPrimitive
            val valid = when (fields.getValue(key).jsonObject["type"]!!.jsonPrimitive.content) {
                "string" -> primitive?.isString == true
                "integer" -> primitive != null && !primitive.isString && primitive.longOrNull != null
                "number" -> primitive != null && !primitive.isString && primitive.doubleOrNull?.isFinite() == true
                "boolean" -> primitive != null && !primitive.isString && primitive.booleanOrNull != null
                "object" -> value is JsonObject
                "array" -> value is JsonArray && value.all { item ->
                    when (fields.getValue(key).jsonObject["items"]?.jsonObject?.get("type")?.jsonPrimitive?.content) {
                        "string" -> (item as? JsonPrimitive)?.isString == true
                        "object" -> item is JsonObject
                        else -> true
                    }
                }
                else -> false
            }
            if (!valid) throw StError.Validation("invalid assistant tool field type")
        }
    }
    internal suspend fun requireScope() = beforeCall()
    internal val handlesUserQuestions: Boolean get() = beforeUserQuestion != null
    internal suspend fun requireUserQuestionScope() {
        requireScope()
        beforeUserQuestion?.invoke()
    }
    internal fun forcesApproval(name: String) = name in forceApprovalTools
    suspend fun close() = onClose()
    internal suspend fun execute(name: String, arguments: JsonObject): ToolResult = try { dispatch(name, arguments) }
    catch (error: StError) {
        val output = buildJsonObject { put("ok", false); put("tool", name); put("code", error.code); put("message", error.message) }.toString()
        ToolResult(output, buildJsonObject { put("toolName", name); put("ok", false); put("code", error.code) }.toString())
    }
}
