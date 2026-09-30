package com.simpletavern.core.importer

import android.database.sqlite.SQLiteDatabase
import com.simpletavern.core.data.*
import com.simpletavern.core.data.db.AttachmentEntity
import com.simpletavern.core.data.db.ImportBatchEntity
import com.simpletavern.core.model.*
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ImportProgress(val batchId: String, val phase: String, val detail: String, val fraction: Float)

class ImportService(
    private val paths: StoragePaths,
    private val chats: ChatRepository,
    private val content: ContentRepository,
    private val memory: MemoryStore,
    private val importStore: ImportStore,
    private val attachments: AttachmentStore,
) {
    private val progress = MutableStateFlow(ImportProgress("", "idle", "", 0f))
    fun observeProgress(): Flow<ImportProgress> = progress.asStateFlow()

    private var cancelRequested = false

    fun requestCancel() { cancelRequested = true }

    suspend fun preflight(zipFile: File): PreflightResult = withContext(Dispatchers.IO) {
        ZipFile(zipFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            when {
                names.any { it == "rikka_hub.db" || it.endsWith("/rikka_hub.db") } ||
                    names.contains("settings.json") && names.any { it.contains("rikka_hub") } ->
                    PreflightResult("rikka_hub", names.toList(), notes = listOf("Detected RikkaHub-style backup"))
                names.any { it.startsWith("chats/") || it == "settings.json" || it.startsWith("characters/") } ->
                    PreflightResult("desktop", names.toList(), notes = listOf("Detected SimpleTavern desktop backup/snapshot"))
                else ->
                    PreflightResult("unknown", names.toList(), notes = listOf("Unrecognized archive layout"))
            }
        }
    }

    suspend fun execute(zipFile: File, sourceHint: String? = null): ImportReport = withContext(Dispatchers.IO) {
        cancelRequested = false
        val batchId = newId()
        val now = nowIso()
        importStore.upsertBatch(ImportBatchEntity(batchId, sourceHint ?: "auto", "running", null, now, now))
        val staging = paths.importStaging.resolve(batchId).also { it.mkdirs() }
        val items = mutableListOf<ImportReportItem>()
        try {
            unzipSafe(zipFile, staging)
            progress.value = ImportProgress(batchId, "unzipped", staging.absolutePath, 0.2f)
            checkCancelled()
            val detected = sourceHint ?: detectSource(staging)
            val report = when (detected) {
                "rikka_hub" -> importRikkaHub(batchId, staging, items)
                "desktop" -> importDesktop(batchId, staging, items)
                else -> {
                    items += ImportReportItem("archive", null, null, "failed", "unsupported archive")
                    ImportReport(batchId, detected, items, success = false)
                }
            }
            importStore.upsertBatch(
                ImportBatchEntity(batchId, detected, if (report.success) "done" else "failed", Jsonx.encode(report), now, nowIso()),
            )
            report
        } catch (c: CancelledImport) {
            val report = ImportReport(batchId, sourceHint ?: "auto", items, success = false, cancelled = true)
            importStore.upsertBatch(ImportBatchEntity(batchId, report.source, "cancelled", Jsonx.encode(report), now, nowIso()))
            report
        } catch (t: Throwable) {
            items += ImportReportItem("batch", null, null, "failed", t.message)
            val report = ImportReport(batchId, sourceHint ?: "auto", items, success = false)
            importStore.upsertBatch(ImportBatchEntity(batchId, report.source, "failed", Jsonx.encode(report), now, nowIso()))
            report
        }
    }

    private fun detectSource(staging: File): String {
        if (staging.resolve("rikka_hub.db").exists() || staging.walkTopDown().any { it.name == "rikka_hub.db" }) return "rikka_hub"
        if (staging.resolve("settings.json").exists() || staging.resolve("chats").isDirectory) return "desktop"
        return "unknown"
    }

    private suspend fun importDesktop(batchId: String, staging: File, items: MutableList<ImportReportItem>): ImportReport {
        val ns = "desktop:$batchId"
        progress.value = ImportProgress(batchId, "desktop", "settings", 0.3f)
        val settings = staging.resolve("settings.json")
        if (settings.exists()) {
            content.saveSettingsJson(settings.readText(), nowIso())
            items += ImportReportItem("settings", "settings.json", "global", "converted", null)
        } else {
            items += ImportReportItem("settings", null, null, "missing", "settings.json not in package")
        }

        importJsonDir(staging.resolve("characters"), ns, "character", items) { id, obj ->
            val card = CharacterCard(
                id = mapId(ns, id, "character"),
                name = obj.str("name") ?: "角色",
                description = obj.str("description") ?: "",
                personality = obj.str("personality") ?: "",
                scenario = obj.str("scenario") ?: "",
                firstMessage = obj.str("firstMessage") ?: "",
                exampleDialogue = obj.str("exampleDialogue") ?: "",
                systemPrompt = obj.str("systemPrompt") ?: "",
                avatarRelativePath = null,
                attachedWorldBookIds = obj.strList("attachedWorldBookIds"),
                mvuEnabled = obj.bool("mvuEnabled") ?: false,
                mvuMode = obj.str("mvuMode") ?: "regex",
                mvuDirective = obj.str("mvuDirective"),
                initialStateTablesJson = obj["initialStateTables"]?.toString(),
                contentRegexRulesJson = obj["contentRegexRules"]?.toString(),
                createdAt = obj.str("createdAt") ?: nowIso(),
                updatedAt = obj.str("updatedAt") ?: nowIso(),
                sourceNamespace = ns,
                sourceId = id,
                extraJson = obj.toString(),
            )
            content.saveCharacter(card)
            card.id
        }

        // personas may be in settings or personas/
        importJsonDir(staging.resolve("personas"), ns, "persona", items) { id, obj ->
            val p = UserPersona(
                id = mapId(ns, id, "persona"),
                name = obj.str("name") ?: "Persona",
                description = obj.str("description") ?: "",
                createdAt = obj.str("createdAt") ?: nowIso(),
                updatedAt = obj.str("updatedAt") ?: nowIso(),
                sourceNamespace = ns,
                sourceId = id,
                extraJson = obj.toString(),
            )
            content.savePersona(p)
            p.id
        }

        importJsonDir(staging.resolve("world_books").takeIf { it.exists() }
            ?: staging.resolve("worldbooks"), ns, "worldbook", items) { id, obj ->
            val entries = (obj["entries"] as? JsonArray)?.mapNotNull { el ->
                val e = el as? JsonObject ?: return@mapNotNull null
                WorldBookEntry(
                    id = e.str("id") ?: newId(),
                    title = e.str("title") ?: "",
                    regex = e.str("regex") ?: "",
                    content = e.str("content") ?: "",
                    enabled = e.bool("enabled") ?: true,
                    orderIndex = e.int("orderIndex") ?: 0,
                )
            } ?: emptyList()
            val book = WorldBook(
                id = mapId(ns, id, "worldbook"),
                name = obj.str("name") ?: "世界书",
                entries = entries,
                globalActive = obj.bool("globalActive") ?: false,
                sessionChatIds = obj.strList("sessionChatIds"),
                createdAt = obj.str("createdAt") ?: nowIso(),
                updatedAt = obj.str("updatedAt") ?: nowIso(),
                sourceNamespace = ns,
                sourceId = id,
                extraJson = obj.toString(),
            )
            content.saveWorldBook(book)
            book.id
        }

        val chatsDir = staging.resolve("chats")
        if (chatsDir.isDirectory) {
            chatsDir.listFiles()?.filter { it.isDirectory || it.extension == "json" }?.forEach { entry ->
                checkCancelled()
                val chatJsonFile = when {
                    entry.isFile && entry.extension == "json" -> entry
                    entry.isDirectory -> entry.resolve("chat.json").takeIf { it.exists() }
                        ?: entry.listFiles()?.firstOrNull { it.extension == "json" }
                    else -> null
                } ?: return@forEach
                val obj = Jsonx.json.parseToJsonElement(chatJsonFile.readText()).jsonObject
                val sourceId = obj.str("id") ?: chatJsonFile.nameWithoutExtension
                val existing = importStore.resolveId(ns, sourceId)?.let { chats.getSession(it) }
                // Do not overwrite local user edits on re-import of same source blob hash — simple: if mapped and updatedAt newer locally, retain
                val targetId = existing?.id ?: mapId(ns, sourceId, "chat")
                val overridesObj = obj["overrides"] as? JsonObject
                val overrides = ChatOverrides(
                    prompt = overridesObj?.str("prompt"),
                    longTermMemory = overridesObj?.str("longTermMemory"),
                    presetId = overridesObj?.str("presetId"),
                    pureAiMode = overridesObj?.bool("pureAiMode"),
                    autoMemorySummaryEveryN = overridesObj?.int("autoMemorySummaryEveryN"),
                    lastAutoMemorySummaryAfterMessageId = overridesObj?.str("lastAutoMemorySummaryAfterMessageId"),
                    mvuMode = overridesObj?.str("mvuMode"),
                    mvuDirective = overridesObj?.str("mvuDirective"),
                    knowledgeGraphEnabled = overridesObj?.bool("knowledgeGraphEnabled"),
                    extraJson = overridesObj?.toString(),
                )
                val session = ChatSession(
                    id = targetId,
                    characterId = mapId(ns, obj.str("characterId") ?: "unknown", "character"),
                    title = obj.str("title") ?: "对话",
                    userPersonaId = obj.str("userPersonaId")?.let { mapId(ns, it, "persona") },
                    isGroup = obj.bool("isGroup") ?: false,
                    memberIds = obj.strList("memberIds").map { mapId(ns, it, "character") },
                    groupDelayMs = obj.int("groupDelay") ?: 1500,
                    overrides = overrides,
                    forkedFromChatId = obj.str("forkedFromChatId"),
                    forkedFromMessageId = obj.str("forkedFromMessageId"),
                    forkedFromMessageIndex = obj.int("forkedFromMessageIndex"),
                    createdAt = obj.str("createdAt") ?: nowIso(),
                    updatedAt = obj.str("updatedAt") ?: nowIso(),
                    sourceNamespace = ns,
                    sourceId = sourceId,
                    extraJson = obj.toString(),
                )
                if (existing != null && existing.updatedAt > session.updatedAt) {
                    items += ImportReportItem("chat", sourceId, existing.id, "retained", "local edits kept")
                } else {
                    chats.saveSession(session)
                    val messages = (obj["messages"] as? JsonArray) ?: JsonArray(emptyList())
                    val nodes = messages.mapIndexed { idx, el ->
                        val m = el.jsonObject
                        val mid = m.str("id") ?: newId()
                        val variants = (m["greetingVariants"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        val candidates = if (!variants.isNullOrEmpty()) {
                            variants.mapIndexed { vi, text ->
                                MessageCandidate(
                                    id = if (vi == 0) mid else newId(),
                                    role = ChatRole.valueOf(m.str("role") ?: "assistant"),
                                    content = text,
                                    reasoningContent = m.str("reasoningContent"),
                                    characterId = m.str("characterId"),
                                    createdAt = m.str("ts") ?: nowIso(),
                                    usageJson = m["usage"]?.toString(),
                                    generationMetadataJson = m["generationMetadata"]?.toString(),
                                    toolCallsJson = m["tool_calls"]?.toString(),
                                    toolCallId = m.str("tool_call_id"),
                                    extraJson = m.toString(),
                                )
                            }
                        } else {
                            listOf(
                                MessageCandidate(
                                    id = mid,
                                    role = runCatching { ChatRole.valueOf(m.str("role") ?: "user") }.getOrDefault(ChatRole.user),
                                    content = m.str("content") ?: "",
                                    reasoningContent = m.str("reasoningContent"),
                                    reasoningDurationSec = m.double("reasoningDurationSec"),
                                    characterId = m.str("characterId"),
                                    senderPersonaId = m.str("senderPersonaId"),
                                    senderName = m.str("senderName"),
                                    toolCallsJson = m["tool_calls"]?.toString(),
                                    toolCallId = m.str("tool_call_id"),
                                    toolRecordJson = m["toolRecord"]?.toString(),
                                    usageJson = m["usage"]?.toString(),
                                    generationMetadataJson = m["generationMetadata"]?.toString(),
                                    createdAt = m.str("ts") ?: nowIso(),
                                    extraJson = m.toString(),
                                ),
                            )
                        }
                        val selected = m.int("greetingVariantIndex") ?: 0
                        MessageNode(
                            id = newId(),
                            chatId = targetId,
                            orderIndex = idx.toLong(),
                            candidates = candidates,
                            selectedIndex = selected.coerceIn(0, candidates.lastIndex.coerceAtLeast(0)),
                        )
                    }
                    chats.replaceAllMessages(targetId, nodes)
                    overrides.longTermMemory?.let { text ->
                        memory.saveMemory(
                            LongTermMemory(targetId, text, version = 1, updatedAt = nowIso(), source = "import"),
                        )
                    }
                    // memory file beside chat
                    val memFile = chatJsonFile.parentFile?.resolve("long_term_memory.txt")
                        ?: chatJsonFile.parentFile?.resolve("memory.txt")
                    if (memFile?.exists() == true) {
                        memory.saveMemory(LongTermMemory(targetId, memFile.readText(), 1, null, nowIso(), "import"))
                    }
                    val kgFile = chatJsonFile.parentFile?.resolve("knowledge_graph.json")
                    if (kgFile?.exists() == true) {
                        val kgObj = Jsonx.json.parseToJsonElement(kgFile.readText()).jsonObject
                        memory.saveKg(
                            KnowledgeGraph(
                                chatId = targetId,
                                entities = Jsonx.decode(kgObj["entities"]?.toString() ?: "[]"),
                                relations = Jsonx.decode(kgObj["relations"]?.toString() ?: "[]"),
                                version = kgObj.int("version") ?: 0,
                                updatedAt = kgObj.str("updatedAt") ?: nowIso(),
                                source = kgObj.str("source") ?: "mvu_agent",
                            ),
                        )
                    }
                    val state = obj["stateVariables"] as? JsonObject
                    if (state != null) {
                        memory.saveMvu(
                            MvuState(
                                chatId = targetId,
                                version = state.int("version") ?: 1,
                                updatedAt = state.str("updatedAt") ?: nowIso(),
                                source = state.str("source") ?: "mvu_agent",
                                tables = Jsonx.decodeOrNull(state["tables"]?.toString()) ?: emptyList(),
                            ),
                        )
                    }
                    // images dir
                    val imagesDir = chatJsonFile.parentFile?.resolve("images")
                    if (imagesDir?.isDirectory == true) {
                        imagesDir.listFiles()?.forEach { img ->
                            val rel = "$targetId/${img.name}"
                            val dest = paths.attachments.resolve(rel).also { it.parentFile?.mkdirs() }
                            img.copyTo(dest, overwrite = true)
                            val aid = newId()
                            attachments.upsert(
                                AttachmentEntity(aid, rel, img.name, guessMime(img.name), img.length(), targetId, null, null, nowIso()),
                            )
                        }
                    }
                    items += ImportReportItem("chat", sourceId, targetId, "converted", null)
                }
            }
        } else {
            items += ImportReportItem("chats", null, null, "missing", "chats/ not present — old settings-only backup")
        }

        // Assistant config/history
        val assistantSettings = staging.resolve("assistant_settings.json")
            .takeIf { it.exists() }
            ?: staging.resolve("assistant/settings.json")
        if (assistantSettings?.exists() == true) {
            content.saveAssistant(AssistantConfig("default", assistantSettings.readText(), nowIso(), ns))
            items += ImportReportItem("assistant_settings", "assistant_settings", "default", "converted", null)
        } else {
            items += ImportReportItem("assistant_settings", null, null, "missing", null)
        }
        val assistantChat = staging.resolve("assistant_chat.json")
            .takeIf { it.exists() }
            ?: staging.resolve("assistant/chat.json")
        if (assistantChat?.exists() == true) {
            importStore.retainBlob(newId(), ns, "assistant_chat.json", assistantChat.readText(), nowIso())
            items += ImportReportItem("assistant_chat", "assistant_chat.json", null, "retained", "stored raw for history migration")
        }

        items += ImportReportItem(
            "assistant_workspace",
            null,
            null,
            "deferred",
            "Desktop built-in assistant workspace files are explicitly deferred",
        )

        // usage ledger optional
        val usageDir = staging.resolve("usage")
        if (usageDir.isDirectory) {
            items += ImportReportItem("usage", "usage/", null, "retained", "usage jsonl retained as blobs")
            usageDir.walkTopDown().filter { it.isFile }.forEach { f ->
                importStore.retainBlob(newId(), ns, "usage/${f.name}", f.readText(), nowIso())
            }
        }

        progress.value = ImportProgress(batchId, "desktop", "done", 1f)
        val success = items.none { it.status == "failed" }
        return ImportReport(batchId, "desktop", items, success = success)
    }

    private suspend fun importRikkaHub(batchId: String, staging: File, items: MutableList<ImportReportItem>): ImportReport {
        val ns = "rikka:$batchId"
        progress.value = ImportProgress(batchId, "rikka", "db", 0.3f)
        val dbFile = staging.walkTopDown().firstOrNull { it.name == "rikka_hub.db" }
        val settingsFile = staging.walkTopDown().firstOrNull { it.name == "settings.json" }
        if (settingsFile != null) {
            content.saveSettingsJson(settingsFile.readText(), nowIso())
            items += ImportReportItem("settings", "settings.json", "global", "converted", null)
        } else {
            items += ImportReportItem("settings", null, null, "missing", "settings-only or incomplete package")
        }
        if (dbFile == null) {
            items += ImportReportItem("database", "rikka_hub.db", null, "missing", "No database in backup; settings-only import")
            return ImportReport(batchId, "rikka_hub", items, success = items.none { it.status == "failed" })
        }
        // Work on a copy — never replace app DB
        val dbCopy = staging.resolve("rikka_hub.working.db")
        dbFile.copyTo(dbCopy, overwrite = true)
        val db = SQLiteDatabase.openDatabase(dbCopy.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            // schema version heuristic
            val version = runCatching {
                db.rawQuery("PRAGMA user_version", null).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
            }.getOrDefault(-1)
            items += ImportReportItem("schema", version.toString(), null, "converted", "source user_version=$version (sample ref schema 25)")

            // Assistants / providers if tables exist
            if (tableExists(db, "assistant") || tableExists(db, "AssistantEntity")) {
                val table = if (tableExists(db, "assistant")) "assistant" else "AssistantEntity"
                db.rawQuery("SELECT * FROM $table", null).use { c ->
                    while (c.moveToNext()) {
                        val id = c.optString("id") ?: newId()
                        val payload = rowToJson(c)
                        content.saveAssistant(AssistantConfig(mapId(ns, id, "assistant"), payload, nowIso(), ns))
                        items += ImportReportItem("assistant", id, mapId(ns, id, "assistant"), "converted", null)
                    }
                }
            }

            // Conversations
            val convTable = when {
                tableExists(db, "conversation") -> "conversation"
                tableExists(db, "ConversationEntity") -> "ConversationEntity"
                else -> null
            }
            val nodeTable = when {
                tableExists(db, "message_node") -> "message_node"
                tableExists(db, "MessageNodeEntity") -> "MessageNodeEntity"
                else -> null
            }
            if (convTable != null) {
                db.rawQuery("SELECT * FROM $convTable", null).use { c ->
                    while (c.moveToNext()) {
                        checkCancelled()
                        val sourceId = c.optString("id") ?: continue
                        val targetId = mapId(ns, sourceId, "chat")
                        val title = c.optString("title") ?: "Conversation"
                        val assistantId = c.optString("assistant_id") ?: c.optString("assistantId")
                        val session = ChatSession(
                            id = targetId,
                            characterId = assistantId?.let { mapId(ns, it, "assistant") } ?: "imported",
                            title = title,
                            createdAt = nowIso(),
                            updatedAt = nowIso(),
                            sourceNamespace = ns,
                            sourceId = sourceId,
                            extraJson = rowToJson(c),
                        )
                        chats.saveSession(session)
                        val nodes = mutableListOf<MessageNode>()
                        if (nodeTable != null) {
                            db.rawQuery(
                                "SELECT * FROM $nodeTable WHERE conversation_id = ? OR conversationId = ? ORDER BY order_index ASC, `order` ASC",
                                arrayOf(sourceId, sourceId),
                            ).use { nc ->
                                // flexible column read
                                var idx = 0L
                                while (nc.moveToNext()) {
                                    val messagesJson = nc.optString("messages") ?: nc.optString("message_list") ?: "[]"
                                    val selected = nc.optInt("select_index") ?: nc.optInt("selectedIndex") ?: 0
                                    val arr = runCatching { Jsonx.json.parseToJsonElement(messagesJson).jsonArray }.getOrNull()
                                    val candidates = arr?.mapIndexed { i, el ->
                                        val m = el.jsonObject
                                        MessageCandidate(
                                            id = m.str("id") ?: newId(),
                                            role = mapRikkaRole(m.str("role")),
                                            content = extractText(m),
                                            reasoningContent = m.str("reasoning") ?: m.str("reasoningContent"),
                                            createdAt = nowIso(),
                                            extraJson = m.toString(),
                                        )
                                    } ?: emptyList()
                                    if (candidates.isNotEmpty()) {
                                        nodes += MessageNode(
                                            id = nc.optString("id") ?: newId(),
                                            chatId = targetId,
                                            orderIndex = idx++,
                                            candidates = candidates,
                                            selectedIndex = selected.coerceIn(0, candidates.lastIndex),
                                        )
                                    }
                                }
                            }
                        } else {
                            // fallback: conversation.nodes JSON field
                            val nodesField = c.optString("nodes")
                            if (!nodesField.isNullOrBlank()) {
                                items += ImportReportItem(
                                    "messages",
                                    sourceId,
                                    targetId,
                                    "retained",
                                    "Using legacy conversation.nodes JSON; verify schema",
                                )
                                importStore.retainBlob(newId(), ns, "conversation/$sourceId/nodes.json", nodesField, nowIso())
                            }
                        }
                        if (nodes.isNotEmpty()) {
                            chats.replaceAllMessages(targetId, nodes)
                        }
                        items += ImportReportItem("chat", sourceId, targetId, "converted", null)
                    }
                }
            } else {
                items += ImportReportItem("conversation", null, null, "failed", "No conversation table found for this schema")
            }

            // Copy upload files if present
            val uploadDir = staging.walkTopDown().firstOrNull { it.isDirectory && it.name == "upload" }
            uploadDir?.walkTopDown()?.filter { it.isFile }?.forEach { f ->
                val rel = "rikka/${f.name}"
                f.copyTo(paths.attachments.resolve(rel).also { it.parentFile?.mkdirs() }, overwrite = true)
                attachments.upsert(
                    AttachmentEntity(newId(), rel, f.name, guessMime(f.name), f.length(), null, null, null, nowIso()),
                )
            }
            items += ImportReportItem("credentials", null, null, "missing", "Secrets not migrated; reconfigure in UI")
        } finally {
            db.close()
        }
        progress.value = ImportProgress(batchId, "rikka", "done", 1f)
        return ImportReport(batchId, "rikka_hub", items, success = items.none { it.status == "failed" })
    }

    private suspend fun mapId(ns: String, sourceId: String, type: String): String {
        importStore.resolveId(ns, sourceId)?.let { return it }
        val id = newId()
        importStore.mapId(ns, sourceId, id, type)
        return id
    }

    private suspend fun importJsonDir(
        dir: File?,
        ns: String,
        type: String,
        items: MutableList<ImportReportItem>,
        save: suspend (String, JsonObject) -> String,
    ) {
        if (dir == null || !dir.isDirectory) {
            items += ImportReportItem(type, null, null, "missing", "${dir?.name} missing")
            return
        }
        dir.listFiles()?.filter { it.extension == "json" }?.forEach { f ->
            checkCancelled()
            val obj = Jsonx.json.parseToJsonElement(f.readText()).jsonObject
            val sourceId = obj.str("id") ?: f.nameWithoutExtension
            val target = save(sourceId, obj)
            items += ImportReportItem(type, sourceId, target, "converted", null)
        }
    }

    private fun checkCancelled() {
        if (cancelRequested) throw CancelledImport()
    }

    companion object {
        fun nowIso(): String = Instant.now().toString()
        fun newId(): String = UUID.randomUUID().toString().replace("-", "")
        fun guessMime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "txt", "md" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}

class CancelledImport : Exception("import cancelled")

data class PreflightResult(val source: String, val entries: List<String>, val notes: List<String> = emptyList())

private fun unzipSafe(zipFile: File, dest: File) {
    ZipFile(zipFile).use { zip ->
        val destPath = dest.canonicalFile.toPath()
        zip.entries().asSequence().forEach { entry ->
            val out = dest.resolve(entry.name).canonicalFile
            if (!out.toPath().startsWith(destPath)) {
                throw StError.Validation("zip slip rejected: ${entry.name}")
            }
            if (entry.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            }
        }
    }
}

private fun tableExists(db: SQLiteDatabase, name: String): Boolean {
    db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use {
        return it.moveToFirst()
    }
}

private fun android.database.Cursor.optString(column: String): String? {
    val i = getColumnIndex(column)
    if (i < 0 || isNull(i)) return null
    return getString(i)
}

private fun android.database.Cursor.optInt(column: String): Int? {
    val i = getColumnIndex(column)
    if (i < 0 || isNull(i)) return null
    return getInt(i)
}

private fun rowToJson(c: android.database.Cursor): String {
    val map = mutableMapOf<String, JsonElement>()
    for (i in 0 until c.columnCount) {
        val name = c.getColumnName(i)
        map[name] = when (c.getType(i)) {
            android.database.Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
            android.database.Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i))
            android.database.Cursor.FIELD_TYPE_STRING -> JsonPrimitive(c.getString(i))
            android.database.Cursor.FIELD_TYPE_NULL -> JsonPrimitive(null as String?)
            else -> JsonPrimitive("<blob>")
        }
    }
    return JsonObject(map).toString()
}

private fun mapRikkaRole(role: String?): ChatRole = when (role?.lowercase()) {
    "system" -> ChatRole.system
    "user" -> ChatRole.user
    "assistant" -> ChatRole.assistant
    "tool" -> ChatRole.tool
    else -> ChatRole.assistant
}

private fun extractText(m: JsonObject): String {
    m.str("content")?.let { return it }
    val parts = m["parts"] as? JsonArray
    if (parts != null) {
        return parts.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("")
    }
    return ""
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.bool(key: String): Boolean? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.contentOrNull?.toBooleanStrictOrNull()
}
private fun JsonObject.int(key: String): Int? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.contentOrNull?.toIntOrNull()
}
private fun JsonObject.double(key: String): Double? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.contentOrNull?.toDoubleOrNull()
}
private fun JsonObject.strList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
