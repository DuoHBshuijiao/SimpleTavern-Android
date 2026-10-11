package com.simpletavern.core.api

import androidx.room.withTransaction
import com.simpletavern.core.data.*
import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.data.db.AttachmentEntity
import com.simpletavern.core.data.db.UsageEventEntity
import com.simpletavern.core.model.*
import com.simpletavern.core.tools.SpeechVoiceCloneEvent
import com.simpletavern.core.tools.SpeechVoiceCloneInput
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private typealias VoiceRequestRun = suspend (suspend (SpeechVoiceCloneEvent) -> Unit) -> Pair<SpeechVoiceCreationResult, ByteArray>

/** Explicit new remote voice creation; it never changes a preset, plays audio or repeats an old POST. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
internal class SpeechVoiceDesignTaskService(private val resolver: SpeechResolver, private val db: AppDatabase,
    private val paths: StoragePaths, private val tasks: TaskStore, private val gate: DataOperationGate,
    private val scope: CoroutineScope, private val attachments: ChatAttachmentService) {
    private val control = Mutex()
    private val workers = ConcurrentHashMap<String, Job>()
    private val signals = WorkerStopSignals()
    private val latest = MutableStateFlow<SpeechVoiceDesignProgress?>(null)
    private val latestClone = MutableStateFlow<SpeechVoiceCloneProgress?>(null)
    fun observe(): StateFlow<SpeechVoiceDesignProgress?> = latest
    fun observeClone(): StateFlow<SpeechVoiceCloneProgress?> = latestClone
    private fun publish(state: SpeechVoiceCreationProgress) {
        if (state.operation == "clone") latestClone.value = state else latest.value = state
    }
    fun signalHostInterruption(reason: TaskTerminalReason) = signals.interrupt(reason, workers)

    suspend fun start(request: SpeechVoiceDesignRequest): TaskSnapshot = start(TaskKind.SPEECH_VOICE_DESIGN, request.chatId,
        request.previewText.codePointCount(0, request.previewText.length).toLong()) {
        val prepared = resolver.prepareVoiceDesign(request)
        val execute: VoiceRequestRun = { onEvent ->
            val audio = prepared.design { onEvent(SpeechVoiceCloneEvent("designing", requestStarted = true)) }
            SpeechVoiceDesignResult(audio.voiceId) to audio.previewBytes
        }
        prepared.selection to execute
    }
    suspend fun startClone(request: SpeechVoiceCloneRequest): TaskSnapshot = start(TaskKind.SPEECH_VOICE_CLONE, request.chatId,
        listOfNotNull(request.previewText, request.referenceText, request.promptText, request.textValidation)
            .sumOf { it.codePointCount(0, it.length).toLong() }) {
        suspend fun input(id: String): SpeechVoiceCloneInput {
            val row = db.attachments().get(id) ?: throw StError.NotFound("managed cloning audio missing")
            return SpeechVoiceCloneInput(attachments.copySource(id), row.filename, AttachmentMimeTypes.normalize(row.mimeType))
        }
        val source = input(request.sourceAttachmentId)
        val prompt = request.promptAttachmentId?.let { input(it) }
        val prepared = resolver.prepareVoiceClone(request, source, prompt)
        val execute: VoiceRequestRun = { onEvent ->
            val audio = prepared.clone(onEvent)
            SpeechVoiceCloneResult(audio.voiceId, voiceType = audio.voiceType, remotePreviewUrl = audio.remotePreviewUrl,
                warnings = audio.warnings + listOf("voice_clone_cost_unknown", "voice_clone_not_synthesis_availability_proof")) to ByteArray(0)
        }
        prepared.selection to execute
    }
    private suspend fun start(kind: TaskKind, chatId: String?, characters: Long,
        prepare: suspend () -> Pair<SpeechDirectorySelection, VoiceRequestRun>): TaskSnapshot = withContext(Dispatchers.IO) {
        val lease = gate.sharedLease()
        var transferred = false
        try {
            val (selection, execute) = prepare()
            if (characters !in 0..10_000) throw StError.Validation("voice creation text exceeds accounting budget")
            currentCoroutineContext().ensureActive()
            control.withLock {
                if (!scope.isActive) throw StError.Interrupted("voice creation backend stopped")
                if (workers.size >= 16) throw StError.Conflict("voice creation task queue is full")
                withContext(NonCancellable) {
                    val id = UUID.randomUUID().toString().replace("-", "")
                    val at = now()
                    val task = TaskSnapshot(id, kind, TaskStatus.QUEUED, chatId = chatId, createdAt = at, updatedAt = at)
                    val operation = if (kind == TaskKind.SPEECH_VOICE_CLONE) "clone" else "design"
                    val state = SpeechVoiceCreationProgress(id, selection, at, operation = operation)
                    val usage = SpeechVoiceCreationUsage(id, selection.presetId, selection.provider, characters, at,
                        sourceNamespace = selection.sourceNamespace, _kind = "speech_voice_$operation")
                    signals.register(id)
                    try { db.withTransaction {
                        tasks.upsert(task, Jsonx.encode(state))
                        val eventId = eventId(task)
                        if (db.usage().insertIgnore(UsageEventEntity(eventId, eventId, id, task.chatId,
                                "tts-voice-$operation:${usage.provider}", null, Jsonx.encode(usage), null, at)) == -1L)
                            throw StError.Conflict("voice design usage identity exists")
                    } } catch (error: Throwable) { signals.forget(id); throw error }
                    val ready = CompletableDeferred<Unit>()
                    val worker = scope.launch(start = CoroutineStart.ATOMIC) { run(task, state, usage, execute, lease, ready) }
                    workers[id] = worker
                    worker.invokeOnCompletion { workers.remove(id, worker); signals.forget(id) }
                    signals.bind(id, worker)
                    transferred = true
                    ready.complete(Unit)
                    task
                }
            }
        } finally { if (!transferred) lease.close() }
    }

    suspend fun progress(id: String, expectedKind: TaskKind? = TaskKind.SPEECH_VOICE_DESIGN): SpeechVoiceCreationProgress = withContext(Dispatchers.IO) {
        val task = requireTask(id)
        if (expectedKind != null && task.kind != expectedKind) throw StError.Validation("voice creation operation differs")
        val raw = tasks.payloadJson(id) ?: throw StError.Validation("voice design progress missing")
        if (raw.toByteArray(Charsets.UTF_8).size > 32_768) throw StError.Validation("voice design progress exceeds budget")
        val saved = Jsonx.decode<SpeechVoiceDesignProgress>(raw)
        if (saved.taskId != id || saved.version != 1) throw StError.Validation("invalid voice design progress")
        if (task.status.isTerminal && task.status != TaskStatus.SUCCEEDED)
            saved.copy(phase = task.status.name.lowercase(), updatedAt = task.updatedAt) else saved
    }
    suspend fun await(id: String, expectedKind: TaskKind = TaskKind.SPEECH_VOICE_DESIGN): SpeechVoiceCreationResult {
        workers[id]?.join()
        val task = requireTask(id)
        if (task.kind != expectedKind || task.status != TaskStatus.SUCCEEDED) throw StError.Speech("tts_voice_creation_incomplete", "voice creation task did not complete")
        return progress(id, expectedKind).result ?: throw StError.Validation("voice creation result missing")
    }
    suspend fun stop(id: String, reason: TaskTerminalReason): TaskSnapshot = withContext(NonCancellable + Dispatchers.IO) {
        val worker = control.withLock {
            val task = requireTask(id)
            if (task.status.isTerminal) return@withLock null
            val first = signals.first(id, reason)
            val running = workers[id]?.also { it.cancel() }
            if (tasks.requestStop(id, first).status.isTerminal) null else running
        }
        worker?.join()
        tasks.update(id) { it.copy(status = it.stoppingStatus(), terminalReason = it.completionReason(it.stoppingStatus()), updatedAt = now()) }
    }

    private suspend fun run(initial: TaskSnapshot, original: SpeechVoiceDesignProgress, originalUsage: SpeechVoiceDesignUsage,
        execute: VoiceRequestRun, lease: AutoCloseable, ready: Deferred<Unit>) {
        var state = original
        var usage = originalUsage
        var owned: File? = null
        var published = false
        var failure: Exception? = null
        var cancelled = false
        var handoff: AutoCloseable? = null
        suspend fun save(next: SpeechVoiceDesignProgress, nextUsage: SpeechVoiceDesignUsage, retainUploadedId: Boolean = false) {
            withContext(NonCancellable + Dispatchers.IO) { control.withLock {
                db.withTransaction {
                    val task = requireTask(initial.id).let(signals::effective)
                    if (task.status.isTerminal || task.status == TaskStatus.STOPPING && !retainUploadedId) throw CancellationException("voice design stopped")
                    updateUsage(initial, usage, nextUsage)
                    tasks.upsert(task.copy(status = if (task.status == TaskStatus.STOPPING) task.status else TaskStatus.RUNNING,
                        updatedAt = next.updatedAt), Jsonx.encode(next))
                }
                state = next; usage = nextUsage; publish(state)
            } }
        }
        try {
            ready.await()
            handoff = tasks.retainHostHandoff()
            currentCoroutineContext().ensureActive()
            save(state.copy(phase = if (state.operation == "clone") "preparing" else "designing", updatedAt = now()), usage.copy(status = "running"))
            val (result, previewBytes) = execute { event ->
                save(state.copy(phase = event.phase, requestSent = state.requestSent || event.requestStarted,
                    uploadedFiles = state.uploadedFiles + listOfNotNull(event.uploadedFile),
                    requestCount = state.requestCount + if (event.requestStarted) 1 else 0, updatedAt = now()),
                    usage.copy(requestSent = usage.requestSent || event.requestStarted), retainUploadedId = event.uploadedFile != null)
            }
            // Preserve an acknowledged remote voice even if local preview publication later fails or is stopped.
            state = state.copy(phase = "staging", result = result, updatedAt = now())
            withContext(NonCancellable + Dispatchers.IO) { tasks.checkpoint(initial.id, Jsonx.encode(state), state.updatedAt) }
            currentCoroutineContext().ensureActive()
            val ref = if (previewBytes.isEmpty()) null else {
                val relative = previewPath(initial)
                val file = paths.attachmentFile(relative)
                if (!file.createNewFile()) throw StError.Conflict("voice design preview already exists")
                owned = file
                withContext(Dispatchers.IO) { FileOutputStream(file).use { output ->
                    var offset = 0
                    while (offset < previewBytes.size) {
                        currentCoroutineContext().ensureActive()
                        val count = minOf(65_536, previewBytes.size - offset)
                        output.write(previewBytes, offset, count); offset += count
                    }
                    output.fd.sync()
                } }
                AttachmentRef(eventId(initial), "voice-preview.mp3", "audio/mpeg", relative,
                    previewBytes.size.toLong(), modelVisible = false)
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable + Dispatchers.IO) { control.withLock {
                val done = state.copy(phase = "done", result = state.result!!.copy(previewAttachment = ref), updatedAt = now())
                val finishedUsage = usage.copy(status = "completed", endedAt = done.updatedAt)
                db.withTransaction {
                    val task = requireTask(initial.id).let(signals::effective)
                    if (task.status.isTerminal || task.status == TaskStatus.STOPPING) throw CancellationException("voice design stopped before publication")
                    ref?.let { db.attachments().upsert(AttachmentEntity(it.id, it.relativePath, it.filename, it.mimeType,
                        it.size, initial.chatId, null, null, done.updatedAt)) }
                    updateUsage(initial, usage, finishedUsage)
                    tasks.upsert(task.copy(status = TaskStatus.SUCCEEDED, terminalReason = TaskTerminalReason.COMPLETED,
                        updatedAt = done.updatedAt, resultSummary = "voice creation completed; result retained"), Jsonx.encode(done))
                }
                state = done; usage = finishedUsage; published = true; publish(state)
            } }
        } catch (_: CancellationException) { cancelled = true }
        catch (error: Exception) { if (!currentCoroutineContext().isActive) cancelled = true else failure = error }
        finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    if (!published && owned?.exists() == true && owned?.delete() != true)
                        failure = StError.Speech("tts_voice_design_cleanup_failed", "voice preview cleanup failed")
                    control.withLock { db.withTransaction {
                        val current = requireTask(initial.id).let(signals::effective)
                        if (!current.status.isTerminal) {
                            val status = if (cancelled || current.status == TaskStatus.STOPPING) current.stoppingStatus() else TaskStatus.FAILED
                            val at = now()
                            state = state.copy(phase = status.name.lowercase(), updatedAt = at,
                                errorCode = (failure as? StError.Speech)?.code ?: if (failure != null) "tts_voice_creation_failed" else null)
                            val finishedUsage = usage.copy(status = status.name.lowercase(), endedAt = at)
                            updateUsage(initial, usage, finishedUsage)
                            tasks.upsert(current.copy(status = status, terminalReason = signals.completionReason(current, status),
                                updatedAt = at, errorMessage = state.errorCode,
                                resultSummary = if (state.result == null) "voice creation incomplete; remote outcome may be unknown"
                                    else "remote voice acknowledged; local task incomplete"), Jsonx.encode(state))
                            usage = finishedUsage
                        }
                    } }
                    publish(state)
                } finally { try { lease.close() } finally { handoff?.close() } }
            }
        }
    }

    /** Stop interrupted records locally; a possibly accepted remote request is never repeated or deleted. */
    suspend fun onProcessStart() = withContext(Dispatchers.IO) {
        var after = ""
        while (true) {
            val page = db.tasks().voiceDesignRecoveryPage(after, 100)
            if (page.isEmpty()) break
            for (id in page) {
                currentCoroutineContext().ensureActive()
                val task = requireTask(id)
                val event = db.usage().cacheEvent(eventId(task)) ?: continue
                val usage = Jsonx.decode<SpeechVoiceDesignUsage>(event.usageJson)
                if (task.status.isTerminal && usage.status !in setOf("prepared", "running")) continue
                val saved = progress(id, expectedKind = null)
                if (saved.result?.previewAttachment == null && id.matches(Regex("[0-9a-f]{32}"))) {
                    val file = paths.attachmentFile(previewPath(task))
                    if (file.exists() && !file.delete()) throw StError.Speech("tts_voice_design_cleanup_failed", "voice preview cleanup failed")
                }
                db.withTransaction {
                    val at = now()
                    val status = if (task.status.isTerminal) task.status else TaskStatus.INTERRUPTED
                    updateUsage(task, usage, usage.copy(status = if (status == TaskStatus.SUCCEEDED) "completed" else status.name.lowercase(), endedAt = at))
                    tasks.upsert(task.copy(status = status, terminalReason = task.terminalReason ?: TaskTerminalReason.PROCESS_KILLED,
                        updatedAt = at), Jsonx.encode(saved.copy(phase = if (status == TaskStatus.SUCCEEDED) "done" else status.name.lowercase(), updatedAt = at)))
                }
            }
            after = page.last()
        }
    }
    private suspend fun updateUsage(task: TaskSnapshot, before: SpeechVoiceDesignUsage, after: SpeechVoiceDesignUsage) {
        val operation = if (task.kind == TaskKind.SPEECH_VOICE_CLONE) "clone" else "design"
        val cost = after.endedAt?.let { Jsonx.encode(CostSnapshot(reason = "voice_${operation}_price_not_configured")) }
        if (db.usage().checkpointCache(eventId(task), task.id, task.chatId, "tts-voice-$operation:${before.provider}", null,
                task.createdAt, Jsonx.encode(before), null, Jsonx.encode(after), cost) != 1)
            throw StError.Conflict("voice design usage changed")
    }
    private suspend fun requireTask(id: String): TaskSnapshot = (tasks.get(id) ?: throw StError.NotFound("voice design task missing")).also {
        if (it.kind !in setOf(TaskKind.SPEECH_VOICE_DESIGN, TaskKind.SPEECH_VOICE_CLONE)) throw StError.Validation("not a voice creation task")
    }
    private fun eventId(task: TaskSnapshot) = "voice-${if (task.kind == TaskKind.SPEECH_VOICE_CLONE) "clone" else "design"}:${task.id}"
    private fun previewPath(task: TaskSnapshot) = "voice-${if (task.kind == TaskKind.SPEECH_VOICE_CLONE) "clone" else "design"}-${task.id}.mp3"
    private fun now() = Instant.now().toString()
}
