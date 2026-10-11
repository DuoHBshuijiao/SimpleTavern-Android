package com.simpletavern.core.api

import android.content.ContentResolver
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.data.StoragePaths
import com.simpletavern.core.data.TaskStore
import com.simpletavern.core.model.*
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class BackupTaskService(private val tasks: TaskStore, private val backup: NativeBackupService,
                        private val scope: CoroutineScope, private val paths: StoragePaths, resolver: ContentResolver) {
    private sealed interface Destination {
        data class Local(val file: File, val overwrite: Boolean) : Destination
        data class Document(val uri: Uri) : Destination
    }
    private val control = Mutex()
    private val workers = ConcurrentHashMap<String, Job>()
    private val stopSignals = ConcurrentHashMap<String, TaskTerminalReason>()
    @Volatile private var registering: String? = null
    private val latest = MutableStateFlow<BackupTaskProgress?>(null)
    private val documents = SafDocumentOutput(resolver)
    private val documentRoot get() = paths.backups.resolve("document_exports")
    fun observe(): StateFlow<BackupTaskProgress?> = latest

    suspend fun start(destination: File, options: BackupOptions, password: CharArray? = null, overwrite: Boolean = false): TaskSnapshot {
        val dest = withContext(Dispatchers.IO) { destination.canonicalFile.also(backup::validateDestination) }
        return register(Destination.Local(dest, overwrite), options, password)
    }
    suspend fun start(destination: Uri, options: BackupOptions, password: CharArray? = null): TaskSnapshot {
        SafDocumentOutput.validate(destination)
        return register(Destination.Document(destination), options, password)
    }
    private suspend fun register(destination: Destination, options: BackupOptions, password: CharArray?): TaskSnapshot {
        if (options.includeCredentials) com.simpletavern.core.data.CredentialArchiveCipher.validatePassword(password)
        if (options.includeRebuildableRuntimes) throw StError.Unsupported("bundled runtime export is not implemented yet")
        return control.withLock {
            currentCoroutineContext().ensureActive()
            if (!scope.isActive) throw StError.Interrupted("backend scope stopped")
            if (workers.values.any { !it.isCompleted }) throw StError.Conflict("a native backup is already running")
            val secret = if (options.includeCredentials) password!!.copyOf() else null
            try {
                withContext(NonCancellable) {
                    val id = UUID.randomUUID().toString().replace("-", "")
                    val at = now()
                    val task = TaskSnapshot(id, TaskKind.BACKUP, TaskStatus.QUEUED, createdAt = at, updatedAt = at)
                    val initial = BackupTaskProgress(id, "queued", at,
                        outputPath = (destination as? Destination.Local)?.file?.path,
                        outputUri = (destination as? Destination.Document)?.uri?.toString())
                    registering = id
                    try {
                        tasks.upsert(task, Jsonx.encode(initial))
                        val ready = CompletableDeferred<Unit>()
                        val worker = scope.launch(start = CoroutineStart.ATOMIC) { run(task, initial, destination, options, secret, ready) }
                        workers[id] = worker
                        worker.invokeOnCompletion { secret?.fill('\u0000'); workers.remove(id, worker); stopSignals.remove(id) }
                        if (stopSignals.containsKey(id)) worker.cancel()
                        ready.complete(Unit)
                        task
                    } catch (error: Throwable) { stopSignals.remove(id); throw error }
                    finally { registering = null }
                }
            } catch (error: Throwable) { secret?.fill('\u0000'); throw error }
        }
    }
    suspend fun progress(id: String): BackupTaskProgress {
        val task = tasks.get(id) ?: throw StError.NotFound(id)
        if (task.kind != TaskKind.BACKUP) throw StError.Validation("not a native backup task")
        val saved = Jsonx.decodeOrNull<BackupTaskProgress>(tasks.payloadJson(id)) ?: throw StError.NotFound("backup progress")
        return if (task.status.isTerminal && task.status != TaskStatus.SUCCEEDED)
            saved.copy(phase = task.status.name.lowercase(), updatedAt = task.updatedAt, error = task.errorMessage ?: saved.error) else saved
    }
    suspend fun await(id: String): File {
        workers[id]?.join()
        val task = tasks.get(id) ?: throw StError.NotFound(id)
        if (!task.status.isTerminal) throw StError.Conflict("backup has not completed")
        if (task.status != TaskStatus.SUCCEEDED) throw StError.Validation(task.errorMessage ?: "backup did not complete")
        return File(progress(id).outputPath ?: throw StError.NotFound("backup output"))
    }
    suspend fun awaitDocument(id: String): Uri {
        workers[id]?.join()
        val task = tasks.get(id) ?: throw StError.NotFound(id)
        if (task.kind != TaskKind.BACKUP || !task.status.isTerminal) throw StError.Conflict("backup has not completed")
        if (task.status != TaskStatus.SUCCEEDED) throw StError.Validation(task.errorMessage ?: "native backup did not complete")
        val saved = progress(id)
        if (!saved.documentWriteCompleted) throw StError.Validation("backup document was not written")
        return Uri.parse(saved.outputUri ?: throw StError.NotFound("backup document output"))
    }
    suspend fun stop(id: String, reason: TaskTerminalReason): TaskSnapshot = withContext(NonCancellable) {
        val worker = control.withLock {
            val task = tasks.get(id) ?: throw StError.NotFound(id)
            if (task.kind != TaskKind.BACKUP) throw StError.Validation("not a backup task")
            if (task.status.isTerminal) return@withLock null
            val effectiveReason = stopSignals.putIfAbsent(id, reason) ?: reason
            // Release a potentially long Room snapshot before waiting for its STOPPING write transaction.
            val worker = workers[id]?.also { it.cancel() }
            if (tasks.requestStop(id, effectiveReason).status.isTerminal) null else worker
        }
        worker?.join()
        tasks.update(id) { it.copy(status = it.stoppingStatus(), terminalReason = it.completionReason(it.stoppingStatus()), updatedAt = now()) }
    }
    fun signalHostInterruption(reason: TaskTerminalReason) {
        require(reason in setOf(TaskTerminalReason.HOST_TIMEOUT, TaskTerminalReason.HOST_UNAVAILABLE))
        registering?.let { stopSignals.putIfAbsent(it, reason) }
        workers.forEach { (id, worker) -> stopSignals.putIfAbsent(id, reason); worker.cancel() }
    }
    private suspend fun run(initial: TaskSnapshot, initialProgress: BackupTaskProgress, destination: Destination,
                            options: BackupOptions, secret: CharArray?, ready: Deferred<Unit>) {
        var state = initialProgress
        var failure: Exception? = null
        var cancelled = false
        var completed = false
        var privateStage: File? = null
        var cleanupIssue: String? = null
        var handoff: AutoCloseable? = null
        try {
            ready.await()
            handoff = tasks.retainHostHandoff()
            val task = tasks.update(initial.id) { if (it.status == TaskStatus.STOPPING) it else it.copy(status = TaskStatus.RUNNING, updatedAt = now()) }
            if (task.status.isTerminal || task.status == TaskStatus.STOPPING) throw CancellationException("backup stopped")
            val archive = when (destination) {
                is Destination.Local -> destination.file
                is Destination.Document -> {
                    val stage = withContext(Dispatchers.IO) {
                        createDocumentStage(initial.id).also {
                            privateStage = it
                            state = state.copy(privateArchiveRetained = true, updatedAt = now())
                        }
                    }
                    checkpoint(state)
                    stage.resolve("archive.zip")
                }
            }
            backup.export(initial.id, archive, options, secret, (destination as? Destination.Local)?.overwrite ?: false) { next, persist ->
                state = if (destination is Destination.Document) next.copy(outputPath = null, outputUri = state.outputUri,
                    phase = if (next.phase == "done") "archive_ready" else next.phase, privateArchiveRetained = true) else next
                latest.value = state
                if (persist) checkpoint(state)
            }
            secret?.fill('\u0000')
            if (destination is Destination.Document) {
                state = state.copy(phase = "opening_document", documentMayBePartial = true, updatedAt = now())
                latest.value = state; checkpoint(state)
                var lastDocumentCheckpoint = 0L
                withContext(Dispatchers.IO) { archive.inputStream().use { input ->
                    documents.write(input, destination.uri, NativeBackupService.MAX_BYTES + 512L * 1024 * 1024, { bytes ->
                        state = state.copy(phase = "writing_document", documentBytes = bytes, updatedAt = now())
                        latest.value = state
                        val at = System.nanoTime()
                        if (lastDocumentCheckpoint == 0L || at - lastDocumentCheckpoint >= 500_000_000L) {
                            checkpoint(state); lastDocumentCheckpoint = at
                        }
                    }) { state = state.copy(documentMayBePartial = false, documentWriteCompleted = true, updatedAt = now()) }
                } }
            }
            completed = true
        } catch (error: CancellationException) { cancelled = true }
        catch (error: Exception) { if (!currentCoroutineContext().isActive) cancelled = true else failure = error }
        finally {
            secret?.fill('\u0000')
            try { withContext(NonCancellable) {
                privateStage?.let { stage ->
                    try {
                        withContext(Dispatchers.IO) { clearDocumentStage(stage) }
                        state = state.copy(privateArchiveRetained = false)
                    } catch (_: Exception) { cleanupIssue = "private backup archive cleanup pending" }
                }
                control.withLock {
                    val task = tasks.get(initial.id) ?: return@withLock
                    if (task.status.isTerminal) return@withLock
                    val status = when {
                        task.status == TaskStatus.STOPPING -> task.stoppingStatus()
                        stopSignals[initial.id] != null -> if (stopSignals[initial.id] == TaskTerminalReason.USER_STOP) TaskStatus.STOPPED else TaskStatus.INTERRUPTED
                        cancelled -> TaskStatus.INTERRUPTED
                        failure != null || !completed -> TaskStatus.FAILED
                        else -> TaskStatus.SUCCEEDED
                    }
                    val error = task.errorMessage ?: failure?.let { (it as? StError)?.message ?: "native backup failed (${it.javaClass.simpleName})" }
                    state = state.copy(phase = if (status == TaskStatus.SUCCEEDED)
                        if (cleanupIssue == null) "done" else "done_cleanup_pending" else status.name.lowercase(),
                        updatedAt = now(), error = error ?: cleanupIssue)
                    val reason = if (task.status == TaskStatus.STOPPING) task.completionReason(status)
                        else stopSignals[initial.id] ?: task.completionReason(status)
                    tasks.upsert(task.copy(status = status, terminalReason = reason, updatedAt = now(),
                        errorMessage = error, resultSummary = if (status == TaskStatus.SUCCEEDED) "native backup published" else "native backup ${state.phase}"), Jsonx.encode(state))
                    latest.value = state
                }
            } } finally { handoff?.close() }
        }
    }
    private suspend fun checkpoint(progress: BackupTaskProgress) {
        // Existing task only: task registrations remain blocked by the exclusive snapshot gate.
        tasks.checkpoint(progress.taskId, Jsonx.encode(progress), progress.updatedAt)
    }

    private fun createDocumentStage(id: String): File {
        if (!documentRoot.exists() && !documentRoot.mkdir()) throw StError.Validation("backup document staging unavailable")
        if (!documentRoot.isDirectory || documentRoot.canonicalFile != documentRoot.absoluteFile)
            throw StError.Validation("backup document staging is redirected")
        val stage = documentRoot.resolve(id)
        if (!stage.mkdir()) throw StError.Conflict("backup document stage already exists or unavailable")
        Os.chmod(stage.path, 448)
        return stage
    }

    private fun clearDocumentStage(stage: File) {
        if (stage.parentFile != documentRoot || !stage.name.matches(Regex("[a-f0-9]{32}")) ||
            stage.canonicalFile != stage.absoluteFile || !stage.isDirectory)
            throw StError.Validation("invalid private backup document stage")
        val files = stage.listFiles() ?: throw StError.Validation("backup document stage unavailable")
        if (files.any { it.name != "archive.zip" || !OsConstants.S_ISREG(Os.lstat(it.path).st_mode) })
            throw StError.Validation("unexpected private backup document files retained")
        files.forEach { if (!it.delete()) throw StError.Validation("private backup archive cleanup failed") }
        if (!stage.delete()) throw StError.Validation("private backup directory cleanup failed")
    }

    /** Only releases known private ZIPs; never opens or deletes a provider document or resumes a backup. */
    suspend fun onProcessStart() = withContext(Dispatchers.IO) {
        if (!documentRoot.exists()) return@withContext
        if (!documentRoot.isDirectory || documentRoot.canonicalFile != documentRoot.absoluteFile)
            throw StError.Validation("backup document staging is redirected")
        val stages = documentRoot.listFiles() ?: throw StError.Validation("backup document staging unavailable")
        for (stage in stages) {
            currentCoroutineContext().ensureActive()
            if (!stage.name.matches(Regex("[a-f0-9]{32}"))) continue
            val task = tasks.get(stage.name)?.takeIf { it.kind == TaskKind.BACKUP } ?: continue
            val raw = tasks.payloadJson(task.id) ?: continue
            val saved = Jsonx.decodeOrNull<BackupTaskProgress>(raw)?.takeIf { it.outputUri != null } ?: continue
            try { clearDocumentStage(stage) } catch (_: Exception) { continue } // Retain unknown/unremovable files; do not block unrelated data.
            val fields = Jsonx.decode<JsonObject>(raw).toMutableMap()
            fields["privateArchiveRetained"] = JsonPrimitive(false)
            if (task.status == TaskStatus.SUCCEEDED && saved.phase == "done_cleanup_pending") {
                fields["phase"] = JsonPrimitive("done"); fields.remove("error")
            }
            tasks.upsert(task, JsonObject(fields).toString()) // Preserve opaque fields and the original lifecycle.
        }
    }
    private fun now() = Instant.now().toString()
}
