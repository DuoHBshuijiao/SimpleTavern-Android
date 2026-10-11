package com.simpletavern.runtime.sandbox

import com.simpletavern.core.data.FileChangeStore
import com.simpletavern.core.data.SandboxStore
import com.simpletavern.core.data.StoragePaths
import com.simpletavern.core.model.FileChangeRecord
import com.simpletavern.core.model.FileChangeType
import com.simpletavern.core.model.StError
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

/**
 * Multi-sandbox runtime with per-environment roots under app files.
 *
 * Bundled PRoot supplies independent guest roots; file-boundary/device verification remains outstanding.
 */
class SandboxManager(
    private val paths: StoragePaths,
    private val store: SandboxStore,
    private val changes: FileChangeStore,
    private val tasks: com.simpletavern.core.data.TaskStore? = null,
    private val dataGate: com.simpletavern.core.data.DataOperationGate? = null,
    private val runtime: SandboxRuntime? = null,
) {
    private val runner = ShellRunner(paths.logs.resolve("shell_control").apply { mkdirs() }, runtime)
    private val fileEvents = SandboxFileEvents(changes, dataGate)
    private val activityLock = Any()
    private val activities = linkedMapOf<String, Activity>()
    private val deleting = mutableSetOf<String>()
    private val initializing = mutableSetOf<String>()
    private val fileLocks = PathOperationLocks()
    private val premiseRoot = paths.logs.resolve("file_premises")
    private class Activity(val sandboxId: String, val mutating: Boolean) { var overlapped = false }
    private fun beginActivity(sandboxId: String, mutating: Boolean = true): Pair<String, Activity> = synchronized(activityLock) {
        if (sandboxId in deleting || sandboxId in initializing || !paths.sandboxRoot(sandboxId).isDirectory)
            throw StError.Conflict("sandbox is unavailable, being initialized or deleted")
        val key = UUID.randomUUID().toString()
        val activity = Activity(sandboxId, mutating)
        if (mutating) activities.values.filter { it.sandboxId == sandboxId && it.mutating }
            .forEach { it.overlapped = true; activity.overlapped = true }
        activities[key] = activity
        key to activity
    }
    private fun endActivity(key: String) = synchronized(activityLock) { activities.remove(key) }
    suspend fun requireSandbox(id: String): File = withContext(Dispatchers.IO) {
        store.get(id) ?: throw StError.NotFound("sandbox $id")
        val root = paths.sandboxRoot(id)
        if (!root.isDirectory || root.canonicalFile != root.absoluteFile) throw StError.Sandbox("sandbox root unavailable or linked")
        root.canonicalFile
    }

    suspend fun create(name: String): String = withContext(Dispatchers.IO) {
        val lease = dataGate?.sharedLease()
        try {
            val id = UUID.randomUUID().toString().replace("-", "")
            val root = paths.sandboxRoot(id)
            if (!root.mkdir()) throw StError.Conflict("sandbox root already exists")
            var registered = false
            try {
                root.resolve("home").mkdirs()
                root.resolve("tmp").mkdirs()
                root.resolve("imports").mkdirs()
                (runtime ?: throw StError.Unsupported("sandbox runtime is unavailable")).install(root)
                currentCoroutineContext().ensureActive()
                val now = Instant.now().toString()
                withContext(NonCancellable) {
                    store.upsert(id, name, "sandboxes/$id", now, now,
                        """{"isolation":"proot_guest_paths","isolationVerified":false,"proot":true,"runtime":"${SandboxRuntime.VERSION}"}""")
                    registered = true
                }
                id
            } catch (error: Throwable) {
                if (!registered) {
                    try { withContext(NonCancellable) { removeTree(root) } }
                    catch (cleanupError: Throwable) { error.addSuppressed(cleanupError) }
                }
                throw error
            }
        } finally { lease?.close() }
    }

    suspend fun list() = store.list()
    suspend fun runtimeStatus(id: String): com.simpletavern.core.model.SandboxRuntimeStatus = withContext(Dispatchers.IO) {
        val root = requireSandbox(id)
        (runtime ?: throw StError.Unsupported("sandbox runtime is unavailable")).status(id, root)
    }
    /** Explicit setup for a legacy root; refuse active work and collisions instead of replacing user files. */
    suspend fun initializeRuntime(id: String): com.simpletavern.core.model.SandboxRuntimeStatus = withContext(Dispatchers.IO) {
        val lease = dataGate?.sharedLease()
        try {
            val root = requireSandbox(id)
            synchronized(activityLock) {
                if (id in deleting || id in initializing || activities.values.any { it.sandboxId == id })
                    throw StError.Conflict("sandbox has active operations")
                initializing += id
            }
            try {
                if (tasks?.nonTerminal()?.any { it.sandboxId == id } == true) throw StError.Conflict("sandbox has pending tasks")
                val selected = runtime ?: throw StError.Unsupported("sandbox runtime is unavailable")
                selected.install(root)
                val saved = store.get(id) ?: throw StError.NotFound(id)
                val original = saved.metaJson?.let { com.simpletavern.core.data.Jsonx.json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }
                    ?: kotlinx.serialization.json.JsonObject(emptyMap())
                val metadata = kotlinx.serialization.json.JsonObject(original + mapOf(
                    "isolation" to kotlinx.serialization.json.JsonPrimitive("proot_guest_paths"),
                    "isolationVerified" to kotlinx.serialization.json.JsonPrimitive(false), "proot" to kotlinx.serialization.json.JsonPrimitive(true),
                    "runtime" to kotlinx.serialization.json.JsonPrimitive(SandboxRuntime.VERSION)))
                store.upsert(id, saved.name, saved.rootRelativePath, saved.createdAt, Instant.now().toString(), metadata.toString())
                selected.status(id, root)
            } finally { synchronized(activityLock) { initializing -= id } }
        } finally { lease?.close() }
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val lease = dataGate?.sharedLease()
        try {
            val root = requireSandbox(id)
            synchronized(activityLock) {
                if (id in deleting || id in initializing || activities.values.any { it.sandboxId == id }) throw StError.Conflict("sandbox has active operations")
                deleting += id
            }
            try {
                if (tasks?.nonTerminal()?.any { it.sandboxId == id } == true || store.hasChatReferences(id))
                    throw StError.Conflict("sandbox is referenced by a chat or active task")
                removeTree(root)
                store.delete(id)
            } finally { synchronized(activityLock) { deleting -= id } }
        } finally { lease?.close() }
    }

    suspend fun reserveOperation(sandboxId: String, mutating: Boolean = false): AutoCloseable = withContext(kotlinx.coroutines.NonCancellable) {
        withContext(Dispatchers.IO) {
            val lease = dataGate?.sharedLease()
            try {
                requireSandbox(sandboxId)
                val (key, _) = beginActivity(sandboxId, mutating)
                AutoCloseable { try { endActivity(key) } finally { lease?.close() } }
            } catch (error: Throwable) { lease?.close(); throw error }
        }
    }
    private suspend fun <T> mutate(sandboxId: String, relatives: List<String>, commitDespiteCancellation: Boolean = true, mutating: Boolean = true,
                                   premise: FileMutationPremise? = null,
                                   followFinalLink: Boolean = true,
                                   block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val activity = reserveOperation(sandboxId, mutating = mutating)
        var acquired: AutoCloseable? = null
        try {
            val targets = relatives.map { resolveInSandbox(sandboxId, it, followFinalLink).path }.distinct().sorted()
            acquired = fileLocks.acquire(targets)
            premise?.verify(this@SandboxManager, sandboxId, relatives, followFinalLink)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (commitDespiteCancellation) withContext(kotlinx.coroutines.NonCancellable) { block() } else block()
        } finally {
            acquired?.close()
            activity.close()
        }
    }

    suspend fun captureMutationPremise(sandboxId: String, relatives: List<String>, allowDirectory: Boolean = false,
                                       followFinalLink: Boolean = true): FileMutationPremise {
        var captured: FileMutationPremise? = null
        try {
            return mutate(sandboxId, relatives, commitDespiteCancellation = false, mutating = false, followFinalLink = followFinalLink) {
                FileMutationPremise.capture(this, sandboxId, relatives, premiseRoot, allowDirectory, followFinalLink).also { captured = it }
            }
        } catch (error: Throwable) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { captured?.close() }
            throw error
        }
    }

    /** Called before accepting any new task. Old byte copies never authorize replay. */
    suspend fun clearAbandonedPremises() = withContext(Dispatchers.IO) {
        if (!premiseRoot.exists()) return@withContext
        if (premiseRoot.canonicalFile != premiseRoot.absoluteFile) throw StError.Sandbox("linked premise storage rejected")
        val directories = premiseRoot.listFiles() ?: throw StError.Sandbox("cannot inspect abandoned premises")
        for (directory in directories) {
            if (!directory.name.matches(Regex("[0-9a-f-]{36}")) ||
                !android.system.OsConstants.S_ISDIR(android.system.Os.lstat(directory.path).st_mode))
                throw StError.Sandbox("unexpected private premise entry")
            val files = directory.listFiles() ?: throw StError.Sandbox("cannot inspect private premise directory")
            for (file in files) {
                if (!file.name.matches(Regex("entry-[0-9]+")) ||
                    !android.system.OsConstants.S_ISREG(android.system.Os.lstat(file.path).st_mode) || !file.delete())
                    throw StError.Sandbox("cannot remove abandoned private premise")
            }
            if (!directory.delete()) throw StError.Sandbox("cannot remove abandoned premise directory")
        }
    }

    suspend fun validateCopyTarget(sandboxId: String, relative: String): File = withContext(Dispatchers.IO) {
        val root = requireSandbox(sandboxId)
        val components = relative.replace('\\', '/').split('/')
        if (relative.toByteArray(Charsets.UTF_8).size > 8192 || components.size > 128 || components.any {
            it.isBlank() || it in setOf(".", "..") || it.contains('\u0000') || it.contains(':') ||
                it.toByteArray(Charsets.UTF_8).size > 255 }) throw StError.Validation("invalid copy target")
        var file = root
        for ((index, component) in components.withIndex()) {
            file = file.resolve(component)
            val stat = try { android.system.Os.lstat(file.path) }
                catch (error: android.system.ErrnoException) { if (error.errno == android.system.OsConstants.ENOENT) null else throw error }
            if (index < components.lastIndex && stat != null && !android.system.OsConstants.S_ISDIR(stat.st_mode))
                throw StError.Sandbox("copy target ancestor is linked or not a directory")
        }
        file
    }

    /** A private, fully copied input is published without overwriting any existing destination. */
    suspend fun publishCopy(sandboxId: String, input: File, relativeTarget: String, taskId: String,
        prepare: suspend (String) -> Unit, linked: () -> Unit,
        commit: suspend (String, List<String>) -> Unit, exactTarget: Boolean = false): File = mutate(sandboxId, listOf(relativeTarget), false) {
        val requested = validateCopyTarget(sandboxId, relativeTarget)
        val existing = try { android.system.Os.lstat(requested.path) }
            catch (error: android.system.ErrnoException) { if (error.errno == android.system.OsConstants.ENOENT) null else throw error }
        if (existing != null && exactTarget) throw StError.Conflict("copy destination already exists")
        val final = if (existing == null) requested else requested.parentFile!!.resolve("${UUID.randomUUID()}.${requested.extension.take(32)}".trimEnd('.'))
        val relative = final.relativeTo(paths.sandboxRoot(sandboxId)).invariantSeparatorsPath
        prepare(relative) // Persist publication intent before making any live path.
        val chain = mutableListOf<File>()
        var parent = final.parentFile!!
        val root = requireSandbox(sandboxId)
        while (parent != root) { chain += parent; parent = parent.parentFile ?: throw StError.Sandbox("copy target escapes sandbox") }
        val directories = mutableListOf<String>()
        for (directory in chain.asReversed()) {
            currentCoroutineContext().ensureActive()
            if (!directory.exists()) {
                if (!directory.mkdir() && !directory.isDirectory) throw StError.Sandbox("cannot create copy target directory")
                val name = directory.relativeTo(root).invariantSeparatorsPath
                record(sandboxId, taskId, name, FileChangeType.CREATED, null, "copy parent directory")
                directories += name
            }
            if (!android.system.OsConstants.S_ISDIR(android.system.Os.lstat(directory.path).st_mode))
                throw StError.Sandbox("copy directory identity changed")
            syncCopyDirectory(directory.parentFile!!)
        }
        validateCopyTarget(sandboxId, relative)
        if (!android.system.OsConstants.S_ISREG(android.system.Os.lstat(input.path).st_mode)) throw StError.Sandbox("copy input is not a regular file")
        currentCoroutineContext().ensureActive()
        // The caller wraps this publication and its Room metadata in a non-cancellable commit decision.
        android.system.Os.link(input.path, final.path)
        linked()
        syncCopyDirectory(final.parentFile!!)
        commit(relative, directories)
        final
    }

    private fun syncCopyDirectory(directory: File) {
        val fd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW or
            android.system.OsConstants.O_CLOEXEC, 0)
        try {
            if (!android.system.OsConstants.S_ISDIR(android.system.Os.fstat(fd).st_mode)) throw StError.Sandbox("copy sync target is not a directory")
            android.system.Os.fsync(fd)
        } finally { android.system.Os.close(fd) }
    }

    suspend fun listDir(sandboxId: String, relative: String): List<String> = withContext(Dispatchers.IO) {
        val dir = resolveInSandbox(sandboxId, relative)
        if (!dir.isDirectory) throw StError.Validation("not a directory")
        dir.list()?.sorted()?.toList() ?: emptyList()
    }

    suspend fun readFile(sandboxId: String, relative: String): String = withContext(Dispatchers.IO) {
        val f = resolveInSandbox(sandboxId, relative)
        if (!f.isFile) throw StError.NotFound(relative)
        readBounded(f, 500_000, truncate = true)
    }

    /** A caller owns the result copy, not a persistent host path or a mount into this sandbox. */
    suspend fun <T> withExportStream(sandboxId: String, relative: String, block: suspend (java.io.InputStream) -> T): T = withContext(Dispatchers.IO) {
        val reservation = reserveOperation(sandboxId)
        try {
            val file = resolveInSandbox(sandboxId, relative)
            val fd = android.system.Os.open(file.path, android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW or
                android.system.OsConstants.O_NONBLOCK or android.system.OsConstants.O_CLOEXEC, 0)
            val stream = object : java.io.FileInputStream(fd) {
                private val before = android.system.Os.fstat(fd)
                fun requireRegular() { if (!android.system.OsConstants.S_ISREG(before.st_mode)) throw StError.Validation("export source is not a regular file") }
                private fun finish(count: Int): Int {
                    if (count < 0) {
                        val after = android.system.Os.fstat(fd)
                        val sameTime = if (android.os.Build.VERSION.SDK_INT >= 27) before.st_mtim == after.st_mtim else before.st_mtime == after.st_mtime
                        if (after.st_size != before.st_size || !sameTime) throw StError.Conflict("sandbox file changed during export")
                    }
                    return count
                }
                override fun read(): Int = finish(super.read())
                override fun read(bytes: ByteArray): Int = read(bytes, 0, bytes.size)
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = finish(super.read(bytes, offset, length))
            }
            stream.use { it.requireRegular(); block(it) }
        } finally { reservation.close() }
    }

    suspend fun readWorkspaceText(sandboxId: String, relative: String,
                                  onRead: suspend (Long) -> Unit = {}): String = withExportStream(sandboxId, relative) { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > com.simpletavern.core.data.AssistantWorkspaceStore.MAX_TEXT_BYTES)
                throw StError.Validation("workspace text file exceeds 256 KiB; export as an asset instead")
            output.write(buffer, 0, count)
            onRead(output.size().toLong())
        }
        try {
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { throw StError.Validation("workspace text must be UTF-8; export binary as an asset") }
    }

    suspend fun writeFile(sandboxId: String, taskId: String, relative: String, content: String,
                          premise: FileMutationPremise, createOnly: Boolean = false) = mutate(sandboxId, listOf(relative), premise = premise) {
        val f = resolveInSandbox(sandboxId, relative)
        val existed = f.exists()
        if (createOnly && existed) throw StError.Conflict("create_file target already exists")
        f.parentFile?.mkdirs()
        atomicWrite(f, content)
        record(
            sandboxId, taskId, relative,
            if (existed) FileChangeType.MODIFIED else FileChangeType.CREATED,
            null, "bytes=${f.length()}",
        )
    }

    suspend fun editFile(
        sandboxId: String,
        taskId: String,
        relative: String,
        expected: String?,
        content: String,
        premise: FileMutationPremise,
    ) = mutate(sandboxId, listOf(relative), premise = premise) {
        val f = resolveInSandbox(sandboxId, relative)
        if (!f.isFile) throw StError.NotFound(relative)
        val current = if (expected != null) readBounded(f, 2_000_000) else null
        if (expected != null && current != expected) {
            throw StError.Conflict("edit premise stale", mapOf("path" to relative))
        }
        f.parentFile?.mkdirs()
        atomicWrite(f, content)
        record(sandboxId, taskId, relative, FileChangeType.MODIFIED, expected?.take(200), content.take(200))
    }

    suspend fun deleteFile(sandboxId: String, taskId: String, relative: String, premise: FileMutationPremise) = mutate(
        sandboxId, listOf(relative), premise = premise, followFinalLink = false) {
        val f = resolveInSandbox(sandboxId, relative, followFinalLink = false)
        val info = try { android.system.Os.lstat(f.path) }
            catch (error: android.system.ErrnoException) { if (error.errno == android.system.OsConstants.ENOENT) null else throw error }
        if (info == null) throw StError.NotFound(relative)
        if (!android.system.OsConstants.S_ISREG(info.st_mode) && !android.system.OsConstants.S_ISLNK(info.st_mode))
            throw StError.Validation("delete_file requires a regular file or symbolic link")
        if (!f.delete()) throw StError.Sandbox("file deletion failed")
        record(sandboxId, taskId, relative, FileChangeType.DELETED, null, null)
    }

    suspend fun renameFile(sandboxId: String, taskId: String, from: String, to: String, premise: FileMutationPremise) = mutate(
        sandboxId, listOf(from, to), premise = premise, followFinalLink = false) {
        val src = resolveInSandbox(sandboxId, from, followFinalLink = false)
        val dst = resolveInSandbox(sandboxId, to, followFinalLink = false)
        val sourceInfo = try { android.system.Os.lstat(src.path) }
            catch (error: android.system.ErrnoException) { if (error.errno == android.system.OsConstants.ENOENT) null else throw error }
        if (sourceInfo == null) throw StError.NotFound(from)
        val destinationInfo = try { android.system.Os.lstat(dst.path) }
            catch (error: android.system.ErrnoException) { if (error.errno == android.system.OsConstants.ENOENT) null else throw error }
        if (destinationInfo != null) throw StError.Conflict("rename target already exists")
        if (src == paths.sandboxRoot(sandboxId).canonicalFile) throw StError.Validation("cannot rename the sandbox root")
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) throw StError.Sandbox("rename failed")
        record(sandboxId, taskId, "$from -> $to", FileChangeType.RENAMED, from, to)
    }

    suspend fun openStdio(sandboxId: String, command: String, args: List<String>, environment: Map<String, String>): SandboxStdioProcess = withContext(Dispatchers.IO) {
        val lease = dataGate?.sharedLease()
        var activity: String? = null
        var window: SandboxFileEvents.Window? = null
        var process: SandboxStdioProcess? = null
        var handedOff = false
        try {
            val root = requireSandbox(sandboxId)
            val activityKey = beginActivity(sandboxId).first
            activity = activityKey
            val observation = fileEvents.begin(sandboxId, root, null)
            window = observation
            val owned = runner.openStdio(root, command, args, environment)
            process = owned
            val result = SandboxStdioProcess(owned.stdin, owned.stdout, owned.stderr) {
                try { owned.close() } finally {
                    try { fileEvents.finish(observation); recordIncomplete(sandboxId, null, "stdio process events are best effort; no complete filesystem attribution") }
                    finally { endActivity(activityKey); lease?.close() }
                }
            }
            currentCoroutineContext().ensureActive()
            handedOff = true
            result
        } finally { if (!handedOff) withContext(kotlinx.coroutines.NonCancellable) {
            try { process?.close(); window?.let { fileEvents.finish(it) } }
            finally { activity?.let(::endActivity); lease?.close() }
        } }
    }

    suspend fun runShell(sandboxId: String, taskId: String, command: String, timeoutSec: Long = 300,
                         checkpoint: suspend (ShellOutput) -> Unit = {}): ShellResult = withContext(Dispatchers.IO) {
        val root = requireSandbox(sandboxId)
        val lease = dataGate?.sharedLease()
        try {
            val (key, activity) = beginActivity(sandboxId)
            var before: Observation? = null
            var result: ShellResult? = null
            var window: SandboxFileEvents.Window? = null
            var report: com.simpletavern.core.model.FileObservationReport? = null
            try {
                window = fileEvents.begin(sandboxId, root, taskId)
                before = observeFiles(root)
                result = runner.run(root, taskId, command, timeoutSec, checkpoint)
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    try {
                        try { window?.let { report = fileEvents.finish(it) } }
                        finally {
                            val after = observeFiles(root)
                            val overlap = synchronized(activityLock) { activity.overlapped }
                            val incomplete = before == null || before?.incomplete == true || after.incomplete ||
                                result == null || result?.cancelled == true || result?.timedOut == true || result?.cleanupIncomplete == true
                            // A lone shell window still does not prove authorship (old descendants/external tools).
                            diffAndRecord(sandboxId, null, before?.files.orEmpty(), after.files,
                                allowPresenceChanges = before?.incomplete == false && !after.incomplete)
                            // Events and metadata complement each other; neither proves exhaustive observation.
                            recordIncomplete(sandboxId, taskId, if (overlap || incomplete) "overlap, interruption or scan gap" else "events/metadata are best effort; platform/attachment/stop gaps remain")
                        }
                    } finally { endActivity(key) }
                }
            }
            result!!.copy(fileObservation = report)
        } finally { lease?.close() }
    }

    fun cancelTask(taskId: String) = runner.cancel(taskId)

    fun resolveInSandbox(sandboxId: String, relative: String, followFinalLink: Boolean = true): File {
        val rawRoot = paths.sandboxRoot(sandboxId)
        val root = rawRoot.canonicalFile
        if (!root.isDirectory || root != rawRoot.absoluteFile) throw StError.Sandbox("sandbox root unavailable or linked")
        return GuestPaths.resolve(root, relative.replace('\\', '/'), followFinalLink)
    }

    private fun relativize(sandboxId: String, file: File): String {
        val root = paths.sandboxRoot(sandboxId).canonicalFile
        return file.canonicalFile.relativeTo(root).path.replace('\\', '/')
    }

    private suspend fun record(
        sandboxId: String,
        taskId: String?,
        path: String,
        type: FileChangeType,
        before: String?,
        after: String?,
        attribution: String = "confirmed",
        incomplete: Boolean = false,
    ) {
        changes.record(
            FileChangeRecord(
                id = UUID.randomUUID().toString().replace("-", ""),
                sandboxId = sandboxId,
                taskId = taskId,
                path = path,
                changeType = type,
                observedAt = Instant.now().toString(),
                beforeSummary = before,
                afterSummary = after,
                attribution = attribution,
                incomplete = incomplete,
            ),
        )
    }

    private suspend fun recordIncomplete(sandboxId: String, taskId: String?, reason: String) {
        record(sandboxId, taskId, ".", FileChangeType.UNKNOWN, null, reason, attribution = "unknown", incomplete = true)
    }

    private fun readBounded(file: File, limit: Int, truncate: Boolean = false): String {
        val text = StringBuilder()
        file.bufferedReader().use { reader ->
            val chars = CharArray(8192)
            while (true) {
                val n = reader.read(chars, 0, minOf(chars.size, limit + 1 - text.length))
                if (n < 0) break
                text.append(chars, 0, n)
                if (text.length > limit) {
                    if (!truncate) throw StError.Validation("file text exceeds edit budget")
                    return text.substring(0, limit) + "\n/* truncated */"
                }
            }
        }
        return text.toString()
    }
    private fun atomicWrite(file: File, content: String) {
        if (content.length > 2_000_000) throw StError.Validation("file text exceeds write budget")
        if (file.exists() && !file.isFile) throw StError.Validation("write target is not a regular file")
        if (file.parentFile?.mkdirs() != true && file.parentFile?.isDirectory != true) throw StError.Sandbox("parent directory unavailable")
        val temporary = File.createTempFile(".st-write-", ".tmp", file.parentFile)
        try {
            temporary.outputStream().use { it.write(content.toByteArray(Charsets.UTF_8)); it.flush(); it.fd.sync() }
            android.system.Os.rename(temporary.path, file.path)
        } finally { temporary.delete() }
    }

    private data class Observation(val files: Map<String, String>, val incomplete: Boolean)
    private suspend fun observeFiles(root: File): Observation {
        val out = linkedMapOf<String, String>()
        val queue = java.util.ArrayDeque<File>().apply { add(root) }
        var incomplete = false
        var visited = 0
        var queueBytes = root.path.toByteArray(Charsets.UTF_8).size
        val rootBytes = queueBytes
        var outputBytes = 0
        val deadline = System.nanoTime() + 10_000_000_000L
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (System.nanoTime() >= deadline) { incomplete = true; break }
            val file = queue.removeFirst()
            queueBytes -= file.path.toByteArray(Charsets.UTF_8).size
            if (++visited > 20_000) { incomplete = true; break }
            val stat = runCatching { android.system.Os.lstat(file.path) }.getOrNull()
            if (stat == null) { incomplete = true; continue }
            if (android.system.OsConstants.S_ISLNK(stat.st_mode)) { incomplete = true; continue }
            if (android.system.OsConstants.S_ISDIR(stat.st_mode)) {
                try {
                    java.nio.file.Files.newDirectoryStream(file.toPath()).use { children ->
                        for (child in children) {
                            currentCoroutineContext().ensureActive()
                            if (System.nanoTime() >= deadline) { incomplete = true; break }
                            if (visited + queue.size >= 20_000) { incomplete = true; break }
                            val bytes = child.toString().toByteArray(Charsets.UTF_8).size
                            if (bytes > rootBytes + 8192 || queueBytes + bytes > 2 * 1024 * 1024) {
                                incomplete = true; break
                            }
                            queue.add(child.toFile())
                            queueBytes += bytes
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { incomplete = true }
            } else if (android.system.OsConstants.S_ISREG(stat.st_mode)) {
                val rel = file.relativeTo(root).path.replace('\\', '/')
                val summary = "size=${stat.st_size};mtimeMs=${file.lastModified()};inode=${stat.st_ino}"
                val bytes = rel.toByteArray(Charsets.UTF_8).size + summary.length * 2 + 128
                if (outputBytes + bytes > 8 * 1024 * 1024) { incomplete = true; break }
                outputBytes += bytes
                out[rel] = summary
            } else incomplete = true
        }
        return Observation(out, incomplete)
    }

    private suspend fun diffAndRecord(sandboxId: String, taskId: String?, before: Map<String, String>, after: Map<String, String>,
                                      allowPresenceChanges: Boolean) {
        val keys = before.keys + after.keys
        keys.forEach { path ->
            val b = before[path]
            val a = after[path]
            when {
                allowPresenceChanges && b == null && a != null -> record(sandboxId, taskId, path, FileChangeType.CREATED, null, a, attribution = "observed", incomplete = true)
                allowPresenceChanges && b != null && a == null -> record(sandboxId, taskId, path, FileChangeType.DELETED, b, null, attribution = "observed", incomplete = true)
                b != null && a != null && b != a -> record(sandboxId, taskId, path, FileChangeType.MODIFIED, b, a, attribution = "observed", incomplete = true)
            }
        }
    }
    private fun removeTree(root: File) {
        val pending = java.util.ArrayDeque<Pair<File, Boolean>>().apply { add(root to false) }
        while (pending.isNotEmpty()) {
            val (file, visited) = pending.removeLast()
            if (file != root) {
                val parent = file.parentFile?.canonicalFile ?: throw StError.Sandbox("invalid deletion parent")
                if (parent != root && !parent.path.startsWith(root.path + File.separator)) throw StError.Sandbox("linked deletion parent rejected")
            }
            val stat = runCatching { android.system.Os.lstat(file.path) }.getOrNull() ?: continue
            if (android.system.OsConstants.S_ISDIR(stat.st_mode) && !visited) {
                pending.add(file to true)
                val children = file.listFiles() ?: throw StError.Sandbox("cannot enumerate deletion target")
                children.forEach { pending.add(it to false) }
            } else if (!file.delete()) throw StError.Sandbox("sandbox deletion incomplete")
        }
    }
}
