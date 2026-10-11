package com.simpletavern.core.api

import android.net.Uri
import com.simpletavern.core.data.Jsonx
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

/** Native data recovery is a NEW task, never an execution-resume API for migrated tasks. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class RestoreTaskService internal constructor(private val tasks: TaskStore, private val restore: NativeRestoreService,
                         private val scope: CoroutineScope, private val inputs: SourceArchiveInputs) {
    private sealed interface Input {
        data class Local(val file: File) : Input
        data class Document(val source: SourceArchiveInput) : Input
    }
    private val control = Mutex()
    private val workers = ConcurrentHashMap<String, Job>()
    private val signals = ConcurrentHashMap<String, TaskTerminalReason>()
    @Volatile private var registering: String? = null
    private val latest = MutableStateFlow<NativeRestoreProgress?>(null)
    fun observe(): StateFlow<NativeRestoreProgress?> = latest

    suspend fun start(archive: File, password: CharArray?, mode: NativeRestoreMode = NativeRestoreMode.ADD_MISSING): TaskSnapshot {
        val input = withContext(Dispatchers.IO) { archive.absoluteFile }
        return register(Input.Local(input), password, mode)
    }
    suspend fun start(archive: Uri, password: CharArray?, mode: NativeRestoreMode = NativeRestoreMode.ADD_MISSING): TaskSnapshot =
        register(Input.Document(inputs.open(archive)), password, mode)

    suspend fun inspect(archive: Uri, password: CharArray?, mode: NativeRestoreMode): NativeRestoreInspection {
        val source = inputs.open(archive)
        try { return restore.inspect(source, inputs, password, mode) } finally { source.close() }
    }

    suspend fun preflight(archive: Uri): NativeBackupPreflight {
        val source = inputs.open(archive)
        try { return restore.preflight(source, inputs) } finally { source.close() }
    }

    private suspend fun register(input: Input, password: CharArray?, mode: NativeRestoreMode): TaskSnapshot {
        var transferred = false
        try { return control.withLock {
            currentCoroutineContext().ensureActive()
            if (!scope.isActive) throw StError.Interrupted("backend scope stopped")
            if (workers.values.any { !it.isCompleted }) throw StError.Conflict("a native restore is already running")
            val secret = password?.copyOf()
            try { withContext(NonCancellable) {
                val id = UUID.randomUUID().toString().replace("-", "")
                val at = now()
                val task = TaskSnapshot(id, TaskKind.RESTORE, TaskStatus.QUEUED, createdAt = at, updatedAt = at)
                val initial = NativeRestoreProgress(id, "queued", at, mode = mode)
                registering = id
                try {
                    tasks.upsert(task, Jsonx.encode(initial))
                    val ready = CompletableDeferred<Unit>()
                    val worker = scope.launch(start = CoroutineStart.ATOMIC) { run(task, initial, input, secret, ready) }
                    transferred = true
                    workers[id] = worker
                    worker.invokeOnCompletion { secret?.fill('\u0000'); workers.remove(id, worker); signals.remove(id) }
                    if (signals.containsKey(id)) worker.cancel()
                    ready.complete(Unit)
                    task
                } catch (error: Throwable) { signals.remove(id); throw error }
                finally { registering = null }
            } } catch (error: Throwable) { secret?.fill('\u0000'); throw error }
        } } finally { if (!transferred && input is Input.Document) input.source.close() }
    }
    suspend fun progress(id: String): NativeRestoreProgress {
        val task = tasks.get(id) ?: throw StError.NotFound(id)
        if (task.kind != TaskKind.RESTORE) throw StError.Validation("not a native restore task")
        val saved = Jsonx.decodeOrNull<NativeRestoreProgress>(tasks.payloadJson(id)) ?: throw StError.NotFound("restore progress")
        return if (task.status.isTerminal && task.status != TaskStatus.SUCCEEDED)
            saved.copy(phase = task.status.name.lowercase(), committed = false, updatedAt = task.updatedAt, error = task.errorMessage ?: saved.error) else saved
    }
    suspend fun await(id: String): NativeRestoreProgress {
        workers[id]?.join()
        val task = tasks.get(id) ?: throw StError.NotFound(id)
        if (task.status != TaskStatus.SUCCEEDED) throw StError.Validation(task.errorMessage ?: "native restore did not commit")
        return progress(id)
    }
    suspend fun stop(id: String, reason: TaskTerminalReason): TaskSnapshot = withContext(NonCancellable) {
        val worker = control.withLock {
            val task = tasks.get(id) ?: throw StError.NotFound(id)
            if (task.kind != TaskKind.RESTORE) throw StError.Validation("not a native restore task")
            if (task.status.isTerminal) return@withLock null
            val first = signals.putIfAbsent(id, reason) ?: reason
            val worker = workers[id]?.also { it.cancel() } // Cancel Room transaction BEFORE waiting for STOPPING write.
            if (tasks.requestStop(id, first).status.isTerminal) null else worker
        }
        worker?.join()
        tasks.update(id) { it.copy(status = it.stoppingStatus(), terminalReason = it.completionReason(it.stoppingStatus()), updatedAt = now()) }
    }
    fun signalHostInterruption(reason: TaskTerminalReason) {
        require(reason in setOf(TaskTerminalReason.HOST_TIMEOUT, TaskTerminalReason.HOST_UNAVAILABLE))
        registering?.let { signals.putIfAbsent(it, reason) }
        workers.forEach { (id, worker) -> signals.putIfAbsent(id, reason); worker.cancel() }
    }
    private suspend fun run(initial: TaskSnapshot, initialProgress: NativeRestoreProgress, input: Input, secret: CharArray?, ready: Deferred<Unit>) {
        var state = initialProgress
        var failure: Exception? = null
        var cancelled = false
        var handoff: AutoCloseable? = null
        try {
            ready.await()
            handoff = tasks.retainHostHandoff()
            val current = tasks.update(initial.id) { if (it.status == TaskStatus.STOPPING) it else it.copy(status = TaskStatus.RUNNING, updatedAt = now()) }
            if (current.status.isTerminal || current.status == TaskStatus.STOPPING) throw CancellationException("restore stopped")
            val update: suspend (NativeRestoreProgress, Boolean) -> Unit = { next, persist ->
                state = next; latest.value = next
                if (persist) tasks.checkpoint(next.taskId, Jsonx.encode(next), next.updatedAt)
            }
            when (input) {
                is Input.Local -> restore.restore(initial.id, input.file, secret, initialProgress.mode, update)
                is Input.Document -> restore.restore(initial.id, input.source, inputs, secret, initialProgress.mode, update)
            }
        } catch (_: CancellationException) { cancelled = true }
        catch (error: Exception) { failure = error; cancelled = !currentCoroutineContext().isActive }
        finally {
            secret?.fill('\u0000')
            try { if (input is Input.Document) input.source.close() }
            catch (_: Exception) { if (failure == null) failure = StError.Validation("native restore source close failed") }
            try { withContext(NonCancellable) { control.withLock {
                val task = tasks.get(initial.id) ?: return@withLock
                if (task.status.isTerminal) {
                    // A committed restore wins a cancellation racing the return from the Room transaction.
                    val persisted = progress(initial.id)
                    if (task.status == TaskStatus.SUCCEEDED && failure != null) {
                        state = persisted.copy(phase = "done_cleanup_pending", error = "restore committed; private staging cleanup pending", updatedAt = now())
                        tasks.upsert(task.copy(resultSummary = "native restore committed; cleanup pending", updatedAt = state.updatedAt), Jsonx.encode(state))
                        latest.value = state
                    } else latest.value = persisted
                    return@withLock
                }
                val status = when {
                    task.status == TaskStatus.STOPPING -> task.stoppingStatus()
                    signals[initial.id] != null -> if (signals[initial.id] == TaskTerminalReason.USER_STOP) TaskStatus.STOPPED else TaskStatus.INTERRUPTED
                    cancelled -> TaskStatus.INTERRUPTED
                    else -> TaskStatus.FAILED // Success MUST have been committed by NativeRestoreService itself.
                }
                val error = task.errorMessage ?: failure?.let { (it as? StError)?.message ?: "native restore failed (${it.javaClass.simpleName})" }
                state = state.copy(phase = status.name.lowercase(), committed = false, error = error, updatedAt = now())
                val reason = if (task.status == TaskStatus.STOPPING) task.completionReason(status) else signals[initial.id] ?: task.completionReason(status)
                tasks.upsert(task.copy(status = status, terminalReason = reason, updatedAt = state.updatedAt,
                    errorMessage = error, resultSummary = "native restore ${state.phase}"), Jsonx.encode(state))
                latest.value = state
            } } } finally { handoff?.close() }
        }
    }
    private fun now() = Instant.now().toString()
}
