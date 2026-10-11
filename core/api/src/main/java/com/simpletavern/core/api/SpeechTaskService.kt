package com.simpletavern.core.api

import com.simpletavern.core.data.*
import com.simpletavern.core.model.*
import com.simpletavern.core.tools.SpeechSession
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-owned explicit new synthesis; restart cleans/interrupts, never retries old vendor requests. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
internal class SpeechTaskService(private val resolver: SpeechResolver, private val cache: SpeechCacheService,
    private val tasks: TaskStore, private val gate: DataOperationGate, private val scope: CoroutineScope) {
    private val control = Mutex()
    private val workers = ConcurrentHashMap<String, Job>()
    private val signals = WorkerStopSignals()
    private val latest = MutableStateFlow<SpeechTaskProgress?>(null)
    fun observe(): StateFlow<SpeechTaskProgress?> = latest
    fun signalHostInterruption(reason: TaskTerminalReason) = signals.interrupt(reason, workers)

    suspend fun start(request: SpeechRequest, candidateId: String?, preparedTask: Pair<SpeechSelection, SpeechSession>? = null,
        onAudio: (suspend (SpeechAudioChunk) -> Unit)? = null): TaskSnapshot {
        val lease = gate.sharedLease()
        var transferred = false
        try {
            if (candidateId != null) {
                val chatId = request.chatId ?: throw StError.Validation("message synthesis requires chatId")
                withContext(Dispatchers.IO) { cache.requireMessage(chatId, candidateId, request.text) }
            }
            val (selection, prepared) = preparedTask ?: resolver.prepareTask(request)
            currentCoroutineContext().ensureActive()
            return control.withLock {
                currentCoroutineContext().ensureActive()
                if (!scope.isActive) throw StError.Interrupted("speech backend stopped")
                // Do not queue unbounded in-memory text/key snapshots behind the two HTTP slots.
                if (workers.size >= 16) throw StError.Conflict("speech task queue is full")
                withContext(NonCancellable) {
                    val id = UUID.randomUUID().toString().replace("-", "")
                    val at = now()
                    val task = TaskSnapshot(id, TaskKind.SPEECH_SYNTHESIS, TaskStatus.QUEUED, chatId = request.chatId, createdAt = at, updatedAt = at)
                    val state = SpeechTaskProgress(id, selection, at, messageCandidateId = candidateId)
                    SpeechTaskMetadata.validate(state)
                    val payload = Jsonx.encode(state)
                    if (payload.toByteArray(Charsets.UTF_8).size > SpeechTaskMetadata.MAX_PAYLOAD_BYTES)
                        throw StError.Validation("speech task exceeds payload budget")
                    if (tasks.get(id) != null) throw StError.Conflict("speech task identity exists")
                    signals.register(id)
                    try { tasks.upsert(task, payload) } catch (error: Throwable) { signals.forget(id); throw error }
                    val ready = CompletableDeferred<Unit>()
                    val worker = scope.launch(start = CoroutineStart.ATOMIC) { run(task, state, prepared, request.text, lease, ready, onAudio) }
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
    suspend fun progress(id: String): SpeechTaskProgress {
        val saved = cache.progress(id)
        val task = tasks.get(id) ?: throw StError.NotFound("speech task missing")
        return if (task.status.isTerminal && task.status != TaskStatus.SUCCEEDED)
            saved.copy(phase = task.status.name.lowercase(), updatedAt = task.updatedAt) else saved
    }
    suspend fun await(id: String): SpeechAsset {
        workers[id]?.join()
        val task = tasks.get(id) ?: throw StError.NotFound("speech task missing")
        if (task.kind != TaskKind.SPEECH_SYNTHESIS || task.status != TaskStatus.SUCCEEDED)
            throw StError.Speech("tts_task_incomplete", "speech task did not complete")
        return cache.asset(id)
    }
    suspend fun stop(id: String, reason: TaskTerminalReason): TaskSnapshot = withContext(NonCancellable) {
        val worker = control.withLock {
            val task = tasks.get(id) ?: throw StError.NotFound(id)
            if (task.kind != TaskKind.SPEECH_SYNTHESIS) throw StError.Validation("not a speech task")
            if (task.status.isTerminal) return@withLock null
            val first = signals.first(id, reason)
            val running = workers[id]?.also { it.cancel() }
            if (tasks.requestStop(id, first).status.isTerminal) null else running
        }
        worker?.join()
        tasks.update(id) { it.copy(status = it.stoppingStatus(), terminalReason = it.completionReason(it.stoppingStatus()), updatedAt = now()) }
    }
    private suspend fun run(initial: TaskSnapshot, original: SpeechTaskProgress, prepared: SpeechSession,
        spokenText: String, lease: AutoCloseable, ready: Deferred<Unit>, onAudio: (suspend (SpeechAudioChunk) -> Unit)?) {
        var state = original
        var stage: SpeechCacheService.Stage? = null
        var failure: Exception? = null
        var cancelled = false
        var handoff: AutoCloseable? = null
        try {
            ready.await()
            handoff = tasks.retainHostHandoff()
            val task = tasks.update(initial.id) { if (it.status == TaskStatus.STOPPING) it else it.copy(status = TaskStatus.RUNNING, updatedAt = now()) }
            if (task.status.isTerminal || task.status == TaskStatus.STOPPING) throw CancellationException("speech stopped")
            state = state.copy(phase = if (state.selection.preprocessing == null) "synthesizing" else "preprocessing", updatedAt = now()); checkpoint(state)
            if (state.selection.preprocessing == null) cache.preflightSynthesis(state, initial.chatId, spokenText, spokenText)
            val audio = prepared.synthesize(SpeechCallContext(initial.chatId, initial.id, state.messageCandidateId), onAudio) { result ->
                state = state.copy(phase = "synthesizing", preprocessingRequestId = result.requestId, updatedAt = now()); checkpoint(state)
                cache.preflightSynthesis(state, initial.chatId, spokenText, result.processedText)
                val current = tasks.get(initial.id)?.let(signals::effective) ?: throw StError.NotFound("speech task removed before synthesis")
                if (current.status.isTerminal || current.status == TaskStatus.STOPPING) throw CancellationException("speech stopped after preprocessing")
            }
            currentCoroutineContext().ensureActive()
            val asset = SpeechAsset(initial.id, audio.provider, audio.model, audio.voiceId, audio.format, audio.sampleRate,
                audio.bytes.size.toLong(), now(), state.selection.assistantId, initial.chatId, state.messageCandidateId,
                audio.reportedUsageCharacters, audio.warnings, version = 2, spokenText = audio.spokenText ?: spokenText,
                sourceNamespace = state.selection.sourceNamespace, requestId = audio.requestId, preprocessingRequestId = audio.preprocessingRequestId)
            state = state.copy(phase = "staging", asset = asset, updatedAt = now()); checkpoint(state)
            val staged = cache.stage(asset, audio.bytes) { owned ->
                stage = owned
                state = state.copy(device = owned.device, inode = owned.inode)
            }
            state = state.copy(phase = "publishing", device = staged.device, inode = staged.inode, updatedAt = now())
            checkpoint(state) // write the publication identity before a link can be visible in attachments.
            currentCoroutineContext().ensureActive()
            var chosen: TaskSnapshot? = null
            cache.publish(state, staged, spokenText, decision = { commit ->
                withContext(NonCancellable + Dispatchers.IO) { control.withLock {
                    val taskAtCommit = tasks.get(initial.id)?.let(signals::effective) ?: throw StError.NotFound("speech task removed")
                    if (taskAtCommit.status.isTerminal || taskAtCommit.status == TaskStatus.STOPPING) throw CancellationException("speech stopped before publication")
                    chosen = taskAtCommit
                    commit()
                    state = state.copy(phase = "done", updatedAt = now()); latest.value = state
                } }
            }, finish = { published ->
                    val done = published.copy(updatedAt = now())
                    tasks.upsert(chosen!!.copy(status = TaskStatus.SUCCEEDED, terminalReason = TaskTerminalReason.COMPLETED,
                        updatedAt = done.updatedAt, resultSummary = "speech audio published; cleanup pending"), Jsonx.encode(done))
            })
        } catch (_: CancellationException) { cancelled = true }
        catch (error: Exception) { if (!currentCoroutineContext().isActive) cancelled = true else failure = error }
        finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    var cleaned = false
                    try { cache.cleanup(state, stage); cleaned = true }
                    catch (_: Exception) { gate.requireRecovery(); state = state.copy(errorCode = "tts_cleanup_failed"); if (failure == null) failure = StError.Speech("tts_cleanup_failed", "speech cleanup requires process-start recovery") }
                    control.withLock {
                        val current = tasks.get(initial.id) ?: return@withLock
                        if (current.status.isTerminal) {
                            if (current.status == TaskStatus.SUCCEEDED && cleaned) {
                                val saved = cache.progress(initial.id).copy(device = null, inode = null)
                                tasks.upsert(current.copy(resultSummary = "speech audio published"), Jsonx.encode(saved))
                                latest.value = saved
                            }
                            return@withLock // a successful DB commit remains authoritative even if private cleanup failed.
                        }
                        val task = signals.effective(current)
                        val status = when { task.status == TaskStatus.STOPPING -> task.stoppingStatus(); cancelled -> TaskStatus.INTERRUPTED; else -> TaskStatus.FAILED }
                        val code = state.errorCode ?: (failure as? StError.Speech)?.code ?: if (cancelled) "tts_interrupted" else "tts_task_failed"
                        state = state.copy(phase = status.name.lowercase(), errorCode = code, updatedAt = now(),
                            device = if (cleaned) null else state.device, inode = if (cleaned) null else state.inode)
                        tasks.upsert(task.copy(status = status, terminalReason = signals.completionReason(task, status), updatedAt = state.updatedAt,
                            errorMessage = code, resultSummary = if (cleaned) "speech ${state.phase}" else "speech cleanup required"), Jsonx.encode(state))
                        latest.value = state
                    }
                } finally { lease.close(); handoff?.close() }
            }
        }
    }
    private suspend fun checkpoint(state: SpeechTaskProgress) {
        SpeechTaskMetadata.validate(state)
        val raw = Jsonx.encode(state)
        if (raw.toByteArray(Charsets.UTF_8).size > SpeechTaskMetadata.MAX_PAYLOAD_BYTES) throw StError.Validation("speech task exceeds payload budget")
        tasks.checkpoint(state.taskId, raw, state.updatedAt); latest.value = state
    }
    private fun now() = Instant.now().toString()
}
