package com.simpletavern.runtime.sandbox

import android.os.Build
import android.os.FileObserver
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.simpletavern.core.data.DataOperationGate
import com.simpletavern.core.data.FileChangeStore
import com.simpletavern.core.model.*
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Best-effort events, NOT an isolation boundary or an exhaustive audit. No content is read. */
internal class SandboxFileEvents(private val changes: FileChangeStore, private val gate: DataOperationGate?) {
    private val control = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    internal data class Window(val entry: Entry, val taskId: String?, val startedAt: String,
                               val from: FileChangeCursor, val eventsAtStart: Long, val dropsAtStart: Long)

    suspend fun begin(sandboxId: String, root: File, taskId: String?): Window = control.withLock {
        val entry = entries[sandboxId] ?: Entry(sandboxId, root, gate?.sharedLease()).also { entries[sandboxId] = it }
        try {
            val window = entry.open(taskId)
            entry.references++
            window
        } catch (e: Throwable) {
            if (entry.references == 0) {
                entries.remove(sandboxId)
                withContext(NonCancellable) { entry.shutdown() }
            }
            throw e
        }
    }

    suspend fun finish(window: Window): FileObservationReport = withContext(NonCancellable) {
        control.withLock {
            val entry = window.entry
            check(entries[entry.sandboxId] === entry && entry.references > 0)
            val last = --entry.references == 0
            try { entry.close(window, last) }
            finally {
                if (last) {
                    entries.remove(entry.sandboxId)
                    entry.shutdown() // No detached database writes after the operation's lease is released.
                }
            }
        }
    }

    private sealed interface Message {
        data class Event(val directory: String, val identity: String, val name: String?, val mask: Int, val at: Long) : Message
        data class Open(val taskId: String?, val reply: CompletableDeferred<Window>) : Message
        data class Close(val window: Window, val last: Boolean, val reply: CompletableDeferred<FileObservationReport>) : Message
    }

    private data class Metadata(val identity: String, val directory: Boolean, val summary: String)

    internal inner class Entry(val sandboxId: String, private val root: File, private val lease: AutoCloseable?) {
        var references = 0 // registry mutex only
        private val sessionId = UUID.randomUUID().toString()
        private val queue = Channel<Message>(2048)
        private val accepting = AtomicBoolean(true)
        private val drops = AtomicLong()
        private val issues = linkedSetOf("watch_registration_unconfirmed", "platform_overflow_unobservable",
            "start_stop_callback_gap", "path_checks_not_isolation", "task_attribution_unknown")
        private val cache = linkedMapOf<String, Metadata>()
        private val watchers = linkedMapOf<String, Watch>()
        private var cacheBytes = 0
        private var attempts = 0
        private var recorded = 0L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ ->
            android.util.Log.w("STFileEvents", "file event collector failed")
        })
        private var rootIdentity: String? = null
        @OptIn(ExperimentalCoroutinesApi::class)
        private val worker = scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                val stat = Os.lstat(root.path)
                if (!OsConstants.S_ISDIR(stat.st_mode) || root.canonicalFile != root.absoluteFile)
                    throw StError.Sandbox("file observation root unavailable")
                rootIdentity = identity(stat)
                scan("", 5_000_000_000L)
                var maintenanceAt = System.nanoTime()
                while (isActive) {
                    val message = select<Message?> {
                        queue.onReceive { it }
                        onTimeout(1000) { null }
                    }
                    when (message) {
                        is Message.Event -> event(message)
                        is Message.Open -> {
                            val at = Instant.now().toString()
                            val cursor = marker(message.taskId, "start;session=$sessionId")
                            message.reply.complete(Window(this@Entry, message.taskId, at, cursor, recorded, drops.get()))
                        }
                        is Message.Close -> {
                            if (message.last) stopWatches()
                            if (drops.get() > 0) issues.add("callback_queue_overflow")
                            val through = marker(message.window.taskId, "end;session=$sessionId;from=${message.window.from.afterSequence};epoch=${message.window.from.streamEpoch};events=${recorded - message.window.eventsAtStart};watchAttempts=$attempts;gaps=${issues.joinToString(",")};dropped=${drops.get()}")
                            message.reply.complete(FileObservationReport(sessionId, sandboxId, message.window.taskId,
                                message.window.startedAt, Instant.now().toString(), message.window.from,
                                through.afterSequence, recorded - message.window.eventsAtStart,
                                drops.get() - message.window.dropsAtStart, attempts, cache.size, issues.toList()))
                            if (message.last) break
                        }
                        null -> Unit
                    }
                    // Reattach directories missed during a move/create callback gap. Not synthetic change events.
                    if (System.nanoTime() - maintenanceAt >= 5_000_000_000L) {
                        scan("")
                        maintenanceAt = System.nanoTime()
                    }
                }
            } finally {
                accepting.set(false)
                stopWatches()
                queue.close()
                lease?.close()
            }
        }

        suspend fun open(taskId: String?): Window {
            val reply = CompletableDeferred<Window>()
            return request(reply, Message.Open(taskId, reply))
        }

        suspend fun close(window: Window, last: Boolean): FileObservationReport {
            // Reject late native callbacks before the end barrier. Their absence remains an explicit gap.
            if (last) accepting.set(false)
            val reply = CompletableDeferred<FileObservationReport>()
            return request(reply, Message.Close(window, last, reply))
        }

        private suspend fun <T> request(reply: CompletableDeferred<T>, message: Message): T {
            val hook = worker.invokeOnCompletion { cause ->
                reply.completeExceptionally(cause ?: StError.Sandbox("file event collector stopped"))
            }
            try {
                return withTimeout(10_000) { queue.send(message); reply.await() }
            } catch (_: TimeoutCancellationException) {
                throw StError.Sandbox("file event collector drain deadline exceeded")
            } finally { hook.dispose() }
        }

        suspend fun shutdown() {
            accepting.set(false)
            worker.cancelAndJoin()
            scope.cancel()
        }

        private suspend fun marker(taskId: String?, summary: String): FileChangeCursor = changes.recordCursor(
            FileChangeRecord(UUID.randomUUID().toString(), sandboxId, taskId, ".", FileChangeType.UNKNOWN,
                Instant.now().toString(), afterSummary = summary, attribution = "unknown", incomplete = true))

        private inner class Watch(val directory: String, val metadata: Metadata, val observer: FileObserver)

        private fun identity(stat: StructStat) = "${stat.st_dev}:${stat.st_ino}"
        private fun metadata(stat: StructStat): Metadata {
            val mtime = if (Build.VERSION.SDK_INT >= 27) "${stat.st_mtim.tv_sec}:${stat.st_mtim.tv_nsec}" else stat.st_mtime.toString()
            return Metadata(identity(stat), OsConstants.S_ISDIR(stat.st_mode),
                "dev=${stat.st_dev};inode=${stat.st_ino};mode=${stat.st_mode};size=${stat.st_size};mtime=$mtime")
        }

        private fun validComponent(name: String) = name.isNotEmpty() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == '\u0000' } && name.toByteArray(Charsets.UTF_8).size <= 255

        /** lstat every ancestor; root identity fixed for this session. Still not race-free openat/guest isolation. */
        private fun stat(relative: String): StructStat? {
            if (relative.toByteArray(Charsets.UTF_8).size > 8192) { issues.add("path_budget"); return null }
            var file = root
            val rootStat = runCatching { Os.lstat(file.path) }.getOrNull()
            if (rootStat == null || !OsConstants.S_ISDIR(rootStat.st_mode) || identity(rootStat) != rootIdentity) {
                issues.add("root_identity_changed"); return null
            }
            if (relative.isEmpty()) return rootStat
            val parts = relative.split('/')
            if (parts.size > 128 || parts.any { !validComponent(it) }) { issues.add("path_budget"); return null }
            for ((index, part) in parts.withIndex()) {
                file = file.resolve(part)
                val next = runCatching { Os.lstat(file.path) }.getOrNull() ?: return null
                if (index == parts.lastIndex) return next // final symlink metadata is allowed, never followed
                if (!OsConstants.S_ISDIR(next.st_mode)) { issues.add("ancestor_not_directory"); return null }
            }
            return null
        }

        private fun remember(path: String, value: Metadata) {
            val previous = cache[path]
            val oldBytes = previous?.let { path.toByteArray(Charsets.UTF_8).size + it.summary.length * 2 + 128 } ?: 0
            val newBytes = path.toByteArray(Charsets.UTF_8).size + value.summary.length * 2 + 128
            if ((previous == null && cache.size >= 20_000) || cacheBytes - oldBytes + newBytes > 8 * 1024 * 1024) {
                issues.add("metadata_cache_budget"); return
            }
            cache[path] = value
            cacheBytes += newBytes - oldBytes
        }

        private fun forgetTree(path: String) {
            fun inside(candidate: String) = candidate == path || path.isEmpty() || candidate.startsWith("$path/")
            cache.keys.filter(::inside).forEach { key ->
                val value = cache.remove(key)!!
                cacheBytes -= key.toByteArray(Charsets.UTF_8).size + value.summary.length * 2 + 128
            }
            watchers.keys.filter(::inside).forEach { key -> runCatching { watchers.remove(key)?.observer?.stopWatching() } }
        }

        @Suppress("DEPRECATION") // String constructor supports API 26-28.
        private fun watch(path: String, value: Metadata) {
            val old = watchers[path]
            if (old?.metadata?.identity == value.identity) return
            if (old != null) forgetTree(path)
            if (watchers.size >= 1024 || attempts >= 4096) { issues.add("watch_budget"); return }
            attempts++
            val observer = object : FileObserver(root.resolve(path).path, EVENT_MASK) {
                override fun onEvent(event: Int, name: String?) {
                    // No filesystem access, database I/O, blocking send or exception propagation on native thread.
                    try {
                        if (!accepting.get()) return
                        if (name != null && !validComponent(name)) { drops.incrementAndGet(); return }
                        if (queue.trySend(Message.Event(path, value.identity, name, event, System.currentTimeMillis())).isFailure)
                            drops.incrementAndGet()
                    } catch (_: Exception) { drops.incrementAndGet() }
                }
            }
            try {
                observer.startWatching()
                watchers[path] = Watch(path, value, observer) // strong reference; success is not confirmed by Android
            } catch (_: Exception) { runCatching { observer.stopWatching() }; issues.add("watch_start_failed") }
        }

        private suspend fun scan(start: String, timeBudgetNanos: Long = 1_000_000_000L) {
            val pending = java.util.ArrayDeque<String>().apply { add(start) }
            var pendingBytes = start.toByteArray(Charsets.UTF_8).size
            val deadline = System.nanoTime() + timeBudgetNanos
            var visited = 0
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() >= deadline) { issues.add("tree_scan_time_budget"); break }
                val path = pending.removeFirst()
                pendingBytes -= path.toByteArray(Charsets.UTF_8).size
                if (++visited > 20_000) { issues.add("tree_scan_budget"); break }
                val value = stat(path)?.let(::metadata) ?: continue
                remember(path, value)
                if (!value.directory) continue
                watch(path, value) // attach before enumerating children
                try {
                    Files.newDirectoryStream(root.resolve(path).toPath()).use { children ->
                        for (child in children) {
                            currentCoroutineContext().ensureActive()
                            if (System.nanoTime() >= deadline) { issues.add("tree_scan_time_budget"); break }
                            if (visited + pending.size >= 20_000) { issues.add("tree_scan_budget"); break }
                            val name = child.fileName.toString()
                            if (!validComponent(name)) { issues.add("path_budget"); continue }
                            val relative = if (path.isEmpty()) name else "$path/$name"
                            val bytes = relative.toByteArray(Charsets.UTF_8).size
                            if (bytes > 8192) { issues.add("path_budget"); continue }
                            if (pendingBytes + bytes > 2 * 1024 * 1024) { issues.add("tree_queue_budget"); break }
                            pending.add(relative)
                            pendingBytes += bytes
                        }
                    }
                    if (stat(path)?.let(::identity) != value.identity) issues.add("scan_identity_changed")
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { issues.add("directory_scan_failed") }
            }
            // A missed DELETE_SELF/MOVE_SELF must not leave a watcher attached to an old outside inode.
            for (path in watchers.keys.toList()) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() >= deadline) { issues.add("watch_reconcile_time_budget"); break }
                if (stat(path)?.let(::identity) != watchers[path]?.metadata?.identity) forgetTree(path)
            }
        }

        private suspend fun event(raw: Message.Event) {
            if (watchers[raw.directory]?.metadata?.identity != raw.identity) {
                issues.add("stale_watch_event"); return
            }
            val mask = raw.mask and EVENT_MASK
            if (mask == 0) { issues.add("unrecognized_event"); return }
            val self = mask and (FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) != 0
            val parent = stat(raw.directory)
            if (!self && (parent == null || identity(parent) != raw.identity)) {
                issues.add("stale_watch_event"); forgetTree(raw.directory); return
            }
            val path = raw.name?.let { if (raw.directory.isEmpty()) it else "${raw.directory}/$it" } ?: raw.directory
            val before = cache[path]
            val after = stat(path)?.let(::metadata)
            val moved = mask and (FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.MOVE_SELF) != 0
            val removed = mask and (FileObserver.DELETE or FileObserver.DELETE_SELF or FileObserver.MOVED_FROM or FileObserver.MOVE_SELF) != 0
            val type = when {
                moved -> FileChangeType.UNKNOWN // Android does not expose the rename cookie; never invent a pairing.
                removed -> FileChangeType.DELETED
                mask and FileObserver.CREATE != 0 -> FileChangeType.CREATED
                else -> FileChangeType.MODIFIED
            }
            if (moved) issues.add("move_pairing_unavailable")
            if (recorded < 200_000) {
                changes.record(FileChangeRecord(UUID.randomUUID().toString(), sandboxId, null, path.ifEmpty { "." }, type,
                    Instant.ofEpochMilli(raw.at).toString(), before?.summary,
                    "eventMask=$mask;metadataAtProcessing=${after?.summary ?: "unavailable"}",
                    attribution = "event", incomplete = true))
                recorded++
            } else issues.add("event_record_budget")
            if (removed) forgetTree(path)
            else if (after != null) remember(path, after)
            if (after?.directory == true && !removed && mask and (FileObserver.CREATE or FileObserver.MOVED_TO) != 0) {
                issues.add("new_directory_attachment_gap")
                scan(path)
            }
        }

        private fun stopWatches() {
            watchers.values.forEach { runCatching { it.observer.stopWatching() } }
            watchers.clear()
        }
    }

    private companion object {
        const val EVENT_MASK = FileObserver.CREATE or FileObserver.MODIFY or FileObserver.CLOSE_WRITE or
            FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.ATTRIB or
            FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
    }
}
