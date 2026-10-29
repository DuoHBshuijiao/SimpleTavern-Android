package com.simpletavern.core.api

import android.content.Context
import com.simpletavern.core.conversation.AssistantGenerationPlan
import com.simpletavern.core.data.*
import com.simpletavern.core.llm.ProviderConfig
import com.simpletavern.core.memory.ContextBuilder
import com.simpletavern.core.memory.MemoryService
import com.simpletavern.core.model.*
import com.simpletavern.core.tools.AssistantToolSession
import com.simpletavern.core.tools.ToolResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.json.*

/** Ordinary assistant conversations reuse chat persistence, generation tasks and approval, not another agent engine. */
internal class AssistantService(private val content: ContentRepository, private val chats: ChatRepository,
    private val memory: MemoryService, private val contexts: ContextBuilder, private val providers: ProviderResolver,
    private val gate: DataOperationGate, private val workspace: AssistantWorkspaceService, private val mcp: AssistantMcpService,
    private val skills: AssistantSkillService, private val appContext: Context, private val deviceTools: AssistantDeviceTools,
    private val speech: AssistantSpeechService) {
    private val library = ContentLibraryService(content, chats)
    private val conversationReferences = AssistantConversationService(content, chats)
    suspend fun create(binding: AssistantChatBinding, title: String, sandboxId: String?): ChatSession {
        val config = configuration(binding.assistantId, createDefault = true)
        binding.targetChatId?.let { target ->
            val chat = chats.getSession(target) ?: throw StError.NotFound("assistant target chat")
            if (chat.overrides.assistant != null) throw StError.Validation("bind an ordinary main chat, not an assistant chat")
        }
        if (binding.sandboxToolsEnabled && sandboxId == null) throw StError.Validation("assistant sandbox tools require a selected sandbox")
        val at = now()
        val settings = Jsonx.decode<JsonObject>(config.settingsJson)
        val initial = settings["chatDefaults"]?.let { Jsonx.decode<ChatCreationDefaults>(it.toString()).initialMessages }.orEmpty()
        return chats.transaction {
            val chat = ChatSession(id(), "", title = title, sandboxId = sandboxId,
                overrides = ChatOverrides(assistant = binding), createdAt = at, updatedAt = at, sourceNamespace = config.sourceNamespace)
            chats.saveSession(chat)
            for (message in initial) chats.appendNode(chat.id, message.copy(id = id(), characterId = null,
                toolCallsJson = null, toolCallId = null, generationMetadataJson = null))
            chats.getSession(chat.id)!!
        }
    }

    private suspend fun configuration(id: String, createDefault: Boolean = false): AssistantConfig {
        val config = content.getAssistant(id)
            ?: if (id == "default" && createDefault) AssistantConfig(id, "{}", now()).also { content.saveAssistant(it) }
            else throw StError.NotFound("assistant configuration $id")
        if (content.isSourceContainer(config)) throw StError.Validation("select an assistant, not shared source settings or memory library")
        return config
    }

    suspend fun prepare(session: ChatSession, override: ProviderConfig?,
        throughOrder: Long = Long.MAX_VALUE): AssistantGenerationPlan? {
        val binding = session.overrides.assistant ?: return null
        val config = configuration(binding.assistantId)
        val settings = NativeJsonInput.parse(config.settingsJson) as? JsonObject ?: throw StError.Validation("assistant settings must be an object")
        if (override != null && config.sourceNamespace != session.sourceNamespace) throw StError.Conflict("assistant configuration source differs from its conversation")
        val provider = override ?: providers.resolve(providers.assistantConnection(session, config, settings))
        val (prompt, _) = assistantPrompt(config, settings)
        val contextSize = settings.integer("context_size", 0, 1_000_000) ?: 0
        val maxToolTurns = settings.integer("maxToolTurns", 1, 64) ?: 8
        val maxToolsPerTurn = settings.integer("maxToolsPerTurn", 1, 128)
        val tools = toolSession(session, binding, settings, config)
        try {
            val messages = contexts.buildAssistant(contextSession(session, settings), prompt, contextSize,
                assistantMessageLimit(session, settings), throughOrder = throughOrder,
                outputTokens = com.simpletavern.core.memory.ContextTokens.output(provider),
                toolsJson = tools.definitions.toString())
            return AssistantGenerationPlan(provider, messages, tools,
                maxToolTurns, maxToolsPerTurn, contextTokenBudget = contextSize)
        } catch (error: Throwable) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                try { tools.close() } catch (closeError: Throwable) { error.addSuppressed(closeError) }
            }
            throw error
        }
    }

    suspend fun preview(session: ChatSession): ProviderSelection {
        val binding = session.overrides.assistant ?: throw StError.Validation("not an assistant conversation")
        val config = configuration(binding.assistantId)
        val settings = NativeJsonInput.parse(config.settingsJson) as? JsonObject ?: throw StError.Validation("assistant settings must be an object")
        return providers.preview(providers.assistantConnection(session, config, settings))
    }

    suspend fun context(session: ChatSession): ContextBuildResult {
        val binding = session.overrides.assistant ?: throw StError.Validation("not an assistant conversation")
        val config = configuration(binding.assistantId)
        val settings = NativeJsonInput.parse(config.settingsJson) as? JsonObject ?: throw StError.Validation("assistant settings must be an object")
        val messageLimit = assistantMessageLimit(session, settings)
        val (prompt, hasSourceMemory) = assistantPrompt(config, settings)
        val messages = contexts.buildAssistant(contextSession(session, settings), prompt,
            settings.integer("context_size", 0, 1_000_000) ?: 0, messageLimit)
        return ContextBuildResult(messages, hasSourceMemory, emptyList(), false, false, messages.sumOf { com.simpletavern.core.memory.ContextTokens.message(it) },
            listOf("token_estimate_strategy=${com.simpletavern.core.memory.ContextTokens.STRATEGY}", "history_source=all_selected_messages", "context_message_limit=$messageLimit", "media_tokens_conservatively_estimated"),
            "assistant=true main_chat_autoinjected=false maintenance_state_autoinjected=false")
    }

    private fun assistantMessageLimit(session: ChatSession, settings: JsonObject): Int =
        session.overrides.contextMessageLimit ?: settings.integer("contextMessageLimit", 0, Int.MAX_VALUE) ?: 0

    private fun contextSession(session: ChatSession, settings: JsonObject) = session.copy(overrides = session.overrides.copy(
        messageTemplate = session.overrides.messageTemplate ?: settings.text("messageTemplate")))

    private suspend fun assistantPrompt(config: AssistantConfig, settings: JsonObject): Pair<String, Boolean> {
        val prompt = settings.text("systemPrompt") ?: DEFAULT_PROMPT
        val sourceMemory = content.sourceAssistantMemory(config)
        val parts = listOfNotNull(prompt, sourceMemory?.let { "<AssistantMemory>\n$it\n</AssistantMemory>" },
            content.skills.prompt(config).takeIf { it.isNotBlank() })
        return parts.joinToString("\n\n") to (sourceMemory != null)
    }

    private suspend fun toolSession(session: ChatSession, binding: AssistantChatBinding, settings: JsonObject, config: AssistantConfig): AssistantToolSession {
        val definitions = mutableListOf<JsonElement>()
        val categories = linkedMapOf<String, ApprovalCategory>()
        val sourceMemory = content.assistantMemoryPool(config)?.takeIf { it.enabled }?.let {
            AssistantMemoryTools(content, gate, it, binding.allowWriteMemory, binding.allowDestructiveTools)
        }
        sourceMemory?.let { definitions.addAll(it.definitions); categories.putAll(it.categories) }
        val localTools = AssistantLocalTools(content, gate, config, appContext, deviceTools, speech, session.id)
        definitions.addAll(localTools.definitions); categories.putAll(localTools.categories)
        val skillTools = skills.tools(config, session.sandboxId.takeIf { binding.sandboxToolsEnabled })
        definitions.addAll(skillTools.definitions); categories.putAll(skillTools.categories)
        if (conversationReferences.enabled(config)) {
            definitions.addAll(conversationReferences.definitions); categories.putAll(conversationReferences.categories)
        }
        fun tool(name: String, description: String, properties: JsonObject = JsonObject(emptyMap()),
            required: List<String> = emptyList(), category: ApprovalCategory = ApprovalCategory.READ, native: Boolean = false) {
            definitions += buildJsonObject {
                put("type", "function"); put("function", buildJsonObject {
                    put("name", name); put("description", description)
                    put("parameters", buildJsonObject { put("type", "object"); put("properties", properties)
                        put("required", JsonArray(required.map(::JsonPrimitive))); put("additionalProperties", false) })
                })
            }
            if (!native) categories[name] = category
        }
        fun fields(vararg fields: Pair<String, String>) = JsonObject(fields.associate { (name, type) -> name to buildJsonObject {
            put("type", type.substringBefore(':'))
            if (type.startsWith("array:")) put("items", buildJsonObject { put("type", type.substringAfter(':')) })
        } })
        tool("core_get_time", "返回当前本地时间和时区。")
        tool("workspace_list_files", "分页列出本助手配置的持久文本工作区；与沙箱文件不同。", fields("afterPath" to "string", "limit" to "integer"))
        tool("workspace_list_assets", "分页列出本助手配置的已导入二进制/大文件与 ingest 文件。", fields("afterPath" to "string", "limit" to "integer"))
        tool("workspace_get_asset", "读取工作区托管附件元数据；不自动把文件发给模型。", fields("path" to "string"), listOf("path"))
        tool("workspace_link_asset", "把已注册附件关联为本助手工作区资产；修改已有路径需 expectedAttachmentId，新建不传。只改引用，不复制或执行文件。",
            fields("path" to "string", "attachmentId" to "string", "expectedAttachmentId" to "string"), listOf("path", "attachmentId"), ApprovalCategory.EDIT)
        tool("workspace_read_file", "读取本助手工作区文本；修改时 expected 使用返回的完整 content。", fields("path" to "string"), listOf("path"))
        tool("workspace_create_file", "新建工作区文本文件，已存在失败；不修改资料库。",
            fields("path" to "string", "content" to "string"), listOf("path", "content"), ApprovalCategory.WRITE)
        tool("workspace_write_file", "写入工作区文本，覆盖需 expected 为已读原文；不传 expected 仅允许新建。",
            fields("path" to "string", "content" to "string", "expected" to "string"), listOf("path", "content"), ApprovalCategory.EDIT)
        tool("workspace_begin_character_card", "以资料库角色或空白角色新建工作区草稿，不改角色；覆盖已有草稿需 expected 为原文。",
            fields("characterId" to "string", "expected" to "string"), category = ApprovalCategory.WRITE)
        tool("workspace_patch_character_card", "修改 character_card.json 草稿；patch 是 Android 角色字段补丁，expected 为最新完整草稿原文；不修改资料库。",
            fields("patch" to "object", "expected" to "string"), listOf("patch", "expected"), ApprovalCategory.EDIT)
        tool("workspace_publish_character_card", "明确发布草稿到资料库；不传 characterId 新建，更新目标需 expectedUpdatedAt。expected 为最新草稿原文。来源保持，只接受已托管头像引用，不安装外部文件路径。",
            fields("expected" to "string", "characterId" to "string", "expectedUpdatedAt" to "string"), listOf("expected"), ApprovalCategory.WRITE)
        if (binding.allowDestructiveTools) {
            tool("workspace_delete_asset", "删除工作区资产引用，保留可能由其他聊天使用的附件字节；需已读 expectedAttachmentId。",
                fields("path" to "string", "expectedAttachmentId" to "string"), listOf("path", "expectedAttachmentId"), ApprovalCategory.EDIT)
            tool("workspace_delete_file", "删除已读取的工作区文本，不删除角色；expected 为完整原文。",
                fields("path" to "string", "expected" to "string"), listOf("path", "expected"), ApprovalCategory.EDIT)
            tool("workspace_replace_character_card", "整卡覆盖工作区 character_card.json，不改资料库；card 为完整 Android 角色卡。覆盖时 expected 为原文，新建不传。",
                fields("card" to "object", "expected" to "string"), listOf("card"), ApprovalCategory.EDIT)
        }
        tool("worldbook_list", "列出资料库世界书摘要。")
        tool("worldbook_get", "读取世界书及条目。", fields("worldbookId" to "string"), listOf("worldbookId"))
        tool("worldbook_create", "创建一个空世界书。", fields("name" to "string", "globalActive" to "boolean"), listOf("name"), ApprovalCategory.WRITE)
        tool("worldbook_update_meta", "修改世界书名称/全局激活；expectedUpdatedAt 来自最近读取。",
            fields("worldbookId" to "string", "expectedUpdatedAt" to "string", "name" to "string", "globalActive" to "boolean", "sessionChatIds" to "array:string"), listOf("worldbookId", "expectedUpdatedAt"), ApprovalCategory.EDIT)
        val entryControls = fields("scanDepth" to "integer", "constantActive" to "boolean", "priority" to "integer",
            "position" to "string", "injectDepth" to "integer", "role" to "string")
        tool("worldbook_entry_add", "增加世界书条目，返回稳定条目 ID；可设置逐条扫描/常驻/优先级/位置/角色。",
            JsonObject(fields("worldbookId" to "string", "expectedUpdatedAt" to "string", "title" to "string", "content" to "string", "regex" to "string", "enabled" to "boolean", "orderIndex" to "integer") + entryControls), listOf("worldbookId", "expectedUpdatedAt", "content"), ApprovalCategory.WRITE)
        tool("worldbook_entry_update", "修改世界书条目，未传保持；scanDepth=-1继承整书，0不扫描；position空串恢复整书，否则before_system_prompt/after_system_prompt/top_of_chat/bottom_of_chat/at_depth；role为system/user/assistant。",
            JsonObject(fields("worldbookId" to "string", "expectedUpdatedAt" to "string", "entryId" to "string", "title" to "string", "content" to "string", "regex" to "string", "enabled" to "boolean", "orderIndex" to "integer") + entryControls), listOf("worldbookId", "expectedUpdatedAt", "entryId"), ApprovalCategory.EDIT)
        if (binding.allowDestructiveTools) tool("worldbook_entry_delete", "删除指定世界书条目。",
            fields("worldbookId" to "string", "expectedUpdatedAt" to "string", "entryId" to "string"), listOf("worldbookId", "expectedUpdatedAt", "entryId"), ApprovalCategory.EDIT)
        if (binding.allowDestructiveTools) tool("worldbook_delete", "删除整本世界书，同时移除角色与聊天引用；不删除来源合并基线。",
            fields("worldbookId" to "string", "expectedUpdatedAt" to "string"), listOf("worldbookId", "expectedUpdatedAt"), ApprovalCategory.EDIT)
        if (binding.targetChatId != null) {
            tool("chat_get_worldbook_state", "读取绑定主聊天的世界书挂载、全局排除和 updatedAt。")
            tool("chat_summarize_active_worldbooks", "列出绑定主聊天实际激活的世界书摘要，按上下文顺序返回。")
            tool("chat_worldbook_selection_set", "设置主聊天世界书选择：merge合并角色/会话，character只用角色挂载，session只用会话挂载（空列表也有效）；独立全局激活仍生效。",
                fields("mode" to "string", "expectedUpdatedAt" to "string"), listOf("mode", "expectedUpdatedAt"), ApprovalCategory.EDIT)
            tool("chat_worldbook_global_exclusion_set", "开关主聊天的全局世界书排除；显式挂载仍生效。",
                fields("worldbookId" to "string", "excluded" to "boolean", "expectedUpdatedAt" to "string"), listOf("worldbookId", "excluded", "expectedUpdatedAt"), ApprovalCategory.EDIT)
            for (action in listOf("add", "update")) tool("chat_worldbook_attachment_$action", "${if (action == "add") "挂载" else "修改"}主聊天世界书；扫描深度不传继承默认，0 重置默认；插入深度默认5。",
                fields("worldbookId" to "string", "scanDepth" to "integer", "insertDepth" to "integer", "expectedUpdatedAt" to "string"), listOf("worldbookId", "expectedUpdatedAt"), ApprovalCategory.EDIT)
            tool("chat_worldbook_attachment_remove", "取消主聊天世界书挂载，不删除世界书；全局激活可能仍生效。",
                fields("worldbookId" to "string", "expectedUpdatedAt" to "string"), listOf("worldbookId", "expectedUpdatedAt"), ApprovalCategory.EDIT)
            tool("chat_worldbook_attachment_reorder", "调整挂载顺序；未列出的挂载保持原顺序放在最后。",
                fields("orderedWorldBookIds" to "array:string", "expectedUpdatedAt" to "string"), listOf("orderedWorldBookIds", "expectedUpdatedAt"), ApprovalCategory.EDIT)
            tool("chat_content_regex_list", "读取主聊天自身正文规则；expectedRules 使用返回的 rules。")
            tool("character_content_regex_list", "读取主聊天主角色自身正文规则；expectedRules 使用返回的 rules。")
            val ruleFields = fields("id" to "string", "name" to "string", "enabled" to "boolean", "order" to "integer", "pattern" to "string",
                "action" to "string", "replacement" to "string", "matchMode" to "string", "scanDepthOverride" to "integer", "extractSource" to "string", "extractGroupIndex" to "integer",
                "engine" to "string", "roles" to "array:string", "visualOnly" to "boolean", "applyToReasoning" to "boolean", "scanAllHistory" to "boolean", "skipOpeningMessage" to "boolean")
            val manageFields = JsonObject(fields("operation" to "string", "rule_id" to "string", "expectedRules" to "array:object").toMap() +
                ("rule" to buildJsonObject { put("type", "object"); put("properties", ruleFields); put("additionalProperties", false) }))
            for (scope in listOf("chat", "character")) tool("${scope}_content_regex_manage", "管理${if (scope == "chat") "主聊天" else "主角色"}正文规则。operation=list/upsert/delete；写入需完整 expectedRules；upsert 的 rule 是字段补丁，新规则不传 id；不修改历史原文。",
                manageFields, listOf("operation"), ApprovalCategory.EDIT)
            tool("chat_patch_character_card", "修改绑定主聊天参与角色的资料库正文，不是工作区草稿；影响所有使用该角色的聊天。未传字段保持不变。",
                fields("characterId" to "string", "expectedUpdatedAt" to "string", "name" to "string", "description" to "string", "personality" to "string", "scenario" to "string",
                    "firstMessage" to "string", "exampleDialogue" to "string", "systemPrompt" to "string"), listOf("characterId", "expectedUpdatedAt"), ApprovalCategory.EDIT)
            tool("chat_read_conversation", "分页读取所绑定主聊天的选中正文，不自动读取其他聊天。",
                fields("range" to "string", "afterOrder" to "integer", "limit" to "integer"))
            tool("chat_read_long_term_memory", "读取绑定主聊天的记忆文本和版本。")
            tool("chat_list_participants", "列出绑定主聊天的参与角色。")
            tool("chat_read_character_card", "读取绑定主聊天的参与角色卡。", fields("characterId" to "string"), listOf("characterId"))
            tool("mvu_get_chat_context", "读取绑定主聊天的状态变量表和版本。")
            tool("read_mvu_logs", "读取绑定主聊天最近的状态维护工作日志。", fields("limit" to "integer"))
            tool("kg_get_context", "读取绑定主聊天的知识图谱上下文、活跃实体和关系。")
            tool("kg_query", "按实体名、实体类型或关系谓词查询绑定主聊天图谱。", fields("entity_name" to "string", "type" to "string", "relation_type" to "string"))
            if (binding.allowWriteState) {
                val cellFields = fields("table_name" to "string", "field" to "string", "column" to "string", "value" to "string", "expectedVersion" to "integer")
                tool("mvu_set_cell", "修改/创建状态表单元格；expectedVersion 来自最新读取。", cellFields,
                    listOf("table_name", "field", "column", "value", "expectedVersion"), ApprovalCategory.EDIT)
                tool("patch_state_variable", "按表名、行标识、列名修改状态值。", cellFields,
                    listOf("table_name", "field", "column", "value", "expectedVersion"), ApprovalCategory.EDIT)
                tool("kg_upsert_entity", "创建/修改图谱实体；类型为人物、地点、物品、势力或事件。",
                    fields("entity_id" to "string", "name" to "string", "type" to "string", "properties" to "object", "expectedVersion" to "integer"),
                    listOf("name", "type", "expectedVersion"), ApprovalCategory.EDIT)
                tool("kg_upsert_relation", "创建/更新图谱关系；expectedVersion 来自读取结果。",
                    fields("subject_id" to "string", "predicate" to "string", "object_id" to "string", "confidence" to "number", "expectedVersion" to "integer"),
                    listOf("subject_id", "predicate", "object_id", "expectedVersion"), ApprovalCategory.EDIT)
                if (binding.allowDestructiveTools) tool("kg_delete_entity", "软删除图谱实体并清理它的关系。",
                    fields("entity_id" to "string", "expectedVersion" to "integer"), listOf("entity_id", "expectedVersion"), ApprovalCategory.EDIT)
            }
            if (binding.allowWriteMemory) {
                tool("chat_append_long_term_memory", "向绑定主聊天的长期记忆追加文本。", fields("content" to "string"), listOf("content"), ApprovalCategory.WRITE)
                if (binding.allowDestructiveTools) tool("chat_overwrite_long_term_memory", "覆盖绑定主聊天记忆；expectedVersion 来自读取结果。",
                    fields("content" to "string", "expectedVersion" to "integer"), listOf("content", "expectedVersion"), ApprovalCategory.EDIT)
            }
        }
        if (binding.sandboxToolsEnabled) {
            tool("shell", "在明确选定的 Linux 沙箱运行命令。", fields("command" to "string", "timeoutSec" to "integer"), listOf("command"), native = true)
            tool("read_file", "读取沙箱内 UTF-8 文件。", fields("path" to "string"), listOf("path"), native = true)
            tool("list_dir", "列出沙箱内目录。", fields("path" to "string"), native = true)
            tool("create_file", "新建沙箱文件，不覆盖。", fields("path" to "string", "content" to "string"), listOf("path", "content"), native = true)
            tool("edit_file", "修改沙箱文件，expected 必须为已读取的原文。", fields("path" to "string", "content" to "string", "expected" to "string"), listOf("path", "content", "expected"), native = true)
            if (binding.allowDestructiveTools) {
                tool("delete_file", "删除沙箱普通文件或符号链接；末尾链接只删除链接本身，不删除目标，不删除目录。", fields("path" to "string"), listOf("path"), native = true)
                tool("rename_file", "重命名沙箱文件、目录或符号链接；末尾链接只移动链接本身。目标已存在时拒绝，不覆盖。", fields("from" to "string", "to" to "string"), listOf("from", "to"), native = true)
            }
        }
        val readLimit = settings.integer("tool_read_max_messages", 1, 200) ?: 100
        val readTokens = settings.integer("tool_read_max_tokens", 1, 1_000_000)
        val remoteTools = mcp.prepare(config)
        definitions.addAll(remoteTools.definitions); categories.putAll(remoteTools.categories)
        return AssistantToolSession(UUID.randomUUID().toString(), JsonArray(definitions), categories,
            remoteSchemaTools = remoteTools.categories.keys, forceApprovalTools = remoteTools.forceApprovalTools + localTools.forceApprovalTools,
            beforeUserQuestion = localTools.beforeUserQuestion,
            onClose = remoteTools::close, beforeCall = {
            val current = chats.getSession(session.id)
            if (current?.overrides?.assistant != binding || current.sandboxId != session.sandboxId)
                throw StError.Conflict("assistant binding/permissions or sandbox changed")
        }, categoryResolver = { name, args -> sourceMemory?.category(name, args) ?: localTools.category(name, args) ?: skillTools.category(name, args) }) { name, args ->
            if (name in sourceMemory?.categories.orEmpty()) sourceMemory!!.dispatch(name, args)
            else if (name in localTools.categories) localTools.dispatch(name, args)
            else if (name in skillTools.categories) skillTools.dispatch(name, args)
            else if (name in remoteTools.categories) remoteTools.dispatch(name, args)
            else if (name in conversationReferences.categories) gate.write { conversationReferences.dispatch(config, name, args) }
            else gate.write {
                val data = dispatch(name, args, binding, readLimit, readTokens)
                val output = buildJsonObject { put("ok", true); put("tool", name); put("data", data) }.toString()
                if (output.toByteArray(Charsets.UTF_8).size > 1_000_000) throw StError.Validation("assistant tool result exceeds text budget")
                ToolResult(output, buildJsonObject { put("toolName", name); put("ok", true); put("targetChatId", binding.targetChatId?.let(::JsonPrimitive) ?: JsonNull) }.toString())
            }
        }
    }

    private suspend fun dispatch(name: String, args: JsonObject, binding: AssistantChatBinding, readLimit: Int, readTokens: Int?): JsonElement {
        suspend fun target(): ChatSession = binding.targetChatId?.let { chats.getSession(it) } ?: throw StError.NotFound("assistant main-chat scope")
        fun required(key: String) = args.text(key) ?: throw StError.Validation("$key is required")
        return when (name) {
            "core_get_time" -> buildJsonObject { put("time", DateTimeFormatter.ofPattern("yyyy/MM/dd - HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now())); put("zone", ZoneId.systemDefault().id) }
            "workspace_list_files" -> Jsonx.json.parseToJsonElement(Jsonx.encode(workspace.list(binding.assistantId, args.text("afterPath").orEmpty(), args.integer("limit", 1, 200) ?: 100)))
            "workspace_list_assets" -> Jsonx.json.parseToJsonElement(Jsonx.encode(workspace.listAssets(binding.assistantId, args.text("afterPath").orEmpty(), args.integer("limit", 1, 200) ?: 100)))
            "workspace_get_asset" -> Jsonx.json.parseToJsonElement(Jsonx.encode(workspace.asset(binding.assistantId, required("path"))))
            "workspace_link_asset" -> Jsonx.json.parseToJsonElement(Jsonx.encode(workspace.linkAsset(binding.assistantId,
                required("path"), required("attachmentId"), args.text("expectedAttachmentId"))))
            "workspace_delete_asset" -> {
                workspace.deleteAsset(binding.assistantId, required("path"), required("expectedAttachmentId"))
                buildJsonObject { put("path", required("path")); put("deleted", true); put("attachmentBytesRetained", true) }
            }
            "workspace_read_file" -> Jsonx.json.parseToJsonElement(Jsonx.encode(workspace.read(binding.assistantId, required("path"))))
            "workspace_create_file", "workspace_write_file", "workspace_begin_character_card", "workspace_patch_character_card", "workspace_replace_character_card" -> {
                val file = when (name) {
                    "workspace_begin_character_card" -> workspace.beginCard(binding.assistantId, args.text("characterId"), args.text("expected"))
                    "workspace_patch_character_card" -> workspace.patchCard(binding.assistantId, args["patch"]!!.jsonObject, required("expected"))
                    "workspace_replace_character_card" -> workspace.replaceCard(binding.assistantId, args["card"]!!.jsonObject, args.text("expected"))
                    else -> workspace.write(binding.assistantId, required("path"), required("content"), if (name.endsWith("create_file")) null else args.text("expected"))
                }
                buildJsonObject { put("path", file.path); put("updatedAt", file.updatedAt) }
            }
            "workspace_delete_file" -> {
                val path = required("path"); workspace.delete(binding.assistantId, path, required("expected"))
                buildJsonObject { put("path", path); put("deleted", true) }
            }
            "workspace_publish_character_card" -> {
                val card = workspace.publishCard(binding.assistantId, required("expected"), args.text("characterId"), args.text("expectedUpdatedAt"))
                buildJsonObject { put("id", card.id); put("name", card.name); put("updatedAt", card.updatedAt) }
            }
            "chat_read_conversation" -> {
                val chat = target()
                val range = args.text("range") ?: "transcript"
                if (range !in setOf("transcript", "full", "since_memory_marker")) throw StError.Unsupported("assistant conversation range is not supported")
                val marker = if (range == "since_memory_marker") memory.get(chat.id)?.anchorMessageId?.let { chats.selectedMessageOrder(chat.id, it) } ?: -1L else -1L
                val after = maxOf(args["afterOrder"]?.jsonPrimitive?.longOrNull ?: -1L, marker)
                val limit = minOf(args.integer("limit", 1, 200) ?: readLimit, readLimit)
                val rows = chats.selectedMessagePage(chat.id, after, Long.MAX_VALUE, 0, limit + 1)
                var size = 0; var estimate = 0
                val visible = mutableListOf<JsonElement>()
                for (row in rows.take(limit)) {
                    val item = buildJsonObject { put("id", row.candidate.id); put("orderIndex", row.orderIndex)
                        put("role", row.candidate.role.name); put("name", row.candidate.senderName ?: row.candidate.characterId.orEmpty()); put("content", row.candidate.content) }
                    val text = item.toString(); val bytes = text.toByteArray(Charsets.UTF_8).size; val tokens = text.length / 4
                    if (size + bytes > 800_000 || readTokens != null && estimate + tokens > readTokens) break
                    visible += item; size += bytes; estimate += tokens
                }
                if (visible.isEmpty() && rows.isNotEmpty()) throw StError.Validation("one main-chat message exceeds assistant read budget")
                buildJsonObject { put("chatId", chat.id); put("title", chat.title); put("messages", JsonArray(visible))
                    put("hasMore", rows.size > visible.size); put("nextAfterOrder", rows.getOrNull(visible.lastIndex)?.orderIndex?.let(::JsonPrimitive) ?: JsonNull)
                    put("tokenEstimateStrategy", "chars/4") }
            }
            "chat_read_long_term_memory" -> {
                val chat = target()
                memory.get(chat.id)?.let { Jsonx.json.parseToJsonElement(Jsonx.encode(it)) }
                    ?: buildJsonObject { put("chatId", chat.id); put("content", ""); put("version", 0) }
            }
            "chat_list_participants" -> {
                val chat = target(); val ids = if (chat.isGroup) chat.memberIds else listOf(chat.characterId)
                JsonArray(ids.map { id -> buildJsonObject { put("id", id); put("name", content.getCharacter(id)?.name ?: id) } })
            }
            "chat_read_character_card" -> {
                val chat = target(); val selected = required("characterId")
                if (selected !in if (chat.isGroup) chat.memberIds else listOf(chat.characterId)) throw StError.Validation("character is not a bound main-chat participant")
                Jsonx.json.parseToJsonElement(Jsonx.encode(content.getCharacter(selected) ?: throw StError.NotFound("character")))
            }
            "chat_patch_character_card" -> chats.transaction {
                val chat = target(); val selected = required("characterId")
                if (selected !in if (chat.isGroup) chat.memberIds else listOf(chat.characterId)) throw StError.Validation("character is not a bound main-chat participant")
                val card = content.getCharacter(selected) ?: throw StError.NotFound("character")
                if (card.updatedAt != required("expectedUpdatedAt")) throw StError.Conflict("character changed since it was read")
                val changed = card.copy(name = args.text("name") ?: card.name, description = args.text("description") ?: card.description,
                    personality = args.text("personality") ?: card.personality, scenario = args.text("scenario") ?: card.scenario,
                    firstMessage = args.text("firstMessage") ?: card.firstMessage, exampleDialogue = args.text("exampleDialogue") ?: card.exampleDialogue,
                    systemPrompt = args.text("systemPrompt") ?: card.systemPrompt, updatedAt = now())
                content.saveCharacter(changed)
                buildJsonObject { put("id", changed.id); put("name", changed.name); put("updatedAt", changed.updatedAt) }
            }
            "chat_content_regex_list", "character_content_regex_list", "chat_content_regex_manage", "character_content_regex_manage" -> {
                val chat = target(); val characterScope = name.startsWith("character_")
                val operation = if (name.endsWith("_list")) "list" else required("operation")
                if (operation !in setOf("list", "upsert", "delete")) throw StError.Validation("operation must be list/upsert/delete")
                val rules = if (operation == "list") {
                    if (characterScope) memory.characterContentRegexRules(chat.characterId) else memory.sessionContentRegexRules(chat.id)
                } else {
                    val expected = Jsonx.decodeOrNull<List<ContentRegexRule>>(args["expectedRules"]?.toString()) ?: throw StError.Validation("expectedRules must be the rules from the latest list")
                    val changed = if (operation == "delete") {
                        val ruleId = required("rule_id")
                        if (expected.none { it.id == ruleId }) throw StError.NotFound("content regex rule")
                        expected.filterNot { it.id == ruleId }
                    } else {
                        val patch = args["rule"] as? JsonObject ?: throw StError.Validation("rule must be an object")
                        val ruleId = patch.text("id")?.takeIf { it.isNotBlank() }
                        val previous = ruleId?.let { selected -> expected.firstOrNull { it.id == selected } ?: throw StError.NotFound("content regex rule") }
                            ?: ContentRegexRule(id(), order = (expected.maxOfOrNull { it.order } ?: -1) + 1)
                        val base = Jsonx.json.parseToJsonElement(Jsonx.encode(previous)).jsonObject
                        if (patch.keys.any { it !in setOf("id", "name", "enabled", "order", "pattern", "action", "replacement", "matchMode", "scanDepthOverride", "extractSource", "extractGroupIndex",
                                "engine", "roles", "visualOnly", "applyToReasoning", "scanAllHistory", "skipOpeningMessage") })
                            throw StError.Validation("unknown content regex rule field")
                        val edited = Jsonx.decodeOrNull<ContentRegexRule>(JsonObject(base + patch + ("id" to JsonPrimitive(previous.id))).toString())
                            ?: throw StError.Validation("invalid content regex rule")
                        expected.filterNot { it.id == edited.id } + edited
                    }
                    val ordered = changed.sortedWith(compareBy<ContentRegexRule> { it.order }.thenBy { it.id }).mapIndexed { index, rule -> rule.copy(order = index) }
                    if (characterScope) memory.saveCharacterContentRegexRules(chat.characterId, ordered, expected)
                    else memory.saveSessionContentRegexRules(chat.id, ordered, expected)
                }
                buildJsonObject { put("chatId", chat.id); if (characterScope) put("characterId", chat.characterId)
                    put("rules", Jsonx.json.parseToJsonElement(Jsonx.encode(rules))); put("count", rules.size) }
            }
            "chat_get_worldbook_state" -> worldBookState(target())
            "chat_summarize_active_worldbooks" -> {
                val chat = target()
                buildJsonObject { put("chatId", chat.id); put("updatedAt", chat.updatedAt)
                    put("attachmentOrder", JsonArray(chat.overrides.worldBookAttachments.map { JsonPrimitive(it.worldBookId) }))
                    put("activeWorldbooks", JsonArray(content.activeWorldBooks(chat, content.getCharacter(chat.characterId)).map { book -> buildJsonObject {
                        put("id", book.id); put("name", book.name); put("globalActive", book.globalActive); put("inSessionList", chat.id in book.sessionChatIds); put("entryCount", book.entries.size)
                    } })) }
            }
            "chat_worldbook_selection_set", "chat_worldbook_global_exclusion_set", "chat_worldbook_attachment_add", "chat_worldbook_attachment_update", "chat_worldbook_attachment_remove", "chat_worldbook_attachment_reorder" -> {
                val chat = target(); val expected = required("expectedUpdatedAt")
                val saved = when (name) {
                    "chat_worldbook_selection_set" -> library.setSelectionMode(chat.id, required("mode"), expected)
                    "chat_worldbook_global_exclusion_set" -> library.setGlobalExclusion(chat.id, required("worldbookId"), args["excluded"]!!.jsonPrimitive.boolean, expected)
                    "chat_worldbook_attachment_remove" -> library.detach(chat.id, required("worldbookId"), expected)
                    "chat_worldbook_attachment_reorder" -> library.reorder(chat.id, args.strings("orderedWorldBookIds") ?: throw StError.Validation("orderedWorldBookIds required"), expected)
                    else -> {
                        val bookId = required("worldbookId")
                        val previous = chat.overrides.worldBookAttachments.firstOrNull { it.worldBookId == bookId }
                        val depth = if ("scanDepth" in args) args.integer("scanDepth", 0, 500)?.takeIf { it > 0 } else previous?.scanDepth
                        val attachment = WorldBookAttachment(bookId, depth, args.integer("insertDepth", 1, 500) ?: previous?.insertDepth ?: 5)
                        if (name.endsWith("_add")) library.attach(chat.id, attachment, expected) else library.updateAttachment(chat.id, attachment, expected)
                    }
                }
                worldBookState(saved)
            }
            "mvu_get_chat_context" -> {
                val chat = target(); val state = memory.getMvu(chat.id) ?: MvuState(chat.id, version = 0)
                Jsonx.json.parseToJsonElement(Jsonx.encode(state))
            }
            "read_mvu_logs" -> Jsonx.json.parseToJsonElement(Jsonx.encode(memory.listMvuLogs(target().id, args.integer("limit", 1, 200) ?: 50)))
            "kg_get_context", "kg_query" -> {
                val graph = memory.getKg(target().id) ?: KnowledgeGraph(target().id)
                val entityName = args.text("entity_name"); val entityType = args.text("type"); val relationType = args.text("relation_type")
                val entities = graph.entities.filter { !it.deleted && (entityName == null || it.name == entityName) &&
                    (entityType == null || it.type == entityType) }
                val activeIds = graph.entities.filterNot { it.deleted }.map { it.id }.toSet()
                val selectedIds = entities.map { it.id }.toSet()
                val deletedIds = graph.entities.filter { it.deleted }.map { it.id }.toSet()
                val relations = graph.relations.filter { it.subject in activeIds && it.`object` !in deletedIds &&
                    (relationType == null || it.predicate == relationType) &&
                    (entityName == null && entityType == null || it.subject in selectedIds || it.`object` in selectedIds) }
                val result = graph.copy(entities = entities, relations = relations)
                buildJsonObject { put("version", graph.version); put("entities", Jsonx.json.parseToJsonElement(Jsonx.encode(entities)))
                    put("relations", Jsonx.json.parseToJsonElement(Jsonx.encode(relations))); put("contextText", memory.renderKgForPrompt(result)) }
            }
            "mvu_set_cell", "patch_state_variable" -> chats.transaction {
                val chat = target(); val state = memory.getMvu(chat.id) ?: MvuState(chat.id, version = 0)
                val expected = args.integer("expectedVersion", 0, Int.MAX_VALUE) ?: throw StError.Validation("expectedVersion required")
                val tableName = required("table_name"); val field = required("field"); val column = required("column"); val value = required("value")
                if (tableName.isBlank() || field.isBlank() || column.isBlank()) throw StError.Validation("state cell identity must be nonblank")
                val table = state.tables.singleOrNull { it.name == tableName } ?: StatusTableDef(tableName)
                val row = table.rows.singleOrNull { it.field == field } ?: StatusTableRow(field)
                val changedRow = row.copy(cells = row.cells + (column to value))
                val rows = if (table.rows.any { it.field == field }) table.rows.map { if (it.field == field) changedRow else it } else table.rows + changedRow
                val changed = table.copy(columns = (table.columns + column).distinct(), rows = rows)
                val tables = if (state.tables.any { it.name == tableName }) state.tables.map { if (it.name == tableName) changed else it } else state.tables + changed
                val saved = memory.saveMvu(state.copy(tables = tables, updatedAt = now(), source = "user"), expected)
                buildJsonObject { put("chatId", saved.chatId); put("version", saved.version); put("table", tableName); put("field", field); put("column", column) }
            }
            "kg_upsert_entity", "kg_delete_entity" -> chats.transaction {
                val chat = target(); val expected = args.integer("expectedVersion", 0, Int.MAX_VALUE) ?: throw StError.Validation("expectedVersion required")
                val entity = if (name == "kg_delete_entity") {
                    val graph = memory.getKg(chat.id) ?: throw StError.NotFound("knowledge graph")
                    (graph.entities.firstOrNull { it.id == required("entity_id") } ?: throw StError.NotFound("graph entity")).copy(deleted = true)
                } else {
                    val properties = (args["properties"] as? JsonObject).orEmpty().mapValues { (_, value) ->
                        (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw StError.Validation("entity properties must be text") }
                    KgEntity(args.text("entity_id") ?: id(), required("name"), required("type"), properties)
                }
                val saved = memory.upsertKgEntity(chat.id, entity, expected)
                val entityId = saved.entities.first { it.id == entity.id || it.name == entity.name && it.type == entity.type && !it.deleted }.id
                buildJsonObject { put("entityId", entityId); put("version", saved.version) }
            }
            "kg_upsert_relation" -> chats.transaction {
                val chat = target(); val expected = args.integer("expectedVersion", 0, Int.MAX_VALUE) ?: throw StError.Validation("expectedVersion required")
                val relation = KgRelation(required("subject_id"), required("predicate"), required("object_id"),
                    confidence = args["confidence"]?.jsonPrimitive?.doubleOrNull ?: 1.0)
                val saved = memory.upsertKgRelation(chat.id, relation, expected)
                buildJsonObject { put("version", saved.version) }
            }
            "chat_append_long_term_memory", "chat_overwrite_long_term_memory" -> chats.transaction {
                val chat = target(); val current = memory.get(chat.id); val text = required("content")
                val replacement = if (name == "chat_append_long_term_memory") listOfNotNull(current?.content?.takeIf { it.isNotEmpty() }, text).joinToString("\n") else text
                val expected = if (name == "chat_append_long_term_memory") current?.version ?: 0L
                    else args["expectedVersion"]?.jsonPrimitive?.longOrNull ?: throw StError.Validation("expectedVersion required")
                val saved = memory.edit(chat.id, replacement, expected)
                buildJsonObject { put("chatId", saved.chatId); put("version", saved.version); put("updatedAt", saved.updatedAt) }
            }
            "worldbook_list" -> JsonArray(content.listWorldBooks().map { book -> buildJsonObject { put("id", book.id); put("name", book.name)
                put("updatedAt", book.updatedAt); put("globalActive", book.globalActive); put("entryCount", book.entries.size) } })
            "worldbook_get" -> Jsonx.json.parseToJsonElement(Jsonx.encode(content.getWorldBook(required("worldbookId")) ?: throw StError.NotFound("worldbook")))
            "worldbook_delete" -> {
                val bookId = required("worldbookId"); content.deleteWorldBook(bookId, required("expectedUpdatedAt"))
                buildJsonObject { put("id", bookId); put("deleted", true) }
            }
            "worldbook_create" -> {
                val at = now(); val book = WorldBook(id(), required("name"), globalActive = args["globalActive"]?.jsonPrimitive?.booleanOrNull ?: false, createdAt = at, updatedAt = at)
                content.saveWorldBook(book); Jsonx.json.parseToJsonElement(Jsonx.encode(book))
            }
            "worldbook_update_meta", "worldbook_entry_add", "worldbook_entry_update", "worldbook_entry_delete" -> chats.transaction {
                val book = content.getWorldBook(required("worldbookId")) ?: throw StError.NotFound("worldbook")
                if (book.updatedAt != required("expectedUpdatedAt")) throw StError.Conflict("worldbook changed since it was read")
                val changed = when (name) {
                    "worldbook_update_meta" -> {
                        val suppliedIds = args.strings("sessionChatIds")
                        for (chatId in suppliedIds.orEmpty()) if (chats.getSession(chatId) == null) throw StError.NotFound("worldbook session chat")
                        val sessionIds = suppliedIds ?: book.sessionChatIds
                        book.copy(name = args.text("name") ?: book.name, globalActive = args["globalActive"]?.jsonPrimitive?.booleanOrNull ?: book.globalActive, sessionChatIds = sessionIds.distinct())
                    }
                    "worldbook_entry_add" -> book.copy(entries = book.entries + patchWorldBookEntry(
                        WorldBookEntry(id(), content = required("content"), orderIndex = (book.entries.maxOfOrNull { it.orderIndex } ?: -1) + 1), args))
                    else -> {
                        val entryId = required("entryId")
                        if (book.entries.none { it.id == entryId }) throw StError.NotFound("worldbook entry")
                        if (name == "worldbook_entry_delete") book.copy(entries = book.entries.filterNot { it.id == entryId })
                        else book.copy(entries = book.entries.map { entry -> if (entry.id != entryId) entry else patchWorldBookEntry(entry, args) })
                    }
                }.copy(updatedAt = now())
                content.saveWorldBook(changed)
                buildJsonObject { put("id", changed.id); put("name", changed.name); put("updatedAt", changed.updatedAt)
                    put("entryId", if (name == "worldbook_entry_add") JsonPrimitive(changed.entries.last().id) else args.text("entryId")?.let(::JsonPrimitive) ?: JsonNull) }
            }
            else -> throw StError.Unsupported("assistant tool is not enabled")
        }
    }

    private fun patchWorldBookEntry(entry: WorldBookEntry, args: JsonObject): WorldBookEntry {
        val position = if ("position" in args) args.text("position")?.takeIf { it.isNotEmpty() } else entry.position
        if (position != null && position !in setOf("before_system_prompt", "after_system_prompt", "top_of_chat", "bottom_of_chat", "at_depth"))
            throw StError.Validation("unsupported worldbook entry position")
        val role = args.text("role")?.let { value ->
            when (value) {
                "system" -> ChatRole.system
                "user" -> ChatRole.user
                "assistant" -> ChatRole.assistant
                else -> throw StError.Validation("worldbook entry role must be system/user/assistant")
            }
        } ?: entry.role
        return entry.copy(title = args.text("title") ?: entry.title, regex = args.text("regex") ?: entry.regex,
            content = args.text("content") ?: entry.content,
            enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull ?: entry.enabled,
            orderIndex = args.integer("orderIndex", 0, Int.MAX_VALUE) ?: entry.orderIndex,
            scanDepth = if ("scanDepth" in args) args.integer("scanDepth", -1, 500)?.takeIf { it >= 0 } else entry.scanDepth,
            constantActive = args["constantActive"]?.jsonPrimitive?.booleanOrNull ?: entry.constantActive,
            priority = args.integer("priority", Int.MIN_VALUE, Int.MAX_VALUE) ?: entry.priority,
            position = position, injectDepth = args.integer("injectDepth", 1, 500) ?: entry.injectDepth, role = role)
    }

    private fun worldBookState(chat: ChatSession) = buildJsonObject {
        put("chatId", chat.id); put("updatedAt", chat.updatedAt)
        put("worldBookSelectionMode", chat.overrides.worldBookSelectionMode)
        put("worldBookAttachments", Jsonx.json.parseToJsonElement(Jsonx.encode(chat.overrides.worldBookAttachments)))
        put("worldBookGlobalExclusions", JsonArray(chat.overrides.worldBookGlobalExclusions.map(::JsonPrimitive)))
    }
    private fun JsonObject.strings(key: String): List<String>? = get(key)?.let { value ->
        (value as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content ?: throw StError.Validation("$key must contain text") }
            ?: throw StError.Validation("$key must be an array")
    }

    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it != JsonNull }?.let {
        (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: throw StError.Validation("$key must be text")
    }
    private fun JsonObject.integer(key: String, min: Int, max: Int): Int? = get(key)?.takeIf { it != JsonNull }?.let {
        (it as? JsonPrimitive)?.takeIf { value -> !value.isString }?.intOrNull?.takeIf { value -> value in min..max }
            ?: throw StError.Validation("$key must be $min..$max")
    }
    private fun id() = UUID.randomUUID().toString().replace("-", "")
    private fun now() = Instant.now().toString()
    companion object {
        private const val DEFAULT_PROMPT = "你是角色叙事设计师与聊天助手，帮助用户分析和维护角色、设定、世界书、记忆和选定沙箱。" +
            "你有独立的助手聊天历史；主聊天不会自动进入上下文。需要主聊天内容时先用已提供的工具读取，不假装知道。" +
            "仅使用当前已声明工具；权限关闭或工具失败时如实说明，不声称已经修改成功。修改资料前读取其最新版本。" +
            "破坏性操作与记忆写入受明确开关和逐项审批约束。停止或中断的旧调用不得重放，按用户新的提示词继续。"
    }
}
