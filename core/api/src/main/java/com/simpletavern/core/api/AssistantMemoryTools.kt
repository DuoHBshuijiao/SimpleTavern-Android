package com.simpletavern.core.api

import com.simpletavern.core.data.ContentRepository
import com.simpletavern.core.data.DataOperationGate
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.model.*
import com.simpletavern.core.tools.AssistantToolSession
import com.simpletavern.core.tools.ToolResult
import java.util.UUID
import kotlinx.serialization.json.*

/** Source memory uses the existing request-local tool runtime and approval queue. */
internal class AssistantMemoryTools(
    private val content: ContentRepository,
    private val gate: DataOperationGate,
    private val pool: AssistantMemoryPool,
    private val allowWrite: Boolean,
    private val allowDelete: Boolean,
) {
    val categories = buildMap {
        put("memory_list", ApprovalCategory.READ)
        if (allowWrite) put("memory_tool", ApprovalCategory.WRITE)
    }
    val definitions = JsonArray(buildList {
        fun tool(name: String, description: String, fields: Map<String, String>, required: List<String>) = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name); put("description", description)
                put("parameters", buildJsonObject {
                    put("type", "object"); put("additionalProperties", false)
                    put("properties", JsonObject(fields.mapValues { (_, type) -> buildJsonObject { put("type", type) } }))
                    put("required", JsonArray(required.map(::JsonPrimitive)))
                })
            })
        }
        add(tool("memory_list", "分页读取当前助手选择的个人或同来源全局记忆池，返回稳定字符串 ID 和原文；翻页使用 nextAfterId。修改前先读取。",
            mapOf("afterId" to "string", "limit" to "integer"), emptyList()))
        if (allowWrite) add(tool("memory_tool", "操作当前记忆池：action=create/edit${if (allowDelete) "/delete" else ""}。create 需 content；edit 需字符串 id、content、expectedContent；delete 需 id、expectedContent。expectedContent 是 memory_list 返回的完整原文；不操作其他池。",
            mapOf("action" to "string", "id" to "string", "content" to "string", "expectedContent" to "string"), listOf("action")))
    })

    fun category(name: String, args: JsonObject): ApprovalCategory? = when (name) {
        "memory_list" -> ApprovalCategory.READ
        "memory_tool" -> when (action(args)) {
            "create" -> ApprovalCategory.WRITE
            else -> ApprovalCategory.EDIT
        }
        else -> null
    }

    private fun action(args: JsonObject): String {
        val action = required(args, "action")
        if (!allowWrite || action !in listOf("create", "edit", "delete") || (action == "delete" && !allowDelete))
            throw StError.Validation("memory action is not enabled")
        return action
    }

    private fun required(args: JsonObject, key: String): String =
        (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw StError.Validation("$key is required")

    suspend fun dispatch(name: String, args: JsonObject): ToolResult = gate.write {
        if (content.getAssistantMemoryPool(pool.assistantId) != pool)
            throw StError.Conflict("assistant memory selection or enabled state changed")
        val data = when (name) {
            "memory_list" -> {
                val limit = (args["limit"] as? JsonPrimitive)?.intOrNull ?: 100
                if (limit !in 1..200) throw StError.Validation("memory limit must be between 1 and 200")
                Jsonx.encode(content.listAssistantMemories(pool, (args["afterId"] as? JsonPrimitive)?.content.orEmpty(), limit))
            }
            "memory_tool" -> when (action(args)) {
                "create" -> Jsonx.encode(content.createAssistantMemory(pool, required(args, "content")))
                "edit" -> Jsonx.encode(content.updateAssistantMemory(pool, required(args, "id"), required(args, "content"), required(args, "expectedContent")))
                else -> buildJsonObject { put("deleted", content.deleteAssistantMemory(pool, required(args, "id"), required(args, "expectedContent"))) }.toString()
            }
            else -> throw StError.Unsupported("unknown assistant memory tool")
        }
        val output = buildJsonObject { put("ok", true); put("tool", name); put("data", Jsonx.json.parseToJsonElement(data)) }.toString()
        if (output.toByteArray(Charsets.UTF_8).size > 1_000_000) throw StError.Validation("memory tool result exceeds text budget; read a smaller page")
        ToolResult(output, buildJsonObject { put("toolName", name); put("ok", true); put("assistantId", pool.assistantId) }.toString())
    }

    fun session(beforeCall: suspend () -> Unit): AssistantToolSession =
        AssistantToolSession(UUID.randomUUID().toString(), definitions, categories, beforeCall,
            categoryResolver = ::category, dispatch = ::dispatch)
}
