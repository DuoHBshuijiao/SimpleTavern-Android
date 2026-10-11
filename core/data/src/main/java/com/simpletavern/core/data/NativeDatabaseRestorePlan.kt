package com.simpletavern.core.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.data.db.ImportBatchEntity
import com.simpletavern.core.data.db.UsageEventEntity
import com.simpletavern.core.model.*
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

data class NativeDatabaseRestoreInspection(val tables: List<NativeRestoreTablePlan>,
    val interruptedTasks: Long, val expiredApprovals: Long, val interruptedSummaryJobs: Long,
    val protectedSpeechFiles: Map<String, Long> = emptyMap())

/** Disk-backed candidate data. Only commit writes live rows; no worker registration or replay handle. */
class NativeDatabaseRestorePlan private constructor(
    private val db: AppDatabase, private val staged: SQLiteDatabase,
    private val manifest: NativeBackupManifest, private val snapshotAt: java.time.Instant,
) : AutoCloseable {
    private val stageCells = NativeSqlCells(::stageQuery)
    private var interruptedTasks = 0L
    private var expiredApprovals = 0L
    private var interruptedSummaryJobs = 0L

    private fun stageQuery(sql: String, args: Array<Any>): Cursor = staged.rawQuery(sql, args.map { it.toString() }.toTypedArray())

    suspend fun inspect(mode: NativeRestoreMode = NativeRestoreMode.ADD_MISSING,
                        issue: (NativeRestoreIssue) -> Unit): NativeDatabaseRestoreInspection = db.withTransaction {
        val replacing = mode == NativeRestoreMode.REPLACE_INCLUDED
        val live = db.openHelper.readableDatabase
        val liveCells = NativeSqlCells { sql, args -> live.query(SimpleSQLiteQuery(sql, args)) }
        fun liveId(table: String, key: JsonObject): Long? = live.query(SimpleSQLiteQuery(
            "SELECT rowid FROM `$table` WHERE ${key.keys.joinToString(" AND ") { "`$it`=?" }} LIMIT 1",
            key.values.map { (it as JsonPrimitive).content }.toTypedArray<Any>())).use { if (it.moveToFirst()) it.getLong(0) else null }
        fun stagedId(table: String, key: JsonObject): Long? = stageQuery(
            "SELECT rowid FROM `$table` WHERE ${key.keys.joinToString(" AND ") { "`$it`=?" }} LIMIT 1",
            key.values.map { (it as JsonPrimitive).content }.toTypedArray<Any>()).use { if (it.moveToFirst()) it.getLong(0) else null }
        fun singleKey(table: String, id: String) = buildJsonObject { put(primaryKey(table), id) }
        fun exists(table: String, id: String): Boolean = stagedId(table, singleKey(table, id)) != null || liveId(table, singleKey(table, id)) != null
        suspend fun effective(table: String, id: String, column: String, byteBudget: Int = 32_000_000): JsonElement? {
            val key = singleKey(table, id)
            return stagedId(table, key)?.let { stageCells.read(table, column, it, byteBudget) }
                ?: liveId(table, key)?.let { liveCells.read(table, column, it, byteBudget) }
        }
        // eventId is the historical request identity; old ledgers can have a different primary id.
        suspend fun usageByRequest(requestId: String, incoming: Boolean): UsageEventEntity? {
            val stageRow = if (incoming) stageQuery("SELECT rowid FROM usage_events WHERE eventId=? LIMIT 1", arrayOf(requestId))
                .use { if (it.moveToFirst()) it.getLong(0) else null } else null
            val oldRow = if (stageRow == null) live.query(SimpleSQLiteQuery(
                "SELECT rowid FROM usage_events WHERE eventId=? LIMIT 1", arrayOf(requestId)))
                .use { if (it.moveToFirst()) it.getLong(0) else null } else null
            if (stageRow == null && oldRow == null) return null
            val oldKey = oldRow?.let { liveCells.read("usage_events", "id", it, 1024).jsonPrimitive.content }
            // A replacement of that primary id must not hide its changed eventId behind the old lookup.
            val replacement = oldKey?.takeIf { incoming }?.let { stagedId("usage_events", singleKey("usage_events", it)) }
            val cells = if (stageRow != null || replacement != null) stageCells else liveCells
            val rowId = stageRow ?: replacement ?: oldRow!!
            val columns = buildJsonObject {
                for (name in listOf("id", "eventId", "requestId", "chatId", "provider", "model", "usageJson", "costJson", "createdAt")) {
                    val budget = when (name) { "usageJson" -> GeminiCacheAccounting.MAX_EVENT_BYTES; "costJson" -> 32_768; "model" -> 2048; "createdAt" -> 64; else -> 1024 }
                    put(name, cells.read("usage_events", name, rowId, budget))
                }
            }
            return TokenRequestAccounting.fromColumns(columns)
        }
        val cacheLifecycle = NativeGeminiCacheLifecycleGuard(read = { id ->
            if (effective("tasks", id, "kind", 128)?.jsonPrimitive?.contentOrNull !in setOf(TaskKind.GEMINI_CACHE_OPERATION.name, TaskKind.GEMINI_CACHE_LIST.name)) null
            else effective("tasks", id, "payloadJson", GeminiCacheTaskMetadata.MAX_LIST_BYTES)?.jsonPrimitive?.contentOrNull
        }, issue = issue)
        val plans = mutableListOf<NativeRestoreTablePlan>()
        for (table in manifest.tables) {
            var insert = 0L; var identical = 0L; var conflicting = 0L
            forEachRow(table) { _, row ->
                val key = keyOf(table, row)
                fun report(code: String, column: String? = null, blocking: Boolean = true) = issue(
                    NativeRestoreIssue(code, table.name, key.toString().take(2048), column = column, blocking = blocking))
                val oldId = liveId(table.name, key)
                if (oldId == null) insert++ else {
                    var same = true
                    for (column in table.columns) if (liveCells.read(table.name, column.name, oldId) != row[column.name]) { same = false; break }
                    if (same) identical++ else {
                        conflicting++; report("ROW_CONFLICT", blocking = !replacing)
                        // An identity is not permission to move another conversation's history.
                        val owners = when (table.name) {
                            "message_nodes" -> listOf("chatId")
                            "message_candidates", "content_regex_records" -> listOf("chatId", "nodeId")
                            "assistant_messages" -> listOf("assistantId")
                            else -> emptyList()
                        }
                        for (column in owners) if (liveCells.read(table.name, column, oldId) != row[column])
                            report("IDENTITY_SCOPE_CONFLICT", column)
                    }
                }
                fun ref(column: String, target: String, id: String? = row[column]?.jsonPrimitive?.contentOrNull,
                        historical: Boolean = false) {
                    if (!id.isNullOrBlank() && !exists(target, id)) report("MISSING_REFERENCE", column, !historical)
                }
                suspend fun anchor(column: String, id: String?, chat: String, historical: Boolean = false) {
                    if (id.isNullOrBlank()) return
                    val candidateChat = effective("message_candidates", id, "chatId")?.jsonPrimitive?.contentOrNull
                    val nodeChat = effective("message_nodes", id, "chatId")?.jsonPrimitive?.contentOrNull
                    if ((candidateChat ?: nodeChat) != chat) report("MESSAGE_ANCHOR_OUTSIDE_CHAT", column, !historical)
                    if (candidateChat != null) {
                        val node = effective("message_candidates", id, "nodeId")!!.jsonPrimitive.content
                        val selected = effective("message_nodes", node, "selectedIndex")?.jsonPrimitive?.longOrNull
                        if (selected != effective("message_candidates", id, "candidateIndex")?.jsonPrimitive?.longOrNull)
                            report("MESSAGE_ANCHOR_NOT_SELECTED", column, !historical)
                    }
                }
                fun string(column: String) = row[column]?.jsonPrimitive?.contentOrNull
                fun long(column: String) = row[column]!!.jsonPrimitive.long
                fun json(column: String) = NativeJsonInput.parse(string(column)!!)
                fun strings(column: String): List<String> = decode(column, string(column)!!) { Jsonx.decode<List<String>>(it) }
                when (table.name) {
                    "chats" -> {
                        ref("characterId", "characters"); ref("userPersonaId", "personas"); ref("sandboxId", "sandboxes")
                        ref("forkedFromChatId", "chats", historical = true)
                        anchor("forkedFromMessageId", string("forkedFromMessageId"), string("forkedFromChatId") ?: "", true)
                        strings("memberIdsJson").forEach { ref("memberIdsJson", "characters", it) }
                        val members = decode("memberSettingsJson", string("memberSettingsJson")!!) { Jsonx.decode<Map<String, GroupMemberSettings>>(it) }
                        members.forEach { (id, settings) -> ref("memberSettingsJson", "characters", id); ref("memberSettingsJson", "model_presets", settings.presetId) }
                        val overrides = decode("overridesJson", string("overridesJson")!!) { Jsonx.decode<ChatOverrides>(it) }
                        overrides.assistant?.let { binding ->
                            ref("overridesJson", "assistants", binding.assistantId)
                            ref("overridesJson", "chats", binding.targetChatId)
                            if (binding.assistantId.isBlank() || binding.targetChatId == string("id") || row["isGroup"]?.jsonPrimitive?.intOrNull == 1 ||
                                binding.sandboxToolsEnabled && string("sandboxId").isNullOrBlank()) report("INVALID_ASSISTANT_BINDING", "overridesJson")
                        }
                        ref("overridesJson", "model_presets", overrides.presetId)
                        ref("overridesJson", "characters", overrides.groupMvuAnchorCharacterId)
                        ref("overridesJson", "characters", overrides.groupMvuTemplateCharacterId)
                        (overrides.worldBookAttachments.map { it.worldBookId } + overrides.worldBookGlobalExclusions)
                            .forEach { ref("overridesJson", "world_books", it) }
                        listOf(overrides.contextStartMessageId, overrides.lastAutoMemorySummaryAfterMessageId,
                            overrides.lastStateMaintenanceAfterMessageId).forEach { anchor("overridesJson", it, string("id")!!) }
                    }
                    "message_nodes" -> {
                        ref("chatId", "chats"); ref("parentNodeId", "message_nodes")
                        string("parentNodeId")?.let {
                            if (it == string("id") || effective("message_nodes", it, "chatId")?.jsonPrimitive?.contentOrNull != string("chatId") ||
                                (effective("message_nodes", it, "orderIndex")?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE) >= long("orderIndex"))
                                report("INVALID_NODE_PARENT", "parentNodeId")
                        }
                        live.query(SimpleSQLiteQuery("SELECT id FROM message_nodes WHERE chatId=? AND orderIndex=? AND id<>?",
                            arrayOf(string("chatId")!!, long("orderIndex"), string("id")!!))).use {
                            while (it.moveToNext()) {
                                // Incoming nodes may move together; candidate schema already checks incoming order uniqueness.
                                if (stagedId("message_nodes", singleKey("message_nodes", it.getString(0))) == null)
                                    report("NODE_ORDER_CONFLICT", "orderIndex")
                            }
                        }
                    }
                    "message_candidates" -> {
                        ref("chatId", "chats"); ref("nodeId", "message_nodes")
                        if (effective("message_nodes", string("nodeId")!!, "chatId")?.jsonPrimitive?.contentOrNull != string("chatId")) report("CANDIDATE_OUTSIDE_NODE", "nodeId")
                        ref("characterId", "characters", historical = true); ref("senderPersonaId", "personas", historical = true)
                        for (column in listOf("imagesJson", "attachmentsJson")) decode(column, string(column)!!) { Jsonx.decode<List<AttachmentRef>>(it) }
                        string("generationMetadataJson")?.let { raw ->
                            var verifiedLedger = false
                            try {
                                val frame = NativeGenerationUsage.frame(raw)
                                if (frame == null || !NativeGenerationUsage.known(frame)) report("GENERATION_METADATA_UNVERIFIED", "generationMetadataJson", false)
                                else {
                                    val usage = usageByRequest(frame.metadata.requestId, true)
                                    if (usage == null) report("GENERATION_USAGE_ABSENT", "generationMetadataJson", false)
                                    else {
                                        val ledger = NativeGenerationUsage.ledger(usage)
                                        if (ledger == null) report("GENERATION_USAGE_UNVERIFIED", "generationMetadataJson", false)
                                        else {
                                            verifiedLedger = true
                                            if (NativeGenerationUsage.proof(frame, string("id")!!, string("chatId")!!, string("role"),
                                                    string("usageJson"), ledger) { target, owner, column, budget -> effective(target, owner, column, budget) } == null)
                                                report("GENERATION_USAGE_MISMATCH", "generationMetadataJson")
                                        }
                                    }
                                }
                            } catch (_: StError.Validation) { report("GENERATION_USAGE_UNREADABLE", "generationMetadataJson", verifiedLedger) }
                            catch (_: StError.Unsupported) { report("GENERATION_USAGE_UNREADABLE", "generationMetadataJson", verifiedLedger) }
                        }
                        val extra = string("extraJson")?.let { NativeJsonInput.parse(it) } as? JsonObject
                        extra?.get("_tts")?.takeIf { it != JsonNull }?.let { raw ->
                            val binding = decode("extraJson", raw.toString()) { Jsonx.decode<SpeechMessageBinding>(it) }
                            SpeechTaskMetadata.validateBinding(binding)
                            val bytes = listOf("content", "extraJson", "generationMetadataJson").sumOf {
                                (string(it)?.toByteArray(Charsets.UTF_8)?.size ?: 0).toLong()
                            }
                            if (bytes > 262_144) report("SPEECH_MESSAGE_BUDGET", "extraJson")
                            if ((binding.messageText ?: binding.sourceText) != string("content"))
                                report("SPEECH_BINDING_STALE", "extraJson", false)
                            if (!exists("tasks", binding.assetId)) report("SPEECH_CACHE_ABSENT", "extraJson", false)
                            else if (effective("tasks", binding.assetId, "kind")?.jsonPrimitive?.contentOrNull != TaskKind.SPEECH_SYNTHESIS.name)
                                report("SPEECH_BINDING_TASK_KIND", "extraJson")
                            else {
                                val payload = effective("tasks", binding.assetId, "payloadJson", SpeechTaskMetadata.MAX_PAYLOAD_BYTES)?.jsonPrimitive?.contentOrNull
                                    ?: throw StError.Validation("native speech binding task has no payload")
                                if (payload.toByteArray(Charsets.UTF_8).size > SpeechTaskMetadata.MAX_PAYLOAD_BYTES)
                                    throw StError.Validation("native speech binding payload exceeds budget")
                                val speech = decode("extraJson", payload) { Jsonx.decode<SpeechTaskProgress>(it) }
                                SpeechTaskMetadata.validate(speech)
                                if (speech.taskId != binding.assetId) report("SPEECH_TASK_ID_MISMATCH", "extraJson")
                                val asset = speech.asset
                                if (asset == null) report("SPEECH_CACHE_ABSENT", "extraJson", false)
                                else {
                                    if (asset.version == 2) {
                                        if (asset.spokenText != binding.sourceText) report("SPEECH_TRANSCRIPT_MISMATCH", "extraJson")
                                        if (exists("chats", string("chatId")!!) && asset.sourceNamespace !=
                                            effective("chats", string("chatId")!!, "sourceNamespace")?.jsonPrimitive?.contentOrNull)
                                            report("SPEECH_SOURCE_MISMATCH", "extraJson")
                                    }
                                    if (!exists("attachments", "tts:${asset.id}")) report("SPEECH_CACHE_ABSENT", "extraJson", false)
                                }
                            }
                        }
                    }
                    "characters" -> {
                        strings("attachedWorldBookIdsJson").forEach { ref("attachedWorldBookIdsJson", "world_books", it) }
                        string("initialStateTablesJson")?.let { decode("initialStateTablesJson", it) { value -> Jsonx.decode<List<StatusTableDef>>(value) } }
                        string("contentRegexRulesJson")?.let { decode("contentRegexRulesJson", it) { value -> Jsonx.decode<List<ContentRegexRule>>(value) } }
                    }
                    "world_books" -> {
                        strings("sessionChatIdsJson").forEach { ref("sessionChatIdsJson", "chats", it, true) }
                        decode("entriesJson", string("entriesJson")!!) { Jsonx.decode<List<WorldBookEntry>>(it) }
                    }
                    "long_term_memories" -> { ref("chatId", "chats"); anchor("anchorMessageId", string("anchorMessageId"), string("chatId")!!) }
                    "memory_summary_jobs" -> {
                        ref("chatId", "chats", historical = true)
                        strings("sourceMessageIdsJson").forEach { anchor("sourceMessageIdsJson", it, string("chatId")!!, true) }
                        anchor("coveredThroughMessageId", string("coveredThroughMessageId"), string("chatId")!!, true)
                    }
                    "mvu_states" -> { ref("chatId", "chats"); decode("tablesJson", string("tablesJson")!!) { Jsonx.decode<List<StatusTableDef>>(it) } }
                    "knowledge_graphs" -> {
                        ref("chatId", "chats")
                        val entities = decode("entitiesJson", string("entitiesJson")!!) { Jsonx.decode<List<KgEntity>>(it) }
                        val relations = decode("relationsJson", string("relationsJson")!!) { Jsonx.decode<List<KgRelation>>(it) }
                        val ids = entities.map { it.id }.toSet()
                        if (ids.size != entities.size || relations.any { it.subject !in ids || it.`object` !in ids }) report("INVALID_GRAPH_REFERENCES")
                    }
                    "mvu_work_logs" -> ref("chatId", "chats", historical = true)
                    "attachments" -> { ref("chatId", "chats", historical = true); ref("messageCandidateId", "message_candidates", historical = true) }
                    "assistant_messages" -> ref("assistantId", "assistants")
                    "assistants" -> if (json("settingsJson") !is JsonObject) report("INVALID_CONFIG_JSON", "settingsJson")
                    "model_presets" -> if (json("configJson") !is JsonObject) report("INVALID_CONFIG_JSON", "configJson")
                    "app_settings" -> {
                        if (json("settingsJson") !is JsonObject) report("INVALID_CONFIG_JSON", "settingsJson")
                        if (string("id") == "speech_pricing_v1") {
                            val raw = string("settingsJson")!!
                            if (raw.toByteArray(Charsets.UTF_8).size > 262_144) throw StError.Validation("native speech pricing exceeds budget")
                            SpeechUsageStore.validateRules(decode("settingsJson", raw) { Jsonx.decode<SpeechPricingRuleSet>(it) })
                        }
                        if (string("id") == GeminiCachePriceStore.SETTINGS_ID) {
                            val raw = string("settingsJson")!!
                            if (raw.toByteArray(Charsets.UTF_8).size > 262_144) throw StError.Validation("native cache pricing exceeds budget")
                            GeminiCachePricing.validateRules(decode("settingsJson", raw) { Jsonx.decode<GeminiCachePricingRuleSet>(it) })
                        }
                    }
                    "id_maps" -> {
                        // Deleted source objects intentionally retain mappings/baselines (import tombstones).
                        val type = string("entityType")!!
                        ID_MAP_TABLES[type]?.let { ref("targetId", it, historical = true) }
                        val namespace = string("namespace")!!
                        val size = namespace.substringBefore(':').toIntOrNull()
                        if (size == null || size <= 0 || size > namespace.length ||
                            namespace.substringAfter(':').let { it.length <= size || it.substring(size) != ":$type" })
                            report("LEGACY_OR_INVALID_TYPED_NAMESPACE", "namespace", false)
                    }
                    "tasks" -> {
                        ref("chatId", "chats", historical = true); ref("sandboxId", "sandboxes", historical = true); strings("fileChangeIdsJson")
                        if (string("kind") in setOf(TaskKind.GEMINI_CACHE_OPERATION.name, TaskKind.GEMINI_CACHE_LIST.name)) {
                            val raw = string("payloadJson") ?: throw StError.Validation("native cache operation payload missing")
                            if (raw.toByteArray(Charsets.UTF_8).size > GeminiCacheTaskMetadata.MAX_LIST_BYTES) throw StError.Validation("native cache operation exceeds budget")
                            val cache = decode("payloadJson", raw) { Jsonx.decode<GeminiCacheTaskProgress>(it) }
                            GeminiCacheAccounting.operation(raw)
                            if (string("kind") != GeminiCacheTaskMetadata.kind(cache.action).name) report("CACHE_TASK_KIND_MISMATCH", "kind")
                            GeminiCacheTaskMetadata.validateTask(cache, string("id")!!, string("chatId"), string("createdAt")!!, TaskStatus.valueOf(string("status")!!))
                            cacheLifecycle.inspect(cache.taskId)
                            for (ancestorId in cache.lifecyclePreviousTaskIds) {
                                ref("payloadJson", "tasks", ancestorId, historical = true)
                                if (exists("tasks", ancestorId)) {
                                    val ancestorRaw = effective("tasks", ancestorId, "payloadJson", GeminiCacheTaskMetadata.MAX_BYTES)?.jsonPrimitive?.contentOrNull
                                    val ancestor = ancestorRaw?.let(GeminiCacheAccounting::operation)
                                    if (effective("tasks", ancestorId, "kind", 128)?.jsonPrimitive?.contentOrNull != TaskKind.GEMINI_CACHE_OPERATION.name ||
                                        ancestor == null || ancestor.owner != cache.owner || ancestor.resourceName != cache.resourceName || ancestor.endedAt == null ||
                                        java.time.OffsetDateTime.parse(cache.createdAt).toInstant() < java.time.OffsetDateTime.parse(ancestor.updatedAt).toInstant())
                                        report("CACHE_LIFECYCLE_ANCESTOR_MISMATCH", "payloadJson")
                                }
                            }
                            if (cache.accountingVersion == 1) {
                                val ledger = usageByRequest(GeminiCacheAccounting.eventId(cache.taskId), incoming = true)
                                if (ledger == null) report("CACHE_OPERATION_LEDGER_MISSING", "payloadJson")
                                else if (!GeminiCacheAccounting.sameEvidence(GeminiCacheAccounting.parse(ledger), cache) ||
                                    !GeminiCacheAccounting.samePayload(raw, ledger.usageJson)) report("CACHE_OPERATION_LEDGER_MISMATCH", "payloadJson")
                            }
                            ref("payloadJson", "model_presets", cache.owner.presetId, historical = true)
                            ref("payloadJson", "characters", cache.owner.characterId, historical = true)
                            ref("payloadJson", "tasks", cache.generationTaskId, historical = true)
                            cache.generationTaskId?.let { task ->
                                if (exists("tasks", task) && (effective("tasks", task, "chatId", 1024)?.jsonPrimitive?.contentOrNull != cache.owner.chatId ||
                                    effective("tasks", task, "kind", 128)?.jsonPrimitive?.contentOrNull !in setOf(TaskKind.CHAT_GENERATION.name,
                                        TaskKind.GROUP_GENERATION.name, TaskKind.MEMORY_SUMMARY.name, TaskKind.MVU_UPDATE.name,
                                        TaskKind.KG_UPDATE.name, TaskKind.SPEECH_SYNTHESIS.name)))
                                    report("CACHE_GENERATION_TASK_MISMATCH", "payloadJson")
                            }
                            cache.generationRequestId?.let { request ->
                                val usage = usageByRequest(request, incoming = true)
                                if (usage == null) report("CACHE_GENERATION_USAGE_HISTORICAL", "payloadJson", false)
                                else {
                                    val accounting = TokenRequestAccounting.parse(usage)
                                    if (accounting == null || usage.chatId != cache.owner.chatId || usage.provider != cache.owner.providerId ||
                                        usage.model != cache.owner.model || accounting.context.sourceNamespace != cache.owner.sourceNamespace ||
                                        accounting.context.protocol != LlmProtocol.GEMINI_GENERATE_CONTENT.name || accounting.context.taskId != cache.generationTaskId)
                                        report("CACHE_GENERATION_USAGE_MISMATCH", "payloadJson")
                                }
                            }
                            if (exists("chats", cache.owner.chatId) && effective("chats", cache.owner.chatId, "sourceNamespace", 1024)?.jsonPrimitive?.contentOrNull != cache.owner.sourceNamespace)
                                report("CACHE_TASK_SOURCE_MISMATCH", "payloadJson")
                            cache.referenceTaskId?.let { reference ->
                                ref("payloadJson", "tasks", reference, historical = true)
                                if (exists("tasks", reference)) {
                                    val previousRaw = effective("tasks", reference, "payloadJson", GeminiCacheTaskMetadata.MAX_LIST_BYTES)?.jsonPrimitive?.contentOrNull
                                    val previous = previousRaw?.let { Jsonx.decodeOrNull<GeminiCacheTaskProgress>(it) }
                                    val expectedKind = if (cache.action == GeminiCacheAction.LIST) TaskKind.GEMINI_CACHE_LIST else TaskKind.GEMINI_CACHE_OPERATION
                                    val matching = previous != null && previous.owner == cache.owner && previous.remoteCompleted &&
                                        if (cache.action == GeminiCacheAction.LIST) previous.action == GeminiCacheAction.LIST && previous.phase == "completed" &&
                                            previous.listingPageSize == cache.listingPageSize && previous.listing?.hasNextPage == true &&
                                            java.time.OffsetDateTime.parse(cache.createdAt).toInstant() >= java.time.OffsetDateTime.parse(previous.updatedAt).toInstant()
                                        else previous.resourceName == cache.resourceName && previous.action != GeminiCacheAction.DELETE
                                    if (effective("tasks", reference, "kind", 128)?.jsonPrimitive?.contentOrNull != expectedKind.name || !matching)
                                        report("CACHE_REFERENCE_MISMATCH", "payloadJson")
                                }
                            }
                        }
                        if (string("kind") in setOf(TaskKind.SPEECH_VOICE_DESIGN.name, TaskKind.SPEECH_VOICE_CLONE.name)) {
                            val raw = string("payloadJson") ?: throw StError.Validation("native voice design payload missing")
                            if (raw.toByteArray(Charsets.UTF_8).size > 32_768) throw StError.Validation("native voice design payload exceeds budget")
                            val design = decode("payloadJson", raw) { Jsonx.decode<SpeechVoiceDesignProgress>(it) }
                            val operation = if (string("kind") == TaskKind.SPEECH_VOICE_CLONE.name) "clone" else "design"
                            if (design.version != 1 || design.taskId != string("id") || design.operation != operation ||
                                design.selection.provider !in (if (operation == "clone") setOf("minimax", "glm", "siliconflow") else setOf("minimax")))
                                report("VOICE_DESIGN_TASK_MISMATCH", "payloadJson")
                            ref("payloadJson", "assistants", design.selection.assistantId, historical = true)
                            ref("payloadJson", "model_presets", design.selection.presetId, historical = true)
                            design.result?.previewAttachment?.let { preview ->
                                ref("payloadJson", "attachments", preview.id, historical = true)
                                if (preview.id != "voice-$operation:${design.taskId}" || preview.relativePath != "voice-$operation-${design.taskId}.mp3")
                                    report("VOICE_DESIGN_PREVIEW_MISMATCH", "payloadJson")
                            }
                        }
                        if (string("kind") == TaskKind.SPEECH_PLAYBACK.name) {
                            val raw = string("payloadJson") ?: throw StError.Validation("native playback payload missing")
                            if (raw.toByteArray(Charsets.UTF_8).size > SpeechTaskMetadata.MAX_PAYLOAD_BYTES)
                                throw StError.Validation("native playback payload exceeds budget")
                            val playback = decode("payloadJson", raw) { Jsonx.decode<SpeechPlaybackProgress>(it) }
                            if (playback.mode == "voice_preview") {
                                if (playback.taskId != string("id") || playback.voiceCreationTaskId == null ||
                                    playback.downloadedBytes !in 0..20_000_000L)
                                    report("VOICE_PREVIEW_PLAYBACK_MISMATCH", "payloadJson")
                                ref("payloadJson", "attachments", playback.attachmentId, historical = true)
                                ref("payloadJson", "tasks", playback.voiceCreationTaskId, historical = true)
                                playback.voiceCreationTaskId?.let { source ->
                                    if (exists("tasks", source) && effective("tasks", source, "kind", 128)?.jsonPrimitive?.contentOrNull !in
                                        setOf(TaskKind.SPEECH_VOICE_DESIGN.name, TaskKind.SPEECH_VOICE_CLONE.name))
                                        report("VOICE_PREVIEW_SOURCE_KIND_MISMATCH", "payloadJson")
                                }
                            }
                        }
                        if (string("kind") == TaskKind.SPEECH_SYNTHESIS.name) {
                            val raw = string("payloadJson") ?: throw StError.Validation("native speech task has no payload")
                            if (raw.toByteArray(Charsets.UTF_8).size > SpeechTaskMetadata.MAX_PAYLOAD_BYTES) throw StError.Validation("native speech payload exceeds 128 KiB")
                            val speech = decode("payloadJson", raw) { Jsonx.decode<SpeechTaskProgress>(it) }
                            SpeechTaskMetadata.validate(speech)
                            if (speech.taskId != string("id")) report("SPEECH_TASK_ID_MISMATCH", "payloadJson")
                            ref("payloadJson", "assistants", speech.selection.assistantId, historical = true)
                            ref("payloadJson", "model_presets", speech.selection.presetId, historical = true)
                            speech.selection.preprocessing?.let { selection ->
                                ref("payloadJson", "model_presets", selection.llmPresetId, historical = true)
                                if (exists("model_presets", selection.llmPresetId) &&
                                    effective("model_presets", selection.llmPresetId, "sourceNamespace", 1024)?.jsonPrimitive?.contentOrNull != selection.sourceNamespace)
                                    report("SPEECH_PREPROCESS_PRESET_SOURCE_HISTORICAL", "payloadJson", false)
                                speech.preprocessingRequestId?.let { request ->
                                    ref("payloadJson", "usage_events", request, historical = true)
                                    if (exists("usage_events", request) && SpeechPreprocessingUsage.proof(selection, request,
                                            speech.selection.preprocessingTaskId ?: speech.taskId,
                                            string("chatId"), speech.messageCandidateId) { column, budget -> effective("usage_events", request, column, budget) } == null)
                                        report("SPEECH_PREPROCESS_USAGE_MISMATCH", "payloadJson")
                                }
                            }
                            speech.asset?.let { asset ->
                                if (asset.chatId != string("chatId")) report("SPEECH_CHAT_MISMATCH", "payloadJson")
                                asset.chatId?.let { chat ->
                                    if (asset.version == 2 && exists("chats", chat) && asset.sourceNamespace !=
                                        effective("chats", chat, "sourceNamespace")?.jsonPrimitive?.contentOrNull)
                                        report("SPEECH_SOURCE_MISMATCH", "payloadJson")
                                }
                                ref("payloadJson", "message_candidates", asset.messageCandidateId, historical = true)
                                asset.requestId?.let { request ->
                                    val usageId = "tts:$request"
                                    ref("payloadJson", "usage_events", usageId, historical = true)
                                    if (exists("usage_events", usageId)) {
                                        val usageRaw = effective("usage_events", usageId, "usageJson", 65_536)?.jsonPrimitive?.contentOrNull
                                            ?: throw StError.Validation("native speech asset usage missing")
                                        NativeJsonInput.parse(usageRaw)
                                        val usage = decode("payloadJson", usageRaw) { Jsonx.decode<SpeechUsageSnapshot>(it) }
                                        SpeechUsageStore.validateSnapshot(usage)
                                        if (!SpeechUsageStore.matchesAsset(usage, asset) ||
                                            effective("usage_events", usageId, "provider", 1024)?.jsonPrimitive?.contentOrNull != "tts:${asset.provider}" ||
                                            effective("usage_events", usageId, "model", 2048)?.jsonPrimitive?.contentOrNull != asset.model ||
                                            effective("usage_events", usageId, "eventId", 1024)?.jsonPrimitive?.contentOrNull != usageId ||
                                            effective("usage_events", usageId, "requestId", 1024)?.jsonPrimitive?.contentOrNull != request ||
                                            effective("usage_events", usageId, "chatId", 1024)?.jsonPrimitive?.contentOrNull != asset.chatId ||
                                            effective("usage_events", usageId, "createdAt", 64)?.jsonPrimitive?.contentOrNull != usage.startedAt)
                                            report("SPEECH_USAGE_ASSET_MISMATCH", "payloadJson")
                                        val cost = effective("usage_events", usageId, "costJson", 32_768)?.jsonPrimitive?.contentOrNull?.let {
                                            NativeJsonInput.parse(it); Jsonx.decodeOrNull<CostSnapshot>(it)
                                        }
                                        if (cost != SpeechUsageStore.cost(usage)) report("SPEECH_COST_MISMATCH", "payloadJson")
                                    }
                                }
                                val attachmentId = "tts:${asset.id}"
                                if (!exists("attachments", attachmentId)) report("SPEECH_CACHE_ABSENT", "payloadJson", false)
                                else {
                                    val filename = "tts-${asset.id}.${asset.format}"
                                    val mime = when (asset.format) { "mp3" -> "audio/mpeg"; "pcm" -> "audio/pcm"; "opus" -> "audio/ogg"; else -> "audio/${asset.format}" }
                                    if (effective("attachments", attachmentId, "relativePath")?.jsonPrimitive?.contentOrNull != "tts-${asset.id}.${asset.format}" ||
                                        effective("attachments", attachmentId, "filename")?.jsonPrimitive?.contentOrNull != filename ||
                                        effective("attachments", attachmentId, "mimeType")?.jsonPrimitive?.contentOrNull != mime ||
                                        effective("attachments", attachmentId, "size")?.jsonPrimitive?.longOrNull != asset.bytes ||
                                        effective("attachments", attachmentId, "chatId")?.jsonPrimitive?.contentOrNull != asset.chatId ||
                                        effective("attachments", attachmentId, "messageCandidateId")?.jsonPrimitive?.contentOrNull != asset.messageCandidateId ||
                                        effective("attachments", attachmentId, "sourceUri") != JsonNull)
                                        report("SPEECH_ATTACHMENT_MISMATCH", "payloadJson")
                                }
                            }
                        }
                    }
                    "approvals" -> { ref("taskId", "tasks", historical = true); ref("sandboxId", "sandboxes", historical = true) }
                    "file_changes" -> { ref("taskId", "tasks", historical = true); ref("sandboxId", "sandboxes", historical = true) }
                    "sandboxes" -> if (string("id")?.matches(Regex("[A-Za-z0-9_-]{1,128}")) != true || string("rootRelativePath") != "sandboxes/${string("id")}") report("INVALID_SANDBOX_ROOT", "rootRelativePath")
                    "usage_events" -> {
                        ref("chatId", "chats", historical = true)
                        live.query(SimpleSQLiteQuery("SELECT id FROM usage_events WHERE eventId=? AND id<>? LIMIT 1", arrayOf(string("eventId")!!, string("id")!!))).use {
                            if (it.moveToFirst()) report("UNIQUE_EVENT_CONFLICT", "eventId")
                        }
                        val raw = string("usageJson")!!
                        val envelope = NativeJsonInput.parse(raw) as? JsonObject ?: throw StError.Validation("invalid native usage envelope")
                        when (envelope["_kind"]?.jsonPrimitive?.contentOrNull) {
                            null, "llm" -> {
                                decode("usageJson", raw) { Jsonx.decode<UsageTokens>(it) }
                                if (envelope.containsKey("_requestLifecycle")) TokenRequestAccounting.parse(TokenRequestAccounting.fromColumns(row))?.let { request ->
                                    ref("usageJson", "tasks", request.context.taskId, historical = true)
                                    ref("usageJson", "message_candidates", request.context.messageCandidateId, historical = true)
                                    request.context.messageCandidateId?.let { candidate ->
                                        if (exists("message_candidates", candidate) &&
                                            effective("message_candidates", candidate, "chatId", 1024)?.jsonPrimitive?.contentOrNull != string("chatId"))
                                            report("TOKEN_REQUEST_MESSAGE_SCOPE_MISMATCH", "usageJson")
                                    }
                                    request.context.taskId?.let { task ->
                                        if (exists("tasks", task)) {
                                            if (effective("tasks", task, "chatId", 1024)?.jsonPrimitive?.contentOrNull != string("chatId"))
                                                report("TOKEN_REQUEST_TASK_SCOPE_MISMATCH", "usageJson")
                                            val expected = when (request.context.purpose) {
                                                "tts_preprocess" -> setOf(TaskKind.SPEECH_SYNTHESIS.name, TaskKind.SPEECH_PLAYBACK.name)
                                                "chat_generation" -> setOf(TaskKind.CHAT_GENERATION.name, TaskKind.GROUP_GENERATION.name)
                                                "memory_summary" -> setOf(TaskKind.MEMORY_SUMMARY.name)
                                                "state_maintenance" -> setOf(TaskKind.MVU_UPDATE.name, TaskKind.KG_UPDATE.name)
                                                else -> setOf(TaskKind.SPEECH_SYNTHESIS.name)
                                            }
                                            if (effective("tasks", task, "kind", 128)?.jsonPrimitive?.contentOrNull !in expected)
                                                report("TOKEN_REQUEST_TASK_KIND_MISMATCH", "usageJson")
                                        }
                                        if (request.context.purpose == "memory_summary") {
                                            ref("usageJson", "memory_summary_jobs", task, historical = true)
                                            if (exists("memory_summary_jobs", task) &&
                                                effective("memory_summary_jobs", task, "chatId", 1024)?.jsonPrimitive?.contentOrNull != string("chatId"))
                                                report("TOKEN_REQUEST_SUMMARY_SCOPE_MISMATCH", "usageJson")
                                        }
                                    }
                                    request.context.scope?.takeIf { it.startsWith("assistant:") }?.let {
                                        ref("usageJson", "assistants", it.removePrefix("assistant:"), historical = true)
                                    }
                                }
                            }
                            GeminiCacheAccounting.KIND -> {
                                val cache = GeminiCacheAccounting.parse(TokenRequestAccounting.fromColumns(row))
                                ref("usageJson", "tasks", cache.taskId, historical = true)
                                if (exists("tasks", cache.taskId)) {
                                    val payload = effective("tasks", cache.taskId, "payloadJson", GeminiCacheTaskMetadata.MAX_LIST_BYTES)?.jsonPrimitive?.contentOrNull
                                    if (effective("tasks", cache.taskId, "kind", 128)?.jsonPrimitive?.contentOrNull != GeminiCacheTaskMetadata.kind(cache.action).name ||
                                        payload == null || !GeminiCacheAccounting.sameEvidence(GeminiCacheAccounting.operation(payload), cache) ||
                                        !GeminiCacheAccounting.samePayload(payload, raw))
                                        report("CACHE_LEDGER_TASK_MISMATCH", "usageJson")
                                }
                                if (exists("chats", cache.owner.chatId) && effective("chats", cache.owner.chatId, "sourceNamespace", 1024)?.jsonPrimitive?.contentOrNull != cache.owner.sourceNamespace)
                                    report("CACHE_LEDGER_SOURCE_MISMATCH", "usageJson")
                            }
                            "web_search" -> {
                                val search = decode("usageJson", raw) { Jsonx.decode<SearchUsageSnapshot>(it) }
                                SearchUsageStore.validateSnapshot(search)
                                if (string("provider") != "web-search:${search.provider}" || string("chatId") != search.context.chatId) report("SEARCH_USAGE_SCOPE_MISMATCH", "usageJson")
                                val cost = string("costJson")?.let { Jsonx.decodeOrNull<CostSnapshot>(it) }
                                if (cost != SearchUsageStore.cost(search)) report("SEARCH_COST_MISMATCH", "costJson")
                                ref("taskId", "tasks", search.context.taskId, historical = true)
                            }
                            "speech_voice_design", "speech_voice_clone" -> {
                                val design = decode("usageJson", raw) { Jsonx.decode<SpeechVoiceDesignUsage>(it) }
                                val cloning = envelope["_kind"] == JsonPrimitive("speech_voice_clone")
                                val kind = if (cloning) TaskKind.SPEECH_VOICE_CLONE else TaskKind.SPEECH_VOICE_DESIGN
                                val valid = if (cloning) UsageStore.validVoiceCloneUsage(TokenRequestAccounting.fromColumns(row), design)
                                    else UsageStore.validVoiceDesignUsage(TokenRequestAccounting.fromColumns(row), design)
                                if (!valid)
                                    report("VOICE_DESIGN_USAGE_MISMATCH", "usageJson")
                                ref("usageJson", "tasks", design.taskId, historical = true)
                                if (exists("tasks", design.taskId) &&
                                    (effective("tasks", design.taskId, "kind", 128)?.jsonPrimitive?.contentOrNull != kind.name ||
                                        effective("tasks", design.taskId, "chatId", 1024)?.jsonPrimitive?.contentOrNull != string("chatId")))
                                    report("VOICE_DESIGN_USAGE_TASK_MISMATCH", "usageJson")
                            }
                            "tts" -> {
                                if (raw.toByteArray(Charsets.UTF_8).size > 65_536) throw StError.Validation("native speech usage exceeds budget")
                                val speech = decode("usageJson", raw) { Jsonx.decode<SpeechUsageSnapshot>(it) }
                                SpeechUsageStore.validateSnapshot(speech)
                                if (string("provider") != "tts:${speech.provider}" || string("model") != speech.model ||
                                    string("chatId") != speech.context.chatId || string("createdAt") != speech.startedAt ||
                                    !SpeechUsageStore.validRequestId(string("requestId")) ||
                                    string("eventId") != "tts:${string("requestId")}" || string("id") != string("eventId"))
                                    report("SPEECH_USAGE_SCOPE_MISMATCH", "usageJson")
                                val cost = string("costJson")?.let { Jsonx.decodeOrNull<CostSnapshot>(it) }
                                if (cost != SpeechUsageStore.cost(speech)) report("SPEECH_COST_MISMATCH", "costJson")
                                ref("usageJson", "tasks", speech.context.taskId, historical = true)
                                ref("usageJson", "message_candidates", speech.context.messageCandidateId, historical = true)
                                if (speech.scope.startsWith("assistant:")) ref("usageJson", "assistants", speech.scope.removePrefix("assistant:"), true)
                            }
                            else -> report("UNSUPPORTED_USAGE_KIND", "usageJson")
                        }
                    }
                    "content_regex_records" -> {
                        ref("chatId", "chats"); ref("nodeId", "message_nodes"); ref("messageId", "message_candidates")
                        val candidateId = string("messageId")!!
                        if (effective("message_candidates", candidateId, "nodeId")?.jsonPrimitive?.contentOrNull != string("nodeId") ||
                            effective("message_candidates", candidateId, "chatId")?.jsonPrimitive?.contentOrNull != string("chatId") ||
                            effective("message_nodes", string("nodeId")!!, "orderIndex")?.jsonPrimitive?.longOrNull != long("orderIndex"))
                            report("REGEX_SOURCE_MISMATCH", blocking = string("queueStatus") != "stale")
                        val rules = json("rulesJson") as? JsonObject ?: throw StError.Validation("invalid native regex rules envelope")
                        val ruleList = rules["rules"] ?: throw StError.Validation("native regex rules missing")
                        decode("rulesJson", ruleList.toString()) { Jsonx.decode<List<ContentRegexRule>>(it) }
                        if (rules["depth"]?.jsonPrimitive?.intOrNull !in 1..500) report("INVALID_REGEX_DEPTH", "rulesJson")
                        decode("outputJson", string("outputJson")!!) { Jsonx.decode<ContentRegexOutput>(it) }
                    }
                }
            }
            plans += NativeRestoreTablePlan(table.name, table.rows, insert, identical, conflicting)
        }
        // Includes existing nodes receiving new incoming candidates, not just nodes present in the ZIP.
        var after = Long.MIN_VALUE
        while (true) {
            currentCoroutineContext().ensureActive()
            val affected = stageQuery("SELECT rowid,id FROM native_affected_nodes WHERE rowid>? ORDER BY rowid LIMIT 200", arrayOf(after)).use {
                buildList { while (it.moveToNext()) add(it.getLong(0) to it.getString(1)) }
            }
            if (affected.isEmpty()) break
            for ((_, id) in affected) {
                val indices = mutableSetOf<Long>()
                var count = 0L; var duplicate = false
                fun collect(cursor: Cursor, local: Boolean) = cursor.use {
                    while (count <= 100_000 && it.moveToNext()) {
                        if (local && stagedId("message_candidates", singleKey("message_candidates", it.getString(0))) != null) continue
                        count++; if (!indices.add(it.getLong(1))) duplicate = true
                    }
                }
                val sql = "SELECT id,candidateIndex FROM message_candidates WHERE nodeId=?"
                collect(stageQuery(sql, arrayOf(id)), false)
                collect(live.query(SimpleSQLiteQuery(sql, arrayOf(id))), true)
                val selected = effective("message_nodes", id, "selectedIndex")?.jsonPrimitive?.longOrNull
                if (duplicate || count == 0L || count > 100_000 || indices.minOrNull() != 0L || indices.maxOrNull() != count - 1 || selected !in indices)
                    issue(NativeRestoreIssue("INVALID_CANDIDATE_SEQUENCE", "message_nodes", buildJsonObject { put("id", id) }.toString().take(2048), column = "selectedIndex"))
            }
            after = affected.last().first
        }
        if (replacing && manifest.tables.any { it.name in setOf("message_nodes", "message_candidates") && it.rows > 0 }) {
            // Check dependencies retained outside the archive too, without treating unrelated old damage as new damage.
            suspend fun oldCell(table: String, id: String, column: String): JsonElement? =
                liveId(table, singleKey(table, id))?.let { liveCells.read(table, column, it) }
            suspend fun selectedAnchor(id: String?, chat: String, incoming: Boolean): Boolean {
                if (id.isNullOrBlank()) return true
                suspend fun cell(table: String, identity: String, column: String) =
                    if (incoming) effective(table, identity, column) else oldCell(table, identity, column)
                val candidateChat = cell("message_candidates", id, "chatId")?.jsonPrimitive?.contentOrNull
                if (candidateChat != null) {
                    val node = cell("message_candidates", id, "nodeId")!!.jsonPrimitive.content
                    return candidateChat == chat && cell("message_nodes", node, "chatId")?.jsonPrimitive?.contentOrNull == chat &&
                        cell("message_nodes", node, "selectedIndex")?.jsonPrimitive?.longOrNull ==
                        cell("message_candidates", id, "candidateIndex")?.jsonPrimitive?.longOrNull
                }
                return cell("message_nodes", id, "chatId")?.jsonPrimitive?.contentOrNull == chat
            }
            suspend fun retained(table: String, columns: List<String>, consume: suspend (JsonObject) -> Unit) {
                var last = Long.MIN_VALUE
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val ids = live.query(SimpleSQLiteQuery("SELECT rowid FROM `$table` WHERE rowid>? ORDER BY rowid LIMIT 200", arrayOf(last))).use {
                        buildList { while (it.moveToNext()) add(it.getLong(0)) }
                    }
                    if (ids.isEmpty()) break
                    for (id in ids) {
                        val keyName = primaryKey(table)
                        val key = buildJsonObject { put(keyName, liveCells.read(table, keyName, id)) }
                        if (stagedId(table, key) != null) continue
                        val row = key.toMutableMap()
                        for (column in columns) row[column] = liveCells.read(table, column, id)
                        consume(JsonObject(row))
                    }
                    last = ids.last()
                }
            }
            retained("message_nodes", listOf("parentNodeId", "chatId", "orderIndex")) { row ->
                val parent = row["parentNodeId"]!!.jsonPrimitive.contentOrNull
                if (parent != null && stagedId("message_nodes", singleKey("message_nodes", parent)) != null) {
                    val chat = row["chatId"]!!.jsonPrimitive.content
                    val order = row["orderIndex"]!!.jsonPrimitive.long
                    val previouslyValid = oldCell("message_nodes", parent, "chatId")?.jsonPrimitive?.contentOrNull == chat &&
                        (oldCell("message_nodes", parent, "orderIndex")?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE) < order
                    if (previouslyValid && (effective("message_nodes", parent, "chatId")?.jsonPrimitive?.contentOrNull != chat ||
                        (effective("message_nodes", parent, "orderIndex")?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE) >= order))
                        issue(NativeRestoreIssue("PRESERVED_NODE_PARENT_CHANGED", "message_nodes", buildJsonObject { put("id", row.getValue("id")) }.toString().take(2048), column = "parentNodeId"))
                }
            }
            suspend fun checkAnchor(table: String, key: JsonObject, anchor: String?, chat: String, column: String) {
                if (selectedAnchor(anchor, chat, false) && !selectedAnchor(anchor, chat, true))
                    issue(NativeRestoreIssue("PRESERVED_MESSAGE_ANCHOR_CHANGED", table, key.toString().take(2048), column = column))
            }
            fun affectedChat(id: String) = stageQuery("SELECT 1 FROM message_nodes WHERE chatId=? UNION ALL SELECT 1 FROM message_candidates WHERE chatId=? LIMIT 1",
                arrayOf(id, id)).use { it.moveToFirst() }
            retained("chats", listOf("overridesJson")) { row ->
                if (affectedChat(row["id"]!!.jsonPrimitive.content)) {
                    val overrides = decode("overridesJson", row["overridesJson"]!!.jsonPrimitive.content) { Jsonx.decode<ChatOverrides>(it) }
                    for (anchor in listOf(overrides.contextStartMessageId, overrides.lastAutoMemorySummaryAfterMessageId, overrides.lastStateMaintenanceAfterMessageId))
                        checkAnchor("chats", buildJsonObject { put("id", row.getValue("id")) }, anchor, row["id"]!!.jsonPrimitive.content, "overridesJson")
                }
            }
            retained("long_term_memories", listOf("anchorMessageId")) { row ->
                checkAnchor("long_term_memories", buildJsonObject { put("chatId", row.getValue("chatId")) }, row["anchorMessageId"]!!.jsonPrimitive.contentOrNull,
                    row["chatId"]!!.jsonPrimitive.content, "anchorMessageId")
            }
            retained("content_regex_records", listOf("nodeId", "sourceText", "orderIndex", "queueStatus")) { row ->
                val candidate = row["messageId"]!!.jsonPrimitive.content
                val node = row["nodeId"]!!.jsonPrimitive.content
                val affected = stagedId("message_candidates", singleKey("message_candidates", candidate)) != null ||
                    stagedId("message_nodes", singleKey("message_nodes", node)) != null
                val previouslyValid = if (affected) oldCell("message_candidates", candidate, "content") == row["sourceText"] &&
                    oldCell("message_nodes", node, "orderIndex") == row["orderIndex"] else false
                if (affected && previouslyValid && row["queueStatus"]!!.jsonPrimitive.content != "stale" &&
                    (effective("message_candidates", candidate, "content") != row["sourceText"] ||
                        effective("message_nodes", node, "orderIndex") != row["orderIndex"]))
                    issue(NativeRestoreIssue("PRESERVED_REGEX_SOURCE_CHANGED", "content_regex_records", buildJsonObject { put("messageId", candidate) }.toString().take(2048)))
            }
        }
        if (replacing && manifest.tables.any { it.name in setOf("usage_events", "chats", "tasks", "message_candidates") && it.rows > 0 }) {
            val guard = NativeGenerationRestoreGuard(
                prior = { table, id, column, limit -> liveId(table, singleKey(table, id))?.let { liveCells.read(table, column, it, limit) } },
                combined = { table, id, column, limit -> effective(table, id, column, limit) },
                lookupUsage = { requestId, incoming -> usageByRequest(requestId, incoming) },
                included = { table, id -> stagedId(table, singleKey(table, id)) != null }, issue = issue,
            )
            var last = Long.MIN_VALUE; var scanned = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = live.query(SimpleSQLiteQuery("SELECT rowid, length(CAST(id AS BLOB)), " +
                    "length(CAST(chatId AS BLOB)), length(CAST(generationMetadataJson AS BLOB)), COALESCE(length(CAST(usageJson AS BLOB)),0) " +
                    "FROM message_candidates WHERE rowid>? AND generationMetadataJson IS NOT NULL ORDER BY rowid LIMIT 200", arrayOf(last))).use {
                    buildList { while (it.moveToNext()) add(longArrayOf(it.getLong(0), it.getLong(1), it.getLong(2), it.getLong(3), it.getLong(4))) }
                }
                if (page.isEmpty()) break
                for (sizes in page) {
                    currentCoroutineContext().ensureActive()
                    if (++scanned > 100_000) throw StError.Validation("retained generation reference scan exceeds 100000 rows")
                    if (sizes[1] > 1024 || sizes[2] > 1024 || sizes[3] > NativeGenerationUsage.MAX_METADATA_BYTES || sizes[4] > 65_536) {
                        issue(NativeRestoreIssue("LOCAL_GENERATION_UNREADABLE", "message_candidates",
                            buildJsonObject { put("rowid", sizes[0]) }.toString(), column = "generationMetadataJson", blocking = false))
                        continue
                    }
                    val id = liveCells.read("message_candidates", "id", sizes[0], 1024).jsonPrimitive.content
                    guard.inspect(id); guard.requireBudget()
                }
                last = page.last()[0]
            }
        }
        if (replacing && manifest.tables.any { it.name in setOf("usage_events", "tasks", "chats") && it.rows > 0 }) {
            val guard = NativeGeminiCacheRestoreGuard(
                prior = { table, id, column, limit -> liveId(table, singleKey(table, id))?.let { liveCells.read(table, column, it, limit) } },
                combined = { table, id, column, limit -> effective(table, id, column, limit) },
                lookupUsage = { eventId, incoming -> usageByRequest(eventId, incoming) },
                included = { table, id -> stagedId(table, singleKey(table, id)) != null }, issue = issue,
            )
            var last = Long.MIN_VALUE; var scanned = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = live.query(SimpleSQLiteQuery("SELECT rowid, length(CAST(id AS BLOB)), length(CAST(payloadJson AS BLOB)) " +
                    "FROM tasks WHERE rowid>? AND kind IN ('GEMINI_CACHE_OPERATION','GEMINI_CACHE_LIST') ORDER BY rowid LIMIT 200", arrayOf(last))).use {
                    buildList { while (it.moveToNext()) add(longArrayOf(it.getLong(0), it.getLong(1), it.getLong(2))) }
                }
                if (page.isEmpty()) break
                for (sizes in page) {
                    currentCoroutineContext().ensureActive()
                    if (++scanned > 100_000) throw StError.Validation("retained cache reference scan exceeds 100000 rows")
                    if (sizes[1] > 1024 || sizes[2] > GeminiCacheTaskMetadata.MAX_LIST_BYTES) {
                        issue(NativeRestoreIssue("LOCAL_CACHE_UNREADABLE", "tasks", buildJsonObject { put("rowid", sizes[0]) }.toString(),
                            column = "payloadJson", blocking = false))
                        continue
                    }
                    val id = liveCells.read("tasks", "id", sizes[0], 1024).jsonPrimitive.content
                    guard.inspect(id); guard.requireBudget()
                }
                last = page.last()[0]
            }
        }
        var protectedSpeechFiles: Map<String, Long> = emptyMap()
        if (replacing && (manifest.tables.any { it.name in setOf("tasks", "attachments", "chats", "message_candidates", "assistants", "model_presets", "usage_events") && it.rows > 0 } ||
                manifest.files.any { it.path.startsWith("attachments/tts-") })) {
            val guard = NativeSpeechRestoreGuard(
                prior = { table, id, column, limit -> liveId(table, singleKey(table, id))?.let { liveCells.read(table, column, it, limit) } },
                combined = { table, id, column, limit ->
                    val key = singleKey(table, id)
                    stagedId(table, key)?.let { stageCells.read(table, column, it, limit) }
                        ?: liveId(table, key)?.let { liveCells.read(table, column, it, limit) }
                },
                included = { table, id -> stagedId(table, singleKey(table, id)) != null },
                incomingFiles = manifest.files.mapTo(mutableSetOf()) { it.path }, issue = issue,
            )
            var last = Long.MIN_VALUE; var scanned = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = live.query(SimpleSQLiteQuery("SELECT rowid, length(CAST(id AS BLOB)), length(CAST(content AS BLOB)) + " +
                    "COALESCE(length(CAST(extraJson AS BLOB)),0) + COALESCE(length(CAST(generationMetadataJson AS BLOB)),0) " +
                    "FROM message_candidates WHERE rowid>? AND extraJson IS NOT NULL ORDER BY rowid LIMIT 200", arrayOf(last))).use {
                    buildList { while (it.moveToNext()) add(Triple(it.getLong(0), it.getLong(1), it.getLong(2))) }
                }
                if (page.isEmpty()) break
                for ((rowId, idBytes, bytes) in page) {
                    currentCoroutineContext().ensureActive()
                    if (++scanned > 100_000) throw StError.Validation("retained speech reference scan exceeds 100000 rows")
                    if (idBytes > 1024 || bytes > 262_144) {
                        issue(NativeRestoreIssue("LOCAL_MESSAGE_SPEECH_UNREADABLE", "message_candidates",
                            buildJsonObject { put("rowid", rowId) }.toString(), column = "extraJson", blocking = false))
                        continue // already unavailable to the speech API; never load an oversized old row to guess its references.
                    }
                    val id = liveCells.read("message_candidates", "id", rowId, 1024).jsonPrimitive.content
                    guard.inspect(id); guard.requireBudget()
                }
                last = page.last().first
            }
            protectedSpeechFiles = guard.protectedFiles
        }
        NativeDatabaseRestoreInspection(plans, interruptedTasks, expiredApprovals, interruptedSummaryJobs, protectedSpeechFiles)
    }

    /** Walk staged rows without loading the full archive or a full-row CursorWindow into RAM. */
    suspend fun forEachRow(table: NativeBackupTable, consume: suspend (Long, JsonObject) -> Unit) {
        var last = Long.MIN_VALUE
        while (true) {
            currentCoroutineContext().ensureActive()
            val ids = stageQuery("SELECT rowid FROM `${table.name}` WHERE rowid>? ORDER BY rowid LIMIT 200", arrayOf(last)).use {
                buildList { while (it.moveToNext()) add(it.getLong(0)) }
            }
            if (ids.isEmpty()) break
            for (id in ids) {
                val cells = linkedMapOf<String, JsonElement>()
                for (column in table.columns) cells[column.name] = stageCells.read(table.name, column.name, id)
                consume(id, JsonObject(cells))
            }
            last = ids.last()
        }
    }

    private fun normalize(table: String, row: JsonObject): JsonObject {
        val cells = row.toMutableMap()
        fun put(key: String, value: String) { cells[key] = JsonPrimitive(value) }
        // Stable for the same archive, even across repeated inspections/restores; never reopen an old execution.
        fun endedAt(): String {
            var at = snapshotAt
            for (column in listOf("createdAt", "updatedAt")) row[column]?.jsonPrimitive?.contentOrNull?.let {
                val prior = try { java.time.Instant.parse(it) }
                    catch (_: Exception) { throw StError.Validation("invalid native lifecycle time") }
                if (prior > at) at = prior
            }
            return at.toString()
        }
        when (table) {
            "tasks", "memory_summary_jobs" -> {
                val status = row["status"]!!.jsonPrimitive.content
                val parsed = TaskStatus.entries.find { it.name == status } ?: throw StError.Validation("unknown native task status")
                if (table == "tasks") {
                    if (TaskKind.entries.none { it.name == row["kind"]!!.jsonPrimitive.content }) throw StError.Validation("unknown native task kind")
                    row["terminalReason"]?.takeIf { it != JsonNull }?.let { reason ->
                        if (TaskTerminalReason.entries.none { it.name == reason.jsonPrimitive.content }) throw StError.Validation("unknown native terminal reason")
                    }
                }
                if (!parsed.isTerminal) {
                    put("status", TaskStatus.INTERRUPTED.name); put("updatedAt", endedAt())
                    if (table == "tasks") { interruptedTasks++; put("terminalReason", TaskTerminalReason.PROCESS_KILLED.name) }
                    else { interruptedSummaryJobs++; put("failureReason", "native restore does not resume old summary work") }
                }
                if (table == "tasks" && row["kind"]!!.jsonPrimitive.content in setOf(TaskKind.SPEECH_VOICE_DESIGN.name, TaskKind.SPEECH_VOICE_CLONE.name)) {
                    val raw = row["payloadJson"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content ?: throw StError.Validation("native voice design payload missing")
                    if (raw.toByteArray(Charsets.UTF_8).size > 32_768) throw StError.Validation("native voice design payload exceeds budget")
                    val payload = NativeJsonInput.parse(raw) as? JsonObject ?: throw StError.Validation("invalid native voice design payload")
                    val ended = if (parsed.isTerminal) parsed else TaskStatus.INTERRUPTED
                    put("payloadJson", JsonObject(payload + mapOf("phase" to JsonPrimitive(if (ended == TaskStatus.SUCCEEDED) "done" else ended.name.lowercase()),
                        "updatedAt" to (if (parsed.isTerminal) payload["updatedAt"]!! else JsonPrimitive(endedAt())))).toString())
                }
                if (table == "tasks" && row["kind"]!!.jsonPrimitive.content == TaskKind.SPEECH_SYNTHESIS.name) {
                    val raw = row["payloadJson"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content ?: throw StError.Validation("native speech payload missing")
                    if (raw.toByteArray(Charsets.UTF_8).size > SpeechTaskMetadata.MAX_PAYLOAD_BYTES) throw StError.Validation("native speech payload exceeds budget")
                    NativeJsonInput.parse(raw)
                    val speech = Jsonx.decodeOrNull<SpeechTaskProgress>(raw) ?: throw StError.Validation("invalid native speech payload")
                    SpeechTaskMetadata.validate(speech)
                    // Archive inode numbers are not authority over live files, even for a terminal historic intent.
                    val ended = if (parsed.isTerminal) parsed else TaskStatus.INTERRUPTED
                    val normalized = speech.copy(device = null, inode = null,
                        asset = if (ended == TaskStatus.SUCCEEDED) speech.asset else null,
                        phase = if (ended == TaskStatus.SUCCEEDED) "done" else ended.name.lowercase(),
                        updatedAt = if (parsed.isTerminal) speech.updatedAt else endedAt())
                    put("payloadJson", Jsonx.encode(normalized))
                    put("resultSummary", if (ended == TaskStatus.SUCCEEDED) "speech audio published" else "speech ${ended.name.lowercase()}")
                }
                if (table == "tasks" && row["kind"]!!.jsonPrimitive.content in setOf(TaskKind.GEMINI_CACHE_OPERATION.name, TaskKind.GEMINI_CACHE_LIST.name)) {
                    val raw = row["payloadJson"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content ?: throw StError.Validation("native cache operation payload missing")
                    if (raw.toByteArray(Charsets.UTF_8).size > GeminiCacheTaskMetadata.MAX_LIST_BYTES) throw StError.Validation("native cache operation exceeds budget")
                    val cache = GeminiCacheAccounting.operation(raw)
                    if (row["kind"]!!.jsonPrimitive.content != GeminiCacheTaskMetadata.kind(cache.action).name) throw StError.Validation("native cache task kind mismatch")
                    GeminiCacheTaskMetadata.validateTask(cache, row["id"]!!.jsonPrimitive.content, row["chatId"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
                        row["createdAt"]!!.jsonPrimitive.content, parsed)
                    put("payloadJson", GeminiCacheAccounting.restoredOperation(raw, snapshotAt))
                }
            }
            "approvals" -> {
                val status = row["status"]!!.jsonPrimitive.content
                if (ApprovalDecision.entries.none { it.name == status } || ApprovalCategory.entries.none { it.name == row["category"]!!.jsonPrimitive.content })
                    throw StError.Validation("unknown native approval value")
                if (status == ApprovalDecision.PENDING.name) { expiredApprovals++; put("status", ApprovalDecision.EXPIRED.name); put("decidedAt", endedAt()) }
            }
            "import_batches" -> if (row["status"]!!.jsonPrimitive.content == "running") { put("status", "interrupted"); put("updatedAt", endedAt()) }
            "usage_events" -> {
                val raw = row["usageJson"]!!.jsonPrimitive.content
                val envelope = NativeJsonInput.parse(raw) as? JsonObject ?: throw StError.Validation("invalid native usage envelope")
                if (envelope["_kind"] == JsonPrimitive(GeminiCacheAccounting.KIND)) {
                    val final = GeminiCacheAccounting.restoredEvent(TokenRequestAccounting.fromColumns(row), snapshotAt)
                    put("usageJson", final.usageJson)
                    final.costJson?.let { put("costJson", it) }
                }
                if (envelope.containsKey("_requestLifecycle")) {
                    val final = TokenRequestAccounting.interrupt(TokenRequestAccounting.fromColumns(row), endedAt(), "native_restore_interrupted")
                    put("usageJson", final.usageJson)
                    final.costJson?.let { put("costJson", it) }
                }
                if ((envelope["_kind"] as? JsonPrimitive)?.contentOrNull == "web_search") {
                    val search = Jsonx.decode<SearchUsageSnapshot>(raw).also(SearchUsageStore::validateSnapshot)
                    if (search.status in setOf("prepared", "running")) {
                        val at = maxOf(java.time.Instant.parse(endedAt()), java.time.Instant.parse(search.startedAt)).toString()
                        val final = search.copy(status = "interrupted", endedAt = at, errorCode = "native_restore_interrupted")
                        put("usageJson", Jsonx.encode(final)); put("costJson", Jsonx.encode(SearchUsageStore.cost(final)))
                    }
                }
                if (envelope["_kind"] in setOf(JsonPrimitive("speech_voice_design"), JsonPrimitive("speech_voice_clone"))) {
                    val design = Jsonx.decode<SpeechVoiceDesignUsage>(raw)
                    if (design.status in setOf("prepared", "running")) {
                        val at = maxOf(java.time.Instant.parse(endedAt()), PricingEngine.instant(design.startedAt)).toString()
                        put("usageJson", JsonObject(envelope + mapOf("status" to JsonPrimitive("interrupted"), "endedAt" to JsonPrimitive(at))).toString())
                        val operation = if (envelope["_kind"] == JsonPrimitive("speech_voice_clone")) "clone" else "design"
                        put("costJson", Jsonx.encode(CostSnapshot(reason = "voice_${operation}_price_not_configured")))
                    }
                }
                if ((envelope["_kind"] as? JsonPrimitive)?.contentOrNull == "tts") {
                    if (raw.toByteArray(Charsets.UTF_8).size > 65_536) throw StError.Validation("native speech usage exceeds budget")
                    val speech = Jsonx.decode<SpeechUsageSnapshot>(raw).also(SpeechUsageStore::validateSnapshot)
                    if (speech.status in setOf("prepared", "running")) {
                        val at = maxOf(java.time.Instant.parse(endedAt()), PricingEngine.instant(speech.startedAt)).toString()
                        val final = speech.copy(status = "interrupted", endedAt = at, errorCode = "native_restore_interrupted")
                        put("usageJson", Jsonx.encode(final)); put("costJson", Jsonx.encode(SpeechUsageStore.cost(final)))
                    }
                }
            }
            "message_candidates" -> {
                if (ChatRole.entries.none { it.name == row["role"]!!.jsonPrimitive.content }) throw StError.Validation("unknown native message role")
                row["generationMetadataJson"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content?.let {
                    put("generationMetadataJson", NativeGenerationUsage.normalize(it))
                }
            }
            "content_regex_records" -> if (row["queueStatus"]!!.jsonPrimitive.content !in setOf("pending", "consumed", "stale", "empty")) throw StError.Validation("unknown native regex queue status")
            "file_changes" -> if (FileChangeType.entries.none { it.name == row["changeType"]!!.jsonPrimitive.content }) throw StError.Validation("unknown native file change type")
        }
        return JsonObject(cells)
    }

    override fun close() = staged.close()

    /** All live rows, durable commit marker and current RESTORE success share ONE Room transaction. */
    suspend fun commit(taskId: String, mode: NativeRestoreMode = NativeRestoreMode.ADD_MISSING,
                       finishTask: suspend (Long) -> Unit): Long = db.withTransaction {
        if (!taskId.matches(Regex("[a-f0-9]{32}"))) throw StError.Validation("invalid restore identity")
        if (stageQuery("SELECT 1 FROM tasks WHERE id=? UNION ALL SELECT 1 FROM import_batches WHERE id=? LIMIT 1", arrayOf(taskId, taskId)).use { it.moveToFirst() })
            throw StError.Conflict("archive collides with the current restore identity")
        var blocking = 0L
        inspect(mode) { if (it.blocking) blocking++ }
        if (blocking != 0L) throw StError.Conflict("restore data changed or contains blocking references")
        val live = db.openHelper.writableDatabase
        val cells = NativeSqlCells { sql, args -> live.query(SimpleSQLiteQuery(sql, args)) }
        fun bind(statement: SupportSQLiteStatement, columns: List<NativeBackupColumn>, row: JsonObject) {
            statement.clearBindings()
            columns.forEachIndexed { index, column ->
                val value = row.getValue(column.name).jsonPrimitive
                when {
                    value == JsonNull -> statement.bindNull(index + 1)
                    column.type == "TEXT" -> statement.bindString(index + 1, value.content)
                    column.type == "INTEGER" -> statement.bindLong(index + 1, value.long)
                    else -> statement.bindDouble(index + 1, value.double)
                }
            }
        }
        var applied = 0L
        for (table in manifest.tables) {
            val names = table.columns.map { it.name }
            live.compileStatement("INSERT INTO `${table.name}` (${names.joinToString { "`$it`" }}) VALUES (${names.joinToString { "?" }})").use { statement ->
                forEachRow(table) { _, row ->
                    currentCoroutineContext().ensureActive()
                    val key = keyOf(table, row)
                    val where = key.keys.joinToString(" AND ") { "`$it`=?" }
                    val oldId = live.query(SimpleSQLiteQuery("SELECT rowid FROM `${table.name}` WHERE $where LIMIT 1",
                        key.values.map { it.jsonPrimitive.content }.toTypedArray<Any>())).use { if (it.moveToFirst()) it.getLong(0) else null }
                    if (oldId == null) {
                        bind(statement, table.columns, row)
                        statement.executeInsert(); applied++
                    } else if (mode == NativeRestoreMode.REPLACE_INCLUDED) {
                        var same = true
                        for (column in table.columns) if (cells.read(table.name, column.name, oldId) != row[column.name]) { same = false; break }
                        if (!same) live.compileStatement("UPDATE `${table.name}` SET ${names.joinToString { "`$it`=?" }} WHERE $where").use { update ->
                            bind(update, table.columns, row)
                            key.values.forEachIndexed { index, value -> update.bindString(names.size + index + 1, value.jsonPrimitive.content) }
                            if (update.executeUpdateDelete() != 1) throw StError.Conflict("restore row changed during commit")
                            applied++
                        }
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val at = java.time.Instant.now().toString()
        val report = ImportReport(taskId, "native_restore", listOf(ImportReportItem("native_rows", null, null, "converted", "$applied inserted/updated rows; mode=${mode.name}")), true)
        if (db.imports().getBatch(taskId) != null) throw StError.Conflict("restore commit marker already exists")
        db.imports().upsertBatch(ImportBatchEntity(taskId, "native_restore", "done", Jsonx.encode(report), at, at))
        finishTask(applied)
        applied
    }

    companion object {
        private val ID_MAP_TABLES = mapOf("chat" to "chats", "node" to "message_nodes", "message" to "message_candidates",
            "message_variant" to "message_candidates", "character" to "characters", "persona" to "personas", "worldbook" to "world_books",
            "preset" to "model_presets", "assistant" to "assistants", "source_settings" to "assistants",
            "source_memory" to "assistants", "attachment" to "attachments")
        private val BOOLEAN_COLUMNS = setOf("isGroup", "groupSystemAlwaysAtBottom", "mvuEnabled", "globalActive", "incompleteFileChanges", "incomplete")
        private val INT_COLUMNS = setOf("groupDelayMs", "groupSystemInjectDepth", "forkedFromMessageIndex", "selectedIndex", "candidateIndex")
        private fun primaryKey(table: String) = when (table) {
            "long_term_memories", "mvu_states", "knowledge_graphs" -> "chatId"
            "content_regex_records" -> "messageId"
            else -> "id"
        }
        private fun keyOf(table: NativeBackupTable, row: JsonObject) = JsonObject(table.columns.filter { it.primaryKey > 0 }
            .sortedBy { it.primaryKey }.associate { it.name to row.getValue(it.name) })
        private fun <T> decode(column: String, text: String, block: (String) -> T): T {
            NativeJsonInput.parse(text)
            return try { block(text) } catch (_: Exception) { throw StError.Validation("invalid native structured column: $column") }
        }

        suspend fun prepare(db: AppDatabase, archive: File, manifest: NativeBackupManifest, staging: File): NativeDatabaseRestorePlan {
            val snapshotAt = try { java.time.Instant.parse(manifest.createdAt) }
                catch (_: Exception) { throw StError.Validation("invalid native snapshot time") }
            // Platform SQLite transactions are thread-bound, unlike Room's coroutine transaction API.
            val writer = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "native-restore-plan") }.asCoroutineDispatcher()
            var prepared: NativeDatabaseRestorePlan? = null
            try { return withContext(writer) {
            if (!staging.createNewFile()) throw StError.Conflict("native restore plan staging already exists")
            val sql = SQLiteDatabase.openDatabase(staging.path, null, SQLiteDatabase.OPEN_READWRITE)
            val plan = NativeDatabaseRestorePlan(db, sql, manifest, snapshotAt)
            try {
                val live = db.openHelper.readableDatabase
                // Only trusted installed schema SQL, never SQL or identifiers supplied by the archive.
                for (table in NativeDatabaseSnapshot.ALL_TABLES) live.query(SimpleSQLiteQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table))).use {
                    if (!it.moveToFirst()) throw StError.Validation("native schema table missing")
                    sql.execSQL(it.getString(0))
                }
                sql.execSQL("CREATE UNIQUE INDEX native_unique_event ON usage_events(eventId)")
                sql.execSQL("CREATE UNIQUE INDEX native_candidate_index ON message_candidates(nodeId,candidateIndex)")
                sql.execSQL("CREATE UNIQUE INDEX native_node_order ON message_nodes(chatId,orderIndex)")
                sql.execSQL("CREATE TABLE native_affected_nodes(id TEXT NOT NULL PRIMARY KEY)")
                sql.beginTransaction()
                try {
                    ZipFile(archive).use { zip -> for (table in manifest.tables) {
                        val trusted = NativeDatabaseSnapshot(db).schemaColumns(table.name)
                        if (trusted != table.columns) throw StError.Validation("native restore schema changed")
                        val entry = zip.getEntry(table.archivePath) ?: throw StError.Validation("native row file missing")
                        val names = trusted.map { it.name }
                        val statement = sql.compileStatement("INSERT INTO `${table.name}` (${names.joinToString { "`$it`" }}) VALUES (${names.joinToString { "?" }})")
                        statement.use {
                            var count = 0L
                            val actual = zip.getInputStream(entry).use { input -> NativeJsonInput.lines(input, entry.size) { line ->
                                currentCoroutineContext().ensureActive()
                                if (++count > table.rows) throw StError.Validation("native row count exceeds manifest")
                                val row = NativeJsonInput.parse(line) as? JsonObject ?: throw StError.Validation("native row is not an object")
                                if (row.keys != names.toSet()) throw StError.Validation("native row columns differ from manifest")
                                for (column in trusted) {
                                    val value = row.getValue(column.name)
                                    val primitive = value as? JsonPrimitive ?: throw StError.Validation("native SQLite cell is not scalar")
                                    if (value == JsonNull) {
                                        if (column.notNull || column.primaryKey > 0) throw StError.Validation("null native required column")
                                    } else when (column.type) {
                                        "TEXT" -> if (!primitive.isString || primitive.content.codePointCount(0, primitive.content.length) > 8_000_000 ||
                                            column.primaryKey > 0 && (primitive.content.isBlank() || primitive.content.length > 4096 || primitive.content.contains('\u0000')))
                                            throw StError.Validation("invalid native TEXT cell")
                                        "INTEGER" -> if (primitive.isString || primitive.longOrNull == null || column.name in BOOLEAN_COLUMNS && primitive.long !in 0..1 ||
                                            (column.name in INT_COLUMNS || column.name == "version" && table.name in setOf("mvu_states", "knowledge_graphs")) &&
                                            primitive.long !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw StError.Validation("invalid native INTEGER cell")
                                        "REAL" -> if (primitive.isString || primitive.doubleOrNull?.isFinite() != true) throw StError.Validation("invalid native REAL cell")
                                        else -> throw StError.Unsupported("unsupported native column type")
                                    }
                                }
                                for (column in listOf("orderIndex", "candidateIndex", "selectedIndex", "groupDelayMs", "groupSystemInjectDepth", "version", "revision", "size"))
                                    row[column]?.takeIf { it != JsonNull }?.let { if (it.jsonPrimitive.long < 0) throw StError.Validation("negative native index/version/size") }
                                val normalized = plan.normalize(table.name, row)
                                statement.clearBindings()
                                trusted.forEachIndexed { index, column ->
                                    val value = normalized.getValue(column.name).jsonPrimitive
                                    when {
                                        value == JsonNull -> statement.bindNull(index + 1)
                                        column.type == "TEXT" -> statement.bindString(index + 1, value.content)
                                        column.type == "INTEGER" -> statement.bindLong(index + 1, value.long)
                                        else -> statement.bindDouble(index + 1, value.double)
                                    }
                                }
                                try { statement.executeInsert() } catch (_: SQLiteConstraintException) { throw StError.Validation("duplicate native key or invalid row constraint") }
                            } }
                            if (actual != table.rows) throw StError.Validation("native row count differs from manifest")
                        }
                    } }
                    sql.execSQL("INSERT OR IGNORE INTO native_affected_nodes(id) SELECT id FROM message_nodes")
                    sql.execSQL("INSERT OR IGNORE INTO native_affected_nodes(id) SELECT nodeId FROM message_candidates")
                    currentCoroutineContext().ensureActive()
                    sql.setTransactionSuccessful()
                } finally { sql.endTransaction() }
                plan.also { prepared = it }
            } catch (error: Throwable) { sql.close(); throw error }
            } } catch (error: Throwable) { prepared?.close(); throw error }
            finally { writer.close() }
        }
    }
}
