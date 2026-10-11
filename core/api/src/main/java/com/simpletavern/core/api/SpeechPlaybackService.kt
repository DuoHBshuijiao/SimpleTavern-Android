package com.simpletavern.core.api

import com.simpletavern.core.data.*
import com.simpletavern.core.model.*
import com.simpletavern.core.tools.SpeechSession
import com.simpletavern.core.tools.SpeechVoicePreviewDownloader
import java.time.Instant
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select

/** Process-owned playback through the ordinary host/task store; runtime input is never replayed. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
internal class SpeechPlaybackService(private val output: DeviceAudioOutput, private val resolver: SpeechResolver,
    private val synthesis: SpeechTaskService, private val cache: SpeechCacheService, private val tasks: TaskStore,
    private val content: ContentRepository, private val chats: ChatRepository, private val gate: DataOperationGate,
    private val attachments: ChatAttachmentService,
    private val scope: CoroutineScope) {
    private sealed interface Input {
        data class System(val request: SystemSpeechRequest) : Input
        data class Cached(val audio: SpeechAudio, val pcmFormat: PcmPlaybackFormat?, val playbackSpeed: Double) : Input
        data class Attachment(val source: ChatAttachmentService.AudioFile, val playbackSpeed: Double) : Input
        class VoicePreview(val url: String, val playbackSpeed: Double) : Input {
            override fun toString() = "VoicePreview(url=<private>)"
        }
        data class Cloud(val plan: SpeechPlaybackPlan, val pcmFormat: PcmPlaybackFormat?, val playbackSpeed: Double) : Input
        data class Playlist(val clips: List<PlaylistClip>, val playbackSpeed: Double) : Input
    }
    private class PlaylistClip(val item: SpeechPlaylistItem, val file: File, val format: String,
        val pcmFormat: PcmPlaybackFormat?, val pin: SpeechCacheService.PlaybackFile? = null)
    private class PlaylistJump(val index: Int?, val offset: Int, val positionMs: Long) {
        val reply = CompletableDeferred<Unit>()
    }
    private class PlaylistRuntime {
        val pending = ConcurrentHashMap.newKeySet<PlaylistJump>()
        val commands = Channel<PlaylistJump>(8, onUndeliveredElement = {
            it.reply.completeExceptionally(StError.Conflict("playlist has ended"))
        })
        suspend fun jump(command: PlaylistJump) {
            pending.add(command)
            try { commands.send(command); command.reply.await() }
            catch (error: CancellationException) { command.reply.cancel(); throw error }
            finally { pending.remove(command) }
        }
        fun close() {
            commands.cancel()
            pending.forEach { it.reply.completeExceptionally(StError.Conflict("playlist has ended")) }
        }
    }
    private val control = Mutex()
    private val workers = ConcurrentHashMap<String, Job>()
    private val playbackControls = ConcurrentHashMap<String, AudioPlaybackControl>()
    private val playlists = ConcurrentHashMap<String, PlaylistRuntime>()
    private val signals = WorkerStopSignals()
    private val latest = MutableStateFlow<SpeechPlaybackProgress?>(null)
    private val previewDownloader = SpeechVoicePreviewDownloader()
    fun observe(): StateFlow<SpeechPlaybackProgress?> = latest
    fun signalHostInterruption(reason: TaskTerminalReason) = signals.interrupt(reason, workers)

    suspend fun startSystem(request: SystemSpeechRequest, playbackSpeed: Double = 1.0): TaskSnapshot = start(request.chatId) { id, at ->
        validateSpeed(playbackSpeed)
        val effectiveRate = request.speechRate * playbackSpeed
        SpeechText.validate(request.text, 40_000, spoken = true)
        if (!request.speechRate.isFinite() || request.speechRate <= 0 || !request.pitch.isFinite() || request.pitch <= 0 ||
            !effectiveRate.toFloat().isFinite() || !request.pitch.toFloat().isFinite())
            throw StError.Validation("system speech rate and pitch must be finite and positive")
        val chat = request.chatId?.let { chats.getSession(it) ?: throw StError.NotFound("speech chat") }
        val config = request.assistantId?.let { content.getAssistant(it) ?: throw StError.NotFound("speech assistant") }
        if (chat != null && config != null && chat.sourceNamespace != config.sourceNamespace) throw StError.Conflict("system speech source mismatch")
        Input.System(request.copy(speechRate = effectiveRate)) to SpeechPlaybackProgress(id, "system", at, assistantId = config?.id,
            sourceNamespace = chat?.sourceNamespace ?: config?.sourceNamespace, playbackSpeed = playbackSpeed)
    }

    private fun validateSpeed(speed: Double) {
        if (!speed.isFinite() || speed !in 0.25..4.0) throw StError.Validation("playback speed must be 0.25..4")
    }

    suspend fun startAsset(assetId: String, pcmFormat: PcmPlaybackFormat? = null, playbackSpeed: Double = 1.0): TaskSnapshot = start(null) { id, at ->
        validateSpeed(playbackSpeed)
        val asset = cache.asset(assetId)
        val audio = cache.read(assetId)
        Input.Cached(audio, pcmFormat, playbackSpeed) to SpeechPlaybackProgress(id, "cached", at, assetId = assetId,
            assistantId = asset.assistantId, sourceNamespace = asset.sourceNamespace, playbackSpeed = playbackSpeed)
    }

    suspend fun startAttachment(attachmentId: String, pcmFormat: PcmPlaybackFormat?,
                                playbackSpeed: Double): TaskSnapshot = start(null) { id, at ->
        validateSpeed(playbackSpeed)
        // Resolve while retaining the same data-operation lease as the queued/running playback.
        Input.Attachment(attachments.audioFile(attachmentId, pcmFormat), playbackSpeed) to SpeechPlaybackProgress(id, "attachment", at,
            attachmentId = attachmentId, playbackSpeed = playbackSpeed)
    }

    /** Only an explicitly selected, completed creation task can supply a remote preview URL. */
    suspend fun startVoicePreview(voiceTaskId: String, playbackSpeed: Double): TaskSnapshot = start(null) { id, at ->
        validateSpeed(playbackSpeed)
        val source = tasks.get(voiceTaskId) ?: throw StError.NotFound("voice creation task")
        if (source.kind !in setOf(TaskKind.SPEECH_VOICE_DESIGN, TaskKind.SPEECH_VOICE_CLONE))
            throw StError.Validation("not a voice creation task")
        if (source.status != TaskStatus.SUCCEEDED) throw StError.Conflict("voice creation has not completed successfully")
        val creation = Jsonx.decode<SpeechVoiceCreationProgress>(tasks.payloadJson(voiceTaskId)
            ?: throw StError.NotFound("voice creation progress"))
        val result = creation.result ?: throw StError.NotFound("voice creation result")
        val local = result.previewAttachment
        val input = if (local != null) Input.Attachment(attachments.audioFile(local.id, null), playbackSpeed)
            else Input.VoicePreview(result.remotePreviewUrl ?: throw StError.NotFound("voice preview unavailable"), playbackSpeed)
        input to SpeechPlaybackProgress(id, "voice_preview", at, attachmentId = local?.id,
            assistantId = creation.selection.assistantId, sourceNamespace = creation.selection.sourceNamespace,
            playbackSpeed = playbackSpeed, voiceCreationTaskId = voiceTaskId)
    }

    suspend fun startCloud(request: SpeechRequest, pcmFormat: PcmPlaybackFormat? = null, playbackSpeed: Double = 1.0): TaskSnapshot = start(request.chatId) { id, at ->
        validateSpeed(playbackSpeed)
        val plan = resolver.preparePlayback(request)
        val selection = plan.selection
        Input.Cloud(plan, pcmFormat, playbackSpeed) to SpeechPlaybackProgress(id, "cloud", at, assistantId = selection.assistantId,
            sourceNamespace = selection.sourceNamespace, playbackSpeed = playbackSpeed, segmentCount = 0)
    }

    suspend fun startPlaylist(items: List<SpeechPlaylistItem>, playbackSpeed: Double): TaskSnapshot = start(null) { id, at ->
        validateSpeed(playbackSpeed)
        if (items.isEmpty() || items.size > 100 || items.any { it.id.isBlank() || it.id.toByteArray(Charsets.UTF_8).size > 1024 || it.id.any(Char::isISOControl) })
            throw StError.Validation("playlist requires 1..100 managed audio items")
        val pins = mutableListOf<SpeechCacheService.PlaybackFile>()
        try {
            val clips = items.toList().map { item ->
                currentCoroutineContext().ensureActive()
                when (item.source) {
                    SpeechPlaylistSource.CACHED -> {
                        // Record ownership before cancellation can discard a newly acquired pin.
                        val pin = withContext(NonCancellable) { cache.pinForPlayback(item.id).also(pins::add) }
                        val asset = pin.asset
                        val pcm = item.pcmFormat
                        if (asset.format == "pcm" && (pcm == null ||
                            asset.sampleRate != null && asset.sampleRate != pcm.sampleRate))
                            throw StError.Validation("cached PCM playlist item requires its actual layout")
                        PlaylistClip(item, pin.file, asset.format, pcm, pin)
                    }
                    SpeechPlaylistSource.ATTACHMENT -> {
                        val audio = attachments.audioFile(item.id, item.pcmFormat)
                        PlaylistClip(item, audio.file, audio.format, audio.pcmFormat)
                    }
                }.also { clip -> clip.pcmFormat?.let {
                    if (it.sampleRate <= 0 || it.channels !in 1..2) throw StError.Validation("invalid playlist PCM layout")
                } }
            }
            Input.Playlist(clips, playbackSpeed) to SpeechPlaybackProgress(id, "playlist", at,
                playbackSpeed = playbackSpeed, segmentCount = clips.size, playlist = clips.map { it.item })
        } catch (error: Throwable) { pins.forEach { it.close() }; throw error }
    }

    private fun release(input: Input?) {
        if (input is Input.Playlist) input.clips.forEach { it.pin?.close() }
    }

    private suspend fun start(chatId: String?, prepare: suspend (String, String) -> Pair<Input, SpeechPlaybackProgress>): TaskSnapshot {
        val lease = gate.sharedLease()
        var transferred = false
        var prepared: Input? = null
        try {
            return control.withLock {
                currentCoroutineContext().ensureActive()
                if (!scope.isActive) throw StError.Interrupted("playback backend stopped")
                if (workers.isNotEmpty()) throw StError.Conflict("device speech is already active; stop it before starting a new playback")
                val id = UUID.randomUUID().toString().replace("-", "")
                val at = now()
                val (input, progress) = prepare(id, at)
                prepared = input
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    val task = TaskSnapshot(id, TaskKind.SPEECH_PLAYBACK, TaskStatus.QUEUED, chatId = chatId, createdAt = at, updatedAt = at)
                    signals.register(id)
                    try { tasks.upsert(task, Jsonx.encode(progress)) } catch (error: Throwable) { signals.forget(id); throw error }
                    val ready = CompletableDeferred<Unit>()
                    val playback = AudioPlaybackControl()
                    playbackControls[id] = playback
                    if (input is Input.Playlist) playlists[id] = PlaylistRuntime()
                    val worker = scope.launch(start = CoroutineStart.ATOMIC) { run(task, input, progress, lease, ready, playback) }
                    workers[id] = worker
                    worker.invokeOnCompletion {
                        workers.remove(id, worker); playbackControls.remove(id, playback)
                        playlists.remove(id)?.close(); signals.forget(id)
                    }
                    signals.bind(id, worker)
                    transferred = true
                    latest.value = progress; ready.complete(Unit)
                    task
                }
            }
        } finally { if (!transferred) { release(prepared); lease.close() } }
    }

    suspend fun progress(id: String): SpeechPlaybackProgress {
        val task = tasks.get(id) ?: throw StError.NotFound("speech playback")
        if (task.kind != TaskKind.SPEECH_PLAYBACK) throw StError.Validation("not a speech playback task")
        val saved = Jsonx.decode<SpeechPlaybackProgress>(tasks.payloadJson(id) ?: throw StError.NotFound("playback progress"))
        val value = if (task.status.isTerminal) saved.copy(phase = if (task.status == TaskStatus.SUCCEEDED) "done" else task.status.name.lowercase(), updatedAt = task.updatedAt) else saved
        return value.copy(playback = playbackControls[id]?.state() ?: value.playback ?: AudioPlaybackState())
    }
    suspend fun await(id: String): SpeechPlaybackProgress { workers[id]?.join(); return progress(id) }

    suspend fun pause(id: String, paused: Boolean) = command(id) { it.pause(paused) }
    suspend fun seek(id: String, positionMs: Long) = command(id) { it.seek(positionMs) }
    suspend fun skipTo(id: String, index: Int, positionMs: Long): SpeechPlaybackProgress {
        if (index < 0 || positionMs < 0) throw StError.Validation("playlist index and position must be nonnegative")
        return jump(id, PlaylistJump(index, 0, positionMs))
    }
    suspend fun next(id: String) = jump(id, PlaylistJump(null, 1, 0))
    suspend fun previous(id: String) = jump(id, PlaylistJump(null, -1, 0))
    private suspend fun jump(id: String, command: PlaylistJump): SpeechPlaybackProgress {
        val runtime = control.withLock {
            val task = tasks.get(id) ?: throw StError.NotFound("speech playlist")
            if (task.kind != TaskKind.SPEECH_PLAYBACK || task.status != TaskStatus.RUNNING)
                throw StError.Conflict("playlist is not running")
            playlists[id] ?: throw StError.Unsupported("this playback is not a managed audio playlist")
        }
        // Never hold the task-control mutex while waiting for decoder release/initial seek.
        runtime.jump(command)
        return progress(id)
    }
    private suspend fun command(id: String, action: suspend (AudioPlaybackControl) -> AudioPlaybackState): SpeechPlaybackProgress {
        control.withLock {
            val task = tasks.get(id) ?: throw StError.NotFound("speech playback")
            if (task.kind != TaskKind.SPEECH_PLAYBACK || task.status != TaskStatus.RUNNING)
                throw StError.Conflict("playback task is not running")
            if (progress(id).mode == "system") throw StError.Unsupported("system TextToSpeech has no reliable pause or millisecond seek; stop it explicitly")
            action(playbackControls[id] ?: throw StError.Conflict("playback has no live device output"))
        }
        return progress(id).also { latest.value = it }
    }

    suspend fun stop(id: String, reason: TaskTerminalReason): TaskSnapshot = withContext(NonCancellable) {
        val worker = control.withLock {
            val task = tasks.get(id) ?: throw StError.NotFound("playback")
            if (task.kind != TaskKind.SPEECH_PLAYBACK) throw StError.Validation("not a speech playback task")
            if (task.status.isTerminal) return@withLock null
            val first = signals.first(id, reason)
            val running = workers[id]?.also { it.cancel() }
            tasks.requestStop(id, first); running
        }
        worker?.join()
        tasks.update(id) { it.copy(status = it.stoppingStatus(), terminalReason = it.completionReason(it.stoppingStatus()), updatedAt = now()) }
    }

    private suspend fun run(initial: TaskSnapshot, input: Input, original: SpeechPlaybackProgress,
        lease: AutoCloseable, ready: Deferred<Unit>, playback: AudioPlaybackControl) {
        var state = original
        var failure: Exception? = null
        var cancelled = false
        var ticker: Job? = null
        try {
            ready.await()
            val owner = tasks.update(initial.id) { if (it.status == TaskStatus.STOPPING) it else it.copy(status = TaskStatus.RUNNING, updatedAt = now()) }
            if (owner.status == TaskStatus.STOPPING) throw CancellationException("playback stopped before dispatch")
            if (input !is Input.System) playback.begin()
            state = state.copy(phase = when (input) {
                is Input.VoicePreview -> "downloading"
                is Input.Cloud -> if (input.plan.selection.preprocessing == null) "synthesizing" else "preprocessing"
                is Input.Playlist -> "preparing"
                else -> "playing"
            }, updatedAt = now()); checkpoint(state)
            ticker = scope.launch {
                while (isActive) {
                    delay(500)
                    val live = try { playback.state() } catch (error: CancellationException) { throw error }
                        catch (_: Exception) { continue } // A device error is reported by the owning playback worker.
                    latest.update { current -> current?.takeIf { it.taskId == initial.id }?.copy(playback = live) ?: current }
                }
            }
            when (input) {
                is Input.System -> output.speak(input.request)
                is Input.Cached -> output.play(input.audio, input.pcmFormat, input.playbackSpeed, playback)
                is Input.Attachment -> output.playFile(input.source.file, input.source.format, input.source.pcmFormat, input.playbackSpeed, playback)
                is Input.VoicePreview -> {
                    var lastCheckpoint = 0L
                    val ref = previewDownloader.download(input.url) { filename, mimeType, stream ->
                        attachments.importVoicePreview(filename, mimeType, { stream }, { bytes ->
                            state = state.copy(downloadedBytes = bytes, updatedAt = now())
                            latest.value = state
                            val clock = System.nanoTime()
                            if (lastCheckpoint == 0L || clock - lastCheckpoint >= 500_000_000L) {
                                tasks.checkpoint(state.taskId, Jsonx.encode(state), state.updatedAt)
                                lastCheckpoint = clock
                            }
                        }) { attachment ->
                            // The complete file and its reference publish together, before device playback.
                            val task = tasks.get(initial.id)?.let(signals::effective)
                                ?: throw StError.NotFound("speech playback")
                            if (task.status.isTerminal || task.status == TaskStatus.STOPPING)
                                throw CancellationException("preview stopped before publication")
                            state = state.copy(phase = "preparing", attachmentId = attachment.id, updatedAt = now())
                            tasks.checkpoint(state.taskId, Jsonx.encode(state), state.updatedAt)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    latest.value = state
                    val audio = attachments.audioFile(ref.id, null)
                    output.playFile(audio.file, audio.format, audio.pcmFormat, input.playbackSpeed, playback) {
                        state = state.copy(phase = "playing", updatedAt = now()); checkpoint(state)
                    }
                }
                is Input.Playlist -> playPlaylist(input, state, playback) { updated -> state = updated; checkpoint(updated) }
                is Input.Cloud -> {
                    val parts = input.plan.prepareParts(SpeechCallContext(initial.chatId, initial.id)) { result ->
                        state = state.copy(preprocessingRequestId = result.requestId, updatedAt = now()); checkpoint(state)
                    }
                    currentCoroutineContext().ensureActive()
                    state = state.copy(phase = "synthesizing", segmentCount = parts.size, updatedAt = now()); checkpoint(state)
                    for ((index, part) in parts.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val chunks = Channel<SpeechAudioChunk>(8)
                        state = state.copy(phase = "synthesizing", segmentIndex = index, assetId = null, synthesisTaskId = null, updatedAt = now()); checkpoint(state)
                        val child = synthesis.start(part.first, null, part.second) { chunks.send(it) }
                        state = state.copy(synthesisTaskId = child.id, synthesisTaskIds = state.synthesisTaskIds + child.id, updatedAt = now()); checkpoint(state)
                        try {
                            val asset = coroutineScope {
                                val completion = async {
                                    try { synthesis.await(child.id).also { chunks.close() } }
                                    catch (error: CancellationException) { throw error }
                                    catch (error: Throwable) { chunks.close(error); throw error }
                                }
                                output.playStream(chunks, input.pcmFormat, input.playbackSpeed, playback) {
                                    state = state.copy(phase = "playing", updatedAt = now()); checkpoint(state)
                                }
                                completion.await()
                            }
                            state = state.copy(assetId = asset.id, assetIds = state.assetIds + asset.id, updatedAt = now()); checkpoint(state)
                        } finally {
                            withContext(NonCancellable) {
                                try {
                                    if (tasks.get(child.id)?.status?.isTerminal == false) {
                                        val reason = signals.effective(tasks.get(initial.id) ?: initial).terminalReason
                                        synthesis.stop(child.id, reason ?: TaskTerminalReason.USER_STOP)
                                    }
                                } finally { chunks.cancel() }
                            }
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
        } catch (_: CancellationException) { cancelled = true }
        catch (error: Exception) { if (!currentCoroutineContext().isActive) cancelled = true else failure = error }
        finally {
            withContext(NonCancellable) {
                try {
                    ticker?.cancelAndJoin()
                    playback.finish()
                    val child = state.synthesisTaskId
                    try {
                        if (child != null && tasks.get(child)?.status?.isTerminal == false) {
                            val reason = signals.effective(tasks.get(initial.id) ?: initial).terminalReason
                            synthesis.stop(child, reason ?: TaskTerminalReason.USER_STOP)
                        }
                    } catch (error: Exception) { if (failure == null) failure = error }
                    control.withLock {
                        val current = tasks.get(initial.id) ?: return@withLock
                        if (current.status.isTerminal) return@withLock
                        val effective = signals.effective(current)
                        val status = if (effective.status == TaskStatus.STOPPING) effective.stoppingStatus()
                            else if (cancelled) TaskStatus.STOPPED else if (failure != null) TaskStatus.FAILED else TaskStatus.SUCCEEDED
                        state = state.copy(phase = if (status == TaskStatus.SUCCEEDED) "done" else status.name.lowercase(),
                            errorCode = (failure as? StError)?.code, updatedAt = now(), playback = playback.state())
                        tasks.upsert(current.copy(status = status, terminalReason = signals.completionReason(current, status),
                            updatedAt = state.updatedAt, errorMessage = failure?.message, resultSummary = "speech playback ${state.phase}"), Jsonx.encode(state))
                        latest.value = state
                    }
                } finally { playlists[initial.id]?.close(); release(input); lease.close() }
            }
        }
    }
    private suspend fun playPlaylist(input: Input.Playlist, original: SpeechPlaybackProgress, playback: AudioPlaybackControl,
                                     publish: suspend (SpeechPlaybackProgress) -> Unit) {
        val runtime = playlists.getValue(original.taskId)
        var state = original
        var index = 0
        var positionMs = 0L
        var waitingJump: PlaylistJump? = null
        while (index < input.clips.size) {
            currentCoroutineContext().ensureActive()
            val clip = input.clips[index]
            playback.prepareItem(positionMs)
            state = state.copy(phase = "preparing", segmentIndex = index, updatedAt = now(),
                assetId = clip.item.id.takeIf { clip.item.source == SpeechPlaylistSource.CACHED },
                attachmentId = clip.item.id.takeIf { clip.item.source == SpeechPlaylistSource.ATTACHMENT },
                assistantId = clip.pin?.asset?.assistantId, sourceNamespace = clip.pin?.asset?.sourceNamespace)
            publish(state)
            val jump = coroutineScope {
                val playing = async {
                    output.playFile(clip.file, clip.format, clip.pcmFormat, input.playbackSpeed, playback) {
                        state = state.copy(phase = "playing", updatedAt = now())
                        publish(state)
                        waitingJump?.reply?.complete(Unit); waitingJump = null
                    }
                }
                try {
                    var selected: Pair<Int, Long>? = null
                    while (true) {
                        val command = select<PlaylistJump?> {
                            runtime.commands.onReceive { it }
                            playing.onAwait { null }
                        } ?: break
                        if (command.reply.isCancelled) continue
                        val target = command.index ?: (index + command.offset)
                        if (target !in input.clips.indices) {
                            command.reply.completeExceptionally(StError.Validation("playlist target is outside the selected items"))
                            continue
                        }
                        playing.cancelAndJoin() // Release the old device output before starting another item.
                        selected = target to command.positionMs
                        // Relative targets are resolved at consumption, not against a stale caller snapshot.
                        waitingJump?.reply?.completeExceptionally(StError.Conflict("playlist jump was superseded"))
                        waitingJump = command
                        break
                    }
                    selected
                } finally { playing.cancelAndJoin() }
            }
            if (jump != null) {
                index = jump.first; positionMs = jump.second
            } else {
                state = state.copy(completedPlaylistIndices = state.completedPlaylistIndices + index,
                    assetIds = if (clip.item.source == SpeechPlaylistSource.CACHED) (state.assetIds + clip.item.id).distinct() else state.assetIds,
                    updatedAt = now())
                publish(state)
                index++; positionMs = 0
            }
        }
    }
    private suspend fun checkpoint(state: SpeechPlaybackProgress) { tasks.checkpoint(state.taskId, Jsonx.encode(state), state.updatedAt); latest.value = state }
    private fun now() = Instant.now().toString()
}
