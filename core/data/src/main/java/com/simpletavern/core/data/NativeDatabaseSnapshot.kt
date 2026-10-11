package com.simpletavern.core.data

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.model.*
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Versioned table data, not a hot .db/WAL copy. Never fetch large full rows into a CursorWindow. */
class NativeDatabaseSnapshot(private val db: AppDatabase) {
    suspend fun schemaColumns(table: String): List<NativeBackupColumn> = withContext(Dispatchers.IO) {
        if (table !in ALL_TABLES) throw StError.Validation("unknown native schema table")
        db.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(NativeBackupColumn(cursor.getString(1), cursor.getString(2),
                cursor.getInt(3) != 0, cursor.getInt(5))) }
        }
    }
    suspend fun export(root: File, options: BackupOptions, taskId: String,
                       progress: suspend (Long) -> Unit): List<NativeBackupTable> = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        val cells = NativeSqlCells { query, args -> sql.query(SimpleSQLiteQuery(query, args)) }
        val actual = sql.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        } - setOf("room_master_table", "android_metadata", "sqlite_sequence")
        if (actual != ALL_TABLES.toSet()) throw StError.Validation("native snapshot table coverage differs from schema v2")
        val tables = mutableListOf<NativeBackupTable>()
        var totalRows = 0L
        var totalBytes = 0L
        for (table in selectedTables(options)) {
            currentCoroutineContext().ensureActive()
            val columns = sql.query("PRAGMA table_info(`$table`)").use { cursor ->
                buildList { while (cursor.moveToNext()) add(NativeBackupColumn(cursor.getString(1), cursor.getString(2),
                    cursor.getInt(3) != 0, cursor.getInt(5))) }
            }
            if (columns.any { !it.name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }) throw StError.Validation("unsupported schema identifier")
            val relative = "database/$table.jsonl"
            val file = File(root, relative)
            if (!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs()) throw StError.Validation("snapshot staging unavailable")
            var count = 0L
            var last = Long.MIN_VALUE
            file.outputStream().bufferedWriter(Charsets.UTF_8).use { output ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val query = if (table == "tasks") "SELECT rowid FROM `$table` WHERE rowid>? AND id<>? ORDER BY rowid LIMIT 200"
                        else if (table == "source_blobs" && !options.includeDatabase)
                            "SELECT rowid FROM `$table` WHERE rowid>? AND namespace<>'tool-user-answers' ORDER BY rowid LIMIT 200"
                        else "SELECT rowid FROM `$table` WHERE rowid>? ORDER BY rowid LIMIT 200"
                    val args = if (table == "tasks") arrayOf<Any>(last, taskId) else arrayOf<Any>(last)
                    val ids = sql.query(SimpleSQLiteQuery(query, args)).use { cursor ->
                        buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
                    }
                    if (ids.isEmpty()) break
                    for (id in ids) {
                        currentCoroutineContext().ensureActive()
                        val row = linkedMapOf<String, JsonElement>()
                        for (column in columns) {
                            val value = cells.read(table, column.name, id)
                            row[column.name] = if (value is JsonPrimitive && value.isString)
                                JsonPrimitive(redact(table, column.name, value.content)) else value
                        }
                        val line = JsonObject(row).toString()
                        val bytes = line.toByteArray(Charsets.UTF_8).size
                        if (bytes > 32_000_000) throw StError.Validation("snapshot row exceeds 32000000 UTF-8 bytes")
                        totalBytes += bytes + 1L
                        if (totalBytes > 8L * 1024 * 1024 * 1024) throw StError.Validation("database snapshot exceeds 8 GiB")
                        output.write(line); output.newLine()
                        count++; totalRows++
                        if (totalRows > 2_000_000) throw StError.Validation("native snapshot exceeds 2000000 rows")
                    }
                    last = ids.last()
                    // Callback must not re-enter Room; host checkpointing happens after this transaction.
                    progress(totalRows)
                }
            }
            tables += NativeBackupTable(table, columns, count, relative)
        }
        tables
    }

    private fun redact(table: String, column: String, text: String): String {
        val config = table in CONFIG_TABLES || table == "source_blobs" || table == "chats"
        val diagnostic = table in setOf("tasks", "approvals", "mvu_work_logs", "import_batches", "message_candidates")
        if ((!config && !diagnostic) || column !in REDACTABLE_JSON_COLUMNS) return text
        val parsed = runCatching { Jsonx.json.parseToJsonElement(text) }.getOrNull() ?: return text
        fun clean(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.filterKeys { it.lowercase().replace("_", "").replace("-", "") !in SECRET_KEYS }.mapValues { (key, child) ->
                if (key in USER_DATA_KEYS) return@mapValues child
                val nested = if (key.endsWith("Json") && child is JsonPrimitive && child.isString)
                    runCatching { Jsonx.json.parseToJsonElement(child.content) }.getOrNull() else null
                if (nested != null) JsonPrimitive(clean(nested).toString()) else clean(child)
            })
            is JsonArray -> JsonArray(value.map(::clean))
            else -> value
        }
        return clean(parsed).toString()
    }

    companion object {
        val CONFIG_TABLES = setOf("characters", "personas", "world_books", "assistants", "model_presets", "app_settings")
        val MEMORY_TABLES = setOf("long_term_memories", "memory_summary_jobs", "mvu_states", "knowledge_graphs", "mvu_work_logs", "content_regex_records")
        val ALL_TABLES = listOf("chats", "message_nodes", "message_candidates", "characters", "personas", "world_books",
            "long_term_memories", "memory_summary_jobs", "mvu_states", "knowledge_graphs", "mvu_work_logs", "attachments",
            "assistants", "assistant_messages", "model_presets", "app_settings", "import_batches", "id_maps", "tasks",
            "approvals", "file_changes", "sandboxes", "usage_events", "source_blobs", "content_regex_records")
        private val SECRET_KEYS = setOf("apikey", "authorization", "accesstoken", "refreshtoken", "password", "secret", "clientsecret", "bearertoken")
        private val REDACTABLE_JSON_COLUMNS = setOf("settingsJson", "configJson", "extraJson", "contentJson", "payloadJson",
            "argumentsJson", "editPremiseJson", "detailJson", "reportJson", "overridesJson", "memberSettingsJson",
            "generationMetadataJson", "toolRecordJson", "toolCallsJson")
        private val USER_DATA_KEYS = setOf("tables", "tablesJson", "state", "initialStateTables", "initialStateTablesJson",
            "contentRegexRules", "contentRegexRulesJson", "entities", "entitiesJson", "relations", "relationsJson", "userQuestionAnswers")
        fun selectedTables(options: BackupOptions): List<String> = ALL_TABLES.filter { table -> when {
            table in CONFIG_TABLES -> options.includeConfig
            table in MEMORY_TABLES -> options.includeMemory
            table == "attachments" -> options.includeDatabase || options.includeAttachments
            table == "sandboxes" -> options.includeDatabase || options.includeSandboxUserFiles
            table in setOf("id_maps", "source_blobs") -> options.includeDatabase || options.includeConfig
            else -> options.includeDatabase
        } }
    }
}
