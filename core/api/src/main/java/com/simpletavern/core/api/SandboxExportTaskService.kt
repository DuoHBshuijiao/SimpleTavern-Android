package com.simpletavern.core.api

import android.content.ContentResolver
import android.net.Uri
import com.simpletavern.core.data.*
import com.simpletavern.core.model.*
import com.simpletavern.runtime.sandbox.SandboxManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit managed export; shares the original file reader and Room publication, never replays. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
internal class SandboxExportTaskService(
    private val tasks: TaskStore,
    private val sandbox: SandboxManager,
    private val workspace: AssistantWorkspaceService,
    private val attachments: ChatAttachmentService,
    private val content: ContentRepository,
    private val chats: ChatRepository,
    private val scope: CoroutineScope,
    resolver: ContentResolver,
) {
    private data class Request(val progress: SandboxExportProgress, val filename: String? = null,
        val mimeType: String = "application/octet-stream", val expectedContent: String? = null,
        val expectedAttachmentId: String? = null)
    private val control = Mutex()
    private val workers = ConcurrentHashMap<String, Job>()
    private val documentTargets = ConcurrentHashMap.newKeySet<String>()
    private val signals = WorkerStopSignals()
    private val latest = MutableStateFlow<SandboxExportProgress?>(null)
    private val documents = SafDocumentOutput(resolver)
    fun observe(): StateFlow<SandboxExportProgress?> = latest
    fun signalHostInterruption(reason: TaskTerminalReason) = signals.interrupt(reason, workers)

    suspend fun startText(sandboxId: String, sourcePath: String, assistantId: String, workspacePath: String,
                          expectedContent: String?): TaskSnapshot = register(sandboxId, sourcePath,
        SandboxExportDestination.WORKSPACE_TEXT, assistantId = assistantId, workspacePath = workspacePath,
        expectedContent = expectedContent)

    suspend fun startAttachment(sandboxId: String, sourcePath: String, chatId: String, filename: String,
                                mimeType: String): TaskSnapshot = register(sandboxId, sourcePath,
        SandboxExportDestination.CHAT_ATTACHMENT, chatId = chatId, filename = filename, mimeType = mimeType)

    suspend fun startAsset(sandboxId: String, sourcePath: String, assistantId: String, workspacePath: String,
                           mimeType: String, expectedAttachmentId: String?): TaskSnapshot = register(sandboxId, sourcePath,
        SandboxExportDestination.WORKSPACE_ASSET, assistantId = assistantId, workspacePath = workspacePath,
        mimeType = mimeType, expectedAttachmentId = expectedAttachmentId)

    suspend fun startDocument(sandboxId: String, sourcePath: String, destination: Uri): TaskSnapshot {
        SafDocumentOutput.validate(destination)
        return register(sandboxId, sourcePath, SandboxExportDestination.DOCUMENT, documentUri = destination.toString())
    }

    private suspend fun register(sandboxId: String, sourcePath: String, destination: SandboxExportDestination,
        assistantId: String? = null, chatId: String? = null, workspacePath: String? = null, filename: String? = null,
        mimeType: String = "application/octet-stream", expectedContent: String? = null,
        expectedAttachmentId: String? = null, documentUri: String? = null): TaskSnapshot = control.withLock {
        currentCoroutineContext().ensureActive()
        if (!scope.isActive) throw StError.Interrupted("export backend stopped")
        if (assistantId != null && content.getAssistant(assistantId) == null) throw StError.NotFound("assistant configuration")
        if (chatId != null && chats.getSession(chatId) == null) throw StError.NotFound("attachment chat missing")
        val normalized = workspacePath?.let(AssistantWorkspaceStore::normalize)
        if (expectedContent != null) AssistantWorkspaceStore.validateContent(expectedContent)
        sandbox.requireSandbox(sandboxId)
        withContext(Dispatchers.IO) { sandbox.resolveInSandbox(sandboxId, sourcePath) }
        if (documentUri != null && !documentTargets.add(documentUri)) throw StError.Conflict("an export to this document is already running")
        withContext(NonCancellable) {
            var reservation: AutoCloseable? = null
            var transferred = false
            try {
                reservation = sandbox.reserveOperation(sandboxId)
                val id = UUID.randomUUID().toString().replace("-", "")
                val at = now()
                val task = TaskSnapshot(id, TaskKind.FILE_EXPORT, TaskStatus.QUEUED, chatId = chatId,
                    sandboxId = sandboxId, createdAt = at, updatedAt = at)
                val progress = SandboxExportProgress(id, sandboxId, sourcePath, destination, at,
                    assistantId = assistantId, chatId = chatId, workspacePath = normalized, documentUri = documentUri)
                val request = Request(progress, filename, mimeType, expectedContent, expectedAttachmentId)
                signals.register(id)
                try {
                    tasks.upsert(task, Jsonx.encode(progress))
                    val ready = CompletableDeferred<Unit>()
                    val held = reservation!!
                    val worker = scope.launch(start = CoroutineStart.ATOMIC) { run(request, ready, held) }
                    transferred = true
                    workers[id] = worker
                    worker.invokeOnCompletion {
                        workers.remove(id, worker); signals.forget(id)
                        if (documentUri != null) documentTargets.remove(documentUri)
                    }
                    signals.bind(id, worker)
                    ready.complete(Unit)
                    task
                } catch (error: Throwable) { signals.forget(id); throw error }
            } finally { if (!transferred) {
                reservation?.close()
                if (documentUri != null) documentTargets.remove(documentUri)
            } }
        }
    }

    suspend fun progress(id: String): SandboxExportProgress {
        val task = tasks.get(id) ?: throw StError.NotFound(id)
        if (task.kind != TaskKind.FILE_EXPORT) throw StError.Validation("not a sandbox export task")
        val saved = Jsonx.decodeOrNull<SandboxExportProgress>(tasks.payloadJson(id)) ?: throw StError.NotFound("export progress")
        return if (task.status.isTerminal && task.status != TaskStatus.SUCCEEDED)
            saved.copy(phase = task.status.name.lowercase(), updatedAt = task.updatedAt, error = task.errorMessage ?: saved.error)
        else saved
    }

    suspend fun stop(id: String, reason: TaskTerminalReason): TaskSnapshot = withContext(NonCancellable) {
        val worker = control.withLock {
            val task = tasks.get(id) ?: throw StError.NotFound(id)
            if (task.kind != TaskKind.FILE_EXPORT) throw StError.Validation("not a sandbox export task")
            if (task.status.isTerminal) return@withLock null
            val first = signals.first(id, reason)
            val running = workers[id]?.also { it.cancel() }
            if (tasks.requestStop(id, first).status.isTerminal) null else running
        }
        worker?.join()
        tasks.update(id) { it.copy(status = it.stoppingStatus(), terminalReason = it.completionReason(it.stoppingStatus()), updatedAt = now()) }
    }

    private suspend fun run(request: Request, ready: Deferred<Unit>, reservation: AutoCloseable) {
        var state = request.progress
        var failure: Exception? = null
        var cancelled = false
        var handoff: AutoCloseable? = null
        var lastCheckpoint = 0L
        suspend fun copied(bytes: Long) {
            state = state.copy(phase = "copying", bytesCopied = bytes, updatedAt = now())
            latest.value = state
            val clock = System.nanoTime()
            if (lastCheckpoint == 0L || clock - lastCheckpoint >= 500_000_000L) {
                tasks.checkpoint(state.taskId, Jsonx.encode(state), state.updatedAt)
                lastCheckpoint = clock
            }
        }
        suspend fun complete(attachment: AttachmentRef? = null, resultUpdatedAt: String? = null) {
            // Called inside the destination's Room transaction: successful publication wins a racing stop.
            val task = tasks.get(state.taskId)?.let(signals::effective) ?: throw StError.NotFound(state.taskId)
            if (task.status == TaskStatus.STOPPING || task.status.isTerminal) throw CancellationException("export stopped before publication")
            state = state.copy(phase = "done", attachment = attachment, resultUpdatedAt = resultUpdatedAt, updatedAt = now())
            tasks.upsert(task.copy(status = TaskStatus.SUCCEEDED, terminalReason = TaskTerminalReason.COMPLETED,
                updatedAt = state.updatedAt, resultSummary = "sandbox file exported to ${state.destination.name.lowercase()}"), Jsonx.encode(state))
        }
        try {
            ready.await()
            handoff = tasks.retainHostHandoff()
            val running = tasks.update(state.taskId) { signals.effective(it).let { current ->
                if (current.status == TaskStatus.STOPPING) current else current.copy(status = TaskStatus.RUNNING, updatedAt = now())
            } }
            if (running.status.isTerminal || running.status == TaskStatus.STOPPING) throw CancellationException("export stopped")
            state = state.copy(phase = "reading", updatedAt = now())
            latest.value = state
            tasks.checkpoint(state.taskId, Jsonx.encode(state), state.updatedAt)
            when (state.destination) {
                SandboxExportDestination.WORKSPACE_TEXT -> {
                    val text = sandbox.readWorkspaceText(state.sandboxId, state.sourcePath, ::copied)
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) { tasks.transaction {
                        val saved = workspace.write(state.assistantId!!, state.workspacePath!!, text, request.expectedContent)
                        complete(resultUpdatedAt = saved.updatedAt)
                    } }
                }
                SandboxExportDestination.WORKSPACE_ASSET, SandboxExportDestination.CHAT_ATTACHMENT -> {
                    sandbox.withExportStream(state.sandboxId, state.sourcePath) { input ->
                        attachments.importForExport(state.chatId,
                            request.filename ?: state.workspacePath!!.substringAfterLast('/'), request.mimeType, { input }, ::copied) { ref ->
                            if (state.destination == SandboxExportDestination.WORKSPACE_ASSET) {
                                val saved = workspace.linkAsset(state.assistantId!!, state.workspacePath!!, ref.id, request.expectedAttachmentId)
                                complete(ref, saved.updatedAt)
                            } else complete(ref)
                        }
                    }
                }
                SandboxExportDestination.DOCUMENT -> {
                    sandbox.withExportStream(state.sandboxId, state.sourcePath) { input ->
                        // Opening a provider's output can truncate it even if the open subsequently fails.
                        state = state.copy(phase = "opening_document", documentMayBePartial = true, updatedAt = now())
                        latest.value = state
                        tasks.checkpoint(state.taskId, Jsonx.encode(state), state.updatedAt)
                        documents.write(input, Uri.parse(state.documentUri!!), 8L * 1024 * 1024 * 1024, ::copied) {
                            state = state.copy(documentMayBePartial = false, documentWriteCompleted = true, updatedAt = now())
                        }
                    }
                    withContext(NonCancellable) { tasks.transaction { complete() } }
                }
            }
        } catch (_: CancellationException) { cancelled = true }
        catch (error: Exception) { failure = error; cancelled = !currentCoroutineContext().isActive }
        finally {
            try { withContext(NonCancellable) { control.withLock {
                val task = tasks.get(state.taskId)?.let(signals::effective)
                if (task != null) {
                    if (task.status.isTerminal) state = progress(task.id)
                    else {
                        val status = when {
                            task.status == TaskStatus.STOPPING -> task.stoppingStatus()
                            cancelled -> TaskStatus.INTERRUPTED
                            else -> TaskStatus.FAILED
                        }
                        val error = task.errorMessage ?: failure?.let { (it as? StError)?.message ?: "sandbox export failed (${it.javaClass.simpleName})" }
                        state = state.copy(phase = status.name.lowercase(), updatedAt = now(), error = error,
                            attachment = null, resultUpdatedAt = null)
                        tasks.upsert(task.copy(status = status, terminalReason = signals.completionReason(task, status),
                            updatedAt = state.updatedAt, errorMessage = error, resultSummary = "sandbox export ${state.phase}"), Jsonx.encode(state))
                    }
                    latest.value = state
                }
            } } } finally { try { handoff?.close() } finally { reservation.close() } }
        }
    }

    private fun now() = Instant.now().toString()
}
