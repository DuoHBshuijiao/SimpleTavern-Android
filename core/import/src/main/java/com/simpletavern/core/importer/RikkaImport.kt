package com.simpletavern.core.importer

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.simpletavern.core.data.ContentRepository
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.model.*
import java.io.File
import java.net.URI
import kotlinx.serialization.json.*

internal class RikkaImport(
    private val root: File,
    private val staging: File,
    private val content: ContentRepository,
    private val merge: ImportMerge,
    private val items: MutableList<ImportReportItem>,
    private val checkCancelled: suspend () -> Unit,
) {
    private val ns get() = merge.namespace
    private var settings: JsonObject? = null
    private val assistants = mutableMapOf<String, JsonObject>()
    private val sourceMemories = linkedMapOf<String, ImportedAssistantMemory>()
    private var sourceMemoryConverted = false
    private val sourceModels = mutableMapOf<String, Pair<JsonObject, String>>()
    private val sourceModelProviders = mutableMapOf<String, String>()
    private val seenUsageMessages = mutableSetOf<String>()
    private val usageLocalZone = java.time.ZoneId.systemDefault()

    suspend fun run() {
        settings = root.resolve("settings.json").takeIf { it.isFile }?.let(::readObject)
        settings?.let { obj ->
            val clean = sanitizeSecrets(obj).jsonObject
            val id = merge.id("source_settings", "settings")
            merge.merge("source_settings", id, AssistantConfig(id, clean.toString(), IMPORT_EPOCH, ns), content.getAssistant(id))
                ?.let { content.saveAssistant(it) }
            merge.retain("settings.json", clean)
            items += ImportReportItem("settings", "settings.json", id, "converted", "source configuration stored independently")
            obj.objects("providers").forEach { provider(it) }
            obj.objects("ttsProviders").forEach { ttsProvider(it) }
            obj.objects("lorebooks").forEach { lorebook(it) }
            obj.objects("modeInjections").forEach { injection ->
                val sourceId = injection.string("id") ?: throw StError.Validation("mode injection ID missing")
                merge.claim("worldbook", "mode:$sourceId")
                val id = merge.id("worldbook", "mode:$sourceId")
                val book = WorldBook(id, injection.string("name") ?: "模式注入",
                    listOf(WorldBookEntry(merge.id("worldbook_entry", sourceId, "mode:$sourceId"), regex = "(?s:.*)", content = injection.string("content").orEmpty(),
                        enabled = injection.boolean("enabled") ?: true, constantActive = true,
                        priority = injection.integer("priority") ?: 0,
                        position = injection.string("position") ?: "after_system_prompt",
                        injectDepth = injection.integer("injectDepth") ?: 4,
                        role = injectionRole(injection))), createdAt = IMPORT_EPOCH, updatedAt = IMPORT_EPOCH,
                    sourceNamespace = ns, sourceId = "mode:$sourceId", extraJson = sanitizeSecrets(injection).toString())
                merge.merge("worldbook", id, book, content.getWorldBook(id))?.let { content.saveWorldBook(it) }
                items += ImportReportItem("worldbook", "mode:$sourceId", id, "converted", "mode injection controls mapped")
            }
            obj.objects("assistants").forEach { assistant(it) }
        } ?: run { items += ImportReportItem("settings", null, null, "missing", "settings not in archive") }

        val databaseFiles = root.walkTopDown().filter { it.isFile && it.name == "rikka_hub.db" }.toList()
        if (databaseFiles.size > 1) throw StError.Validation("multiple RikkaHub databases in archive")
        val sourceDb = databaseFiles.singleOrNull()
        if (sourceDb == null) {
            if (settings == null) throw StError.Validation("RikkaHub settings and database are both absent")
            items += ImportReportItem("database", "rikka_hub.db", null, "missing", "settings-only archive; no chat history imported")
        } else {
            val work = staging.resolve("rikka-working").also { require(it.mkdirs()) }
            val copy = work.resolve("rikka_hub.db")
            sourceDb.copyTo(copy)
            listOf(root.resolve("rikka_hub-wal"), File(sourceDb.path + "-wal")).firstOrNull { it.isFile }
                ?.copyTo(File(copy.path + "-wal"))
            SQLiteDatabase.openDatabase(copy.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val version = db.rawQuery("PRAGMA user_version", null).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
                items += ImportReportItem("schema", version.toString(), null, "converted", "source user_version=$version; columns inspected before querying")
                val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                val assistantTable = listOf("assistant", "AssistantEntity").firstOrNull { it in tables }
                assistantTable?.let { table -> db.rawQuery("SELECT * FROM ${quoted(table)}", null).use { cursor ->
                    while (cursor.moveToNext()) assistant(row(cursor))
                } }
                val memoryTable = listOf("MemoryEntity", "memory").firstOrNull { it in tables }
                memoryTable?.let { table -> db.rawQuery("SELECT * FROM ${quoted(table)} ORDER BY id", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val obj = row(cursor)
                        val sourceId = merge.sourceIdentity("assistant_memory", obj.string("id"))
                        merge.retain("assistant-memory/$sourceId", obj)
                        val assistantId = obj.string("assistant_id") ?: obj.string("assistantId")
                        if (assistantId == null) {
                            items += ImportReportItem("assistant_memory", sourceId, null, "retained", "memory owner missing; not injected")
                            continue
                        }
                        if (sourceId in sourceMemories) throw StError.Validation("duplicate source memory ID")
                        sourceMemories[sourceId] = ImportedAssistantMemory(assistantId, obj.string("content").orEmpty())
                    }
                } }
                if (memoryTable != null) {
                    val libraryId = merge.id("source_memory", "memories")
                    val library = AssistantConfig(libraryId, Jsonx.encode(ImportedMemoryLibrary(sourceMemories)), IMPORT_EPOCH, ns)
                    merge.merge("source_memory", libraryId, library, content.getAssistant(libraryId))?.let { content.saveAssistant(it) }
                    sourceMemoryConverted = true
                    items += ImportReportItem("assistant_memory", "memories", libraryId, "converted", "shared source-scoped memory library; __global__ remains separate")
                }
                val conversations = listOf("ConversationEntity", "conversation").firstOrNull { it in tables }
                    ?: throw StError.Validation("no supported conversation table")
                val nodeTable = listOf("message_node", "MessageNodeEntity").firstOrNull { it in tables }
                val nodeColumns = nodeTable?.let { columns(db, it) }.orEmpty()
                val conversationColumn = listOf("conversation_id", "conversationId").firstOrNull { it in nodeColumns }
                val orderColumn = listOf("node_index", "order_index", "order", "orderIndex").firstOrNull { it in nodeColumns }
                if (nodeTable != null && conversationColumn == null) throw StError.Validation("unsupported message table: no conversation reference")
                db.rawQuery("SELECT * FROM ${quoted(conversations)} ORDER BY id", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        checkCancelled()
                        val obj = row(cursor)
                        val sourceId = obj.string("id") ?: throw StError.Validation("conversation ID missing")
                        val rawNodes = if (nodeTable != null) {
                            val order = orderColumn?.let(::quoted) ?: "rowid"
                            db.rawQuery("SELECT * FROM ${quoted(nodeTable)} WHERE ${quoted(conversationColumn!!)} = ? ORDER BY $order ASC",
                                arrayOf(sourceId)).use { nodes -> buildList { while (nodes.moveToNext()) add(row(nodes)) } }
                        } else {
                            val legacy = obj.string("nodes")
                            if (legacy.isNullOrBlank()) emptyList() else Jsonx.json.parseToJsonElement(legacy).jsonArray.map { it.jsonObject }
                        }
                        chat(obj, rawNodes)
                    }
                }
            }
        }
        items += ImportReportItem("credentials", null, null, "missing", "credentials omitted; configure separately")
        for (folder in listOf("fonts")) {
            if (root.resolve(folder).isDirectory) items += ImportReportItem(folder, folder, null, "deferred", "not installed or executed")
        }
    }

    private suspend fun ttsProvider(obj: JsonObject) {
        checkCancelled()
        val sourceId = obj.string("id") ?: throw StError.Validation("TTS provider ID missing")
        merge.claim("tts_provider", sourceId)
        val id = merge.id("preset", "tts:$sourceId")
        val type = obj.string("type") ?: throw StError.Validation("TTS provider type missing")
        val payload = buildJsonObject {
            put("presetKind", if (type == "system") "system_tts" else "tts")
            put("sourceFormat", "rikkahub_tts"); put("sourceTTSProviderId", sourceId)
            put("ttsProvider", type); put("credentialId", id)
            obj.string("baseUrl")?.let { put("baseUrl", it) }
            put("sourceTTS", sanitizeSecrets(obj))
            (obj.string("model") ?: when (type) { "xai" -> "xai-tts"; "volcengine" -> obj.string("resourceId") ?: "seed-tts-2.0"; else -> null })?.let { put("model", it) }
            (obj.string("voiceId") ?: obj.string("voice") ?: obj.string("voiceName") ?: obj.string("speaker") ?: obj.string("referenceId")
                ?: "".takeIf { type == "fish-audio" })?.let { put("voiceId", it) }
            if (type in setOf("groq", "gemini", "mimo")) put("format", "wav")
            else (obj.string("format") ?: obj.string("responseFormat"))?.let { put("format", it) }
            if (type == "step") obj.string("instruction")?.let { put("instructions", it) }
            if (type == "elevenlabs") {
                obj["stability"]?.let { put("stability", it) }
                obj["similarityBoost"]?.let { put("similarityBoost", it) }
            }
        }
        val preset = ModelPreset(id, obj.string("name")?.takeIf { it.isNotBlank() } ?: "RikkaHub TTS / $type",
            payload.toString(), IMPORT_EPOCH, IMPORT_EPOCH, ns, "tts:$sourceId")
        merge.merge("preset", id, preset, content.getPreset(id))?.let { content.savePreset(it) }
        val supported = type == "system" || type in SpeechProviders.cloud
        items += ImportReportItem("tts_provider", sourceId, id, if (supported) "converted" else "retained",
            if (supported) "TTS selection metadata converted; credentials omitted" else "TTS metadata retained; runtime adapter still required")
    }

    private suspend fun provider(obj: JsonObject) {
        checkCancelled()
        val sourceId = obj.string("id") ?: throw StError.Validation("provider ID missing")
        merge.claim("provider", sourceId)
        val fallbackProtocol = when (obj.string("type")?.lowercase()) {
            "claude", "anthropic" -> "anthropic_messages"
            "google", "gemini" -> "gemini_generate_content"
            "openai" -> if (obj.boolean("useResponseApi") == true) "openai_responses" else "openai_compatible_chat"
            else -> "rikka_unsupported_provider"
        }
        val providerId = merge.id("preset", "provider:$sourceId")
        val clean = sanitizeSecrets(obj).jsonObject
        val canonical = RikkaModelProviderMapper.map(clean)
        val protocol = canonical?.string("protocol") ?: fallbackProtocol
        // Canonical controls are applied once; retained raw aliases must not override a later custom body.
        val providerPayload = JsonObject((canonical ?: clean).filterKeys { it !in GenerationParameters.sourceKeys } +
            controls(obj, sourceId) + mapOf("protocol" to JsonPrimitive(protocol)))
        val incoming = ModelPreset(providerId, obj.string("name") ?: "RikkaHub 供应商", providerPayload.toString(),
            IMPORT_EPOCH, IMPORT_EPOCH, ns, "provider:$sourceId")
        merge.merge("preset", providerId, incoming, content.getPreset(providerId))?.let { content.savePreset(it) }
        obj.objects("models").forEach { model ->
            val sourceModelId = model.string("id") ?: throw StError.Validation("model ID missing")
            val id = merge.id("preset", "model:$sourceModelId")
            val overwrite = model["providerOverwrite"]?.takeIf { it != JsonNull }?.let {
                it as? JsonObject ?: throw StError.Validation("invalid RikkaHub model provider overwrite")
            }
            val convertedOverwrite = overwrite?.let { RikkaModelProviderMapper.map(sanitizeSecrets(it).jsonObject) }
            val connectionPayload = if (convertedOverwrite == null) providerPayload else JsonObject(
                convertedOverwrite.filterKeys { it !in GenerationParameters.sourceKeys } + controls(overwrite!!, sourceModelId))
            val effectiveProtocol = connectionPayload.string("protocol") ?: protocol
            sourceModels[sourceModelId] = model to effectiveProtocol
            sourceModelProviders[sourceModelId] = if (convertedOverwrite == null) providerId else id
            val selectedModel = sanitizeSecrets(model).jsonObject.let {
                if (convertedOverwrite == null) it else JsonObject(it - "providerOverwrite")
            }
            val payload = JsonObject(connectionPayload + controls(model, sourceModelId) +
                mapOf("selectedModel" to selectedModel, "sourceProviderId" to JsonPrimitive(providerId)) +
                (if (convertedOverwrite == null) emptyMap() else mapOf("credentialId" to JsonPrimitive(id))))
            val preset = ModelPreset(id, "${obj.string("name").orEmpty()} / ${model.string("displayName") ?: model.string("modelId") ?: sourceModelId}",
                payload.toString(), IMPORT_EPOCH, IMPORT_EPOCH, ns, "model:$sourceModelId")
            merge.merge("preset", id, preset, content.getPreset(id))?.let { content.savePreset(it) }
            if (overwrite != null) items += ImportReportItem("model_provider_overwrite", sourceModelId, id,
                if (convertedOverwrite == null) "deferred" else "converted",
                if (convertedOverwrite == null) "unsupported override retained; original connection will not be used implicitly"
                else "replacement connection converted; configure credentials on this model preset separately")
        }
        items += ImportReportItem("provider", sourceId, providerId, "converted")
        if (canonical == null) items += ImportReportItem("provider_connection", sourceId, providerId, "deferred",
            "provider connection shape is unsupported or requires a query adapter; source fields retained")
    }

    /** Fixed official source shape: customBodies = [{key, value}], not a free-form request replacement. */
    private fun controls(obj: JsonObject, sourceId: String?, bodiesOnly: Boolean = false): JsonObject {
        val result = if (bodiesOnly) mutableMapOf() else GenerationParameters.normalize(
            JsonObject(sanitizeSecrets(obj).jsonObject.filterKeys { it in GenerationParameters.sourceKeys })).toMutableMap()
        val bodies = obj["customBodies"]?.takeIf { it != JsonNull }?.let { it as? JsonArray ?: throw StError.Validation("invalid RikkaHub customBodies") }
        if ((bodies?.size ?: 0) > 256 || (bodies?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0) > 65_536)
            throw StError.Validation("RikkaHub generation controls exceed budget")
        var deferred = 0
        bodies?.forEach { value ->
            val body = value as? JsonObject ?: throw StError.Validation("invalid RikkaHub custom body item")
            val key = (body["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw StError.Validation("RikkaHub custom body key missing")
            if (key !in GenerationParameters.sourceKeys) deferred++
            else {
                val clean = sanitizeSecrets(body).jsonObject
                clean["value"]?.let { result.putAll(GenerationParameters.normalize(buildJsonObject { put(key, it) })) }
                    ?: throw StError.Validation("RikkaHub generation control value missing")
            }
        }
        if (deferred > 0)
            items += ImportReportItem("generation_params", sourceId, null, "deferred", "unmapped body controls retained; separate adapter required")
        if (!bodiesOnly) obj["customHeaders"]?.takeIf { it != JsonNull }?.let {
            result["customHeaders"] = sanitizeSecrets(JsonObject(mapOf("customHeaders" to it))).jsonObject.getValue("customHeaders")
        }
        return JsonObject(result)
    }

    private fun reasoning(assistant: JsonObject, sourceModel: Pair<JsonObject, String>?, sourceId: String): JsonObject {
        if (sourceModel == null || "REASONING" !in sourceModel.first.strings("abilities")) return JsonObject(emptyMap())
        val level = assistant.string("reasoningLevel")?.lowercase() ?: "auto"
        if (level !in setOf("off", "auto", "low", "medium", "high", "xhigh", "max")) {
            items += ImportReportItem("generation_params", sourceId, null, "deferred", "unknown source reasoning level retained")
            return JsonObject(emptyMap())
        }
        return buildJsonObject {
            when (sourceModel.second) {
                "anthropic_messages" -> {
                    put("thinking", buildJsonObject { put("type", if (level == "off") "disabled" else "adaptive") })
                    if (level !in setOf("off", "auto")) put("output_config", buildJsonObject { put("effort", level) })
                }
                "gemini_generate_content" -> {
                    put("include_thoughts", true)
                    if (level != "auto") {
                        val model = sourceModel.first.string("modelId").orEmpty()
                        if (Regex("^models/", RegexOption.IGNORE_CASE).replace(model, "").startsWith("gemini-3", true))
                            put("thinking_level", when (level) { "off" -> "minimal"; "low", "medium" -> level; else -> "high" })
                        else if (level != "off" || !Regex("2\\.5.*pro", RegexOption.IGNORE_CASE).containsMatchIn(model)) {
                            put("thinking_budget", when (level) { "off" -> 0; "low" -> 1000; "medium" -> 2000; "high" -> 8000; "xhigh" -> 16000; else -> 32000 })
                            if (level == "off") put("include_thoughts", false)
                        }
                    }
                }
                else -> if (level != "auto") put("reasoning_effort", if (level == "off") "none" else level)
            }
        }
    }

    private suspend fun assistant(obj: JsonObject) {
        val sourceId = obj.string("id") ?: throw StError.Validation("assistant ID missing")
        if (sourceId in assistants) return // Settings carry the full definition; legacy DB rows may repeat it.
        assistants[sourceId] = obj
        val id = merge.id("character", sourceId)
        val books = obj.strings("lorebookIds").map { merge.id("worldbook", it) } +
            obj.strings("modeInjectionIds").map { merge.id("worldbook", "mode:$it") }
        val regexRules = obj.objects("regexes").mapIndexed { index, rule ->
            val regexId = merge.sourceIdentity("content_regex", rule.string("id"))
            ContentRegexRule(merge.id("content_regex", regexId, sourceId), name = rule.string("name"),
                enabled = rule.boolean("enabled") ?: true, order = index, pattern = rule.string("findRegex").orEmpty(),
                action = "replace", replacement = rule.string("replaceString").orEmpty(), engine = "kotlin",
                roles = rule.strings("affectingScope").mapNotNull { when (it.uppercase()) {
                    "USER" -> ChatRole.user; "ASSISTANT" -> ChatRole.assistant; else -> null
                } }.toSet(), visualOnly = rule.boolean("visualOnly") ?: false,
                applyToReasoning = true, scanAllHistory = true, skipOpeningMessage = false)
        }
        val avatarRelativePath = avatar(obj, sourceId)
        val incoming = CharacterCard(id = id, name = obj.string("name") ?: "RikkaHub 助手", systemPrompt = obj.string("systemPrompt").orEmpty(),
            attachedWorldBookIds = books, contentRegexRulesJson = Jsonx.encode(regexRules), createdAt = IMPORT_EPOCH, updatedAt = IMPORT_EPOCH,
            sourceNamespace = ns, sourceId = sourceId, avatarRelativePath = avatarRelativePath, extraJson = sanitizeSecrets(obj).toString())
        merge.merge("character", id, incoming, content.getCharacter(id))?.let { content.saveCharacter(it) }
        val configId = merge.id("assistant", sourceId)
        val modelId = obj.string("chatModelId") ?: settings?.string("chatModelId")
        val defaults = ChatCreationDefaults(chatDefaults(obj, sourceId),
            obj.objects("presetMessages").map { candidate(it, "assistant-preset:$sourceId", null) })
        val mapped = JsonObject(sanitizeSecrets(obj).jsonObject + controls(obj, modelId) +
            ("chatDefaults" to Jsonx.json.parseToJsonElement(Jsonx.encode(defaults))) +
            ("contentRegexRules" to Jsonx.json.parseToJsonElement(Jsonx.encode(regexRules))) +
            (modelId?.let { mapOf("presetId" to JsonPrimitive(merge.id("preset", "model:$it"))) } ?: emptyMap()))
        val config = AssistantConfig(configId, mapped.toString(), IMPORT_EPOCH, ns)
        merge.merge("assistant", configId, config, content.getAssistant(configId))?.let { content.saveAssistant(it) }
        items += ImportReportItem("assistant", sourceId, id, "converted")
    }

    private suspend fun avatar(obj: JsonObject, sourceId: String): String? {
        val avatar = obj["avatar"] as? JsonObject ?: return null
        val type = avatar.string("type") ?: return null
        if (type != "image" && !type.endsWith(".Avatar.Image")) return null
        val url = avatar.string("url")?.takeIf { it.isNotBlank() }
        if (url == null) {
            items += ImportReportItem("avatar", sourceId, null, "retained", "invalid image avatar; raw settings retained")
            return null
        }
        if (!url.startsWith("file:", ignoreCase = true) && ':' in url) {
            items += ImportReportItem("avatar", sourceId, null, "retained", "avatar source remains in raw settings; remote or content URI not fetched")
            return null
        }
        val filename = runCatching { File(URI(url).path.orEmpty()).name }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != "." && it != ".." }
        if (filename == null) {
            items += ImportReportItem("avatar", sourceId, null, "retained", "invalid local avatar filename; raw settings retained")
            return null
        }
        val mime = ImportService.guessMime(filename)
        if (mime !in AttachmentMimeTypes.images) {
            items += ImportReportItem("avatar", sourceId, null, "retained", "unsupported image format; raw settings retained")
            return null
        }
        val file = inside(root.resolve("upload"), filename)
        if (file == null) {
            items += ImportReportItem("avatar", sourceId, null, "retained", "invalid local avatar filename; raw settings retained")
            return null
        }
        return merge.attachment(url, file, originalName = filename)?.relativePath
    }

    private suspend fun lorebook(obj: JsonObject) {
        val sourceId = obj.string("id") ?: throw StError.Validation("lorebook ID missing")
        merge.claim("worldbook", sourceId)
        val id = merge.id("worldbook", sourceId)
        val entries = obj.objects("entries").mapIndexed { index, entry ->
            val keywords = entry.strings("keywords")
            val patterns = if (entry.boolean("useRegex") == true) keywords else keywords.map(Regex::escape)
            val regex = when {
                entry.boolean("constantActive") == true -> "(?s:.*)"
                patterns.isEmpty() -> "(?!)"
                entry.boolean("caseSensitive") == true -> "(?-i:${patterns.joinToString("|") { "(?:$it)" }})"
                else -> "(?i:${patterns.joinToString("|") { "(?:$it)" }})"
            }
            WorldBookEntry(merge.id("worldbook_entry", entry.string("id") ?: "entry:$index", sourceId),
                entry.string("name").orEmpty(), regex, entry.string("content").orEmpty(),
                (obj.boolean("enabled") ?: true) && (entry.boolean("enabled") ?: true), index,
                scanDepth = (entry.integer("scanDepth") ?: 4).coerceAtLeast(0),
                constantActive = entry.boolean("constantActive") ?: false,
                priority = entry.integer("priority") ?: 0,
                position = entry.string("position") ?: "after_system_prompt",
                injectDepth = entry.integer("injectDepth") ?: 4,
                role = injectionRole(entry))
        }
        val incoming = WorldBook(id, obj.string("name") ?: "RikkaHub 世界书", entries,
            createdAt = IMPORT_EPOCH, updatedAt = IMPORT_EPOCH, sourceNamespace = ns, sourceId = sourceId,
            extraJson = sanitizeSecrets(obj).toString())
        merge.merge("worldbook", id, incoming, content.getWorldBook(id))?.let { content.saveWorldBook(it) }
        items += ImportReportItem("worldbook", sourceId, id, "converted", "per-entry scan, priority, position and role mapped")
    }

    private fun injectionRole(entry: JsonObject): ChatRole =
        if (entry.string("role").equals("assistant", ignoreCase = true)) ChatRole.assistant else ChatRole.user

    private suspend fun chat(obj: JsonObject, rawNodes: List<JsonObject>) {
        val sourceId = obj.string("id") ?: throw StError.Validation("conversation ID missing")
        val id = merge.id("chat", sourceId)
        val assistantSourceId = obj.string("assistant_id") ?: obj.string("assistantId") ?: "unknown-assistant"
        if (assistantSourceId !in assistants) {
            assistant(buildJsonObject { put("id", assistantSourceId); put("name", "RikkaHub 助手（配置缺失）") })
            items += ImportReportItem("assistant", assistantSourceId, null, "missing", "placeholder created; source configuration absent")
        }
        val assistant = assistants.getValue(assistantSourceId)
        val defaults = chatDefaults(assistant, assistantSourceId)
        val bookIds = decodeStringArray(obj["lorebook_ids"] ?: obj["lorebookIds"]).map { merge.id("worldbook", it) } +
            decodeStringArray(obj["mode_injection_ids"] ?: obj["modeInjectionIds"]).map { merge.id("worldbook", "mode:$it") }
        val incoming = ChatSession(
            id, merge.id("character", assistantSourceId), title = obj.string("title") ?: "Conversation",
            overrides = defaults.copy(
                prompt = obj.string("custom_system_prompt") ?: obj.string("customSystemPrompt"), sessionSystemPromptMode = "override",
                worldBookAttachments = bookIds.map { WorldBookAttachment(it) },
            ),
            createdAt = obj.timestamp("create_at", "createAt"), updatedAt = obj.timestamp("update_at", "updateAt"),
            sourceNamespace = ns, sourceId = sourceId, extraJson = sanitizeSecrets(JsonObject(obj - "nodes")).toString(),
        )
        val usageCounts = linkedMapOf<String, Int>()
        val nodes = rawNodes.mapIndexed { index, node ->
            val nodeSourceId = merge.sourceIdentity("node", node.string("id"))
            val messages = node["messages"] ?: node["message_list"] ?: throw StError.Validation("node $nodeSourceId has no messages")
            val arr = if (messages is JsonPrimitive) Jsonx.json.parseToJsonElement(messages.content).jsonArray else messages.jsonArray
            if (arr.isEmpty()) throw StError.Validation("node $nodeSourceId has no candidates")
            val candidates = arr.map { value ->
                candidate(value.jsonObject, sourceId, id).also { mapped ->
                    importUsage(value.jsonObject, sourceId, mapped.id)?.let { status ->
                        usageCounts[status] = (usageCounts[status] ?: 0) + 1
                    }
                }
            }
            val selected = node.integer("select_index") ?: node.integer("selectIndex") ?: node.integer("selectedIndex") ?: 0
            if (selected !in candidates.indices) throw StError.Validation("node $nodeSourceId has invalid selected index")
            MessageNode(merge.id("node", nodeSourceId, sourceId), id, index.toLong(), candidates, selected)
        }
        if (usageCounts.isNotEmpty()) items += ImportReportItem("usage", sourceId, id,
            if ((usageCounts["converted"] ?: 0) > 0) "converted" else "retained",
            usageCounts.entries.joinToString { "${it.key}=${it.value}" } +
                "; source message snapshots, not a complete request ledger; local times interpreted in ${usageLocalZone.id}; costs unknown")
        if (!merge.chat(incoming, nodes)) return
        // Shared assistant memory now comes from the source library at request time, not a stale chat copy.
        if (sourceMemoryConverted) merge.retireImportedAssistantMemory(id, incoming.updatedAt)
        items += ImportReportItem("chat", sourceId, id, "converted")
    }

    private suspend fun chatDefaults(assistant: JsonObject, sourceId: String): ChatOverrides {
        val modelId = assistant.string("chatModelId") ?: settings?.string("chatModelId")
        val sourceModel = modelId?.let { sourceModels[it] }
        val reasoning = reasoning(assistant, sourceModel, sourceId)
        val assistantControls = controls(JsonObject(assistant - "customBodies"), sourceId).toMutableMap()
        if ((reasoning["thinking"] as? JsonObject)?.get("type")?.jsonPrimitive?.content in setOf("adaptive", "enabled")) {
            assistantControls.remove("temperature"); assistantControls.remove("top_p")
        }
        val params = JsonObject(assistantControls + reasoning +
            (sourceModel?.let { controls(it.first, modelId, bodiesOnly = true) } ?: JsonObject(emptyMap())) +
            controls(assistant, sourceId, bodiesOnly = true))
        return ChatOverrides(sessionSystemPromptMode = "override",
            sessionSystemPromptEnabled = assistant.boolean("allowConversationSystemPrompt") ?: false,
            worldBookSelectionMode = if (assistant.boolean("allowConversationPromptInjection") == true) "session" else "character",
            contextMessageLimit = (assistant.integer("contextMessageLimit") ?: 0).coerceAtLeast(0),
            messageTemplate = assistant.string("messageTemplate") ?: "{{ message }}",
            presetId = modelId?.let { merge.id("preset", "model:$it") }, paramsJson = params.toString())
    }

    private suspend fun candidate(obj: JsonObject, sourceChatId: String, chatId: String?): MessageCandidate {
        val sourceId = merge.sourceIdentity("message", obj.string("id"))
        val id = merge.id("message", sourceId, sourceChatId)
        val parts = obj.objects("parts")
        val images = mutableListOf<AttachmentRef>()
        val files = mutableListOf<AttachmentRef>()
        suspend fun collectAttachments(parts: List<JsonObject>) {
            for (part in parts) {
                when (part.string("type")?.lowercase()) {
                    "image", "audio", "video", "document" -> {
                        val url = part.string("url") ?: continue
                        val filename = runCatching { File(URI(url).path.orEmpty()).name }.getOrDefault(url.substringAfterLast('/'))
                        if (url.startsWith("file:") || !url.contains(":")) {
                            val file = inside(root.resolve("upload"), filename)
                            val attachment = merge.attachment(url, file, chatId, if (chatId == null) null else id, part.string("fileName") ?: filename, part.string("mime"))
                            if (attachment != null) {
                                if (part.string("type") == "image") images += attachment else files += attachment
                            }
                        } else {
                            val remote = AttachmentRef(merge.id("attachment", url, chatId.orEmpty()), filename,
                                part.string("mime") ?: ImportService.guessMime(filename), "", sourceUri = url)
                            if (part.string("type") == "image") images += remote else files += remote
                        }
                    }
                    "tool" -> collectAttachments(part.objects("output"))
                }
            }
        }
        collectAttachments(parts)
        val tools = parts.filter { it.string("type") in setOf("tool", "tool_call", "tool_result", "server_tool") }
        return MessageCandidate(
            id, role(obj.string("role")), obj.string("content") ?: parts.filter { it.string("type") == "text" }
                .joinToString("\n") { it.string("text").orEmpty() },
            reasoningContent = parts.filter { it.string("type") == "reasoning" }.joinToString("\n") { it.string("reasoning").orEmpty() }
                .ifBlank { obj.string("reasoning") ?: obj.string("reasoningContent") },
            images = images, attachments = files, usageJson = obj["usage"]?.toString(),
            toolRecordJson = tools.takeIf { it.isNotEmpty() }?.let { sanitizeSecrets(JsonArray(it)).toString() },
            createdAt = obj.timestamp("createdAt", "create_at"), extraJson = JsonObject(sanitizeSecrets(obj).jsonObject +
                (if (chatId != null) mapOf("rikkaRegexProcessed" to JsonPrimitive(true)) else emptyMap())).toString(),
        )
    }

    private suspend fun importUsage(obj: JsonObject, sourceChatId: String, messageId: String): String? {
        if (obj["usage"] == null || obj["usage"] == JsonNull || obj.string("role")?.lowercase() !in setOf("assistant", "model")) return null
        checkCancelled()
        // Retain the original accounting fields even if conversion fails or the chat was locally deleted.
        merge.retain("usage/messages/$sourceChatId/$messageId", buildJsonObject {
            put("sourceChatId", sourceChatId)
            obj.filterKeys { it in setOf("id", "role", "modelId", "createdAt", "create_at", "finishedAt", "usage") }
                .forEach { (key, value) -> put(key, value) }
        })
        val sourceModelId = obj.string("modelId")
        val sourceModel = sourceModelId?.let(sourceModels::get)
        val record = try {
            RikkaUsageMapper.map(obj, sourceChatId, sourceModelId?.let(sourceModelProviders::get),
                sourceModel?.first?.string("modelId"), sourceModel?.second, usageLocalZone)
        } catch (_: StError.Validation) {
            return "raw-only"
        } ?: return null
        if (!seenUsageMessages.add(record.eventId)) return "duplicate"
        return if (merge.usage(record) != null) "converted" else "locally-deleted"
    }
}

private fun JsonObject.timestamp(vararg keys: String): String = keys.firstOrNull { string(it) != null }?.let { time(it) } ?: IMPORT_EPOCH
private fun decodeStringArray(value: JsonElement?): List<String> {
    if (value == null || value == JsonNull) return emptyList()
    val array = if (value is JsonPrimitive) Jsonx.json.parseToJsonElement(value.content).jsonArray else value.jsonArray
    return array.map { it.jsonPrimitive.content }
}
private fun quoted(identifier: String) = "\"" + identifier.replace("\"", "\"\"") + "\""
private fun columns(db: SQLiteDatabase, table: String): Set<String> =
    db.rawQuery("PRAGMA table_info(${quoted(table)})", null).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
    }
private fun row(cursor: Cursor): JsonObject = buildJsonObject {
    for (index in 0 until cursor.columnCount) {
        put(cursor.getColumnName(index), when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> JsonNull
            Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(index))
            Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(cursor.getDouble(index))
            Cursor.FIELD_TYPE_STRING -> JsonPrimitive(cursor.getString(index))
            else -> throw StError.Validation("unsupported binary field: ${cursor.getColumnName(index)}")
        })
    }
}
