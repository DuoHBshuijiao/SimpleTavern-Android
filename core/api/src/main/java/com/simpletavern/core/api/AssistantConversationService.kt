package com.simpletavern.core.api

import com.simpletavern.core.data.*
import com.simpletavern.core.model.*
import com.simpletavern.core.tools.ToolResult
import kotlinx.serialization.json.*

/** On-demand source history reference. It does not inject history or execute stored calls. */
internal class AssistantConversationService(private val content: ContentRepository, private val chats: ChatRepository) {
    private suspend fun configuration(assistantId: String) = content.getAssistant(assistantId)
        ?: throw StError.NotFound("assistant configuration")

    private fun namespace(config: AssistantConfig): String = config.sourceNamespace
        ?.takeIf { it.substringBefore(':') == "rikka_hub" }
        ?: throw StError.Unsupported("assistant has no RikkaHub conversation source")

    fun enabled(config: AssistantConfig): Boolean = config.sourceNamespace?.substringBefore(':') == "rikka_hub" &&
        (Jsonx.decode<JsonObject>(config.settingsJson)["enableRecentChatsReference"] as? JsonPrimitive)?.booleanOrNull == true

    suspend fun recent(assistantId: String, limit: Int = 10): List<ConversationReferenceSummary> {
        val config = configuration(assistantId)
        return chats.recentSourceConversations(namespace(config), config.id, content.sourceAssistantCharacterId(config), limit)
    }

    suspend fun search(assistantId: String, query: String, limit: Int = 15, offset: Int = 0): ConversationReferenceSearchPage =
        chats.searchSourceConversations(namespace(configuration(assistantId)), query, limit, offset)

    suspend fun read(assistantId: String, chatId: String, afterOrder: Long = -1, limit: Int = 100): ConversationReferencePage = chats.transaction {
        require(limit in 1..200)
        val source = namespace(configuration(assistantId))
        val chat = chats.getSession(chatId)?.takeIf { it.sourceNamespace == source } ?: throw StError.NotFound("source conversation")
        val rows = chats.selectedMessagePage(chatId, afterOrder, Long.MAX_VALUE, 0, limit + 1)
        var bytes = 0
        val visible = rows.take(limit).map { row ->
            ConversationReferenceMessage(row.candidate.id, row.orderIndex, row.candidate.role, row.candidate.content)
        }.takeWhile { row ->
            bytes += Jsonx.encode(row).toByteArray(Charsets.UTF_8).size
            bytes <= 800_000
        }
        if (visible.isEmpty() && rows.isNotEmpty()) throw StError.Validation("one conversation message exceeds read budget")
        ConversationReferencePage(chat.id, chat.title, visible, rows.size > visible.size, visible.lastOrNull()?.orderIndex)
    }

    val categories = listOf("recent_chats", "conversation_search", "conversation_read").associateWith { ApprovalCategory.READ }
    val definitions = JsonArray(buildList {
        fun tool(name: String, description: String, fields: Map<String, String>, required: List<String> = emptyList()) = buildJsonObject {
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
        add(tool("recent_chats", "列出本助手的近期会话标题、ID、更新时间，置顶优先；limit 默认10，最多30。不自动读取正文。", mapOf("limit" to "integer")))
        add(tool("conversation_search", "搜索同来源历史选中用户/助手正文；query 是空格分隔的字面关键词，须全部命中，不是正则或 SQL。返回带匹配标记的片段和消息ID；limit默认15最多50，offset分页。",
            mapOf("query" to "string", "limit" to "integer", "offset" to "integer"), listOf("query")))
        add(tool("conversation_read", "按 conversation_id 分页读取同来源会话的选中原文；不执行旧工具。afterOrder 使用 nextAfterOrder，limit默认100最多200。",
            mapOf("conversation_id" to "string", "afterOrder" to "integer", "limit" to "integer"), listOf("conversation_id")))
    })

    suspend fun dispatch(config: AssistantConfig, name: String, args: JsonObject): ToolResult {
        val current = configuration(config.id)
        if (current.sourceNamespace != config.sourceNamespace || !enabled(current))
            throw StError.Conflict("assistant conversation reference selection or enabled state changed")
        fun text(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull ?: throw StError.Validation("$key is required")
        fun count(default: Int, max: Int) = ((args["limit"] as? JsonPrimitive)?.intOrNull ?: default).also {
            if (it !in 1..max) throw StError.Validation("conversation limit must be between 1 and $max")
        }
        val data = when (name) {
            "recent_chats" -> JsonArray(recent(config.id, count(10, 30)).map { row -> buildJsonObject {
                put("id", row.id); put("title", row.title.ifBlank { "Untitled" }); put("last_chat", row.updatedAt)
                put("pinned", row.pinned)
            } }).toString()
            "conversation_search" -> Jsonx.encode(search(config.id, text("query"), count(15, 50),
                ((args["offset"] as? JsonPrimitive)?.intOrNull ?: 0).also { if (it < 0) throw StError.Validation("offset must not be negative") }))
            "conversation_read" -> Jsonx.encode(read(config.id, text("conversation_id"), (args["afterOrder"] as? JsonPrimitive)?.longOrNull ?: -1, count(100, 200)))
            else -> throw StError.Unsupported("unknown conversation reference tool")
        }
        val output = buildJsonObject { put("ok", true); put("tool", name); put("data", Jsonx.json.parseToJsonElement(data)) }.toString()
        if (output.toByteArray(Charsets.UTF_8).size > 1_000_000) throw StError.Validation("conversation result exceeds text budget; read a smaller page")
        return ToolResult(output, buildJsonObject { put("toolName", name); put("ok", true); put("sourceNamespace", namespace(config)) }.toString())
    }
}
