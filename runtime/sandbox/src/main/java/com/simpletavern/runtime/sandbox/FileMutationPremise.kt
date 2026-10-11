package com.simpletavern.runtime.sandbox

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.simpletavern.core.model.StError
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ApprovalPremiseChunk(val entry: Int, val offset: Long, val totalBytes: Long,
    val base64: String, val hasMore: Boolean)

/** Exact private byte copies, never digests. Lifetime is one live invocation, not a replay token. */
class FileMutationPremise internal constructor(
    val sandboxId: String,
    private val directory: File,
    private val targets: Map<String, File>,
    private val ancestors: Map<File, Identity>,
    private val entries: List<Entry>,
    private val followFinalLink: Boolean,
) : AutoCloseable {
    internal data class Identity(val device: Long, val inode: Long, val mode: Int)
    internal data class Entry(val file: File, val relative: String, val identity: Identity?,
        val children: List<String>?, val copy: File?, val bytes: Long, val linkTarget: String? = null)
    @Volatile private var closed = false
    val overwritesExisting: Boolean get() = entries.any { it.file in targets.values && it.identity != null }
    fun exists(relative: String): Boolean = entries.any { it.file == targets[relative] && it.identity != null }

    suspend fun requireExpectedText(relative: String, expected: String) {
        val entry = entries.firstOrNull { it.file == targets[relative] }
        val copy = entry?.copy ?: throw StError.Conflict("edit premise is not an existing regular file")
        val bytes = expected.toByteArray(Charsets.UTF_8)
        if (entry.bytes != bytes.size.toLong()) stale()
        copy.inputStream().use { input ->
            val buffer = ByteArray(32_768)
            var offset = 0
            while (offset < bytes.size) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer, 0, minOf(buffer.size, bytes.size - offset))
                if (count < 0) stale()
                for (i in 0 until count) if (buffer[i] != bytes[offset + i]) stale()
                offset += count
            }
        }
    }

    suspend fun verify(manager: SandboxManager, sandboxId: String, relatives: List<String>, followFinalLink: Boolean = true) {
        if (closed || sandboxId != this.sandboxId || relatives.toSet() != targets.keys || followFinalLink != this.followFinalLink)
            throw StError.Conflict("file premise belongs to a different invocation")
        for ((relative, expected) in targets) {
            if (manager.resolveInSandbox(sandboxId, relative, followFinalLink) != expected) stale()
        }
        for ((file, expected) in ancestors) {
            currentCoroutineContext().ensureActive()
            if (identity(stat(file)) != expected) stale()
        }
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            val current = stat(entry.file)
            if (identity(current) != entry.identity) stale()
            if (entry.children != null && children(entry.file) != entry.children) stale()
            if (entry.linkTarget != null && Os.readlink(entry.file.path) != entry.linkTarget) stale()
            if (entry.copy != null) {
                if (current?.st_size != entry.bytes || !sameBytes(entry.file, entry.copy)) stale()
                if (identity(stat(entry.file)) != entry.identity) stale()
            }
        }
    }

    /** UI preview is explicitly partial; complete original bytes remain available in bounded chunks. */
    fun describe(offset: Int = 0, limit: Int = 100): String {
        if (offset !in 0..entries.size || limit !in 1..100) throw StError.Validation("premise manifest requires valid offset and limit=1..100")
        return buildJsonObject {
            put("version", 1); put("comparison", if (entries.any { it.linkTarget != null }) "exact_bytes_and_link_targets" else "exact_bytes"); put("sandboxId", sandboxId)
            put("followFinalLink", followFinalLink)
            put("entryCount", entries.size); put("totalBytes", entries.sumOf { it.bytes })
            put("offset", offset); put("manifestTruncated", offset + limit < entries.size)
            put("entries", buildJsonArray {
                entries.drop(offset).take(limit).forEachIndexed { index, entry -> add(buildJsonObject {
                    put("entry", offset + index); put("path", entry.relative); put("bytes", entry.bytes)
                    put("kind", if (entry.identity == null) "absent" else if (entry.linkTarget != null) "symlink" else if (entry.children != null) "directory" else "file")
                    entry.linkTarget?.let { put("target", it) }
                }) }
            })
        }.toString()
    }

    suspend fun readChunk(index: Int, offset: Long, limit: Int): ApprovalPremiseChunk = withContext(Dispatchers.IO) {
        if (closed) throw StError.Conflict("file premise is no longer live")
        if (offset < 0 || limit !in 1..65_536) throw StError.Validation("premise chunk requires offset>=0 and limit=1..65536")
        val entry = entries.getOrNull(index) ?: throw StError.NotFound("premise entry")
        val copy = entry.copy ?: throw StError.Validation("premise entry has no file bytes")
        if (offset > entry.bytes) throw StError.Validation("premise offset exceeds file size")
        val bytes = ByteArray(minOf(limit.toLong(), entry.bytes - offset).toInt())
        java.io.RandomAccessFile(copy, "r").use { input -> input.seek(offset); input.readFully(bytes) }
        ApprovalPremiseChunk(index, offset, entry.bytes,
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP), offset + bytes.size < entry.bytes)
    }

    override fun close() {
        closed = true
        val failures = entries.mapNotNull { it.copy }.filter { it.exists() && !it.delete() }
        if (failures.isNotEmpty() || directory.exists() && !directory.delete())
            throw StError.Sandbox("private file premise cleanup failed")
    }

    internal companion object {
        const val MAX_BYTES = 268_435_456L
        const val MAX_ENTRIES = 20_000
        fun stat(file: File): StructStat? = try { Os.lstat(file.path) } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) null else throw StError.Sandbox("cannot inspect file premise")
        }
        fun identity(stat: StructStat?): Identity? = stat?.let { Identity(it.st_dev, it.st_ino, it.st_mode) }
        fun children(file: File): List<String> = (file.list() ?: throw StError.Sandbox("cannot enumerate file premise")).sorted()
        fun stale(): Nothing = throw StError.Conflict("file premise changed; a new call and approval are required")
        suspend fun sameBytes(source: File, copy: File): Boolean {
            val fd = Os.open(source.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
            FileInputStream(fd).use { input -> copy.inputStream().use { saved ->
                if (!OsConstants.S_ISREG(Os.fstat(fd).st_mode)) return false
                val left = ByteArray(32_768); val right = ByteArray(32_768)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = saved.read(right)
                    if (count < 0) return input.read() < 0
                    var filled = 0
                    while (filled < count) {
                        val n = input.read(left, filled, count - filled)
                        if (n < 0) return false
                        filled += n
                    }
                    for (i in 0 until count) if (left[i] != right[i]) return false
                }
            } }
        }

        suspend fun capture(manager: SandboxManager, sandboxId: String, relatives: List<String>,
                            privateRoot: File, allowDirectory: Boolean, followFinalLink: Boolean = true): FileMutationPremise {
            val targets = relatives.associateWith { manager.resolveInSandbox(sandboxId, it, followFinalLink) }
            val root = manager.requireSandbox(sandboxId)
            if (!privateRoot.isDirectory && !privateRoot.mkdirs()) throw StError.Sandbox("private premise storage unavailable")
            if (privateRoot.canonicalFile != privateRoot.absoluteFile) throw StError.Sandbox("linked private premise storage rejected")
            val directory = File(privateRoot, UUID.randomUUID().toString())
            if (!directory.mkdir()) throw StError.Sandbox("private premise directory unavailable")
            val ancestors = linkedMapOf<File, Identity>()
            val entries = mutableListOf<Entry>()
            val copies = mutableListOf<File>()
            var total = 0L
            try {
                val queue = java.util.ArrayDeque<File>()
                for (file in targets.values.distinct()) {
                    if (file == root) throw StError.Validation("mutation of the sandbox root is not supported")
                    var parent = file.parentFile
                    while (parent != null && (parent == root || parent.path.startsWith(root.path + File.separator))) {
                        val info = stat(parent)
                        if (info != null) {
                            if (!OsConstants.S_ISDIR(info.st_mode)) throw StError.Validation("mutation parent is not a directory")
                            ancestors[parent] = identity(info)!!
                        }
                        if (parent == root) break
                        parent = parent.parentFile
                    }
                    queue.add(file)
                }
                val visited = mutableSetOf<String>()
                while (queue.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val file = queue.removeFirst()
                    if (!visited.add(file.path)) continue
                    if (entries.size >= MAX_ENTRIES) throw StError.Validation("file premise exceeds 20000 entries")
                    val info = stat(file)
                    val relative = file.relativeTo(root).path.replace('\\', '/')
                    if (info == null) entries += Entry(file, relative, null, null, null, 0)
                    else if (OsConstants.S_ISDIR(info.st_mode)) {
                        if (!allowDirectory) throw StError.Validation("this tool requires a regular file")
                        val names = children(file)
                        if (names.size + queue.size + entries.size > MAX_ENTRIES) throw StError.Validation("file premise exceeds 20000 entries")
                        entries += Entry(file, relative, identity(info), names, null, 0)
                        names.forEach { queue.add(File(file, it)) }
                    } else if (OsConstants.S_ISLNK(info.st_mode) && !followFinalLink) {
                        // Snapshot the link object, never its target or a directory beyond it.
                        entries += Entry(file, relative, identity(info), null, null, 0, Os.readlink(file.path))
                    } else if (OsConstants.S_ISREG(info.st_mode)) {
                        if (info.st_size > MAX_BYTES - total) throw StError.Validation("file premise exceeds 256 MiB")
                        val copy = File(directory, "entry-${entries.size}")
                        copies += copy
                        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
                        FileInputStream(fd).use { input -> copy.outputStream().use { output ->
                            if (identity(Os.fstat(fd)) != identity(info)) stale()
                            val bytes = ByteArray(32_768)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(bytes)
                                if (n < 0) break
                                total += n
                                if (total > MAX_BYTES) throw StError.Validation("file premise exceeds 256 MiB")
                                output.write(bytes, 0, n)
                            }
                        } }
                        entries += Entry(file, relative, identity(info), null, copy, copy.length())
                    } else throw StError.Validation("linked or special files are not supported in a mutation premise")
                }
                return FileMutationPremise(sandboxId, directory, targets, ancestors, entries.toList(), followFinalLink).also {
                    it.verify(manager, sandboxId, relatives, followFinalLink)
                }
            } catch (error: Throwable) {
                var cleanupFailed = false
                copies.forEach { if (it.exists() && !it.delete()) cleanupFailed = true }
                val directoryRemoved = directory.delete()
                if (cleanupFailed || !directoryRemoved) error.addSuppressed(StError.Sandbox("partial private premise cleanup failed"))
                throw error
            }
        }
    }
}
