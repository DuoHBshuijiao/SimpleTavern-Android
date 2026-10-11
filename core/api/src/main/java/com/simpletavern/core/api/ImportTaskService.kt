package com.simpletavern.core.api

import android.net.Uri
import com.simpletavern.core.data.DataOperationGate
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.data.TaskStore
import com.simpletavern.core.importer.ImportService
import com.simpletavern.core.importer.PreflightResult
import com.simpletavern.core.model.*
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Source imports retain merge/coexist semantics; only their lifecycle belongs to this worker. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class ImportTaskService internal constructor(private val tasks: TaskStore, private val importer: ImportService,
                        private val scope: CoroutineScope, private val inputs: SourceArchiveInputs,
                        private val gate: DataOperationGate, private val cacheDirectory: File) {
    private val control = Mutex()
    private val workers = ConcurrentHashMap<String, Job>()
    private val signals = ConcurrentHashMap<String, TaskTerminalReason>()
    @Volatile private var registering: String? = null
    private val latest = MutableStateFlow<ImportTaskProgress?>(null)
    fun observe(): StateFlow<ImportTaskProgress?> = latest

    suspend fun start(archive: File, sourceHint: String?, sourceLibraryId: String?): TaskSnapshot {
        return register(inputs.open(archive), sourceHint, sourceLibraryId)
    }
    suspend fun start(uri: Uri, sourceHint: String?, sourceLibraryId: String?): TaskSnapshot =
        register(inputs.open(uri), sourceHint, sourceLibraryId)

    /** URI providers can return a pipe or an asset slice; ZipFile needs one private seekable copy. */
    suspend fun preflight(uri: Uri): PreflightResult = withContext(Dispatchers.IO) {
        val lease = gate.sharedLease()
        try {
            val source = inputs.open(uri)
            try {
                val stage = Files.createTempDirectory(cacheDirectory.toPath(), "source-preflight-").toFile()
                val archive = stage.resolve("archive.zip")
                try {
                    inputs.copyTo(source, archive) {}
                    source.close()
                    importer.preflight(archive)
                } finally {
                    withContext(NonCancellable) {
                        if (archive.exists() && !archive.delete()) throw StError.Validation("source preflight cache cleanup failed")
                        if (!stage.delete()) throw StError.Validation("source preflight cache cleanup failed")
                    }
                }
            } finally { source.close() }
        } finally { lease.close() }
    }

    suspend fun onProcessStart() = inputs.onProcessStart()

    private suspend fun register(input: SourceArchiveInput, sourceHint: String?, sourceLibraryId: String?): TaskSnapshot {
        var transferred = false
        try { return control.withLock {
            currentCoroutineContext().ensureActive()
            if (!scope.isActive) throw StError.Interrupted("backend scope stopped")
            if (workers.values.any { !it.isCompleted }) throw StError.Conflict("a source import is already running")
            withContext(NonCancellable) {
                val id = UUID.randomUUID().toString().replace("-", "")
                if (tasks.get(id) != null || importer.hasBatch(id)) throw StError.Conflict("import task/batch identity exists")
                withContext(Dispatchers.IO) { inputs.requireUnused(id) }
                val at = now()
                val task = TaskSnapshot(id, TaskKind.IMPORT, TaskStatus.QUEUED, createdAt = at, updatedAt = at)
                val state = ImportTaskProgress(id, "queued", at)
                registering = id
                try {
                    tasks.upsert(task, Jsonx.encode(state))
                    val ready = CompletableDeferred<Unit>()
                    // ATOMIC guarantees entry to run/finally even if cancelled before the first dispatch.
                    val worker = scope.launch(start = CoroutineStart.ATOMIC) { run(task, state, input, sourceHint, sourceLibraryId, ready) }
                    transferred = true
                    workers[id] = worker
                    worker.invokeOnCompletion { workers.remove(id, worker); signals.remove(id) }
                    if (signals.containsKey(id)) worker.cancel() // A stop can arrive between Room registration and map publication.
                    ready.complete(Unit)
                    task
                } catch (error: Throwable) { signals.remove(id); throw error }
                finally { registering = null }
            }
        } } finally { if (!transferred) input.close() }
    }
    suspend fun progress(id: String): ImportTaskProgress {
        val task = tasks.get(id) ?: throw StError.NotFound(id)
        if (task.kind != TaskKind.IMPORT) throw StError.Validation("not a source import task")
        val saved = Jsonx.decodeOrNull<ImportTaskProgress>(tasks.payloadJson(id)) ?: throw StError.NotFound("import task progress")
        // Startup recovery may interrupt the task after its last payload checkpoint; never report it as still running.
        return if (task.status.isTerminal && task.status != TaskStatus.SUCCEEDED) saved.copy(phase = task.status.name.lowercase(),
            committed = false, updatedAt = task.updatedAt, error = task.errorMessage ?: saved.error) else saved
    }
    suspend fun await(id: String): ImportReport {
        workers[id]?.join()
        val task = tasks.get(id) ?: throw StError.NotFound(id)
        if (task.kind != TaskKind.IMPORT || !task.status.isTerminal) throw StError.Conflict("source import has not ended")
        val report = importer.getReport(id) ?: throw StError.Validation(task.errorMessage ?: "source import ended before a batch was created")
        if (report.success != (task.status == TaskStatus.SUCCEEDED)) throw StError.Conflict("import task and batch completion disagree")
        return report
    }
    fun cancelActive() {
        registering?.let { signals.putIfAbsent(it, TaskTerminalReason.USER_STOP) }
        workers.forEach { (id, worker) -> signals.putIfAbsent(id, TaskTerminalReason.USER_STOP); worker.cancel() }
    }
    suspend fun stop(id: String, reason: TaskTerminalReason): TaskSnapshot = withContext(NonCancellable) {
        val worker = control.withLock {
            val task = tasks.get(id) ?: throw StError.NotFound(id)
            if (task.kind != TaskKind.IMPORT) throw StError.Validation("not a source import task")
            if (task.status.isTerminal) return@withLock null
            val first = signals.putIfAbsent(id, reason) ?: reason
            val running = workers[id]?.also { it.cancel() } // Cancel the merge transaction before STOPPING tries to write.
            if (tasks.requestStop(id, first).status.isTerminal) null else running
        }
        worker?.join()
        tasks.update(id) { it.copy(status = it.stoppingStatus(), terminalReason = it.completionReason(it.stoppingStatus()), updatedAt = now()) }
    }
    fun signalHostInterruption(reason: TaskTerminalReason) {
        require(reason in setOf(TaskTerminalReason.HOST_TIMEOUT, TaskTerminalReason.HOST_UNAVAILABLE))
        registering?.let { signals.putIfAbsent(it, reason) }
        workers.forEach { (id, worker) -> signals.putIfAbsent(id, reason); worker.cancel() }
    }
    private suspend fun run(initial: TaskSnapshot, initialState: ImportTaskProgress, input: SourceArchiveInput,
                            hint: String?, libraryId: String?, ready: Deferred<Unit>) {
        var state = initialState
        var failure: Exception? = null
        var cancelled = false
        var returned: ImportReport? = null
        var handoff: AutoCloseable? = null
        var inputLease: AutoCloseable? = null
        var inputOwned = false
        try {
            ready.await()
            handoff = tasks.retainHostHandoff()
            val task = tasks.update(initial.id) { if (it.status == TaskStatus.STOPPING) it else it.copy(status = TaskStatus.RUNNING, updatedAt = now()) }
            if (task.status.isTerminal || task.status == TaskStatus.STOPPING) throw CancellationException("import stopped")
            inputLease = gate.sharedLease()
            state = state.copy(phase = "input", updatedAt = now())
            latest.value = state; tasks.checkpoint(initial.id, Jsonx.encode(state), state.updatedAt)
            val archive = inputs.snapshot(initial.id, input, { inputOwned = true }) { bytes ->
                state = state.copy(inputBytes = bytes, updatedAt = now())
                latest.value = state; tasks.checkpoint(initial.id, Jsonx.encode(state), state.updatedAt)
            }
            input.close()
            returned = importer.execute(archive, hint, libraryId, batchId = initial.id, onProgress = { next ->
                state = state.copy(phase = next.phase, fraction = next.fraction, files = next.files, bytes = next.bytes, updatedAt = now())
                latest.value = state
                tasks.checkpoint(initial.id, Jsonx.encode(state), state.updatedAt)
            }, onCommit = { report ->
                currentCoroutineContext().ensureActive()
                val current = tasks.get(initial.id) ?: throw StError.NotFound("import task")
                if (current.status == TaskStatus.STOPPING || current.status.isTerminal) throw CancellationException("import stopped before commit")
                state = state.copy(phase = "done", committed = true, fraction = 1f, updatedAt = now())
                // Import data, source mappings, report and task success share the importer's Room transaction.
                tasks.upsert(current.copy(status = TaskStatus.SUCCEEDED, terminalReason = TaskTerminalReason.COMPLETED,
                    updatedAt = state.updatedAt, resultSummary = "source import committed (${report.source})", errorMessage = null), Jsonx.encode(state))
            })
        } catch (_: CancellationException) { cancelled = true }
        catch (error: Exception) { failure = error; cancelled = !currentCoroutineContext().isActive }
        finally {
            try { withContext(NonCancellable) {
                try { input.close() } catch (error: Exception) { if (failure == null) failure = StError.Conflict("source input close failed") }
                if (inputOwned) try { inputs.cleanup(initial.id) } catch (error: Exception) {
                    failure = StError.Conflict("source input cleanup needs attention; private input retained")
                    if (tasks.get(initial.id)?.status != TaskStatus.SUCCEEDED) gate.requireRecovery()
                }
                control.withLock {
                val task = tasks.get(initial.id) ?: return@withLock
                if (task.status.isTerminal) {
                    val saved = progress(initial.id)
                    if (task.status == TaskStatus.SUCCEEDED && failure != null) {
                        state = saved.copy(phase = "done_cleanup_pending", error = "source import committed; private cleanup pending", updatedAt = now())
                        tasks.upsert(task.copy(updatedAt = state.updatedAt, resultSummary = "source import committed; cleanup pending"), Jsonx.encode(state))
                        latest.value = state
                    } else latest.value = saved
                    return@withLock // A durable successful commit wins a cancellation racing transaction return.
                }
                val reason = signals[initial.id]
                val status = when {
                    task.status == TaskStatus.STOPPING -> task.stoppingStatus()
                    reason == TaskTerminalReason.USER_STOP || returned?.cancelled == true && reason == null -> TaskStatus.STOPPED
                    reason != null || cancelled -> TaskStatus.INTERRUPTED
                    else -> TaskStatus.FAILED // Only the import transaction may declare success.
                }
                val error = task.errorMessage ?: failure?.let { (it as? StError)?.message ?: "source import failed (${it.javaClass.simpleName})" }
                    ?: if (status == TaskStatus.FAILED) "source import did not commit; see batch report" else null
                state = state.copy(phase = status.name.lowercase(), committed = false, error = error, updatedAt = now())
                val terminalReason = if (task.status == TaskStatus.STOPPING) task.completionReason(status) else reason ?: task.completionReason(status)
                tasks.upsert(task.copy(status = status, terminalReason = terminalReason, errorMessage = error,
                    updatedAt = state.updatedAt, resultSummary = "source import ${state.phase}"), Jsonx.encode(state))
                latest.value = state
            } } } finally { inputLease?.close(); handoff?.close() }
        }
    }
    private fun now() = Instant.now().toString()
}
