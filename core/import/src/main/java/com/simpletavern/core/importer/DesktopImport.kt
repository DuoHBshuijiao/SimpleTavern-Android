package com.simpletavern.core.importer

import com.simpletavern.core.data.ContentRepository
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.data.AssistantWorkspaceStore
import com.simpletavern.core.model.*
import java.io.File
import kotlinx.serialization.json.*

internal class DesktopImport(
    private val root: File,
    private val content: ContentRepository,
    private val merge: ImportMerge,
    private val items: MutableList<ImportReportItem>,
    private val checkCancelled: suspend () -> Unit,
    private val workspace: AssistantWorkspaceStore,
) {
    private val ns get() = merge.namespace
    private var settings: JsonObject? = null
    private var defaultPresetId: String? = null

    suspend fun run() {
        settings = root.resolve("settings.json").takeIf { it.isFile }?.let(::readObject)
        settings?.let { incoming ->
            val clean = sanitizeSecrets(incoming).jsonObject
            val id = merge.id("source_settings", "settings")
            val config = AssistantConfig(id, clean.toString(), incoming.time("updatedAt"), ns)
            merge.merge("source_settings", id, config, content.getAssistant(id))?.let { content.saveAssistant(it) }
            merge.retain("settings.json", clean)
            items += ImportReportItem("settings", "settings.json", id, "converted", "source configuration stored independently")
            incoming.objects("apiPresets").forEach { preset(it, it.string("id") ?: throw StError.Validation("preset ID missing")) }
            incoming["llm"]?.jsonObject?.let { llm ->
                val payload = JsonObject(llm + mapOf(
                    "generationDefaults" to (incoming["generationDefaults"] ?: JsonObject(emptyMap())),
                    "prompts" to (incoming["prompts"] ?: JsonObject(emptyMap())),
                ))
                defaultPresetId = preset(payload, "default-connection", "桌面默认连接")
            }
            incoming.objects("userPersonas").forEach { persona(it, it.string("id") ?: throw StError.Validation("persona ID missing")) }
        } ?: missing("settings", "settings.json")

        jsonDir("personas", "persona") { id, obj -> persona(obj, id) }
        jsonDir("world_books", "worldbook", "worldbooks") { id, obj -> worldBook(obj, id) }
        jsonDir("characters", "character") { id, obj -> character(obj, id) }
        jsonDir("presets", "preset") { id, obj -> preset(obj, id) }

        val chatsDir = root.resolve("chats")
        if (!chatsDir.isDirectory) missing("chat", "chats")
        else {
            val sidecars = setOf("chat_memory.json", "knowledge_graph.json", "mvu_state.json", "state_variables.json")
            chatsDir.walkTopDown().onEnter { it == chatsDir || it.name !in setOf("images", "attachments") }
                .filter { it.isFile && it.extension.lowercase() == "json" && it.name !in sidecars }
                .sortedBy { it.invariantSeparatorsPath }.forEach { file ->
                checkCancelled()
                val value = Jsonx.json.parseToJsonElement(file.readText())
                val obj = value as? JsonObject
                val shadowedLegacy = file.name != "chat.json" &&
                    file.parentFile?.resolve("${file.nameWithoutExtension}/chat.json")?.isFile == true
                if (file.name == "chat.json" || (!shadowedLegacy && obj != null && "messages" in obj && "characterId" in obj)) {
                    chat(obj ?: throw StError.Validation("invalid chat record: ${file.name}"), file)
                } else if (file.name == "assistant_chat.json" && obj != null) {
                    val main = file.parentFile?.resolve("chat.json")?.takeIf { it.isFile }?.let(::readObject)
                    assistantHistory(obj, file, main?.string("id"))
                } else {
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    merge.retain(relative, value)
                    items += ImportReportItem("source_data", relative, null, "retained", "sidecar or legacy source preserved")
                }
            }
        }

        root.resolve("assistant_settings.json").takeIf { it.isFile }?.let { file ->
            val obj = sanitizeSecrets(readObject(file)).jsonObject
            val id = merge.id("assistant", "desktop-assistant")
            val mapped = JsonObject(obj + mapOf("presetId" to (obj.string("presetId")?.let { JsonPrimitive(merge.id("preset", it)) }
                ?: defaultPresetId?.let(::JsonPrimitive) ?: JsonNull)))
            merge.merge("assistant", id, AssistantConfig(id, mapped.toString(), obj.time("updatedAt"), ns), content.getAssistant(id))
                ?.let { content.saveAssistant(it) }
            items += ImportReportItem("assistant_settings", file.name, id, "converted")
        }
        root.resolve("assistant_chat.json").takeIf { it.isFile }?.let { file ->
            assistantHistory(readObject(file), file, null)
        }
        importUsage()
        items += ImportReportItem("credentials", null, null, "missing", "credentials omitted; configure separately")
        importWorkspace()
    }

    private suspend fun importUsage() {
        val directory = root.resolve("usage").takeIf { it.isDirectory } ?: run {
            items += ImportReportItem("usage", "usage", null, "missing", "ledger directory absent; message metadata is not counted as a second ledger")
            return
        }
        val seenEvents = mutableSetOf<String>()
        for (file in directory.walkTopDown().filter { it.isFile }.sortedBy { it.invariantSeparatorsPath }) {
            checkCancelled()
            val relative = file.relativeTo(root).invariantSeparatorsPath
            when (file.extension.lowercase()) {
                "json" -> {
                    val text = file.inputStream().use { NativeJsonInput.read(it, 4_194_304) }
                    merge.retain(relative, Jsonx.json.parseToJsonElement(text))
                    items += ImportReportItem("usage_file", relative, null, "retained", "source index/JSON retained; not a ledger event")
                }
                "jsonl" -> {
                    var rows = 0L; var converted = 0L; var retained = 0L; var duplicates = 0L
                    val repairQueue = file.name.equals("repair.jsonl", ignoreCase = true)
                    file.inputStream().use { input ->
                        NativeJsonInput.lines(input, file.length(), maxLineBytes = 131_072, skipBlankLines = true) { line ->
                            checkCancelled()
                            rows++
                            val raw = Jsonx.json.parseToJsonElement(line)
                            merge.retain("$relative#row:$rows", raw)
                            val record = if (repairQueue || raw !is JsonObject) null else try {
                                DesktopUsageMapper.map(raw)
                            } catch (_: StError.Validation) { null }
                            when {
                                record == null -> retained++
                                !seenEvents.add(record.eventId) -> duplicates++
                                merge.usage(record) == null -> retained++
                                else -> converted++
                            }
                        }
                    }
                    items += ImportReportItem("usage_file", relative, null,
                        if (converted > 0) "converted" else "retained",
                        "ledger events=$converted; raw-only=$retained; duplicate events=$duplicates" +
                            if (repairQueue) "; repair queue retained, not replayed or counted" else "")
                }
                else -> items += ImportReportItem("usage_file", relative, null, "deferred", "unsupported usage file type; not converted")
            }
        }
    }

    private suspend fun preset(obj: JsonObject, sourceId: String, name: String? = null): String {
        checkCancelled()
        val id = merge.id("preset", sourceId)
        val incoming = ModelPreset(id, name ?: obj.string("name") ?: "桌面预设", sanitizeSecrets(obj).toString(),
            obj.time("createdAt"), obj.time("updatedAt"), ns, sourceId)
        merge.merge("preset", id, incoming, content.getPreset(id))?.let { content.savePreset(it) }
        items += ImportReportItem("preset", sourceId, id, "converted")
        return id
    }

    private suspend fun persona(obj: JsonObject, sourceId: String) {
        val id = merge.id("persona", sourceId)
        val avatar = obj.string("avatar")?.takeIf { it.isNotBlank() }?.let { filename ->
            merge.attachment("avatar:$filename", inside(root.resolve("avatars"), filename), originalName = filename)?.relativePath
        }
        val incoming = UserPersona(id, obj.string("name") ?: "Persona", obj.string("description").orEmpty(), avatar,
            obj.time("createdAt"), obj.time("updatedAt"), ns, sourceId, sanitizeSecrets(obj).toString())
        merge.merge("persona", id, incoming, content.getPersona(id))?.let { content.savePersona(it) }
        items += ImportReportItem("persona", sourceId, id, "converted")
    }

    private suspend fun character(obj: JsonObject, sourceId: String) {
        merge.claim("character", sourceId)
        val id = merge.id("character", sourceId)
        val avatar = obj.string("avatar")?.takeIf { it.isNotBlank() }?.let { filename ->
            merge.attachment("avatar:$filename", inside(root.resolve("avatars"), filename), originalName = filename)?.relativePath
        }
        val books = obj.strings("attachedWorldBookIds").map { merge.id("worldbook", it) }
        val incoming = CharacterCard(
            id, obj.string("name") ?: "角色", obj.string("description").orEmpty(), obj.string("personality").orEmpty(),
            obj.string("scenario").orEmpty(), obj.string("firstMessage").orEmpty(), obj.string("exampleDialogue").orEmpty(),
            obj.string("systemPrompt").orEmpty(), avatar, books, obj.boolean("mvuEnabled") ?: false,
            obj.string("mvuMode") ?: "regex", obj.string("mvuDirective"), obj["initialStateTables"]?.toString(),
            obj["contentRegexRules"]?.toString(), obj.time("createdAt"), obj.time("updatedAt"), ns, sourceId,
            sanitizeSecrets(obj).toString(),
        )
        merge.merge("character", id, incoming, content.getCharacter(id))?.let { content.saveCharacter(it) }
        items += ImportReportItem("character", sourceId, id, "converted")
    }

    private suspend fun worldBook(obj: JsonObject, sourceId: String) {
        merge.claim("worldbook", sourceId)
        val id = merge.id("worldbook", sourceId)
        val entries = obj.objects("entries").mapIndexed { index, entry ->
            WorldBookEntry(merge.id("worldbook_entry", entry.string("id") ?: "entry:$index", sourceId),
                entry.string("title").orEmpty(), entry.string("regex").orEmpty(), entry.string("content").orEmpty(),
                entry.boolean("enabled") ?: true, entry.integer("orderIndex") ?: index)
        }
        val sessionIds = obj.strings("sessionChatIds").map { merge.id("chat", it) }
        val incoming = WorldBook(id, obj.string("name") ?: "世界书", entries, obj.boolean("globalActive") ?: false,
            sessionIds, obj.time("createdAt"), obj.time("updatedAt"), ns, sourceId, sanitizeSecrets(obj).toString())
        merge.merge("worldbook", id, incoming, content.getWorldBook(id))?.let { content.saveWorldBook(it) }
        items += ImportReportItem("worldbook", sourceId, id, "converted")
    }

    private suspend fun chat(obj: JsonObject, file: File) {
        val chatDir = requireNotNull(file.parentFile) { "chat directory missing" }
        val sourceId = obj.string("id") ?: file.relativeTo(root).invariantSeparatorsPath
        val id = merge.id("chat", sourceId)
        val characterSourceId = obj.string("characterId") ?: chatDir.parentFile?.name ?: "unknown"
        val members = obj.strings("memberIds").map { merge.id("character", it) }
        val memberSettings = mutableMapOf<String, GroupMemberSettings>()
        (obj["memberSettings"] as? JsonObject)?.forEach { (member, data) ->
            val raw = Jsonx.decode<GroupMemberSettings>(data.toString())
            memberSettings[merge.id("character", member)] = raw.copy(
                presetId = raw.presetId?.let { merge.id("preset", it) },
                topP = raw.topP ?: (data as? JsonObject)?.number("top_p"),
            )
        }
        val rawOverrides = obj["overrides"] as? JsonObject ?: JsonObject(emptyMap())
        val overrides = overrides(rawOverrides, sourceId)
        val incoming = ChatSession(
            id = id, characterId = merge.id("character", characterSourceId), title = obj.string("title") ?: "对话",
            userPersonaId = (obj.string("userPersonaId") ?: settings?.string("selectedPersonaId"))?.let { merge.id("persona", it) },
            isGroup = obj.boolean("isGroup") ?: false, memberIds = members, memberSettings = memberSettings,
            groupDelayMs = obj.integer("groupDelay") ?: 1500,
            groupSystemInjectDepth = obj.integer("groupSystemInjectDepth") ?: 5,
            groupSystemAlwaysAtBottom = obj.boolean("groupSystemAlwaysAtBottom") ?: true,
            overrides = overrides,
            forkedFromChatId = obj.string("forkedFromChatId")?.let { merge.id("chat", it) },
            forkedFromMessageId = obj.string("forkedFromMessageId")?.let { message ->
                merge.id("message", message, obj.string("forkedFromChatId") ?: sourceId)
            },
            forkedFromMessageIndex = obj.integer("forkedFromMessageIndex"), createdAt = obj.time("createdAt"),
            updatedAt = obj.time("updatedAt"), sourceNamespace = ns, sourceId = sourceId,
            extraJson = sanitizeSecrets(JsonObject(obj - "messages")).toString(),
        )
        val nodes = obj.objects("messages").mapIndexed { index, message ->
            val sourceMessageId = merge.sourceIdentity("message", message.string("id"))
            val variants = message.strings("greetingVariants").takeIf { it.isNotEmpty() }
                ?: listOf(message.string("content").orEmpty())
            val selected = (message.integer("greetingVariantIndex") ?: 0).coerceIn(0, variants.lastIndex)
            val variantReasonings = message["greetingVariantReasoningContents"] as? JsonArray
            val variantDurations = message["greetingVariantReasoningDurations"] as? JsonArray
            val candidates = variants.mapIndexed { variant, text ->
                val candidateId = if (variant == 0) merge.id("message", sourceMessageId, sourceId)
                    else merge.id("message_variant", variant.toString(), scoped(sourceId, sourceMessageId))
                val images = message.objects("images").mapNotNull { attachment(it, chatDir, id, candidateId) }
                val files = message.objects("attachments").mapNotNull { attachment(it, chatDir, id, candidateId) }
                val variantReasoning = if (variantReasonings != null) {
                    variantReasonings.getOrNull(variant)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                } else if (variant == selected) message.string("reasoningContent")?.takeIf { it.isNotBlank() } else null
                val variantDuration = if (variantDurations != null) {
                    variantDurations.getOrNull(variant)?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it > 0 }
                } else if (variant == selected) message.number("reasoningDurationSec")?.takeIf { it.isFinite() && it >= 0 } else null
                MessageCandidate(
                    id = candidateId, role = role(message.string("role")), content = text,
                    reasoningContent = variantReasoning,
                    reasoningDurationSec = variantDuration, images = images, attachments = files,
                    characterId = message.string("characterId")?.let { merge.id("character", it) },
                    senderPersonaId = message.string("senderPersonaId")?.let { merge.id("persona", it) },
                    senderName = message.string("senderName"), senderAvatar = message.string("senderAvatar")?.let { filename ->
                        merge.attachment("avatar:$filename", inside(root.resolve("avatars"), filename), originalName = filename)?.relativePath
                    },
                    toolCallId = message.string("tool_call_id"), toolCallsJson = message["tool_calls"]?.toString(),
                    toolRecordJson = message["toolRecord"]?.let(::sanitizeSecrets)?.toString(), usageJson = message["usage"]?.toString(),
                    generationMetadataJson = message["generationMetadata"]?.let(::sanitizeSecrets)?.toString(),
                    createdAt = message.time("ts"), extraJson = sanitizeSecrets(message).toString(),
                )
            }
            MessageNode(merge.id("node", sourceMessageId, sourceId), id, index.toLong(), candidates, selected)
        }
        if (!merge.chat(incoming, nodes)) return
        val memoryJson = chatDir.resolve("chat_memory.json").takeIf { it.isFile }
        val memoryTxt = listOf("long_term_memory.txt", "memory.txt").map { chatDir.resolve(it) }.firstOrNull { it.isFile }
        val memoryText = when {
            memoryJson != null -> when (val value = Jsonx.json.parseToJsonElement(memoryJson.readText())) {
                is JsonObject -> value.string("longTermMemory") ?: value.string("content")
                is JsonPrimitive -> value.contentOrNull
                else -> throw StError.Validation("invalid chat memory: ${memoryJson.name}")
            }
            memoryTxt != null -> memoryTxt.readText()
            else -> rawOverrides.string("longTermMemory")
        }
        memoryText?.let { merge.longTermMemory(LongTermMemory(id, it, updatedAt = incoming.updatedAt, source = "import")) }
        val kgFile = chatDir.resolve("knowledge_graph.json")
        if (kgFile.isFile) merge.kg(Jsonx.decode<KnowledgeGraph>(kgFile.readText()).copy(chatId = id))
        val state = obj["stateVariables"] as? JsonObject
        if (state != null) merge.mvu(MvuState(id, state.integer("version") ?: 1, state.time("updatedAt"), "import",
            Jsonx.decode(state["tables"]?.toString() ?: "[]")))
        items += ImportReportItem("chat", sourceId, id, "converted")
    }

    private suspend fun overrides(obj: JsonObject, chatSourceId: String): ChatOverrides {
        val fields = obj.toMutableMap()
        if (obj.string("sessionSystemPromptMode") == "override") fields["sessionSystemPromptMode"] = JsonPrimitive("override_global")
        fields.remove("assistant") // Android binding/permissions are not executable desktop source fields.
        listOf("params" to "paramsJson", "tts" to "ttsJson", "contentRegexRules" to "contentRegexRulesJson").forEach { (from, to) ->
            obj[from]?.let { fields[to] = JsonPrimitive(sanitizeSecrets(it).toString()) }
        }
        val raw = Jsonx.decode<ChatOverrides>(JsonObject(fields).toString())
        val tts = raw.ttsJson?.let { speechOverrides(it) }
        val rawBooks = raw.worldBookAttachments.ifEmpty { obj.strings("worldBookIds").map { WorldBookAttachment(it) } }
        val books = rawBooks.map { it.copy(worldBookId = merge.id("worldbook", it.worldBookId)) }
        val exclusions = raw.worldBookGlobalExclusions.map { merge.id("worldbook", it) }
        return raw.copy(
            ttsJson = tts,
            longTermMemory = null, // The memory store owns the current editable value.
            presetId = raw.presetId?.let { merge.id("preset", it) } ?: defaultPresetId,
            pureAiMode = raw.pureAiMode ?: settings?.boolean("pureAiMode"),
            worldBookAttachments = books, worldBookGlobalExclusions = exclusions,
            contextStartMessageId = raw.contextStartMessageId?.let { merge.id("message", it, chatSourceId) },
            lastAutoMemorySummaryAfterMessageId = raw.lastAutoMemorySummaryAfterMessageId?.let { merge.id("message", it, chatSourceId) },
            lastStateMaintenanceAfterMessageId = raw.lastStateMaintenanceAfterMessageId?.let { merge.id("message", it, chatSourceId) },
            groupMvuAnchorCharacterId = raw.groupMvuAnchorCharacterId?.let { merge.id("character", it) },
            groupMvuTemplateCharacterId = raw.groupMvuTemplateCharacterId?.let { merge.id("character", it) },
            extraJson = sanitizeSecrets(obj).toString(),
        )
    }

    /** Remap references only; model/voice values and unknown source controls stay unchanged. */
    private suspend fun speechOverrides(raw: String): String {
        if (raw.toByteArray(Charsets.UTF_8).size > 65_536) throw StError.Validation("desktop TTS configuration exceeds 64 KiB")
        val obj = NativeJsonInput.parse(raw) as? JsonObject ?: throw StError.Validation("desktop TTS configuration must be an object")
        val fields = obj.toMutableMap()
        for (key in listOf("presetId", "preprocessPresetId")) {
            val value = obj[key]?.takeIf { it != JsonNull } ?: continue
            val id = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw StError.Validation("desktop TTS preset reference must be text")
            fields[key] = JsonPrimitive(merge.id("preset", id))
        }
        for ((key, type) in listOf("voiceByCharacterId" to "character", "voiceByPersonaId" to "persona")) {
            val value = obj[key]?.takeIf { it != JsonNull } ?: continue
            val map = value as? JsonObject ?: throw StError.Validation("desktop TTS voice mapping must be an object")
            val remapped = mutableMapOf<String, JsonElement>()
            for ((sourceId, voice) in map) {
                if (voice !is JsonPrimitive || !voice.isString) throw StError.Validation("desktop TTS voice ID must be text")
                val targetId = merge.id(type, sourceId)
                if (remapped.put(targetId, voice) != null) throw StError.Validation("desktop TTS voice references collide")
            }
            fields[key] = JsonObject(remapped)
        }
        return JsonObject(fields).toString().also {
            if (it.toByteArray(Charsets.UTF_8).size > 65_536) throw StError.Validation("mapped desktop TTS configuration exceeds 64 KiB")
        }
    }

    private suspend fun assistantHistory(obj: JsonObject, file: File, targetSourceId: String?) {
        val sourceScope = targetSourceId ?: if (file.parentFile == root) "global" else "unbound:${file.relativeTo(root).invariantSeparatorsPath}"
        val sourceId = "assistant-history:$sourceScope"
        val configId = merge.id("assistant", "desktop-assistant")
        if (content.getAssistant(configId) == null) {
            val settingsFile = root.resolve("assistant_settings.json").takeIf { it.isFile }
            val raw = settingsFile?.let { sanitizeSecrets(readObject(it)).jsonObject } ?: JsonObject(emptyMap())
            val config = JsonObject(raw + mapOf("presetId" to (raw.string("presetId")?.let { JsonPrimitive(merge.id("preset", it)) }
                ?: defaultPresetId?.let(::JsonPrimitive) ?: JsonNull)))
            merge.merge("assistant", configId, AssistantConfig(configId, config.toString(), raw.time("updatedAt"), ns), null)?.let { content.saveAssistant(it) }
        }
        val chatId = merge.id("assistant_chat", sourceId)
        val targetId = targetSourceId?.let { merge.id("chat", it) }
        val session = ChatSession(chatId, "", title = if (targetId == null) "桌面聊天助手" else "桌面会话助手",
            overrides = ChatOverrides(assistant = AssistantChatBinding(configId, targetId)),
            createdAt = obj.time("createdAt"), updatedAt = obj.time("updatedAt"), sourceNamespace = ns, sourceId = sourceId,
            extraJson = sanitizeSecrets(obj).toString())
        val nodes = obj.objects("messages").mapIndexed { index, message ->
            checkCancelled()
            val sourceMessage = message.string("id") ?: "message:$index"
            val variants = (message["greetingVariants"] as? JsonArray)?.map { it.jsonPrimitive.contentOrNull.orEmpty() }?.takeIf { it.isNotEmpty() }
                ?: listOf(message.string("content").orEmpty())
            val selected = (message.integer("greetingVariantIndex") ?: 0).coerceIn(0, variants.lastIndex)
            val variantReasonings = message["greetingVariantReasoningContents"] as? JsonArray
            val variantDurations = message["greetingVariantReasoningDurations"] as? JsonArray
            val candidates = variants.mapIndexed { variant, text ->
                val messageId = if (variant == 0) merge.id("assistant_message", sourceMessage, sourceId)
                    else merge.id("assistant_message_variant", variant.toString(), scoped(sourceId, sourceMessage))
                val images = message.objects("images").mapNotNull { attachment(it, file.parentFile!!, chatId, messageId) }
                val files = message.objects("attachments").mapNotNull { item -> assistantAttachment(item, chatId, messageId) }
                val variantReasoning = if (variantReasonings != null) {
                    variantReasonings.getOrNull(variant)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                } else if (variant == selected) message.string("reasoningContent")?.takeIf { it.isNotBlank() } else null
                val variantDuration = if (variantDurations != null) {
                    variantDurations.getOrNull(variant)?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it > 0 }
                } else if (variant == selected) message.number("reasoningDurationSec")?.takeIf { it.isFinite() && it >= 0 } else null
                MessageCandidate(messageId, role(message.string("role")), text,
                    reasoningContent = variantReasoning, reasoningDurationSec = variantDuration, images = images, attachments = files,
                    toolCallId = message.string("tool_call_id"), toolCallsJson = message["tool_calls"]?.toString(),
                    toolRecordJson = message["toolRecord"]?.let(::sanitizeSecrets)?.toString(), usageJson = message["usage"]?.toString(),
                    generationMetadataJson = message["generationMetadata"]?.let(::sanitizeSecrets)?.toString(), createdAt = message.time("ts"), extraJson = sanitizeSecrets(message).toString())
            }
            MessageNode(merge.id("assistant_node", sourceMessage, sourceId), chatId, index.toLong(), candidates, selected)
        }
        val converted = merge.chat(session, nodes)
        merge.retain(file.relativeTo(root).invariantSeparatorsPath, sanitizeSecrets(obj))
        items += ImportReportItem("assistant_chat", sourceId, chatId, if (converted) "converted" else "retained", "independent assistant history; original source retained")
    }

    private suspend fun assistantAttachment(item: JsonObject, chatId: String, messageId: String): AttachmentRef? {
        val scope = item.string("storageScope")
        val key = item.string("storageKey")
        val filename = item.string("filename")
        if (scope !in setOf("assistant_chat", "workspace_session") || key.isNullOrBlank() || filename.isNullOrBlank() ||
            key.any { it == '/' || it == '\\' || it == ':' } || filename.any { it == '/' || it == '\\' || it == ':' }) {
            items += ImportReportItem("assistant_attachment", item.string("id"), null, "retained", "unsupported ingest reference; metadata retained")
            return null
        }
        val relative = AssistantWorkspaceStore.normalize("ingest/$scope/$key/$filename")
        val source = inside(root.resolve("ai_workspace"), relative)
        return merge.attachment("assistant-ingest:$relative", source, chatId, messageId,
            originalName = item.string("originalName") ?: filename, mime = item.string("mimeType"))
    }

    private suspend fun importWorkspace() {
        val directory = root.resolve("ai_workspace")
        if (!directory.isDirectory) return
        val configId = merge.id("assistant", "desktop-assistant")
        if (content.getAssistant(configId) == null) {
            val settingsFile = root.resolve("assistant_settings.json").takeIf { it.isFile }
            val raw = settingsFile?.let { sanitizeSecrets(readObject(it)).jsonObject } ?: JsonObject(emptyMap())
            val config = JsonObject(raw + mapOf("presetId" to (raw.string("presetId")?.let { JsonPrimitive(merge.id("preset", it)) }
                ?: defaultPresetId?.let(::JsonPrimitive) ?: JsonNull)))
            merge.merge("assistant", configId, AssistantConfig(configId, config.toString(), raw.time("updatedAt"), ns), null)?.let { content.saveAssistant(it) }
        }
        val texts = setOf("json", "md", "txt", "log", "csv", "yaml", "yml", "toml", "xml", "html", "css", "js", "ts", "py", "kt", "sh")
        directory.walkTopDown().filter { it.isFile }.sortedBy { it.invariantSeparatorsPath }.forEach { file ->
            checkCancelled()
            val path = AssistantWorkspaceStore.normalize(file.relativeTo(directory).invariantSeparatorsPath)
            if (path == "assistant_workspace_chat.json") {
                assistantHistory(readObject(file), file, null)
                return@forEach
            }
            val text = if (file.extension.lowercase() in texts && file.length() <= AssistantWorkspaceStore.MAX_TEXT_BYTES) {
                runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(file.readBytes())).toString().removePrefix("\uFEFF") }.getOrNull()
            } else null
            if (text != null && !path.startsWith("ingest/")) {
                val payload = if (path == "character_card.json") {
                    val obj = sanitizeSecrets(Jsonx.json.parseToJsonElement(text)).jsonObject
                    val sourceId = obj.string("id") ?: "workspace-draft"
                    val avatar = obj.string("avatar")?.takeIf { it.isNotBlank() }?.let { filename ->
                        merge.attachment("avatar:$filename", inside(root.resolve("avatars"), filename), originalName = filename)?.relativePath
                    }
                    Jsonx.encode(CharacterCard(merge.id("character", sourceId), obj.string("name") ?: "角色", obj.string("description").orEmpty(),
                        obj.string("personality").orEmpty(), obj.string("scenario").orEmpty(), obj.string("firstMessage").orEmpty(), obj.string("exampleDialogue").orEmpty(),
                        obj.string("systemPrompt").orEmpty(), avatar, obj.strings("attachedWorldBookIds").map { merge.id("worldbook", it) },
                        obj.boolean("mvuEnabled") ?: false, obj.string("mvuMode") ?: "regex", obj.string("mvuDirective"), obj["initialStateTables"]?.toString(),
                        obj["contentRegexRules"]?.toString(), obj.time("createdAt"), obj.time("updatedAt"), ns, sourceId, obj.toString()))
                } else text
                val incoming = AssistantWorkspaceFile(configId, path, payload, IMPORT_EPOCH, ns, "ai_workspace/$path")
                val id = AssistantWorkspaceStore.identity(configId, path)
                val current = workspace.get(configId, path)
                if (path == "character_card.json") {
                    // Merge draft card fields, not one opaque JSON string; unrelated source changes can coexist with local edits.
                    fun structured(file: AssistantWorkspaceFile): JsonObject {
                        val fields = Jsonx.json.parseToJsonElement(Jsonx.encode(file)).jsonObject
                        val card = runCatching { Jsonx.json.parseToJsonElement(file.content) }.getOrNull() as? JsonObject
                        return JsonObject(fields + ("content" to (card ?: JsonPrimitive(file.content))))
                    }
                    val merged = merge.mergeJson("workspace_file", id, structured(incoming), current?.let(::structured)) as? JsonObject
                    merged?.let { result ->
                        val body = result.getValue("content")
                        val textBody = if (body is JsonPrimitive && body.isString) body.content else body.toString()
                        workspace.saveImported(Jsonx.decode<AssistantWorkspaceFile>(JsonObject(result + ("content" to JsonPrimitive(textBody))).toString()))
                    }
                } else merge.merge("workspace_file", id, incoming, current)?.let { workspace.saveImported(it) }
                items += ImportReportItem("assistant_workspace_file", path, id, "converted", "isolated text workspace; never automatically published")
            } else {
                val ref = merge.attachment("workspace-asset:$path", file, originalName = file.name) ?: return@forEach
                val incoming = AssistantWorkspaceAsset(configId, path, ref, IMPORT_EPOCH, ns)
                val id = AssistantWorkspaceStore.assetIdentity(configId, path)
                merge.merge("workspace_asset", id, incoming, workspace.getAsset(configId, path))?.let { workspace.saveImportedAsset(it) }
                items += ImportReportItem("assistant_workspace_asset", path, ref.id, "converted", "managed file retained; binary/large inputs still subject to generation capabilities")
            }
        }
    }

    private suspend fun attachment(obj: JsonObject, chatDir: File, chatId: String, messageId: String): AttachmentRef? {
        val filename = obj.string("filename") ?: obj.string("originalName") ?: return null
        val sourceId = obj.string("id") ?: filename
        val candidates = listOf(inside(chatDir.resolve("images"), filename), inside(chatDir.resolve("attachments"), filename),
            obj.string("relativePath")?.let { inside(root, it) }, inside(root.resolve("attachments"), filename))
        val sourceFile = candidates.firstOrNull { it?.isFile == true }
        return merge.attachment(sourceId, sourceFile, chatId, messageId, obj.string("originalName") ?: filename,
            obj.string("mimeType"))?.copy(width = obj.integer("width"), height = obj.integer("height"))
    }

    private suspend fun jsonDir(
        name: String, type: String, alternative: String? = null, save: suspend (String, JsonObject) -> Unit,
    ) {
        val dir = root.resolve(name).takeIf { it.isDirectory } ?: alternative?.let { root.resolve(it).takeIf { it.isDirectory } }
        if (dir == null) { missing(type, name); return }
        dir.walkTopDown().filter { it.isFile && it.extension.lowercase() == "json" }.sortedBy { it.name }.forEach { file ->
            checkCancelled()
            val obj = readObject(file)
            save(obj.string("id") ?: file.relativeTo(dir).invariantSeparatorsPath, obj)
        }
    }

    private fun missing(type: String, path: String) { items += ImportReportItem(type, path, null, "missing", "not present in archive") }
}

internal fun role(value: String?): ChatRole = when (value?.lowercase()) {
    "system" -> ChatRole.system
    "user" -> ChatRole.user
    "tool" -> ChatRole.tool
    "reasoning" -> ChatRole.reasoning
    "assistant", "model" -> ChatRole.assistant
    else -> throw StError.Validation("unknown message role: $value")
}
