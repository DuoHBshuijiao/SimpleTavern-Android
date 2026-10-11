package com.simpletavern.core.importer

import android.system.Os
import android.system.OsConstants
import com.simpletavern.core.data.*
import com.simpletavern.core.data.db.AttachmentEntity
import com.simpletavern.core.data.db.UsageEventEntity
import com.simpletavern.core.model.*
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import kotlinx.serialization.json.*

/** Stable source identity and three-way merging, using saved JSON values rather than hashes. */
internal class ImportMerge(
    val namespace: String,
    private val store: ImportStore,
    private val chats: ChatRepository,
    private val memory: MemoryStore,
    private val attachments: AttachmentStore,
    private val paths: StoragePaths,
    private val batchId: String,
    private val items: MutableList<ImportReportItem>,
    private val checkCancelled: suspend () -> Unit,
) {
    private companion object {
        private val EXTENSION_REGEX = Regex("[A-Za-z0-9]{1,10}")
        private val OPAQUE_ID_REGEX = Regex("[A-Za-z0-9_-]{1,128}")
    }

    private val seen = mutableSetOf<Pair<String, String>>()
    private var copiedAttachments = 0
    private var copiedBytes = 0L
    // One merger serves one import transaction; its attachment copies run sequentially.
    private var attachmentBuffer: ByteArray? = null

    suspend fun id(type: String, sourceId: String, scope: String = ""): String {
        val key = scoped(scope, sourceId)
        store.resolveId(namespace, key, type)?.let { return it }
        return ImportService.newId().also { store.mapId(namespace, key, it, type) }
    }

    fun claim(type: String, sourceId: String) {
        if (!seen.add(type to sourceId)) throw StError.Validation("duplicate $type source ID: $sourceId")
    }

    fun sourceIdentity(type: String, sourceId: String?): String {
        sourceId?.takeIf { it.isNotBlank() }?.let { return it }
        val independent = "anonymous:${ImportService.newId()}"
        items += ImportReportItem(type, null, null, "retained", "no durable source ID; preserved independently")
        return independent
    }

    suspend inline fun <reified T> merge(type: String, targetId: String, incoming: T, current: T?): T? {
        // These are already typed records; build their merge trees without a text round-trip.
        val next = Jsonx.json.encodeToJsonElement(incoming)
        val local = current?.let { Jsonx.json.encodeToJsonElement(it) }
        val result = mergeJson(type, targetId, next, local) ?: return null
        val packed = if (result is JsonObject) JsonObject(result.mapValues { (key, value) ->
            if (key == "settingsJson" && value is JsonObject || key == "contentRegexRulesJson" && value is JsonArray)
                JsonPrimitive(value.toString()) else value
        }) else result
        return Jsonx.decode(packed.toString())
    }

    suspend fun mergeJson(type: String, targetId: String, incomingRaw: JsonElement, currentRaw: JsonElement?): JsonElement? {
        val incoming = configPayload(type, incomingRaw)!!
        val current = configPayload(type, currentRaw)
        if (type == "worldbook") {
            val entries = incoming.jsonObject.getValue("entries").jsonArray
            if (entries.map { it.jsonObject.getValue("id") }.distinct().size != entries.size)
                throw StError.Validation("duplicate worldbook entry IDs")
        }
        val blobId = "import-baseline:$type:$targetId"
        val baseline = store.getBlob(blobId)?.contentJson?.let { Jsonx.json.parseToJsonElement(it) }?.let { value ->
            // Read the same old typed control as ChatRepository; a newly exposed default is not a local edit.
            if (type == "chat" && value is JsonObject && value["overrides"] is JsonObject)
                JsonObject(value + ("overrides" to Mappers.normalizeOverrides(value.getValue("overrides").jsonObject)))
            else if (type == "worldbook")
                Jsonx.json.encodeToJsonElement(Jsonx.decode<WorldBook>(value.toString()))
            else configPayload(type, value)!!
        }
        val conflicts = mutableListOf<String>()
        val merged = when {
            current == null && baseline != null -> {
                items += ImportReportItem(type, null, targetId, "retained", "locally deleted record not restored")
                null
            }
            current == null -> incoming
            baseline == null && type == "source_memory" -> {
                val local = current.jsonObject
                val empty = JsonObject(local + ("settingsJson" to Jsonx.json.encodeToJsonElement(ImportedMemoryLibrary())))
                mergeSourceMemory(empty, local, incoming.jsonObject, conflicts)
            }
            baseline == null -> {
                conflicts += "record has no import baseline"
                current
            }
            type == "worldbook" -> mergeWorldBook(baseline.jsonObject, current.jsonObject, incoming.jsonObject, conflicts)
            type == "source_memory" -> mergeSourceMemory(baseline.jsonObject, current.jsonObject, incoming.jsonObject, conflicts)
            else -> mergeValue(baseline, current, incoming, "", conflicts)
        }
        if (conflicts.isNotEmpty()) {
            items += ImportReportItem(type, null, targetId, "retained", "local edits kept: ${conflicts.joinToString()}")
        }
        // Keep source revisions even when local edits win. Removed worldbook IDs remain known so a
        // locally deleted entry is not resurrected if the source later re-adds that same identity.
        val nextBaseline = if (type == "worldbook" && baseline != null) {
            val entries = incoming.jsonObject.getValue("entries").jsonArray
            val ids = entries.map { it.jsonObject.getValue("id") }.toSet()
            val removed = baseline.jsonObject.getValue("entries").jsonArray.filter { it.jsonObject.getValue("id") !in ids }
            JsonObject(incoming.jsonObject + ("entries" to JsonArray(entries + removed)))
        } else if (type == "source_memory" && baseline != null) {
            val settings = incoming.jsonObject.getValue("settingsJson").jsonObject
            val old = baseline.jsonObject.getValue("settingsJson").jsonObject.getValue("entries").jsonObject
            val entries = settings.getValue("entries").jsonObject
            JsonObject(incoming.jsonObject + ("settingsJson" to JsonObject(settings +
                ("entries" to JsonObject(old + entries)))))
        } else incoming
        store.retainBlob(blobId, namespace, "baseline/$type/$targetId", rememberRuleRows(baseline, nextBaseline).toString(), ImportService.nowIso())
        return merged
    }

    suspend fun chat(session: ChatSession, incoming: List<MessageNode>): Boolean {
        checkCancelled()
        claim("chat", session.sourceId ?: session.id)
        val existing = chats.getSession(session.id)
        val mergedSession = merge("chat", session.id, session, existing) ?: return false
        val localNodes = if (existing == null) emptyList() else chats.pageMessages(session.id, Int.MAX_VALUE, 0)
        val localById = localNodes.associateBy { it.id }
        val sourceNodeIds = incoming.map { it.id }.toSet()
        require(sourceNodeIds.size == incoming.size) { "duplicate imported node IDs" }
        val result = mutableListOf<MessageNode>()
        for (node in incoming) {
            checkCancelled()
            val local = localById[node.id]
            val meta = mergeJson("node", node.id, nodeMetadata(node), local?.let(::nodeMetadata)) as? JsonObject
                ?: continue
            val localCandidates = local?.candidates.orEmpty().associateBy { it.id }
            val sourceCandidateIds = node.candidates.map { it.id }.toSet()
            require(sourceCandidateIds.size == node.candidates.size) { "duplicate imported candidate IDs" }
            val candidates = mutableListOf<MessageCandidate>()
            for (candidate in node.candidates) {
                merge("message", candidate.id, candidate, localCandidates[candidate.id])?.let(candidates::add)
            }
            candidates += local?.candidates.orEmpty().filter { it.id !in sourceCandidateIds }
            if (candidates.isEmpty()) continue
            val selectedId = meta.string("selectedCandidateId")
            result += node.copy(
                candidates = candidates,
                selectedIndex = candidates.indexOfFirst { it.id == selectedId }.coerceAtLeast(0),
                parentNodeId = meta.string("parentNodeId"),
            )
        }
        // Source deletions never silently delete local history or locally added candidates.
        val resultIds = result.mapTo(mutableSetOf()) { it.id }
        val leading = mutableListOf<MessageNode>()
        val afterAnchor = mutableMapOf<String, MutableList<MessageNode>>()
        var firstSurvivingLocalId: String? = null
        var lastSurvivingLocalId: String? = null
        for (node in localNodes) {
            if (node.id in resultIds) {
                if (firstSurvivingLocalId == null) firstSurvivingLocalId = node.id
                lastSurvivingLocalId = node.id
            } else if (node.id !in sourceNodeIds) {
                if (lastSurvivingLocalId == null) leading += node
                else afterAnchor.getOrPut(lastSurvivingLocalId) { mutableListOf() } += node
            }
        }
        // Keep leading rows before the first local anchor, following groups after their prior anchor, or at the tail when no anchor survives.
        val retained = mutableListOf<MessageNode>()
        for (node in result) {
            if (node.id == firstSurvivingLocalId) retained += leading
            retained += node
            afterAnchor[node.id]?.let(retained::addAll)
        }
        if (firstSurvivingLocalId == null) retained += leading
        chats.saveSession(mergedSession)
        chats.replaceAllMessages(session.id, retained.mapIndexed { index, node -> node.copy(orderIndex = index.toLong()) })
        return true
    }

    suspend fun longTermMemory(incoming: LongTermMemory) {
        val current = memory.getMemory(incoming.chatId)
        fun normalized(value: LongTermMemory) = value.copy(version = 0, updatedAt = IMPORT_EPOCH, source = "import")
        val next = merge("memory", incoming.chatId, normalized(incoming), current?.let(::normalized)) ?: return
        if (current == null || normalized(current) != next) {
            memory.saveMemory(next.copy(updatedAt = incoming.updatedAt), current?.version ?: 0L)
        }
    }

    suspend fun retireImportedAssistantMemory(chatId: String, updatedAt: String) {
        if (store.getBlob("import-baseline:memory:$chatId") != null)
            longTermMemory(LongTermMemory(chatId, "", updatedAt = updatedAt, source = "import"))
    }

    suspend fun mvu(incoming: MvuState) {
        val current = memory.getMvu(incoming.chatId)
        fun normalized(value: MvuState) = value.copy(version = 0, updatedAt = IMPORT_EPOCH, source = "import")
        val next = merge("mvu", incoming.chatId, normalized(incoming), current?.let(::normalized)) ?: return
        if (current == null || normalized(current) != next) {
            memory.saveMvu(next.copy(updatedAt = incoming.updatedAt), current?.version ?: 0)
        }
    }

    suspend fun kg(incoming: KnowledgeGraph) {
        val current = memory.getKg(incoming.chatId)
        fun normalized(value: KnowledgeGraph) = value.copy(version = 0, updatedAt = IMPORT_EPOCH, source = "import")
        val next = merge("kg", incoming.chatId, normalized(incoming), current?.let(::normalized)) ?: return
        if (current == null || normalized(current) != next) {
            memory.saveKg(next.copy(updatedAt = incoming.updatedAt), current?.version ?: 0)
        }
    }

    suspend fun attachment(
        sourceId: String,
        sourceFile: File?,
        chatId: String? = null,
        messageId: String? = null,
        originalName: String? = null,
        mime: String? = null,
    ): AttachmentRef? {
        checkCancelled()
        val targetId = id("attachment", sourceId, chatId.orEmpty())
        val existing = attachments.get(targetId)
        if (existing != null && paths.attachmentFile(existing.relativePath).isFile) {
            return AttachmentRef(existing.id, File(existing.relativePath).name, existing.mimeType,
                existing.relativePath, existing.size, originalName = existing.filename)
        }
        if (sourceFile?.isFile != true) {
            items += ImportReportItem("attachment", sourceId, null, "missing", "file absent from archive")
            return null
        }
        val extension = sourceFile.extension.takeIf { it.matches(EXTENSION_REGEX) }
        // A preserved opaque logical ID is not a filesystem path or a filename authorization.
        val fileId = targetId.takeIf { it.matches(OPAQUE_ID_REGEX) } ?: ImportService.newId()
        val filename = fileId + (extension?.let { ".$it" } ?: "")
        val relativePath = "imports/$batchId/$filename"
        val dest = paths.attachmentFile(relativePath)
        val batchRoot = paths.attachments.resolve("imports/$batchId")
        if (dest.canonicalFile != dest.absoluteFile || dest.parentFile != batchRoot || batchRoot.canonicalFile != batchRoot.absoluteFile)
            throw StError.Validation("linked or invalid attachment destination")
        require(dest.parentFile!!.isDirectory || dest.parentFile!!.mkdirs()) { "cannot create attachment directory" }
        if (++copiedAttachments > 200_000) throw StError.Validation("import attachment copies exceed 200000 files")
        val before = Os.lstat(sourceFile.path)
        if (!OsConstants.S_ISREG(before.st_mode) || before.st_size < 0 || before.st_size > 8L * 1024 * 1024 * 1024 - copiedBytes)
            throw StError.Validation("import attachment copies exceed 8 GiB or source is not regular")
        if (!dest.createNewFile()) throw StError.Conflict("import attachment destination already exists")
        val fd = Os.open(sourceFile.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
        FileInputStream(fd).use { input ->
            fun stable() = Os.fstat(input.fd).let { it.st_dev == before.st_dev && it.st_ino == before.st_ino &&
                it.st_mode == before.st_mode && it.st_size == before.st_size && it.st_mtime == before.st_mtime && it.st_ctime == before.st_ctime }
            if (!stable()) throw StError.Conflict("import attachment changed before reading")
            dest.outputStream().use { output ->
                val buffer = attachmentBuffer ?: ByteArray(65_536).also { attachmentBuffer = it }
                var bytes = 0L
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > before.st_size - bytes) throw StError.Conflict("import attachment grew during copying")
                    bytes += count; copiedBytes += count
                    output.write(buffer, 0, count)
                }
                if (bytes != before.st_size || !stable()) throw StError.Conflict("import attachment changed during copying")
                output.flush(); output.fd.sync()
            }
        }
        val entity = AttachmentEntity(targetId, relativePath, originalName ?: sourceFile.name,
            mime ?: ImportService.guessMime(sourceFile.name), before.st_size, chatId, messageId, null,
            ImportService.nowIso())
        attachments.upsert(entity)
        return AttachmentRef(targetId, filename, entity.mimeType, relativePath, entity.size,
            originalName = entity.filename)
    }

    /** Historical source events enter the existing ledger, never a new vendor request or repricing. */
    suspend fun usage(record: ImportedUsageRecord): String? {
        checkCancelled()
        val targetId = id("usage", record.eventId)
        val chatId = record.chatId?.let { id("chat", it) }
        val context = record.context.copy(sourceNamespace = namespace, purpose = record.context.purpose ?: "desktop_import",
            messageCandidateId = record.messageId?.takeIf { record.chatId != null }?.let { id("message", it, record.chatId!!) })
        val envelope = JsonObject(Jsonx.json.encodeToJsonElement(record.tokens).jsonObject + mapOf(
            "_kind" to JsonPrimitive("llm"),
            "_recordContext" to Jsonx.json.encodeToJsonElement(context)))
        val incoming = UsageEventEntity(targetId, targetId, id("usage_request", record.requestId ?: record.eventId),
            chatId, record.provider, record.model, envelope.toString(), Jsonx.encode(record.cost), record.createdAt)
        fun json(row: UsageEventEntity) = buildJsonObject {
            put("id", row.id); put("eventId", row.eventId); put("requestId", row.requestId)
            put("chatId", row.chatId); put("provider", row.provider); put("model", row.model)
            put("usageJson", row.usageJson); put("costJson", row.costJson); put("createdAt", row.createdAt)
        }
        val current = store.importedUsage(targetId)
        val merged = mergeJson("usage", targetId, json(incoming), current?.let(::json)) as? JsonObject ?: return null
        fun text(key: String) = merged[key]?.jsonPrimitive?.contentOrNull
        val next = UsageEventEntity(text("id")!!, text("eventId")!!, text("requestId")!!, text("chatId"),
            text("provider"), text("model"), text("usageJson")!!, text("costJson"), text("createdAt")!!)
        if (listOfNotNull(next.id, next.eventId, next.requestId, next.chatId, next.provider, next.model, next.createdAt)
                .sumOf { it.toByteArray(Charsets.UTF_8).size } > 4096 ||
            next.usageJson.toByteArray(Charsets.UTF_8).size > 65_536 ||
            (next.costJson?.toByteArray(Charsets.UTF_8)?.size ?: 0) > 32_768)
            throw StError.Validation("imported usage event exceeds ledger storage budget")
        if (next != current) store.saveImportedUsage(next)
        return targetId
    }

    suspend fun retain(path: String, json: JsonElement) {
        val blobId = id("source_blob", path)
        store.retainBlob(blobId, namespace, path, sanitizeSecrets(json).toString(), ImportService.nowIso())
    }

    private fun nodeMetadata(node: MessageNode) = buildJsonObject {
        put("selectedCandidateId", node.selected?.id?.let(::JsonPrimitive) ?: JsonNull)
        put("parentNodeId", node.parentNodeId?.let(::JsonPrimitive) ?: JsonNull)
    }
}

/** Config JSON is a field container, not one atomic string; old saved baselines are read the same way. */
private fun configPayload(type: String, value: JsonElement?): JsonElement? {
    if (value !is JsonObject) return value
    if (type == "character") {
        val raw = value["contentRegexRulesJson"]?.takeIf { it != JsonNull } ?: return value
        val rules = if (raw is JsonPrimitive) Jsonx.json.parseToJsonElement(raw.content) else raw
        return JsonObject(value + ("contentRegexRulesJson" to normalizeRuleRows(rules)))
    }
    if (type !in setOf("assistant", "source_settings", "source_memory")) return value
    val raw = value["settingsJson"] ?: return value
    val settings = (if (raw is JsonPrimitive) Jsonx.json.parseToJsonElement(raw.content) else raw) as? JsonObject
        ?: throw StError.Validation("imported settings must be an object")
    val normalized = JsonObject(settings.mapValues { (key, entry) ->
        if (key in setOf("contentRegexRules", "contentRegexRuleLibrary") && entry != JsonNull) normalizeRuleRows(entry) else entry
    })
    return JsonObject(value + ("settingsJson" to normalized))
}

private fun normalizeRuleRows(value: JsonElement): JsonArray {
    val entries = value as? JsonArray ?: throw StError.Validation("content regex rules must be an array")
    val rules = entries.map { item ->
        val raw = item.jsonObject
        val normalized = Jsonx.json.encodeToJsonElement(Jsonx.decode<ContentRegexRule>(item.toString())).jsonObject
        JsonObject(raw + normalized)
    }
    if (rules.map { it.getValue("id") }.distinct().size != rules.size) throw StError.Validation("duplicate content regex IDs")
    return JsonArray(rules)
}

private val ruleCollectionPaths = setOf("contentRegexRulesJson", "settingsJson.contentRegexRules", "settingsJson.contentRegexRuleLibrary")

private fun rememberRuleRows(previous: JsonElement?, incoming: JsonElement, path: String = ""): JsonElement {
    if (path in ruleCollectionPaths && previous is JsonArray && incoming == JsonNull) return previous
    if (path in ruleCollectionPaths && previous is JsonArray && incoming is JsonArray) {
        val ids = incoming.map { it.jsonObject.getValue("id") }.toSet()
        return JsonArray(incoming + previous.filter { it.jsonObject.getValue("id") !in ids })
    }
    if (previous is JsonObject && incoming is JsonObject) {
        val fields = incoming.mapValues { (key, value) ->
            rememberRuleRows(previous[key], value, if (path.isEmpty()) key else "$path.$key")
        }.toMutableMap()
        for ((key, value) in previous) if (key !in fields && value is JsonArray &&
            (if (path.isEmpty()) key else "$path.$key") in ruleCollectionPaths) fields[key] = value
        return JsonObject(fields)
    }
    return incoming
}

/** Source omissions do not clean the merged library; explicit local row deletions stay deleted. */
private fun mergeSourceMemory(baseline: JsonObject, local: JsonObject, incoming: JsonObject,
    conflicts: MutableList<String>): JsonObject {
    val before = baseline.getValue("settingsJson").jsonObject.getValue("entries").jsonObject
    val current = local.getValue("settingsJson").jsonObject.getValue("entries").jsonObject
    val next = incoming.getValue("settingsJson").jsonObject.getValue("entries").jsonObject
    val entries = JsonObject((next.keys + current.keys).mapNotNull { id ->
        val value = when {
            id in before && id !in current -> null
            id !in next -> current[id]
            else -> mergeValue(before[id], current[id], next[id], "settingsJson.entries[$id]", conflicts)
        }
        value?.let { id to it }
    }.toMap())
    val merged = mergeValue(baseline, local, incoming, "", conflicts)!!.jsonObject
    val settings = merged.getValue("settingsJson").jsonObject
    return JsonObject(merged + ("settingsJson" to JsonObject(settings + ("entries" to entries))))
}

/** Entries have stable identities: editing one must not freeze the entire imported book. */
private fun mergeWorldBook(
    baseline: JsonObject, local: JsonObject, incoming: JsonObject, conflicts: MutableList<String>,
): JsonObject = JsonObject((baseline.keys + local.keys + incoming.keys).mapNotNull { key ->
    val value = if (key == "entries") mergeWorldBookEntries(
        baseline.getValue(key).jsonArray, local.getValue(key).jsonArray, incoming.getValue(key).jsonArray, conflicts,
    ) else mergeValue(baseline[key], local[key], incoming[key], key, conflicts)
    value?.let { key to it }
}.toMap())

private fun mergeWorldBookEntries(
    baseline: JsonArray, local: JsonArray, incoming: JsonArray, conflicts: MutableList<String>, path: String = "entries",
): JsonArray {
    fun keyed(entries: JsonArray) = entries.associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }
    val before = keyed(baseline)
    val current = keyed(local)
    val next = keyed(incoming)
    val localOrder = current.keys.filter { it in before }
    val baselineOrder = before.keys.filter { it in current }
    val order = if (localOrder != baselineOrder) current.keys + next.keys else next.keys + current.keys
    return JsonArray(order.mapNotNull { id ->
        when {
            id in before && id !in current -> null // explicit local deletion stays deleted
            id !in next -> current[id] // source deletion does not clean the merged local library
            else -> mergeValue(before[id], current[id], next[id], "$path[$id]", conflicts)
        }
    })
}

private fun mergeValue(
    baseline: JsonElement?, local: JsonElement?, incoming: JsonElement?, path: String, conflicts: MutableList<String>,
): JsonElement? {
    if (path in ruleCollectionPaths && baseline is JsonArray && local is JsonArray &&
        (incoming is JsonArray || incoming == null || incoming == JsonNull))
        return mergeWorldBookEntries(baseline, local, incoming as? JsonArray ?: JsonArray(emptyList()), conflicts, path)
    if (baseline is JsonObject && local is JsonObject && incoming is JsonObject) {
        return JsonObject((baseline.keys + local.keys + incoming.keys).mapNotNull { key ->
            mergeValue(baseline[key], local[key], incoming[key], if (path.isEmpty()) key else "$path.$key", conflicts)
                ?.let { key to it }
        }.toMap())
    }
    if (local == baseline) return incoming
    if (incoming == baseline || local == incoming) return local
    conflicts += path.ifEmpty { "record" }
    return local
}

internal const val IMPORT_EPOCH = "1970-01-01T00:00:00Z"
internal fun scoped(scope: String, id: String) = "${scope.length}:$scope:$id"
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.integer(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.strings(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
internal fun JsonObject.objects(key: String) = (this[key] as? JsonArray).orEmpty().map { it.jsonObject }
internal fun JsonObject.time(key: String): String = string(key)?.let { value ->
    value.toLongOrNull()?.let { Instant.ofEpochMilli(it).toString() } ?: value
} ?: IMPORT_EPOCH

internal fun readObject(file: File): JsonObject = Jsonx.json.parseToJsonElement(file.readText()).jsonObject

// Secrets must not enter plaintext source blobs or ordinary config rows.
private val secretKeys = setOf("apikey", "apikeys", "accesskey", "accesskeyid", "secretaccesskey", "accesstoken",
    "refreshtoken", "token", "password", "passwd", "secret", "clientsecret", "privatekey", "authorization", "cookie", "credentials",
    "proxyauthorization", "xapikey", "xgoogapikey", "xmsapikey", "xauthtoken", "authenticationtoken")
private fun sensitiveHeaderName(name: String): Boolean {
    val key = name.lowercase(java.util.Locale.ROOT).filter(Char::isLetterOrDigit)
    return key in secretKeys || key == "setcookie" || key.contains("apikey") || key.contains("token") ||
        key.contains("secret") || key.contains("password")
}
private fun sanitizeHeaderContainer(value: JsonElement): JsonElement = when (value) {
    is JsonArray -> JsonArray(value.map { item ->
        when (item) {
            is JsonObject -> if (item.string("first")?.let(::sensitiveHeaderName) == true)
                sanitizeSecrets(JsonObject(item - "second")) else sanitizeSecrets(item)
            is JsonArray -> if ((item.firstOrNull() as? JsonPrimitive)?.contentOrNull?.let(::sensitiveHeaderName) == true)
                JsonArray(item.take(1)) else sanitizeSecrets(item)
            else -> sanitizeSecrets(item)
        }
    })
    is JsonObject -> JsonObject(value.mapValues { (name, item) ->
        if (!sensitiveHeaderName(name)) sanitizeSecrets(item)
        else if (item is JsonObject) sanitizeSecrets(JsonObject(item - "value"))
        else JsonObject(emptyMap()) // preserve the header name as an explicit missing-credential placeholder
    })
    else -> sanitizeSecrets(value)
}
internal fun sanitizeSecrets(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> {
        val headerName = value.string("name") ?: value.string("key")
        if (headerName?.let(::sensitiveHeaderName) == true && "value" in value) {
            sanitizeSecrets(JsonObject(value - "value"))
        } else JsonObject(value.filterKeys { it.lowercase().filter(Char::isLetterOrDigit) !in secretKeys }
            .mapValues { (name, child) -> if (name.lowercase(java.util.Locale.ROOT) in setOf("headers", "customheaders", "env", "environment"))
                sanitizeHeaderContainer(child) else sanitizeSecrets(child) })
    }
    is JsonArray -> JsonArray(value.map(::sanitizeSecrets))
    else -> value
}

internal fun inside(root: File, relativePath: String): File? {
    if (relativePath.isBlank() || File(relativePath).isAbsolute) return null
    val target = root.resolve(relativePath).canonicalFile
    return target.takeIf { it.toPath().startsWith(root.canonicalFile.toPath()) }
}
