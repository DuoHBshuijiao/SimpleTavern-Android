package com.simpletavern.core.data

import android.system.Os
import android.system.OsConstants
import android.util.Base64
import com.simpletavern.core.model.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.*

/** Read managed attachment IDs once per selected context; no source URI, remote URL or path from a message is opened. */
class MessageAttachmentReader(private val store: AttachmentStore, private val paths: StoragePaths) {
    suspend fun prepare(messages: List<ContextMessage>, allowUploads: Boolean = true): List<ContextMessage> = withContext(Dispatchers.IO) {
        val resolved = mutableMapOf<String, ContextMedia>()
        var bytes = 0L
        var parts = 0
        var requestBytes = 0L
        var uploadBytes = 0L
        messages.map { message ->
            val media = message.attachments.mapIndexed { index, ref ->
                if (++parts > 20) throw StError.Validation("context has more than 20 attachment occurrences")
                val value = resolved.getOrPutSuspend(ref.id) {
                message.media.getOrNull(index)?.let { cached ->
                    if (cached.uploadFile == null) bytes += cached.byteSize
                    if (bytes > MAX_CONTEXT_BYTES) throw StError.Validation("attachment context exceeds input size limit")
                    return@getOrPutSuspend cached
                }
                val row = store.get(ref.id) ?: throw StError.NotFound("message attachment is missing")
                val mime = AttachmentMimeTypes.normalize(row.mimeType)
                val textual = AttachmentMimeTypes.isText(mime)
                if (!textual && !AttachmentMimeTypes.isBinary(mime))
                    throw StError.Unsupported("unsupported attachment MIME type: $mime")
                val file = store.resolveFile(row.relativePath)
                if (!file.canonicalPath.startsWith(paths.attachments.path + File.separator))
                    throw StError.Validation("attachment path is outside managed storage")
                val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
                val content = FileInputStream(fd).use { input ->
                    val stat = Os.fstat(fd)
                    val limit = if (textual) 1_048_576 else MAX_FILE_BYTES
                    if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size !in 1..MAX_UPLOAD_FILE_BYTES)
                        throw StError.Validation("attachment is empty, not regular or exceeds input size limit")
                    val needsUpload = stat.st_size > limit || mime in AttachmentMimeTypes.documents && mime != "application/pdf"
                    if (needsUpload) {
                        if (!allowUploads) throw StError.Unsupported("attachment requires a file upload or file copy; not an inline content read")
                        return@getOrPutSuspend ContextMedia(row.filename, mime, byteSize = stat.st_size.toInt(),
                            sourceMimeType = row.mimeType.takeIf { it != mime }, attachmentId = row.id, uploadFile = file)
                    }
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(65536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytes += read
                        if (output.size().toLong() + read > limit || bytes > MAX_CONTEXT_BYTES)
                            throw StError.Validation("attachment context exceeds input size limit")
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                if (textual) {
                    val text = runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString() }
                        .getOrElse { throw StError.Validation("text attachment must be UTF-8") }
                    ContextMedia(row.filename, mime, text = text.removePrefix("\uFEFF"), byteSize = content.size, attachmentId = row.id)
                } else ContextMedia(row.filename, mime, base64 = Base64.encodeToString(content, Base64.NO_WRAP), byteSize = content.size,
                    sourceMimeType = row.mimeType.takeIf { it != mime }, attachmentId = row.id)
                }
                if (value.uploadFile != null) {
                    uploadBytes += value.byteSize
                    if (uploadBytes > MAX_CONTEXT_UPLOAD_BYTES) throw StError.Validation("file upload context exceeds 512 MiB")
                }
                requestBytes += value.base64?.length?.toLong() ?: value.text.orEmpty().toByteArray(Charsets.UTF_8).size.toLong()
                if (requestBytes > 16_777_216) throw StError.Validation("encoded attachment context exceeds 16 MiB")
                value
            }
            message.copy(media = media)
        }
    }

    private suspend fun MutableMap<String, ContextMedia>.getOrPutSuspend(id: String, read: suspend () -> ContextMedia): ContextMedia {
        return get(id) ?: read().also { put(id, it) }
    }
    companion object {
        const val MAX_FILE_BYTES = 6_291_456
        const val MAX_CONTEXT_BYTES = 12_582_912L
        const val MAX_UPLOAD_FILE_BYTES = 256L * 1024 * 1024
        const val MAX_CONTEXT_UPLOAD_BYTES = 512L * 1024 * 1024
    }
}
