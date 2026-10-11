package com.simpletavern.core.api

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import com.simpletavern.core.model.StError
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

/** Shared provider adapter for explicit exports; no path conversion, permissions, replay or rollback. */
internal class SafDocumentOutput(private val resolver: ContentResolver) {
    suspend fun write(input: InputStream, destination: Uri, maxBytes: Long,
        copied: suspend (Long) -> Unit, closed: () -> Unit) = withContext(Dispatchers.IO) { coroutineScope {
        val target = destination.toString()
        if (!writing.add(target)) throw StError.Conflict("another export is writing this document")
        val owner = currentCoroutineContext().job
        val signal = CancellationSignal()
        val descriptor = AtomicReference<ParcelFileDescriptor?>(null)
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (owner.isCancelled) {
                runCatching { signal.cancel() }
                runCatching { descriptor.get()?.closeWithError("export cancelled") }
            } }
        }
        try {
            currentCoroutineContext().ensureActive()
            val outputDescriptor = resolver.openFileDescriptor(destination, "wt", signal)
                ?: throw StError.Validation("document provider unavailable")
            descriptor.set(outputDescriptor)
            try {
                currentCoroutineContext().ensureActive()
                ParcelFileDescriptor.AutoCloseOutputStream(outputDescriptor).use { output ->
                    try {
                        val buffer = ByteArray(65_536)
                        var bytes = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (bytes > maxBytes - count) throw StError.Validation("document export exceeds its byte limit")
                            output.write(buffer, 0, count)
                            bytes += count
                            copied(bytes)
                        }
                        output.flush()
                        if (outputDescriptor.canDetectErrors()) outputDescriptor.checkError()
                    } catch (error: Throwable) {
                        runCatching { outputDescriptor.closeWithError("export did not complete") }
                        throw error
                    }
                }
                closed() // Capture normal close before a cancellable dispatch back to the owning task.
            } finally { descriptor.set(null); outputDescriptor.close() }
        } finally { cancellation.cancel(); writing.remove(target) }
    } }

    companion object {
        private val writing = ConcurrentHashMap.newKeySet<String>()
        fun validate(destination: Uri) {
            if (destination.scheme != ContentResolver.SCHEME_CONTENT || destination.authority.isNullOrBlank() ||
                destination.toString().toByteArray(Charsets.UTF_8).size > 8192)
                throw StError.Validation("export destination must be a user-authorized content document URI")
        }
    }
}
