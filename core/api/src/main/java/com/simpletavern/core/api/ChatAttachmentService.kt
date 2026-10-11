package com.simpletavern.core.api

import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap
import com.simpletavern.core.data.*
import com.simpletavern.core.data.db.AttachmentEntity
import com.simpletavern.core.model.*
import com.simpletavern.core.tools.ToolResult
import java.io.FileOutputStream
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** User-selected chat inputs. Copy only; uploads happen later at the generation boundary. */
internal class ChatAttachmentService(private val store: AttachmentStore, private val chats: ChatRepository,
    private val paths: StoragePaths, private val resolver: ContentResolver) {
    suspend fun copySource(id: String): File = withContext(Dispatchers.IO) {
        val row = store.get(id) ?: throw StError.NotFound("managed attachment")
        val source = store.resolveFile(row.relativePath)
        if (!source.canonicalPath.startsWith(paths.attachments.path + File.separator))
            throw StError.Validation("attachment path is outside managed storage")
        source
    }
    suspend fun references(ids: List<String>): List<AttachmentRef> {
        if (ids.size > 20 || ids.distinct().size != ids.size) throw StError.Validation("at most 20 distinct attachment IDs")
        return ids.map { id ->
            val row = store.get(id) ?: throw StError.NotFound("attachment missing")
            AttachmentRef(row.id, row.filename, row.mimeType, row.relativePath, row.size)
        }
    }
    suspend fun read(id: String): ContextMedia {
        val ref = references(listOf(id)).single()
        return MessageAttachmentReader(store, paths).prepare(listOf(ContextMessage(ChatRole.user, "",
            attachments = listOf(ref))), allowUploads = false).single().media.single()
    }
    data class AudioFile(val file: File, val format: String, val pcmFormat: PcmPlaybackFormat?)

    /** Resolve one managed audio file without reading its full contents into memory. */
    suspend fun audioFile(id: String, requestedPcm: PcmPlaybackFormat?): AudioFile = withContext(Dispatchers.IO) {
        val row = store.get(id) ?: throw StError.NotFound("managed attachment")
        val file = copySource(id)
        if (!file.isFile || file.length() !in 1..MessageAttachmentReader.MAX_UPLOAD_FILE_BYTES)
            throw StError.Validation("audio attachment is empty, missing or exceeds playback file limit")
        val format = when (AttachmentMimeTypes.normalize(row.mimeType)) {
            "audio/wav" -> "wav"; "audio/mpeg" -> "mp3"; "audio/aac" -> "aac"; "audio/flac" -> "flac"
            "audio/opus", "audio/ogg" -> "opus"; "audio/m4a" -> "m4a"; "audio/webm" -> "webm"
            "audio/l16" -> "pcm"
            else -> throw StError.Unsupported("attachment is not a supported playback audio format")
        }
        val parameters = row.mimeType.split(';').drop(1).mapNotNull { part ->
            val pair = part.trim().split('=', limit = 2)
            if (pair.size == 2) pair[0].lowercase() to pair[1].toIntOrNull() else null
        }.toMap()
        val rate = parameters["rate"]
        val channels = parameters["channels"]
        if (format == "pcm" && requestedPcm != null &&
            (rate != null && requestedPcm.sampleRate != rate || channels != null && requestedPcm.channels != channels))
            throw StError.Validation("PCM playback format differs from attachment layout")
        val pcm = requestedPcm ?: if (rate != null && channels != null) PcmPlaybackFormat(rate, channels) else null
        if (format == "pcm") {
            val actual = pcm ?: throw StError.Speech("pcm_layout_required", "provide the actual 16-bit little-endian PCM rate and channels")
            if (actual.sampleRate <= 0 || actual.channels !in 1..2)
                throw StError.Speech("pcm_format", "PCM playback requires a positive sample rate and mono/stereo channels")
            if (file.length() % (2 * actual.channels) != 0L)
                throw StError.Speech("pcm_shape", "16-bit PCM has an incomplete frame")
        }
        AudioFile(file, format, pcm)
    }
    suspend fun importUri(chatId: String, uri: Uri, filename: String, mimeType: String): AttachmentRef {
        if (uri.scheme != "content") throw StError.Validation("attachment URI must be a user-authorized content URI")
        return import(chatId, filename, mimeType) { resolver.openInputStream(uri) ?: throw StError.NotFound("attachment input missing") }
    }
    suspend fun readCharacterCardUri(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw StError.Validation("character card must be a user-authorized content URI")
        val input = resolver.openInputStream(uri) ?: throw StError.NotFound("character card input missing")
        input.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(65_536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                if (count > com.simpletavern.core.importer.SillyTavernCardDecoder.MAX_INPUT_BYTES - output.size())
                    throw StError.Validation("character card exceeds 16 MiB")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    /** The draft holds the exact PNG parsed for preview; do not reopen the selected provider. */
    suspend fun importCharacterCardAvatar(bytes: ByteArray, filename: String, commit: suspend (AttachmentRef) -> Unit): AttachmentRef =
        copy(null, filename, "image/png", com.simpletavern.core.importer.SillyTavernCardDecoder.MAX_INPUT_BYTES.toLong(),
            { bytes.inputStream() }, commit, modelVisible = false, requireNonEmpty = true)
    suspend fun importAvatarUri(uri: Uri, filename: String, mimeType: String,
                                commit: suspend (AttachmentRef) -> Unit): AttachmentRef {
        if (uri.scheme != "content") throw StError.Validation("avatar URI must be a user-authorized content URI")
        val normalizedMime = AttachmentMimeTypes.normalize(mimeType)
        val mime = if (normalizedMime.isBlank() || normalizedMime == "application/octet-stream") {
            when (filename.substringAfterLast('.', "").lowercase()) {
                "png" -> "image/png"; "jpeg", "jpg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
                else -> throw StError.Validation("avatar must be a supported image")
            }
        } else normalizedMime
        if (mime !in AttachmentMimeTypes.images) throw StError.Validation("avatar must be a supported image")
        return copy(null, filename, mime, 16L * 1024 * 1024,
            { resolver.openInputStream(uri) ?: throw StError.NotFound("avatar input missing") }, commit,
            modelVisible = false, requireNonEmpty = true)
    }

    suspend fun avatarReference(relativePath: String): AttachmentRef {
        val row = store.findByRelativePath(relativePath) ?: throw StError.NotFound("avatar attachment missing")
        val mime = AttachmentMimeTypes.normalize(row.mimeType)
        if (mime !in AttachmentMimeTypes.images) throw StError.Validation("avatar attachment is not an image")
        return AttachmentRef(row.id, row.filename, mime, row.relativePath, row.size, modelVisible = false)
    }

    suspend fun avatarFile(relativePath: String): File = withContext(Dispatchers.IO) {
        val reference = avatarReference(relativePath)
        val file = copySource(reference.id)
        if (!file.isFile) throw StError.NotFound("avatar file missing")
        file
    }
    suspend fun import(chatId: String, filename: String, mimeType: String, open: () -> InputStream): AttachmentRef =
        copy(chatId, filename, mimeType, MessageAttachmentReader.MAX_UPLOAD_FILE_BYTES, open, {})

    suspend fun importAsset(filename: String, mimeType: String, open: () -> InputStream,
                            commit: suspend (AttachmentRef) -> Unit): AttachmentRef =
        copy(null, filename, mimeType, 8L * 1024 * 1024 * 1024, open, commit)

    /** A downloaded voice preview uses the same attachment publication as other managed files. */
    suspend fun importVoicePreview(filename: String, mimeType: String, open: () -> InputStream,
                                   onCopied: suspend (Long) -> Unit, commit: suspend (AttachmentRef) -> Unit): AttachmentRef =
        copy(null, filename, mimeType, com.simpletavern.core.tools.SpeechVoicePreviewDownloader.MAX_BYTES,
            open, commit, onCopied, modelVisible = false)

    /** Export progress and its terminal task record share the original attachment publication. */
    suspend fun importForExport(chatId: String?, filename: String, mimeType: String, open: () -> InputStream,
                               onCopied: suspend (Long) -> Unit, commit: suspend (AttachmentRef) -> Unit): AttachmentRef =
        copy(chatId, filename, mimeType, if (chatId == null) 8L * 1024 * 1024 * 1024 else MessageAttachmentReader.MAX_UPLOAD_FILE_BYTES,
            open, commit, onCopied)

    suspend fun saveToolResult(chatId: String, callId: String, result: ToolResult): MessageCandidate = withContext(Dispatchers.IO) {
        val at = Instant.now().toString()
        val candidateId = UUID.randomUUID().toString().replace("-", "")
        val owned = mutableListOf<File>()
        val refs = mutableListOf<AttachmentRef>()
        var committed = false
        try {
            for (media in result.media) {
                currentCoroutineContext().ensureActive()
                val id = UUID.randomUUID().toString()
                val relative = "tool_$id"
                val file = paths.attachmentFile(relative)
                if (!file.createNewFile()) throw StError.Conflict("tool attachment file already exists")
                owned += file
                FileOutputStream(file).use { output ->
                    var offset = 0
                    while (offset < media.bytes.size) {
                        currentCoroutineContext().ensureActive()
                        val count = minOf(65_536, media.bytes.size - offset)
                        output.write(media.bytes, offset, count); offset += count
                    }
                    output.fd.sync()
                }
                refs += AttachmentRef(id, media.filename, media.mimeType, relative, media.bytes.size.toLong(), modelVisible = media.modelVisible)
            }
            val details = JsonArray(refs.map { ref -> buildJsonObject {
                put("id", ref.id); put("filename", ref.filename); put("mimeType", ref.mimeType); put("bytes", ref.size)
                put("modelVisible", ref.modelVisible)
            } })
            fun withAttachments(value: String): String {
                val data = Jsonx.decodeOrNull<JsonObject>(value) ?: buildJsonObject { put("text", value) }
                return JsonObject(data + ("attachments" to details)).toString()
            }
            val output = if (refs.isEmpty()) result.output else withAttachments(result.output)
            val record = if (refs.isEmpty()) result.recordJson else withAttachments(result.recordJson)
            val saved = MessageCandidate(candidateId, ChatRole.tool, output, toolCallId = callId, toolRecordJson = record,
                images = refs.filter { it.mimeType.startsWith("image/") }, attachments = refs.filterNot { it.mimeType.startsWith("image/") }, createdAt = at)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                chats.transaction {
                    refs.forEach { ref -> store.upsert(AttachmentEntity(ref.id, ref.relativePath, ref.filename, ref.mimeType,
                        ref.size, chatId, candidateId, null, at)) }
                    chats.appendNode(chatId, saved)
                }
                committed = true
            }
            saved
        } finally { if (!committed) owned.forEach { it.delete() } }
    }

    suspend fun saveGeneratedMedia(chatId: String, expected: MessageCandidate, media: List<ToolMedia>,
                                 turn: ProviderTurn, onCommitted: (MessageCandidate, ProviderTurn) -> Unit) = withContext(Dispatchers.IO) {
        val owned = mutableListOf<File>()
        val refs = mutableListOf<AttachmentRef>()
        var committed = false
        try {
            for (item in media) {
                currentCoroutineContext().ensureActive()
                val id = UUID.randomUUID().toString()
                val relative = "generated_$id"
                val file = paths.attachmentFile(relative)
                if (!file.createNewFile()) throw StError.Conflict("generated attachment file already exists")
                owned += file
                FileOutputStream(file).use { output -> output.write(item.bytes); output.fd.sync() }
                refs += AttachmentRef(id, item.filename, item.mimeType, relative, item.bytes.size.toLong(), modelVisible = item.modelVisible)
            }
            val bound = turn.copy(mediaAttachmentIds = refs.map { it.id })
            val metadata = Jsonx.decode<GenerationMetadata>(expected.generationMetadataJson!!).copy(providerTurn = bound)
            val next = expected.copy(images = expected.images + refs.filter { it.mimeType.startsWith("image/") },
                attachments = expected.attachments + refs.filterNot { it.mimeType.startsWith("image/") },
                generationMetadataJson = Jsonx.encode(metadata))
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                chats.transaction {
                    refs.forEach { ref -> store.upsert(AttachmentEntity(ref.id, ref.relativePath, ref.filename, ref.mimeType,
                        ref.size, chatId, expected.id, null, Instant.now().toString())) }
                    chats.updateCandidate(chatId, expected, next)
                }
                committed = true
                onCommitted(next, bound)
            }
        } finally { if (!committed) owned.forEach { it.delete() } }
    }

    private suspend fun copy(chatId: String?, filename: String, mimeType: String, maxBytes: Long,
                             open: () -> InputStream, commit: suspend (AttachmentRef) -> Unit,
                             onCopied: suspend (Long) -> Unit = {}, modelVisible: Boolean = true,
                             requireNonEmpty: Boolean = false): AttachmentRef = withContext(Dispatchers.IO) {
        if (filename.isBlank() || filename.length > 255 || filename.any(Char::isISOControl)) throw StError.Validation("invalid attachment filename")
        if (chatId != null && chats.getSession(chatId) == null) throw StError.NotFound("attachment chat missing")
        val mime = AttachmentMimeTypes.normalize(mimeType).takeUnless { it.isBlank() || it == "application/octet-stream" }
            ?: AttachmentMimeTypes.fromFilename(filename)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(filename.substringAfterLast('.', "").lowercase())
                ?.let(AttachmentMimeTypes::normalize) ?: "application/octet-stream"
        if (!mime.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+"))) throw StError.Validation("invalid attachment MIME type")
        val id = UUID.randomUUID().toString()
        val relative = "chat_$id"
        val file = paths.attachmentFile(relative)
        var created = false
        var committed = false
        try {
            if (!file.createNewFile()) throw StError.Conflict("attachment file already exists")
            created = true
            var bytes = 0L
            open().use { input -> FileOutputStream(file).use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    if (bytes > maxBytes) throw StError.Validation("attachment exceeds copy size limit")
                    output.write(buffer, 0, count)
                    onCopied(bytes)
                }
                if (bytes == 0L && (chatId != null || requireNonEmpty)) throw StError.Validation("attachment is empty")
                output.fd.sync()
            } }
            currentCoroutineContext().ensureActive()
            val ref = AttachmentRef(id, filename, mime, relative, bytes, modelVisible = modelVisible)
            withContext(NonCancellable) {
                chats.transaction {
                    store.upsert(AttachmentEntity(id, relative, filename, mime, bytes, chatId, null, null, Instant.now().toString()))
                    commit(ref)
                }
                committed = true
            }
            ref
        } finally {
            if (created && !committed && file.exists()) file.delete()
        }
    }
}
