package com.simpletavern.core.api

import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.simpletavern.core.data.*
import com.simpletavern.core.model.*
import com.simpletavern.runtime.sandbox.SandboxManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Explicit user copy only: independent input bytes, no mount/sync and no replay after process death. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class FileCopyTaskService internal constructor(private val paths: StoragePaths, private val tasks: TaskStore,
    private val changes: FileChangeStore, private val sandbox: SandboxManager, private val inputs: SourceArchiveInputs,
    private val gate: DataOperationGate, private val scope: CoroutineScope) {
    @Serializable private data class Publication(val taskId: String, val sandboxId: String, val target: String,
        val device: Long, val inode: Long, val bytes: Long, val at: String, val version: Int = 1)
    private val control = Mutex()
    private val workers = ConcurrentHashMap<String, Job>()
    private val signals = WorkerStopSignals()
    private val latest = MutableStateFlow<FileCopyProgress?>(null)
    fun observe(): StateFlow<FileCopyProgress?> = latest
    fun signalHostInterruption(reason: TaskTerminalReason) = signals.interrupt(reason, workers)

    suspend fun start(sandboxId: String, source: File, target: String, exactTarget: Boolean = false): TaskSnapshot {
        sandbox.validateCopyTarget(sandboxId, target)
        return register(sandboxId, inputs.open(source), target, exactTarget = exactTarget)
    }
    suspend fun start(sandboxId: String, source: Uri, target: String): TaskSnapshot {
        sandbox.validateCopyTarget(sandboxId, target)
        return register(sandboxId, inputs.open(source), target)
    }
    internal suspend fun startWorkspaceText(sandboxId: String, text: String, target: String, exactTarget: Boolean = false): TaskSnapshot {
        sandbox.validateCopyTarget(sandboxId, target)
        return register(sandboxId, null, target, text, exactTarget)
    }
    private suspend fun register(sandboxId: String, input: SourceArchiveInput?, target: String, text: String? = null,
        exactTarget: Boolean = false): TaskSnapshot {
        var transferred = false
        try { return control.withLock {
            currentCoroutineContext().ensureActive()
            if (!scope.isActive) throw StError.Interrupted("copy backend stopped")
            withContext(NonCancellable) {
                val reservation = sandbox.reserveOperation(sandboxId)
                try {
                    val id = UUID.randomUUID().toString().replace("-", "")
                    if (tasks.get(id) != null) throw StError.Conflict("copy task identity exists")
                    withContext(Dispatchers.IO) { inputs.requireUnused(id) }
                    val at = now()
                    val task = TaskSnapshot(id, TaskKind.FILE_COPY, TaskStatus.QUEUED, sandboxId = sandboxId,
                        createdAt = at, updatedAt = at)
                    val progress = FileCopyProgress(id, sandboxId, target, at, exactTarget = exactTarget)
                    signals.register(id)
                    try { tasks.upsert(task, Jsonx.encode(progress)) }
                    catch (error: Throwable) { signals.forget(id); throw error }
                    val ready = CompletableDeferred<Unit>()
                    val worker = scope.launch(start = CoroutineStart.ATOMIC) { run(task, progress, input, ready, text) }
                    transferred = true
                    workers[id] = worker
                    worker.invokeOnCompletion { workers.remove(id, worker); signals.forget(id) }
                    signals.bind(id, worker)
                    ready.complete(Unit)
                    task
                } finally { reservation.close() }
            }
        } } finally { if (!transferred) input?.close() }
    }
    suspend fun progress(id: String): FileCopyProgress {
        val task = tasks.get(id) ?: throw StError.NotFound(id)
        if (task.kind != TaskKind.FILE_COPY) throw StError.Validation("not a file copy task")
        val saved = Jsonx.decodeOrNull<FileCopyProgress>(tasks.payloadJson(id)) ?: throw StError.NotFound("copy progress")
        return if (task.status.isTerminal && task.status != TaskStatus.SUCCEEDED)
            saved.copy(phase = task.status.name.lowercase(), updatedAt = task.updatedAt, error = task.errorMessage ?: saved.error) else saved
    }
    suspend fun await(id: String): File {
        workers[id]?.join()
        val task = tasks.get(id) ?: throw StError.NotFound(id)
        if (task.kind != TaskKind.FILE_COPY || task.status != TaskStatus.SUCCEEDED) throw StError.Validation(task.errorMessage ?: "copy did not complete")
        val saved = progress(id)
        return sandbox.validateCopyTarget(saved.sandboxId, saved.targetPath ?: throw StError.NotFound("copy destination"))
    }
    suspend fun stop(id: String, reason: TaskTerminalReason): TaskSnapshot = withContext(NonCancellable) {
        val worker = control.withLock {
            val task = tasks.get(id) ?: throw StError.NotFound(id)
            if (task.kind != TaskKind.FILE_COPY) throw StError.Validation("not a file copy task")
            if (task.status.isTerminal) return@withLock null
            val first = signals.first(id, reason)
            val running = workers[id]?.also { it.cancel() }
            if (tasks.requestStop(id, first).status.isTerminal) null else running
        }
        worker?.join()
        tasks.update(id) { it.copy(status = it.stoppingStatus(), terminalReason = it.completionReason(it.stoppingStatus()), updatedAt = now()) }
    }

    private suspend fun run(initial: TaskSnapshot, original: FileCopyProgress, input: SourceArchiveInput?, ready: Deferred<Unit>, text: String?) {
        var state = original
        var failure: Exception? = null
        var cancelled = false
        var owned = false
        var linked = false
        var reservation: AutoCloseable? = null
        var handoff: AutoCloseable? = null
        try {
            ready.await()
            handoff = tasks.retainHostHandoff()
            reservation = sandbox.reserveOperation(original.sandboxId)
            val running = tasks.update(initial.id) { if (it.status == TaskStatus.STOPPING) it else it.copy(status = TaskStatus.RUNNING, updatedAt = now()) }
            if (running.status.isTerminal || running.status == TaskStatus.STOPPING) throw CancellationException("copy stopped")
            state = state.copy(phase = "input", updatedAt = now()); checkpoint(state)
            val updateInput: suspend (Long) -> Unit = { bytes ->
                state = state.copy(inputBytes = bytes, updatedAt = now()); checkpoint(state)
            }
            val snapshot = if (input != null) inputs.snapshot(initial.id, input, { owned = true }, updateInput)
                else inputs.snapshotText(initial.id, text!!, { owned = true }, updateInput)
            input?.close()
            currentCoroutineContext().ensureActive()
            // Once this bounded publication decision starts, its successful Room commit wins a racing stop.
            withContext(NonCancellable + Dispatchers.IO) { control.withLock { tasks.transaction {
                val task = tasks.get(initial.id)?.let(signals::effective) ?: throw StError.NotFound(initial.id)
                if (task.status == TaskStatus.STOPPING || task.status.isTerminal) throw CancellationException("copy stopped before publication")
                sandbox.publishCopy(original.sandboxId, snapshot, original.requestedTarget, initial.id,
                    prepare = { relative ->
                        state = state.copy(phase = "publishing", targetPath = relative, updatedAt = now())
                        val stat = Os.lstat(snapshot.path)
                        writePublication(Publication(initial.id, original.sandboxId, relative, stat.st_dev, stat.st_ino, stat.st_size, state.updatedAt))
                    }, linked = { linked = true; state = state.copy(published = true) }, commit = { relative, _ ->
                        val intent = readPublication(initial.id) ?: throw StError.Conflict("copy publication intent missing")
                        recordPublication(intent)
                        val records = changes.taskSummary(initial.id)
                        state = state.copy(phase = "done", targetPath = relative, published = true, publicationReconciled = true, updatedAt = now())
                        tasks.upsert(task.copy(status = TaskStatus.SUCCEEDED, terminalReason = TaskTerminalReason.COMPLETED,
                            updatedAt = state.updatedAt, resultSummary = "file copied into selected sandbox",
                            fileChangeIds = records.ids, incompleteFileChanges = records.incomplete || records.total > records.ids.size), Jsonx.encode(state))
                    }, exactTarget = original.exactTarget)
            } } }
        } catch (_: CancellationException) { cancelled = true }
        catch (error: Exception) { failure = error; cancelled = !currentCoroutineContext().isActive }
        finally {
            try { withContext(NonCancellable) {
                try { input?.close() } catch (_: Exception) { if (failure == null) failure = StError.Conflict("copy source close failed") }
                val committed = tasks.get(initial.id)?.status == TaskStatus.SUCCEEDED
                if (owned) {
                    if (linked && !committed) {
                        // Publication cannot be undone by deleting a possibly edited user's file. Startup only reconciles metadata.
                        gate.requireRecovery()
                        failure = StError.Conflict("file published but metadata commit incomplete; publication record retained")
                    } else try {
                        if (!committed) removeUnpublishedIntent(initial.id)
                        inputs.cleanup(initial.id)
                    } catch (_: Exception) {
                        if (!committed) gate.requireRecovery()
                        failure = StError.Conflict("copy private cleanup needs attention")
                    }
                }
                control.withLock {
                    val task = tasks.get(initial.id)?.let(signals::effective) ?: return@withLock
                    if (task.status.isTerminal) {
                        state = progress(initial.id)
                        if (task.status == TaskStatus.SUCCEEDED && failure != null) {
                            state = state.copy(phase = "done_cleanup_pending", error = "file published; private cleanup pending", updatedAt = now())
                            tasks.upsert(task.copy(updatedAt = state.updatedAt), Jsonx.encode(state))
                        }
                    } else {
                        val status = when { task.status == TaskStatus.STOPPING -> task.stoppingStatus(); cancelled -> TaskStatus.INTERRUPTED; else -> TaskStatus.FAILED }
                        val error = failure?.let { (it as? StError)?.message ?: "file copy failed (${it.javaClass.simpleName})" } ?: task.errorMessage
                        state = state.copy(phase = status.name.lowercase(), error = error, updatedAt = now())
                        val gapId = "${initial.id}:copy-incomplete"
                        if (changes.get(gapId) == null) changes.record(FileChangeRecord(gapId, original.sandboxId, initial.id,
                            ".", FileChangeType.UNKNOWN, state.updatedAt, afterSummary = "copy did not commit; partial parent directories or publication may remain",
                            attribution = "unknown", incomplete = true))
                        val records = changes.taskSummary(initial.id)
                        tasks.upsert(task.copy(status = status, terminalReason = signals.completionReason(task, status), updatedAt = state.updatedAt,
                            errorMessage = error, fileChangeIds = records.ids, incompleteFileChanges = true,
                            resultSummary = "file copy ${state.phase}"), Jsonx.encode(state))
                    }
                    latest.value = state
                }
            } } finally { reservation?.close(); handoff?.close() }
        }
    }
    private suspend fun checkpoint(state: FileCopyProgress) { latest.value = state; tasks.checkpoint(state.taskId, Jsonx.encode(state), state.updatedAt) }

    suspend fun onProcessStart() = withContext(Dispatchers.IO) {
        try {
            checkedRoot()
            val stages = paths.copyInputs.listFiles() ?: throw StError.Conflict("copy inputs unavailable")
            if (stages.size > 10_000) throw StError.Validation("copy recovery budget exceeded")
            for (stage in stages) {
                currentCoroutineContext().ensureActive()
                val task = tasks.get(stage.name) ?: throw StError.Conflict("copy input has no owning task")
                if (task.kind != TaskKind.FILE_COPY) throw StError.Conflict("copy input has another task owner")
                val intent = readPublication(stage.name)
                if (intent != null) {
                    val saved = progress(stage.name)
                    if (intent.sandboxId != task.sandboxId || saved.sandboxId != intent.sandboxId) throw StError.Conflict("copy publication owner changed")
                    if (task.status == TaskStatus.SUCCEEDED || saved.publicationReconciled) {
                        val record = changes.get(eventId(intent.taskId))
                        if ((task.status == TaskStatus.SUCCEEDED && !saved.published) || (saved.published &&
                            (record == null || record.taskId != task.id || record.sandboxId != intent.sandboxId || record.path != intent.target)))
                            throw StError.Conflict("copy completion records disagree")
                    } else {
                        val target = sandbox.validateCopyTarget(intent.sandboxId, intent.target)
                        val current = try { Os.lstat(target.path) }
                            catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) null else throw error }
                        val present = current != null
                        if (current != null && (!OsConstants.S_ISREG(current.st_mode) || current.st_dev != intent.device || current.st_ino != intent.inode))
                            throw StError.Conflict("pending copy destination identity is unknown; files retained")
                        tasks.transaction {
                            if (present) recordPublication(intent)
                            val status = if (task.status.isTerminal) task.status else TaskStatus.INTERRUPTED
                            val at = now()
                            val records = changes.taskSummary(task.id)
                            val restored = saved.copy(phase = status.name.lowercase(), published = present, targetPath = intent.target,
                                publicationReconciled = true, updatedAt = at, error = "copy execution ended; publication metadata reconciled without replay")
                            tasks.upsert(task.copy(status = status, terminalReason = task.terminalReason ?: TaskTerminalReason.PROCESS_KILLED,
                                updatedAt = at, incompleteFileChanges = true, fileChangeIds = records.ids), Jsonx.encode(restored))
                        }
                    }
                }
            }
            inputs.onProcessStart() // Only now may the exact private publication/input artifacts be removed.
        } catch (error: Exception) { gate.requireRecovery(); throw error }
    }

    private suspend fun recordPublication(intent: Publication) {
        val id = eventId(intent.taskId)
        val expected = FileChangeRecord(id, intent.sandboxId, intent.taskId, intent.target, FileChangeType.CREATED, intent.at,
            afterSummary = "copied ${intent.bytes}B", attribution = "confirmed")
        val old = changes.get(id)
        if (old != null && old != expected) throw StError.Conflict("copy event identity exists with another record")
        if (old == null) changes.record(expected)
    }
    private fun eventId(id: String) = "$id:copy-publication"
    private fun stage(id: String): File {
        if (!id.matches(Regex("[a-f0-9]{32}"))) throw StError.Validation("invalid copy publication identity")
        checkedRoot()
        val stage = paths.copyInputs.resolve(id)
        if (!OsConstants.S_ISDIR(Os.lstat(stage.path).st_mode) || stage.canonicalFile != stage.absoluteFile)
            throw StError.Conflict("copy publication directory is redirected")
        return stage
    }
    private fun writePublication(intent: Publication) {
        val root = stage(intent.taskId)
        val bytes = Jsonx.encode(intent).toByteArray(Charsets.UTF_8)
        if (bytes.size > 32768) throw StError.Validation("copy publication metadata exceeds budget")
        val part = root.resolve("publication.part")
        val fd = Os.open(part.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
        FileOutputStream(fd).use { it.write(bytes); it.flush(); it.fd.sync() }
        Os.rename(part.path, root.resolve("publication.json").path); sync(root)
    }
    private suspend fun readPublication(id: String): Publication? {
        val file = stage(id).resolve("publication.json")
        val fd = try { Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0) }
            catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) return null else throw error }
        val value = FileInputStream(fd).use {
            if (!OsConstants.S_ISREG(Os.fstat(fd).st_mode)) throw StError.Validation("copy publication is not a regular file")
            Jsonx.decode<Publication>(NativeJsonInput.read(it, 32768))
        }
        if (value.version != 1 || value.taskId != id || value.bytes !in 0..8L * 1024 * 1024 * 1024)
            throw StError.Validation("invalid copy publication record")
        return value
    }
    private fun removeUnpublishedIntent(id: String) {
        val root = stage(id)
        for (name in listOf("publication.json", "publication.part")) {
            val file = root.resolve(name)
            val stat = try { Os.lstat(file.path) } catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) continue else throw error }
            if (!OsConstants.S_ISREG(stat.st_mode) || !file.delete()) throw StError.Conflict("unpublished copy intent cleanup failed")
        }
        sync(root) // An aborted intent must disappear durably before its input inode is removed.
    }
    private fun checkedRoot() {
        if (!OsConstants.S_ISDIR(Os.lstat(paths.copyInputs.path).st_mode) || paths.copyInputs.canonicalFile != paths.copyInputs.absoluteFile)
            throw StError.Conflict("copy input root is redirected")
    }
    private fun sync(directory: File) {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try { if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw StError.Conflict("copy sync target is not a directory"); Os.fsync(fd) }
        finally { Os.close(fd) }
    }
    private fun now() = Instant.now().toString()
}
