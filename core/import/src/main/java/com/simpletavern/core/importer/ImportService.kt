package com.simpletavern.core.importer

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.simpletavern.core.data.*
import com.simpletavern.core.data.db.ImportBatchEntity
import com.simpletavern.core.model.*
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class ImportProgress(val batchId: String, val phase: String, val detail: String, val fraction: Float,
    val files: Int = 0, val bytes: Long = 0)
data class PreflightResult(val source: String, val entries: List<String>, val notes: List<String> = emptyList())

class ImportService(
    private val paths: StoragePaths,
    private val chats: ChatRepository,
    private val content: ContentRepository,
    private val memory: MemoryStore,
    private val importStore: ImportStore,
    private val attachments: AttachmentStore,
    private val dataGate: DataOperationGate? = null,
) {
    private val progress = MutableStateFlow(ImportProgress("", "idle", "", 0f))
    private val importMutex = Mutex()
    @Volatile private var cancelRequested = false

    fun observeProgress(): Flow<ImportProgress> = progress.asStateFlow()
    fun requestCancel() { cancelRequested = true }

    suspend fun getReport(batchId: String): ImportReport? =
        importStore.getBatch(batchId)?.reportJson?.let { Jsonx.decode<ImportReport>(it) }
    suspend fun hasBatch(batchId: String): Boolean = importStore.batchStatus(batchId) != null

    suspend fun sourceRevisions(namespace: String) = importStore.sourceBlobs(namespace)
    suspend fun sourceNamespaces() = importStore.sourceNamespaces()

    suspend fun onProcessStart() = withContext(Dispatchers.IO) { importMutex.withLock { recoverInterrupted() } }

    private suspend fun recoverInterrupted() {
        importStore.incomplete().forEach { batch ->
            val report = ImportReport(batch.id, batch.source,
                listOf(ImportReportItem("batch", null, null, "interrupted", "process ended before batch commit; not resumed")), false)
            importStore.upsertBatch(batch.copy(status = "interrupted", reportJson = Jsonx.encode(report), updatedAt = nowIso()))
            if (batch.id.matches(Regex("[a-f0-9]{32}"))) {
                cleanupOwned(paths.attachments.resolve("imports/${batch.id}"), paths.attachments.resolve("imports"))
                cleanupOwned(paths.importStaging.resolve(batch.id), paths.importStaging)
            }
        }
        // A done batch can be killed during cleanup; never remove its committed attachment directory.
        val stages = paths.importStaging.listFiles() ?: throw StError.Validation("import staging unavailable")
        stages.forEach { stage ->
            if (!stage.name.matches(Regex("[a-f0-9]{32}"))) throw StError.Validation("unknown import staging identity")
            val status = importStore.batchStatus(stage.name) ?: throw StError.Validation("import staging has no owning batch")
            if (status != "done")
                cleanupOwned(paths.attachments.resolve("imports/${stage.name}"), paths.attachments.resolve("imports"))
            cleanupOwned(stage, paths.importStaging)
        }
    }

    suspend fun preflight(zipFile: File): PreflightResult = withContext(Dispatchers.IO) {
        ZipFile(zipFile).use { zip ->
            val entries = mutableListOf<String>()
            val enumeration = zip.entries()
            var settingsEntry: java.util.zip.ZipEntry? = null
            var hasNativeMarker = false
            while (enumeration.hasMoreElements()) {
                currentCoroutineContext().ensureActive()
                if (entries.size >= 200_000) throw StError.Validation("source archive exceeds 200000 entries")
                val entry = enumeration.nextElement()
                entries += entry.name
                if (settingsEntry == null && (entry.name == "settings.json" || entry.name.endsWith("/settings.json")))
                    settingsEntry = entry
                if (entry.name.substringAfterLast('/') == "BACKUP_META.json") hasNativeMarker = true
            }
            if (hasNativeMarker)
                return@withContext PreflightResult("native", entries, listOf("use the native backup preflight/restore contract, not source import"))
            val settings = settingsEntry?.let { entry ->
                zip.getInputStream(entry).use { input ->
                    val text = NativeJsonInput.read(input, 32_000_000)
                    currentCoroutineContext().ensureActive()
                    val parsed = Jsonx.json.parseToJsonElement(text)
                    parsed as? JsonObject ?: throw StError.Validation("settings.json root is not an object")
                }
            }
            val source = detect(entries, settings)
            PreflightResult(source, entries, listOf("source=$source; existing library will be merged"))
        }
    }

    suspend fun execute(zipFile: File, sourceHint: String? = null, sourceLibraryId: String? = null,
                        batchId: String = newId(), onProgress: suspend (ImportProgress) -> Unit = {},
                        onCommit: suspend (ImportReport) -> Unit = {}): ImportReport =
        withContext(Dispatchers.IO) {
            val gateLease = dataGate?.sharedLease()
            try {
                if (!importMutex.tryLock()) throw StError.Conflict("an import is already running")
                cancelRequested = false
                if (!batchId.matches(Regex("[a-f0-9]{32}"))) { importMutex.unlock(); throw StError.Validation("invalid import batch identity") }
                val startedAt = nowIso()
                val staging = paths.importStaging.resolve(batchId)
                val batchFiles = paths.attachments.resolve("imports/$batchId")
                val items = mutableListOf<ImportReportItem>()
                var source = sourceHint ?: "auto"
                var owned = false
                var stagingCreated = false
                suspend fun publish(next: ImportProgress) { progress.value = next; onProgress(next) }
                try {
                    recoverInterrupted()
                    if (importStore.batchStatus(batchId) != null) throw StError.Conflict("import batch identity exists")
                    if (paths.importStaging.canonicalFile != paths.importStaging.absoluteFile || paths.attachments.canonicalFile != paths.attachments.absoluteFile ||
                        staging.exists() || batchFiles.exists() || staging.canonicalFile != staging.absoluteFile || batchFiles.canonicalFile != batchFiles.absoluteFile)
                        throw StError.Conflict("import directory identity exists or is linked")
                    importStore.upsertBatch(ImportBatchEntity(batchId, source, "running", null, startedAt, startedAt))
                    owned = true
                    publish(ImportProgress(batchId, "unzipping", zipFile.name, 0f))
                if (!staging.mkdir()) throw StError.Validation("cannot create import staging directory")
                stagingCreated = true
                ZipFile(zipFile).use { zip ->
                    if (zip.entries().asSequence().any { it.name.substringAfterLast('/') == "BACKUP_META.json" })
                        throw StError.Validation("native backup restore is not supported by source import")
                }
                unzip(zipFile, staging) { count, bytes -> publish(ImportProgress(batchId, "unzipping", "", 0f, count, bytes)) }
                    checkCancelled()
                    val settingsFile = staging.walkTopDown().firstOrNull { it.isFile && it.name == "settings.json" }
                    val settings = settingsFile?.let(::readObject)
                    val detected = detect(
                        staging.walkTopDown().filter { it.isFile }.map { it.relativeTo(staging).invariantSeparatorsPath }.toList(), settings,
                    )
                    if (detected == "native") throw StError.Validation("native backup restore is not supported by source import")
                    source = normalizeHint(sourceHint) ?: detected
                    if (source !in setOf("desktop", "rikka_hub")) throw StError.Validation("unsupported archive: $source")
                    val root = findRoot(staging, source, settingsFile)
                    // Archive names and batch IDs are not source identity. An explicit library ID is optional.
                    val libraryId = sourceLibraryId ?: settings?.string("libraryId") ?: settings?.string("installationId")
                    val namespace = if (libraryId.isNullOrBlank()) source else "$source:${scoped("", libraryId)}"
                    publish(progress.value.copy(phase = "merging", detail = source, fraction = 0.25f))
                    val report = importStore.transaction {
                        val merge = ImportMerge(namespace, importStore, chats, memory, attachments, paths, batchId, items, ::checkCancelled)
                        when (source) {
                                "desktop" -> DesktopImport(root, content, merge, items, ::checkCancelled, importStore.workspace).run()
                            "rikka_hub" -> RikkaImport(root, staging, content, merge, items, ::checkCancelled).run()
                        }
                        val skillImport = SkillImport(importStore.skills, merge, items, ::checkCancelled)
                        skillImport.run(root.resolve("skills"))
                        if (source == "rikka_hub") skillImport.restoreBuiltin()
                        checkCancelled()
                        val result = ImportReport(batchId, source, items.toList(), success = true)
                        importStore.upsertBatch(ImportBatchEntity(batchId, source, "done", Jsonx.encode(result), startedAt, nowIso()))
                        onCommit(result)
                        result
                    }
                    publish(progress.value.copy(phase = "done", detail = source, fraction = 1f))
                    report
                } catch (cancel: CancellationException) {
                    withContext(NonCancellable) {
                        if (owned && importStore.batchStatus(batchId) != "done") recordFailure(batchId, source, startedAt, items, cancel, true)
                    }
                    throw cancel
                } catch (error: Exception) {
                    val cancelled = error is CancelledImport
                    withContext(NonCancellable) {
                        if (!owned) throw error
                        if (importStore.batchStatus(batchId) == "done") throw error
                        recordFailure(batchId, source, startedAt, items, error, cancelled)
                    }
                } finally {
                    try { withContext(NonCancellable) {
                        if (owned) {
                            // Actual commit wins cancellation at the transaction's return/dispatcher boundary.
                            val committed = try { importStore.batchStatus(batchId) == "done" }
                                catch (error: Exception) { dataGate?.requireRecovery(); throw error }
                            if (stagingCreated) {
                                try {
                                    if (!committed) cleanupOwned(batchFiles, paths.attachments.resolve("imports"))
                                    cleanupOwned(staging, paths.importStaging)
                                } catch (error: Exception) {
                                    if (!committed) dataGate?.requireRecovery()
                                    throw StError.Conflict("source import cleanup needs attention; private files retained")
                                }
                            }
                        }
                    } } finally { importMutex.unlock() }
                }
            } finally { gateLease?.close() }
        }

    private suspend fun recordFailure(
        batchId: String, source: String, startedAt: String, items: List<ImportReportItem>, error: Exception, cancelled: Boolean,
    ): ImportReport {
        val rolledBack = items.map { item ->
            if (item.status == "converted") item.copy(targetId = null, status = "rolled_back", message = "batch not committed") else item
        } + ImportReportItem("batch", null, null, if (cancelled) "cancelled" else "failed", error.message)
        val report = ImportReport(batchId, source, rolledBack, success = false, cancelled = cancelled)
        importStore.upsertBatch(ImportBatchEntity(batchId, source, if (cancelled) "cancelled" else "failed",
            Jsonx.encode(report), startedAt, nowIso()))
        progress.value = ImportProgress(batchId, if (cancelled) "cancelled" else "failed", error.message.orEmpty(), 0f)
        return report
    }

    private suspend fun checkCancelled() {
        currentCoroutineContext().ensureActive()
        if (cancelRequested) throw CancelledImport()
    }

    private suspend fun unzip(archive: File, root: File, update: suspend (Int, Long) -> Unit) {
        val seen = mutableSetOf<String>()
        val objects = mutableSetOf(root.path)
        fun reserve(file: File) {
            if (file.path !in objects && objects.size >= 200_001) throw StError.Validation("source archive exceeds 200000 expanded entries")
            objects += file.path
        }
        fun directory(file: File) {
            val chain = mutableListOf<File>()
            var current: File? = file
            while (current != null && current != root) {
                if (!current.toPath().startsWith(root.toPath())) throw StError.Validation("archive directory escapes staging")
                chain += current; current = current.parentFile
            }
            if (current != root) throw StError.Validation("archive directory escapes staging")
            // Count implicit parent directories too; failure cleanup must be able to walk the whole tree.
            for (parent in chain.asReversed()) {
                reserve(parent)
                if (!parent.isDirectory && !parent.mkdir()) throw StError.Validation("cannot create archive directory")
            }
        }
        var bytes = 0L
        var files = 0
        var lastUpdate = 0L
        ZipFile(archive).use { zip ->
            for (entry in zip.entries().asSequence()) {
                checkCancelled()
                if (seen.size >= 200_000) throw StError.Validation("source archive exceeds 200000 entries")
                val target = inside(root, entry.name) ?: throw StError.Validation("archive path escapes staging: ${entry.name}")
                if (!seen.add(target.path)) throw StError.Validation("duplicate archive path: ${entry.name}")
                if (entry.isDirectory) {
                    directory(target)
                } else {
                    directory(target.parentFile!!)
                    reserve(target)
                    zip.getInputStream(entry).use { input -> target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            if (bytes > 8L * 1024 * 1024 * 1024) throw StError.Validation("source archive exceeds 8 GiB")
                            output.write(buffer, 0, count)
                            val at = System.nanoTime() / 1_000_000
                            if (at - lastUpdate >= 500) { update(files, bytes); lastUpdate = at }
                        }
                    } }
                    files++
                }
            }
        }
        update(files, bytes)
    }

    /** Single-item postorder cleanup. Never follows symlink children or recursively deletes a broad root. */
    private fun cleanupOwned(root: File, parent: File) {
        fun stat(file: File) = try { Os.lstat(file.path) }
            catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) null else throw StError.Validation("import cleanup inspection failed") }
        val initial = stat(root) ?: return
        if (root.parentFile != parent || !root.name.matches(Regex("[a-f0-9]{32}")) || parent.canonicalFile != parent.absoluteFile ||
            root.canonicalFile != root.absoluteFile || !OsConstants.S_ISDIR(initial.st_mode)) throw StError.Validation("invalid import cleanup root")
        val pending = java.util.ArrayDeque<Pair<File, Boolean>>()
        pending.add(root to false)
        var count = 0
        while (pending.isNotEmpty()) {
            val (file, visited) = pending.removeLast()
            val currentRoot = stat(root) ?: throw StError.Validation("import cleanup root disappeared")
            if (currentRoot.st_dev != initial.st_dev || currentRoot.st_ino != initial.st_ino || !OsConstants.S_ISDIR(currentRoot.st_mode))
                throw StError.Validation("import cleanup root changed")
            var ancestor = file.parentFile
            while (ancestor != null && ancestor != parent) {
                if (stat(ancestor)?.let { OsConstants.S_ISDIR(it.st_mode) } != true) throw StError.Validation("import cleanup parent is linked or missing")
                ancestor = ancestor.parentFile
            }
            val info = stat(file) ?: continue
            if (!file.toPath().startsWith(root.toPath())) throw StError.Validation("import cleanup escapes owned root")
            if (OsConstants.S_ISDIR(info.st_mode) && !visited) {
                if (++count > 200_001) throw StError.Validation("import cleanup exceeds entry budget")
                val children = file.listFiles() ?: throw StError.Validation("import cleanup directory unavailable")
                if (children.size + count + pending.size > 400_002) throw StError.Validation("import cleanup exceeds entry budget")
                pending.add(file to true)
                children.forEach { pending.add(it to false) }
            } else {
                if (!visited && ++count > 200_001) throw StError.Validation("import cleanup exceeds entry budget")
                if (!OsConstants.S_ISDIR(info.st_mode) && !OsConstants.S_ISREG(info.st_mode) && !OsConstants.S_ISLNK(info.st_mode))
                    throw StError.Validation("special import cleanup file rejected")
                if (!file.delete()) throw StError.Validation("import cleanup failed")
            }
        }
    }

    companion object {
        fun nowIso(): String = Instant.now().toString()
        fun newId(): String = UUID.randomUUID().toString().replace("-", "")
        fun guessMime(name: String): String = AttachmentMimeTypes.fromFilename(name) ?: when (name.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "txt", "md" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}

class CancelledImport : Exception("import cancelled")

private fun normalizeHint(hint: String?) = when (hint) {
    null, "auto" -> null
    "rikka", "rikkahub", "rikka_hub" -> "rikka_hub"
    else -> hint
}

private fun detect(entries: List<String>, settings: JsonObject?): String = when {
    entries.any { it.substringAfterLast('/') == "BACKUP_META.json" } -> "native"
    entries.any { it.substringAfterLast('/') == "rikka_hub.db" } -> "rikka_hub"
    settings?.let { "assistants" in it || "providers" in it } == true -> "rikka_hub"
    entries.any { "chats/" in it || "characters/" in it || it.endsWith("settings.json") } -> "desktop"
    else -> "unknown"
}

private fun findRoot(staging: File, source: String, settingsFile: File?): File {
    if (settingsFile != null) return settingsFile.parentFile!!
    val roots = staging.walkTopDown().filter { dir -> dir.isDirectory && when (source) {
        "desktop" -> dir.resolve("chats").isDirectory || dir.resolve("characters").isDirectory
        else -> dir.resolve("rikka_hub.db").isFile || dir.resolve("database/rikka_hub.db").isFile
    } }.toList()
    if (roots.size != 1) throw StError.Validation("archive does not contain one identifiable source root")
    return roots.single()
}
