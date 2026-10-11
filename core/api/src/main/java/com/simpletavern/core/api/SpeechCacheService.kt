package com.simpletavern.core.api

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.room.withTransaction
import com.simpletavern.core.data.*
import com.simpletavern.core.data.db.*
import com.simpletavern.core.model.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Disposable, UUID-addressed audio. Task payload is the catalog; attachment rows are commit authority. */
internal class SpeechCacheService(private val db: AppDatabase, private val paths: StoragePaths, private val tasks: TaskStore) {
    private val lock = Mutex()
    private val playbackPins = mutableMapOf<String, Int>()
    private fun pinned(id: String) = synchronized(playbackPins) { (playbackPins[id] ?: 0) > 0 }
    internal class PlaybackFile(val asset: SpeechAsset, val file: File, private val release: () -> Unit) : AutoCloseable {
        override fun close() = release()
    }
    internal data class Stage(val file: File, val device: Long, val inode: Long)

    private fun id(id: String) { if (!id.matches(Regex("[0-9a-f]{32}"))) throw StError.Validation("invalid speech asset identity") }
    private fun identity(value: String) {
        if (value.isBlank() || value.toByteArray(Charsets.UTF_8).size > 1024 || value.any(Char::isISOControl))
            throw StError.Validation("invalid speech message identity")
    }
    private fun roots() {
        for (root in listOf(paths.root, paths.attachments, paths.speechStaging)) {
            if (!OsConstants.S_ISDIR(Os.lstat(root.path).st_mode) || root.canonicalFile != root.absoluteFile)
                throw StError.Validation("speech storage root is redirected")
        }
    }
    private fun path(asset: SpeechAsset): File {
        id(asset.id)
        if (asset.version !in 1..2 || asset.format !in setOf("mp3", "wav", "pcm", "opus", "flac", "aac") || asset.bytes !in 1..16_777_216)
            throw StError.Validation("invalid speech asset metadata")
        return paths.attachments.resolve("tts-${asset.id}.${asset.format}")
    }
    private fun stagePath(id: String): File { id(id); return paths.speechStaging.resolve("$id.part") }
    private fun stat(file: File) = try { Os.lstat(file.path) } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) null else throw error
    }
    private fun sync(root: File) {
        val fd = Os.open(root.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        try { if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw StError.Conflict("speech sync target is not a directory"); Os.fsync(fd) } finally { Os.close(fd) }
    }
    suspend fun progress(id: String): SpeechTaskProgress = withContext(Dispatchers.IO) { db.withTransaction {
        id(id)
        val bytes = db.tasks().speechPayloadBytes(id) ?: throw StError.NotFound("speech task missing")
        if (bytes > SpeechTaskMetadata.MAX_PAYLOAD_BYTES) throw StError.Validation("speech task payload exceeds 128 KiB")
        val raw = tasks.payloadJson(id) ?: throw StError.NotFound("speech task progress missing")
        NativeJsonInput.parse(raw)
        val value = Jsonx.decodeOrNull<SpeechTaskProgress>(raw) ?: throw StError.Validation("invalid speech progress")
        SpeechTaskMetadata.validate(value)
        if (value.version != 1 || value.taskId != id || value.selection.cacheLimitBytes !in 10_485_760..10_485_760_000)
            throw StError.Validation("invalid speech progress version/identity/cache limit")
        value.asset?.let { asset ->
            if (asset.id != id || asset.assistantId != value.selection.assistantId || asset.messageCandidateId != value.messageCandidateId ||
                asset.provider != value.selection.provider || asset.format != value.selection.format)
                throw StError.Validation("speech asset identity/scope mismatch")
            path(asset)
        }
        value
    } }

    suspend fun requireMessage(chatId: String, candidateId: String, spokenText: String): SpeechMessageRow = db.withTransaction {
        identity(chatId); identity(candidateId)
        val bytes = db.messages().speechMessageBytes(candidateId, chatId) ?: throw StError.NotFound("speech message missing")
        if (bytes > 262_144) throw StError.Validation("speech message metadata exceeds 256 KiB")
        val message = db.messages().speechMessage(candidateId, chatId) ?: throw StError.NotFound("speech message missing")
        if (message.content != spokenText) throw StError.Conflict("speech message text changed; do not bind stale audio")
        message.generationMetadataJson?.let {
            val meta = NativeJsonInput.parse(it) as? JsonObject ?: throw StError.Validation("speech message generation metadata must be an object")
            if ((meta["status"] as? JsonPrimitive)?.contentOrNull in setOf("running", "awaiting_tools"))
                throw StError.Conflict("wait for the target message generation to finish before binding speech")
        }
        message.extraJson?.let { if (NativeJsonInput.parse(it) !is JsonObject) throw StError.Validation("speech message extraJson must be an object") }
        message
    }

    /** Read-only after preprocessing; publication repeats these checks because edits remain allowed. */
    suspend fun preflightSynthesis(state: SpeechTaskProgress, chatId: String?, originalText: String, spokenText: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            SpeechTaskMetadata.validate(state)
            chatId?.let { requireSource(state.selection.sourceNamespace, it) }
            state.messageCandidateId?.let { candidate ->
                val chat = chatId ?: throw StError.Validation("speech binding needs chat")
                val current = requireMessage(chat, candidate, originalText)
                val desired = SpeechMessageBinding(state.taskId, spokenText, version = 2, messageText = originalText)
                SpeechTaskMetadata.validateBinding(desired)
                bindingJson(current, extras(current) + ("_tts" to Jsonx.json.parseToJsonElement(Jsonx.encode(desired))))
            }
            Unit
        }
    }

    suspend fun stage(asset: SpeechAsset, audio: ByteArray, owned: (Stage) -> Unit): Stage = withContext(Dispatchers.IO) {
        roots(); path(asset)
        if (audio.size.toLong() != asset.bytes) throw StError.Validation("speech audio size changed")
        val file = stagePath(asset.id)
        val fd = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
        val own = try { Os.fstat(fd).let { Stage(file, it.st_dev, it.st_ino) } }
        catch (error: Throwable) { try { Os.close(fd) } catch (_: Exception) { }; throw error }
        owned(own) // worker owns partial writes as well, even when write/first cleanup throws.
        try {
            FileOutputStream(fd).use { output ->
                var at = 0
                while (at < audio.size) { currentCoroutineContext().ensureActive(); val count = minOf(65_536, audio.size - at); output.write(audio, at, count); at += count }
                output.flush(); Os.fsync(output.fd)
            }
            sync(paths.speechStaging)
            own
        } catch (error: Throwable) {
            withContext(NonCancellable) { discardStage(own) }
            throw error
        }
    }
    private fun discardStage(stage: Stage) {
        roots()
        val found = stat(stage.file) ?: return
        if (!OsConstants.S_ISREG(found.st_mode) || found.st_dev != stage.device || found.st_ino != stage.inode)
            throw StError.Conflict("speech staging identity changed")
        Os.remove(stage.file.path); sync(paths.speechStaging)
    }

    /** Preflight/pruning remains cancellable; decision protects only the final commit against a racing stop. */
    suspend fun publish(state: SpeechTaskProgress, stage: Stage, spokenText: String,
        decision: suspend (suspend () -> Unit) -> Unit, finish: suspend (SpeechTaskProgress) -> Unit) = withContext(Dispatchers.IO) { lock.withLock {
        roots()
        val asset = state.asset ?: throw StError.Validation("speech asset missing")
        val output = path(asset)
        val found = stat(stage.file) ?: throw StError.NotFound("speech staging missing")
        if (!OsConstants.S_ISREG(found.st_mode) || found.st_dev != stage.device || found.st_ino != stage.inode || found.st_size != asset.bytes)
            throw StError.Conflict("speech staging changed before publication")
        if (asset.bytes > state.selection.cacheLimitBytes) throw StError.Speech("tts_cache_capacity", "speech audio exceeds cache limit")
        val existing = inventory(state.selection.assistantId).sortedWith(compareBy<SpeechTaskProgress> { java.time.Instant.parse(it.asset!!.createdAt) }.thenBy { it.taskId })
        var bytes = existing.sumOf { it.asset!!.bytes }
        val targets = mutableListOf<SpeechTaskProgress>()
        for (old in existing) {
            if (bytes + asset.bytes <= state.selection.cacheLimitBytes) break
            if (pinned(old.taskId)) continue
            targets += old; bytes -= old.asset!!.bytes
        }
        if (bytes + asset.bytes > state.selection.cacheLimitBytes)
            throw StError.Speech("tts_cache_capacity", "active playlist audio leaves insufficient cache capacity")
        targets.forEach { currentCoroutineContext().ensureActive(); validFile(it.asset!!) }
        for (old in targets) { currentCoroutineContext().ensureActive(); remove(old) }
        currentCoroutineContext().ensureActive()
        decision { db.withTransaction {
            roots()
            val ready = stat(stage.file) ?: throw StError.NotFound("speech staging missing before link")
            if (!OsConstants.S_ISREG(ready.st_mode) || ready.st_dev != stage.device || ready.st_ino != stage.inode || ready.st_size != asset.bytes)
                throw StError.Conflict("speech staging changed before link")
            if (db.attachments().get("tts:${asset.id}") != null || stat(output) != null) throw StError.Conflict("speech asset already exists")
            asset.chatId?.let { requireSource(asset, it) }
            val message = asset.messageCandidateId?.let { requireMessage(asset.chatId ?: throw StError.Validation("speech binding needs chat"), it, spokenText) }
            Os.link(stage.file.path, output.path); sync(paths.attachments)
            val linked = stat(output) ?: throw StError.Conflict("speech publication missing")
            if (!OsConstants.S_ISREG(linked.st_mode) || linked.st_dev != stage.device || linked.st_ino != stage.inode || linked.st_size != asset.bytes)
                throw StError.Conflict("speech publication identity changed")
            val attached = AttachmentEntity("tts:${asset.id}", output.name, output.name, mime(asset.format), asset.bytes,
                asset.chatId, asset.messageCandidateId, null, asset.createdAt)
            db.attachments().upsert(attached)
            if (message != null) {
                val old = message.extraJson?.let { NativeJsonInput.parse(it) as JsonObject } ?: JsonObject(emptyMap())
                val binding = SpeechMessageBinding(asset.id, asset.spokenText ?: spokenText, version = 2, messageText = spokenText)
                SpeechTaskMetadata.validateBinding(binding)
                saveBinding(asset.chatId!!, asset.messageCandidateId!!, message,
                    old + ("_tts" to Jsonx.json.parseToJsonElement(Jsonx.encode(binding))))
            }
            finish(state.copy(phase = "done"))
        } }
    } }

    suspend fun cleanup(state: SpeechTaskProgress, stage: Stage?) = withContext(Dispatchers.IO) { lock.withLock {
        roots()
        if (stage == null && stat(stagePath(state.taskId)) != null)
            throw StError.Conflict("speech stage ownership is unavailable; process-start cleanup required")
        val asset = state.asset
        if (asset != null && state.device != null && state.inode != null && db.attachments().get("tts:${asset.id}") == null) {
            val output = path(asset)
            val found = stat(output)
            if (found != null) {
                if (!OsConstants.S_ISREG(found.st_mode) || found.st_dev != state.device || found.st_ino != state.inode || found.st_size != asset.bytes)
                    throw StError.Conflict("uncommitted speech publication changed; cleanup refused")
                Os.remove(output.path); sync(paths.attachments)
            }
        }
        if (stage != null) discardStage(stage)
    } }

    private suspend fun inventory(assistantId: String?): List<SpeechTaskProgress> {
        if (assistantId != null && (assistantId.isBlank() || assistantId.toByteArray(Charsets.UTF_8).size > 1024 || assistantId.any(Char::isISOControl)))
            throw StError.Validation("invalid speech cache scope")
        val list = mutableListOf<SpeechTaskProgress>()
        var after = ""; var scanned = 0; var scannedBytes = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = db.tasks().speechAssetsPage(after, 200)
            if (page.isEmpty()) break
            for (row in page) {
                val payloadBytes = row.payloadBytes ?: throw StError.Validation("speech catalog has no payload")
                if (++scanned > 100_000 || payloadBytes > SpeechTaskMetadata.MAX_PAYLOAD_BYTES) throw StError.Validation("speech catalog exceeds read budget")
                scannedBytes += payloadBytes
                if (scannedBytes > 67_108_864) throw StError.Validation("speech catalog exceeds aggregate budget")
                val value = progress(row.id)
                val asset = value.asset ?: throw StError.Validation("successful speech catalog row has no audio")
                if (value.phase != "done") throw StError.Validation("successful speech catalog row is incomplete")
                if (value.selection.assistantId == assistantId) { attachment(asset); list += value }
            }
            after = page.last().id
        }
        return list
    }
    private suspend fun attachment(asset: SpeechAsset): AttachmentEntity {
        val file = path(asset)
        val row = db.attachments().get("tts:${asset.id}") ?: throw StError.NotFound("speech audio evicted or absent")
        if (row.relativePath != file.name || row.filename != file.name || row.mimeType != mime(asset.format) || row.size != asset.bytes ||
            row.chatId != asset.chatId || row.messageCandidateId != asset.messageCandidateId || row.sourceUri != null)
            throw StError.Conflict("speech attachment metadata changed")
        return row
    }
    private fun validFile(asset: SpeechAsset): Boolean {
        val found = stat(path(asset)) ?: return false
        if (!OsConstants.S_ISREG(found.st_mode) || found.st_size != asset.bytes) throw StError.Conflict("speech cache file type/size changed")
        return true
    }
    private suspend fun remove(state: SpeechTaskProgress) {
        val asset = state.asset!!; val row = attachment(asset); val file = path(asset)
        if (validFile(asset)) { Os.remove(file.path); sync(paths.attachments) }
        if (db.attachments().deleteSpeech(row.id, row.relativePath) != 1) throw StError.Conflict("speech cache catalog changed")
    }
    suspend fun stats(assistantId: String?): SpeechCacheStats = withContext(Dispatchers.IO) { lock.withLock {
        roots(); val assets = inventory(assistantId)
        SpeechCacheStats(assistantId, assets.size.toLong(), assets.sumOf { it.asset!!.bytes }, assets.count { !validFile(it.asset!!) }.toLong())
    } }
    suspend fun clear(assistantId: String?): SpeechCacheClearResult = withContext(Dispatchers.IO) { lock.withLock {
        roots(); val assets = inventory(assistantId)
        // Validate the complete target set before the first unlink; no unknown/user attachment is pruned.
        val targets = assets.filterNot { pinned(it.taskId) }
        targets.forEach { validFile(it.asset!!) }
        var files = 0L; var bytes = 0L
        for (value in targets) { currentCoroutineContext().ensureActive(); remove(value); files++; bytes += value.asset!!.bytes }
        SpeechCacheClearResult(files, bytes, (assets.size - targets.size).toLong())
    } }
    private suspend fun availableAsset(id: String): SpeechAsset {
        roots(); val state = progress(id)
        val asset = state.asset ?: throw StError.NotFound("speech audio not published")
        if (tasks.get(id)?.status != TaskStatus.SUCCEEDED || state.phase != "done") throw StError.NotFound("speech task did not complete")
        attachment(asset)
        if (!validFile(asset)) throw StError.NotFound("speech audio file missing")
        return asset
    }
    suspend fun asset(id: String): SpeechAsset = withContext(Dispatchers.IO) { lock.withLock { availableAsset(id) } }
    /** Hold queued files against cache pruning without reading or copying their audio bytes. */
    suspend fun pinForPlayback(id: String): PlaybackFile = withContext(Dispatchers.IO) { lock.withLock {
        val asset = availableAsset(id)
        synchronized(playbackPins) { playbackPins[id] = (playbackPins[id] ?: 0) + 1 }
        var released = false
        PlaybackFile(asset, path(asset)) { synchronized(playbackPins) {
            if (!released) {
                released = true
                val count = playbackPins.getValue(id) - 1
                if (count == 0) playbackPins.remove(id) else playbackPins[id] = count
            }
        } }
    } }
    suspend fun read(id: String): SpeechAudio = withContext(Dispatchers.IO) { lock.withLock {
        roots(); val state = progress(id); val asset = state.asset ?: throw StError.NotFound("speech audio not published")
        if (tasks.get(id)?.status != TaskStatus.SUCCEEDED || state.phase != "done") throw StError.NotFound("speech task did not complete")
        attachment(asset)
        val file = path(asset)
        if (!validFile(asset)) throw StError.NotFound("speech audio file missing")
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        FileInputStream(fd).use { input ->
            val found = Os.fstat(input.fd)
            if (!OsConstants.S_ISREG(found.st_mode) || found.st_size != asset.bytes) throw StError.Conflict("speech cache file changed")
            val audio = ByteArray(asset.bytes.toInt()); var at = 0
            while (at < audio.size) { currentCoroutineContext().ensureActive(); val n = input.read(audio, at, minOf(65_536, audio.size - at)); if (n < 0) throw StError.Conflict("speech audio truncated"); at += n }
            if (input.read() != -1) throw StError.Conflict("speech audio grew")
            SpeechAudio(asset.provider, asset.model, asset.voiceId, asset.format, asset.sampleRate, audio,
                asset.reportedUsageCharacters, asset.warnings, asset.requestId, asset.spokenText, asset.preprocessingRequestId)
        }
    } }
    private suspend fun message(chatId: String, candidateId: String): SpeechMessageRow {
        identity(chatId); identity(candidateId)
        val bytes = db.messages().speechMessageBytes(candidateId, chatId) ?: throw StError.NotFound("speech message missing")
        if (bytes > 262_144) throw StError.Validation("speech message exceeds read budget")
        return db.messages().speechMessage(candidateId, chatId) ?: throw StError.NotFound("speech message missing")
    }
    private fun extras(message: SpeechMessageRow) = message.extraJson?.let {
        NativeJsonInput.parse(it) as? JsonObject ?: throw StError.Validation("invalid speech message extras")
    } ?: JsonObject(emptyMap())
    private fun binding(root: JsonObject): SpeechMessageBinding? {
        val raw = root["_tts"]?.takeIf { it != JsonNull } ?: return null
        val value = Jsonx.decodeOrNull<SpeechMessageBinding>(raw.toString()) ?: throw StError.Validation("invalid speech binding")
        SpeechTaskMetadata.validateBinding(value)
        return value
    }
    private suspend fun requireSource(asset: SpeechAsset, chatId: String) {
        val source = if (asset.version == 2) asset.sourceNamespace else {
            val originalChat = asset.chatId ?: throw StError.Unsupported("legacy audio has no durable source scope")
            if (db.chats().speechOverridesBytes(originalChat) == null) throw StError.Unsupported("legacy audio source chat is unavailable")
            db.chats().speechSource(originalChat)
        }
        requireSource(source, chatId)
    }
    private suspend fun requireSource(source: String?, chatId: String) {
        identity(chatId)
        if (db.chats().speechOverridesBytes(chatId) == null) throw StError.NotFound("speech target chat missing")
        if (source != db.chats().speechSource(chatId)) throw StError.Conflict("speech asset belongs to another source")
    }
    suspend fun messageBinding(chatId: String, candidateId: String): SpeechMessageBinding? = withContext(Dispatchers.IO) {
        db.withTransaction { binding(extras(message(chatId, candidateId))) } // include stale bindings so callers can explicitly replace/remove them.
    }
    suspend fun bind(request: SpeechBindRequest): SpeechMessageBinding = withContext(Dispatchers.IO) { lock.withLock {
        id(request.assetId); request.expectedAssetId?.let(::id)
        SpeechText.validate(request.expectedMessageContent, 131_072)
        db.withTransaction {
            val asset = availableAsset(request.assetId)
            requireSource(asset, request.chatId)
            val spoken = asset.spokenText ?: run {
                // A v1 asset can prove its transcript only through its original persisted message binding.
                val originalChat = asset.chatId ?: throw StError.Speech("tts_transcript_unavailable", "legacy audio has no persisted transcript")
                val originalCandidate = asset.messageCandidateId ?: throw StError.Speech("tts_transcript_unavailable", "legacy audio has no persisted transcript")
                val old = binding(extras(message(originalChat, originalCandidate)))
                old?.takeIf { it.assetId == asset.id }?.sourceText ?: throw StError.Speech("tts_transcript_unavailable", "legacy audio transcript is unavailable")
            }
            val current = requireMessage(request.chatId, request.messageCandidateId, request.expectedMessageContent)
            val root = extras(current)
            val previous = binding(root)
            val desired = SpeechMessageBinding(asset.id, spoken, version = 2, messageText = request.expectedMessageContent)
            SpeechTaskMetadata.validateBinding(desired)
            if (previous?.assetId == desired.assetId && previous.sourceText == desired.sourceText &&
                (previous.messageText ?: previous.sourceText) == desired.messageText) return@withTransaction previous
            if (previous?.assetId != request.expectedAssetId) throw StError.Conflict("speech binding changed; refresh the expected asset before replacing")
            saveBinding(request.chatId, request.messageCandidateId, current, root + ("_tts" to Jsonx.json.parseToJsonElement(Jsonx.encode(desired))))
            desired
        }
    } }
    suspend fun unbind(chatId: String, candidateId: String, expectedAssetId: String): Boolean = withContext(Dispatchers.IO) { lock.withLock {
        id(expectedAssetId)
        db.withTransaction {
            val current = message(chatId, candidateId)
            val root = extras(current)
            val previous = binding(root) ?: return@withTransaction false
            if (previous.assetId != expectedAssetId) throw StError.Conflict("speech binding changed; refusing to remove another audio reference")
            requireMessage(chatId, candidateId, current.content)
            saveBinding(chatId, candidateId, current, root - "_tts")
            true // remove only this reference, not shared cache bytes, other messages, or historical tasks.
        }
    } }
    private fun bindingJson(current: SpeechMessageRow, fields: Map<String, JsonElement>): String {
        val next = JsonObject(fields).toString()
        val bytes = next.toByteArray(Charsets.UTF_8).size.toLong() + current.content.toByteArray(Charsets.UTF_8).size +
            (current.generationMetadataJson?.toByteArray(Charsets.UTF_8)?.size ?: 0)
        if (bytes > 262_144) throw StError.Validation("speech message binding exceeds aggregate budget")
        return next
    }
    private suspend fun saveBinding(chatId: String, candidateId: String, current: SpeechMessageRow, fields: Map<String, JsonElement>) {
        val next = bindingJson(current, fields)
        if (db.messages().bindSpeech(candidateId, chatId, current.content, current.extraJson, next) != 1)
            throw StError.Conflict("speech message changed before binding commit")
    }
    suspend fun forMessage(chatId: String, candidateId: String): SpeechAsset? = withContext(Dispatchers.IO) { lock.withLock { db.withTransaction {
        val current = message(chatId, candidateId)
        val binding = binding(extras(current)) ?: return@withTransaction null
        if ((binding.messageText ?: binding.sourceText) != current.content) return@withTransaction null
        try {
            val asset = availableAsset(binding.assetId)
            if (asset.version == 2) { requireSource(asset, chatId); if (asset.spokenText != binding.sourceText) throw StError.Conflict("speech binding transcript differs from its asset") }
            asset
        } catch (_: StError.NotFound) { null }
    } } }
    suspend fun recover() = withContext(Dispatchers.IO) {
        roots(); var after = ""; var count = 0
        while (true) {
            val page = db.tasks().speechTasksPage(after, 200)
            if (page.isEmpty()) break
            for (row in page) {
                currentCoroutineContext().ensureActive()
                val payloadBytes = row.payloadBytes ?: throw StError.Validation("speech recovery has no payload")
                if (++count > 100_000 || payloadBytes > SpeechTaskMetadata.MAX_PAYLOAD_BYTES) throw StError.Validation("speech recovery catalog exceeds budget")
                val state = progress(row.id)
                val file = stagePath(row.id)
                val found = stat(file)
                val stage = found?.let {
                    if (!OsConstants.S_ISREG(it.st_mode)) throw StError.Conflict("speech staging is not a regular file")
                    if (state.inode != null && (state.inode != it.st_ino || state.device != it.st_dev)) throw StError.Conflict("speech staging journal mismatch")
                    Stage(file, it.st_dev, it.st_ino)
                }
                cleanup(state, stage) // committed audio stays; uncommitted links/staging only, never synthesize again.
                tasks.transaction {
                    val task = tasks.get(row.id) ?: throw StError.NotFound("speech recovery task missing")
                    val terminal = if (task.status.isTerminal) task else task.copy(status = TaskStatus.INTERRUPTED,
                        terminalReason = TaskTerminalReason.PROCESS_KILLED, updatedAt = java.time.Instant.now().toString())
                    val clean = state.copy(device = null, inode = null,
                        phase = if (terminal.status == TaskStatus.SUCCEEDED) "done" else terminal.status.name.lowercase(), updatedAt = terminal.updatedAt)
                    tasks.upsert(terminal.copy(resultSummary = if (terminal.status == TaskStatus.SUCCEEDED) "speech audio published" else "speech ${clean.phase}"), Jsonx.encode(clean))
                }
            }
            after = page.last().id
        }
    }
    private fun mime(format: String) = when (format) { "mp3" -> "audio/mpeg"; "pcm" -> "audio/pcm"; "opus" -> "audio/ogg"; else -> "audio/$format" }
}
