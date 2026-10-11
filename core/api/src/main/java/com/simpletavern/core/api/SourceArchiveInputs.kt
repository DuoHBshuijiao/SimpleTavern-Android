package com.simpletavern.core.api

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.simpletavern.core.data.DataOperationGate
import com.simpletavern.core.data.StoragePaths
import com.simpletavern.core.data.TaskStore
import com.simpletavern.core.model.StError
import com.simpletavern.core.model.TaskKind
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** Owns the open source, not its pathname/grant. Private snapshots are never execution checkpoints. */
internal class SourceArchiveInput(val fd: FileDescriptor, private val owner: AutoCloseable,
                                val offset: Long, val declaredLength: Long?, private val checkProvider: () -> Unit = {}) : AutoCloseable {
    val before: StructStat = Os.fstat(fd)
    private var closed = false
    @Synchronized override fun close() { if (!closed) { closed = true; owner.close() } }
    fun checkError() = checkProvider()
}

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
internal class SourceArchiveInputs(private val paths: StoragePaths, private val tasks: TaskStore,
                                   private val gate: DataOperationGate, private val resolver: ContentResolver,
                                   private val scope: CoroutineScope, private val ownerKind: TaskKind = TaskKind.IMPORT,
                                   private val root: File = paths.importInputs,
                                   private val maxBytes: Long = 8L * 1024 * 1024 * 1024) {
    private val partName = if (ownerKind == TaskKind.FILE_COPY) "input.part" else "archive.part"
    private val completeName = if (ownerKind == TaskKind.FILE_COPY) "input.bin" else "archive.zip"
    private val identity = Regex("[a-f0-9]{32}")
    private val opening = Mutex()

    suspend fun open(file: File): SourceArchiveInput = acquire { _ ->
        val fd = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
            OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
        val stream = FileInputStream(fd)
        // SourceArchiveInput takes ownership only after its fstat succeeds.
        try {
            SourceArchiveInput(fd, stream, 0, null).also {
                if (!OsConstants.S_ISREG(it.before.st_mode)) throw StError.Validation("source archive must be a regular file")
            }
        } catch (error: Throwable) { stream.close(); throw error }
    }

    suspend fun open(uri: Uri): SourceArchiveInput {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw StError.Validation("source import requires a content URI")
        return acquire { signal ->
            // AssetFileDescriptor preserves provider offsets/lengths; never resolve a URI into a guessed path.
            val asset = resolver.openAssetFileDescriptor(uri, "r", signal)
                ?: throw StError.Validation("source archive provider unavailable")
            try { SourceArchiveInput(asset.fileDescriptor, asset, asset.startOffset, asset.declaredLength.takeIf { it >= 0 },
                { asset.parcelFileDescriptor.checkError() }) }
            catch (error: Throwable) { asset.close(); throw error }
        }
    }

    private suspend fun acquire(open: (CancellationSignal) -> SourceArchiveInput): SourceArchiveInput =
        withTimeout(30_000) { suspendCancellableCoroutine { continuation ->
            if (!continuation.isActive) return@suspendCancellableCoroutine
            if (!opening.tryLock()) {
                continuation.resumeWith(Result.failure(StError.Conflict("another source descriptor is still opening")))
                return@suspendCancellableCoroutine
            }
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                try {
                    currentCoroutineContext().ensureActive()
                    if (!continuation.isActive) return@launch
                    val source = open(signal)
                    // Also closes the descriptor if cancellation wins the dispatch back to the caller.
                    continuation.resume(source) { _, value, _ -> runCatching { value.close() } }
                } catch (error: Exception) {
                    continuation.resumeWith(Result.failure(if (error is CancellationException || error is StError) error
                        else StError.Validation("cannot open source archive")))
                } finally { opening.unlock() } // Timeout cannot release a provider still ignoring cancellation into another open.
            }
        } }

    fun requireUnused(id: String) {
        requireIdentity(id); checkedRoot()
        if (stat(root.resolve(id)) != null) throw StError.Conflict("source input identity already exists")
    }

    suspend fun snapshot(id: String, source: SourceArchiveInput, claimed: () -> Unit,
                         update: suspend (Long) -> Unit): File = withContext(Dispatchers.IO) {
        requireIdentity(id)
        checkedRoot()
        val stage = root.resolve(id)
        if (!stage.mkdir()) throw StError.Conflict("source input identity exists or unavailable")
        claimed()
        Os.chmod(stage.path, 448) // 0700
        sync(root)
        val output = stage.resolve(partName)
        val bytes = copyTo(source, output, update)
        val complete = stage.resolve(completeName)
        Os.rename(output.path, complete.path)
        sync(stage)
        update(bytes)
        complete
    }

    /** Caller owns this unpublished staging target and its lease; no second snapshot or shared gate. */
    suspend fun copyTo(source: SourceArchiveInput, output: File, update: suspend (Long) -> Unit): Long = withContext(Dispatchers.IO) {
        val before = source.before
        val regular = OsConstants.S_ISREG(before.st_mode)
        if (!regular && !OsConstants.S_ISFIFO(before.st_mode) && !OsConstants.S_ISSOCK(before.st_mode))
            throw StError.Validation("unsupported source descriptor")
        if (source.offset < 0 || (!regular && source.offset != 0L)) throw StError.Validation("unsupported source offset")
        val expected = source.declaredLength ?: if (regular) before.st_size - source.offset else null
        if (expected != null && (expected < 0 || expected > maxBytes)) throw StError.Validation("source archive exceeds its input size limit")
        if (regular) {
            if (source.offset > before.st_size || expected!! > before.st_size - source.offset)
                throw StError.Validation("source asset length is outside the file")
            Os.lseek(source.fd, source.offset, OsConstants.SEEK_SET)
        }
        Os.fcntlInt(source.fd, OsConstants.F_SETFL, Os.fcntlInt(source.fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
        val targetFd = Os.open(output.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
            OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384) // 0600
        var bytes = 0L
        var lastUpdate = 0L
        var lastRead = System.nanoTime()
        FileOutputStream(targetFd).use { target ->
            val buffer = ByteArray(65536)
            while (expected == null || bytes < expected) {
                currentCoroutineContext().ensureActive()
                val wanted = if (expected == null) buffer.size else minOf(buffer.size.toLong(), expected - bytes).toInt()
                val count = try { Os.read(source.fd, buffer, 0, wanted) }
                    catch (error: ErrnoException) {
                        if (error.errno == OsConstants.EINTR) continue
                        if (error.errno != OsConstants.EAGAIN) throw StError.Validation("source archive read failed")
                        if (System.nanoTime() - lastRead > 60_000_000_000L) throw StError.Validation("source archive stream timed out")
                        delay(25); continue
                    }
                if (count == 0) break
                lastRead = System.nanoTime()
                if (bytes > maxBytes - count) throw StError.Validation("source archive exceeds its input size limit")
                target.write(buffer, 0, count); bytes += count
                val at = System.nanoTime() / 1_000_000
                if (at - lastUpdate >= 500) { update(bytes); lastUpdate = at }
            }
            if (expected != null && bytes != expected) throw StError.Validation("source archive ended before its declared length")
            if (regular) {
                val after = Os.fstat(source.fd)
                // Removing/renaming an open source changes ctime, not the selected content; do not require its old path.
                if (after.st_dev != before.st_dev || after.st_ino != before.st_ino || after.st_size != before.st_size ||
                    !sameModifiedTime(after, before) || !OsConstants.S_ISREG(after.st_mode))
                    throw StError.Conflict("source archive changed while being copied")
            }
            source.checkError() // A reliable provider pipe may report failure even after its final bytes.
            currentCoroutineContext().ensureActive()
            if (ownerKind == TaskKind.FILE_COPY && regular) Os.fchmod(targetFd, 384 or (before.st_mode and 64)) // owner execute only; never set-id.
            target.flush(); target.fd.sync()
        }
        bytes
    }

    /** Already-bounded workspace text is immutable here; it needs no external descriptor checks. */
    suspend fun snapshotText(id: String, text: String, claimed: () -> Unit, update: suspend (Long) -> Unit): File = withContext(Dispatchers.IO) {
        requireIdentity(id); checkedRoot()
        val stage = root.resolve(id)
        if (!stage.mkdir()) throw StError.Conflict("source input identity exists or unavailable")
        claimed()
        Os.chmod(stage.path, 448)
        sync(root)
        currentCoroutineContext().ensureActive()
        val output = stage.resolve(partName)
        val bytes = text.toByteArray(Charsets.UTF_8)
        FileOutputStream(output).use { stream -> stream.write(bytes); stream.fd.sync() }
        currentCoroutineContext().ensureActive()
        val complete = stage.resolve(completeName)
        Os.rename(output.path, complete.path)
        sync(stage)
        update(bytes.size.toLong())
        complete
    }

    /** Only exact private per-task artifacts; no recursive delete and no retry of the old import. */
    suspend fun cleanup(id: String) = withContext(Dispatchers.IO) {
        requireIdentity(id); checkedRoot()
        val stage = root.resolve(id)
        val before = stat(stage) ?: return@withContext
        if (!OsConstants.S_ISDIR(before.st_mode) || stage.canonicalFile != stage.absoluteFile)
            throw StError.Conflict("source input directory is redirected")
        val entries = stage.listFiles() ?: throw StError.Validation("source input directory unavailable")
        val allowed = if (ownerKind == TaskKind.FILE_COPY) setOf(partName, completeName, "publication.json", "publication.part")
            else setOf(partName, completeName)
        if (entries.size > allowed.size || entries.any { it.name !in allowed })
            throw StError.Conflict("unknown source input artifacts retained")
        for (file in entries) {
            val current = Os.lstat(stage.path)
            if (current.st_dev != before.st_dev || current.st_ino != before.st_ino || !OsConstants.S_ISDIR(current.st_mode))
                throw StError.Conflict("source input directory identity changed")
            val item = Os.lstat(file.path)
            if (!OsConstants.S_ISREG(item.st_mode) || !file.delete()) throw StError.Conflict("source input cleanup incomplete")
        }
        sync(stage)
        if (!stage.delete()) throw StError.Conflict("source input directory cleanup incomplete")
        sync(root)
    }

    suspend fun onProcessStart() = withContext(Dispatchers.IO) {
        try {
            checkedRoot()
            val pending = root.listFiles() ?: throw StError.Validation("source inputs unavailable")
            if (pending.size > 10_000) throw StError.Validation("source input recovery budget exceeded")
            for (stage in pending) {
                currentCoroutineContext().ensureActive()
                requireIdentity(stage.name)
                if (tasks.get(stage.name)?.kind != ownerKind) throw StError.Conflict("source input has no owning task")
                cleanup(stage.name)
            }
        } catch (error: Exception) { gate.requireRecovery(); throw error }
    }

    private fun requireIdentity(id: String) {
        if (!identity.matches(id)) throw StError.Validation("invalid source input identity")
    }
    private fun sameModifiedTime(first: StructStat, second: StructStat): Boolean =
        if (Build.VERSION.SDK_INT >= 27) first.st_mtim == second.st_mtim else first.st_mtime == second.st_mtime
    private fun checkedRoot() {
        if (!OsConstants.S_ISDIR(Os.lstat(root.path).st_mode) || root.canonicalFile != root.absoluteFile)
            throw StError.Conflict("source input root is redirected")
    }
    private fun stat(file: File): StructStat? = try { Os.lstat(file.path) }
        catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) null else throw error }
    private fun sync(directory: File) {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try { if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw StError.Conflict("source input sync target is not a directory"); Os.fsync(fd) }
        finally { Os.close(fd) }
    }
}
