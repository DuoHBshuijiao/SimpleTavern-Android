package com.simpletavern.runtime.sandbox

import android.os.Process as AndroidProcess
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.simpletavern.core.model.StError
import java.io.File
import java.io.FileDescriptor
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class ShellOutput(
    val stdout: String = "", val stderr: String = "",
    val stdoutBytes: Long = 0, val stderrBytes: Long = 0,
    val stdoutTruncated: Boolean = false, val stderrTruncated: Boolean = false,
    val updatedAt: String = Instant.now().toString(),
)

@Serializable
data class ShellResult(
    val exitCode: Int, val stdout: String, val stderr: String, val combined: String,
    val cancelled: Boolean = false, val timedOut: Boolean = false,
    val stdoutBytes: Long = 0, val stderrBytes: Long = 0,
    val stdoutTruncated: Boolean = false, val stderrTruncated: Boolean = false,
    val cleanupIncomplete: Boolean = false,
    val fileObservation: com.simpletavern.core.model.FileObservationReport? = null,
)

/** Nonblocking Android FIFOs: no full readText, reader threads, disk output spool, or pipe/join deadlock. */
internal class ShellRunner(private val controlRoot: File, private val runtime: SandboxRuntime?) {
    private class Lease {
        val cancelled = AtomicBoolean()
        var process: Process? = null
        var leader: Proc? = null
        val descendants = linkedMapOf<Int, Proc>()
        var incomplete = false
    }
    private data class Proc(val pid: Int, val parent: Int, val group: Int, val session: Int, val start: Long)
    private val running = ConcurrentHashMap<String, Lease>()
    fun cancel(taskId: String) { running[taskId]?.cancelled?.set(true) }
    fun isRunning(taskId: String) = running.containsKey(taskId)

    /** Interactive pipes for a configured MCP server, retaining the shell runner's process-group ownership. */
    suspend fun openStdio(root: File, command: String, args: List<String>, environment: Map<String, String>): SandboxStdioProcess = withContext(Dispatchers.IO) {
        if (command.isBlank() || (listOf(command) + args + environment.values).any { '\u0000' in it } ||
            environment.keys.any { !it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) } ||
            (listOf(command) + args + environment.map { "${it.key}=${it.value}" }).sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } > 100_000)
            throw StError.Validation("invalid stdio command, arguments or environment")
        val lease = Lease()
        val id = "mcp-${UUID.randomUUID()}"
        val dir = File(controlRoot, id)
        if (!dir.mkdir()) throw StError.Sandbox("stdio control directory unavailable")
        val descriptors = mutableListOf<FileDescriptor>()
        var trackingScope: CoroutineScope? = null
        var trackingJob: Job? = null
        var handedOff = false
        try {
            fun fifo(name: String): Pair<File, FileDescriptor> {
                val file = File(dir, name)
                Os.mkfifo(file.path, 384)
                val fd = Os.open(file.path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
                descriptors += fd
                return file to fd
            }
            val control = fifo("identity")
            val ack = fifo("ack")
            val guest = runtime?.launchStdio(root, command, args, environment, dir)
                ?: throw StError.Unsupported("sandbox runtime is not installed")
            val launcher = File("/system/bin/setsid")
            val launch = if (launcher.canExecute()) listOf(launcher.path) else listOf("/system/bin/toybox", "setsid")
            val header = """
                printf '%s\n' "${'$'}${'$'}" > "${'$'}ST_CONTROL"
                IFS= read -r answer < "${'$'}ST_ACK" || exit 125
                [ "${'$'}answer" = go ] || exit 125
                unset ST_CONTROL ST_ACK
                exec "${'$'}@"
            """.trimIndent()
            val process = ProcessBuilder(launch + listOf("/system/bin/sh", "-c", header, "st-mcp") + guest.arguments)
                .directory(root.resolve("home")).apply {
                    environment().clear(); environment().putAll(guest.environment)
                    environment()["ST_CONTROL"] = control.first.path; environment()["ST_ACK"] = ack.first.path
                }.start()
            lease.process = process
            val identity = ByteTail(128)
            val started = System.nanoTime()
            while (lease.leader == null) {
                currentCoroutineContext().ensureActive()
                drain(control.second, identity)
                val line = identity.text()
                if ('\n' in line) {
                    val pid = line.trim().toIntOrNull() ?: throw StError.Sandbox("invalid stdio process identity")
                    val info = proc(pid) ?: throw StError.Sandbox("stdio process identity unavailable")
                    if (info.parent != AndroidProcess.myPid() || info.group != pid || info.session != pid)
                        throw StError.Sandbox("stdio did not establish an independent process group")
                    lease.leader = info; lease.descendants[pid] = info
                    Os.write(ack.second, byteArrayOf(103, 111, 10), 0, 3)
                } else if (!process.isAlive || elapsed(started) >= 5000)
                    throw StError.Sandbox("stdio process-group handshake failed")
                else delay(25)
            }
            running[id] = lease
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val tracking = scope.launch { while (true) { trackDescendants(lease); delay(250) } }
            trackingScope = scope; trackingJob = tracking
            val session = SandboxStdioProcess(process.outputStream, process.inputStream, process.errorStream) {
                tracking.cancelAndJoin(); scope.cancel()
                try { terminate(lease) } finally {
                    runCatching { process.outputStream.close() }; runCatching { process.inputStream.close() }; runCatching { process.errorStream.close() }
                    descriptors.forEach { runCatching { Os.close(it) } }
                    dir.listFiles()?.forEach { it.delete() }; dir.delete(); running.remove(id, lease)
                }
            }
            currentCoroutineContext().ensureActive()
            handedOff = true
            session
        } finally { if (!handedOff) withContext(NonCancellable) {
            trackingJob?.cancelAndJoin(); trackingScope?.cancel()
            terminate(lease)
            descriptors.forEach { runCatching { Os.close(it) } }
            dir.listFiles()?.forEach { it.delete() }; dir.delete(); running.remove(id, lease)
        } }
    }

    suspend fun run(root: File, taskId: String, command: String, timeoutSec: Long,
                    checkpoint: suspend (ShellOutput) -> Unit): ShellResult = withContext(Dispatchers.IO) {
        if (command.isBlank() || command.indexOf('\u0000') >= 0 || command.toByteArray(Charsets.UTF_8).size > 100_000)
            throw StError.Validation("shell command must be nonblank and at most 100000 UTF-8 bytes")
        if (timeoutSec !in 1..3600) throw StError.Validation("shell timeout must be 1..3600 seconds")
        val started = System.nanoTime()
        val lease = Lease()
        if (running.putIfAbsent(taskId, lease) != null) throw StError.Conflict("task already has a running shell")
        // Cancellation is keyed by taskId; each invocation keeps its FIFOs and handshake files in a private control directory.
        val dir = File(controlRoot, UUID.randomUUID().toString()).apply {
            if (!mkdirs()) { running.remove(taskId, lease); throw StError.Sandbox("shell control directory unavailable") }
        }
        val descriptors = mutableListOf<FileDescriptor>()
        val out = ByteTail(200_000)
        val err = ByteTail(50_000)
        try {
            fun fifo(name: String): Pair<File, FileDescriptor> {
                val file = File(dir, name)
                Os.mkfifo(file.path, 384) // 0600
                val fd = Os.open(file.path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
                descriptors += fd
                return file to fd
            }
            val stdout = fifo("stdout")
            val stderr = fifo("stderr")
            val control = fifo("identity")
            val ack = fifo("ack")
            val launcher = File("/system/bin/setsid")
            val launch = if (launcher.canExecute()) listOf(launcher.path) else listOf("/system/bin/toybox", "setsid")
            // Calls get independent process groups, but their commands still access the same sandbox root and files.
            val guest = runtime?.launch(root, command, dir) ?: throw StError.Unsupported("sandbox runtime is not installed")
            val header = """
                printf '%s\n' "${'$'}${'$'}" > "${'$'}ST_CONTROL"
                IFS= read -r answer < "${'$'}ST_ACK" || exit 125
                [ "${'$'}answer" = go ] || exit 125
                unset ST_CONTROL ST_ACK
                exec "${'$'}@"
            """.trimIndent()
            val process = ProcessBuilder(launch + listOf("/system/bin/sh", "-c", header, "st-shell") + guest.arguments)
                .directory(root.resolve("home"))
                .redirectOutput(stdout.first).redirectError(stderr.first).apply {
                    environment().clear()
                    environment().putAll(guest.environment)
                    environment()["ST_CONTROL"] = control.first.path
                    environment()["ST_ACK"] = ack.first.path
                }.start()
            lease.process = process
            process.outputStream.close() // Noninteractive execution: stdin is EOF, not an unattended prompt.
            val identity = ByteTail(128)
            var timedOut = false
            while (lease.leader == null) {
                currentCoroutineContext().ensureActive()
                if (lease.cancelled.get()) throw kotlinx.coroutines.CancellationException("shell stopped before dispatch")
                drain(stdout.second, out); drain(stderr.second, err); drain(control.second, identity)
                if (elapsed(started) >= timeoutSec * 1000) { timedOut = true; break }
                val line = identity.text()
                if ('\n' in line) {
                    val pid = line.trim().toIntOrNull() ?: throw StError.Sandbox("invalid shell process identity")
                    val info = proc(pid) ?: throw StError.Sandbox("shell process identity unavailable")
                    if (info.parent != AndroidProcess.myPid() || info.group != pid || info.session != pid)
                        throw StError.Sandbox("shell did not establish an independent process group")
                    lease.leader = info
                    lease.descendants[pid] = info
                    Os.write(ack.second, byteArrayOf(103, 111, 10), 0, 3) // go\n; no user command runs before identity validation.
                } else if (!process.isAlive || elapsed(started) >= 5_000)
                    throw StError.Sandbox("shell process-group handshake failed")
                else delay(25)
            }
            var lastCheckpoint = 0L
            var lastScan = 0L
            while (!timedOut) {
                currentCoroutineContext().ensureActive()
                drain(stdout.second, out); drain(stderr.second, err)
                val ms = elapsed(started)
                if (ms - lastScan >= 250) { trackDescendants(lease); lastScan = ms }
                if (ms - lastCheckpoint >= 500) { checkpoint(output(out, err)); lastCheckpoint = ms }
                if (lease.cancelled.get() || !process.isAlive) break
                if (ms >= timeoutSec * 1000) { timedOut = true; break }
                delay(25)
            }
            val cancelled = lease.cancelled.get()
            val exit = if (process.isAlive) -1 else process.exitValue()
            terminate(lease)
            repeat(8) { drain(stdout.second, out); drain(stderr.second, err) }
            val tail = output(out, err)
            ShellResult(exit, tail.stdout, tail.stderr,
                tail.stdout + (if (tail.stderr.isBlank()) "" else "\n[stderr]\n" + tail.stderr) +
                    "\n[exit]=$exit" + (if (timedOut) "\n[timeout]" else "") + (if (cancelled) "\n[stopped]" else ""),
                cancelled, timedOut, tail.stdoutBytes, tail.stderrBytes, tail.stdoutTruncated, tail.stderrTruncated, lease.incomplete)
        } finally {
            // This runs on success, cancellation, or failure; cleanup alone does not mean the command succeeded.
            withContext(NonCancellable) {
                terminate(lease)
                descriptors.take(2).forEachIndexed { index, fd -> runCatching { repeat(8) { drain(fd, if (index == 0) out else err) } } }
                // Stop owned processes before draining and saving the final observed output tail.
                try { checkpoint(output(out, err)) }
                finally {
                    descriptors.forEach { runCatching { Os.close(it) } }
                    dir.listFiles()?.forEach { it.delete() }
                    dir.delete()
                    running.remove(taskId, lease)
                }
            }
        }
    }

    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000
    private fun output(out: ByteTail, err: ByteTail) = ShellOutput(out.text(), err.text(), out.total, err.total,
        out.total > out.capacity, err.total > err.capacity)
    private fun drain(fd: FileDescriptor, target: ByteTail) {
        val bytes = ByteArray(8192)
        repeat(32) {
            val n = try { Os.read(fd, bytes, 0, bytes.size) } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) return
                throw error
            }
            if (n <= 0) return
            target.add(bytes, n)
        }
    }
    private fun proc(pid: Int): Proc? = runCatching {
        if (pid <= 1 || Os.stat("/proc/$pid").st_uid != AndroidProcess.myUid()) return@runCatching null
        val bytes = ByteArray(4096)
        val n = File("/proc/$pid/stat").inputStream().use { it.read(bytes) }
        if (n <= 0) return@runCatching null
        val text = String(bytes, 0, n, Charsets.UTF_8)
        val parts = text.substring(text.lastIndexOf(')') + 2).split(' ')
        if (parts[0] == "Z") return@runCatching null
        Proc(pid, parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[19].toLong())
    }.getOrNull()
    private fun trackDescendants(lease: Lease): List<Proc> {
        val leader = lease.leader ?: return emptyList()
        val entries = File("/proc").listFiles() ?: run { lease.incomplete = true; return emptyList() }
        if (entries.size > 8192) lease.incomplete = true
        val current = entries.take(8192).mapNotNull { it.name.toIntOrNull()?.let(::proc) }
        val alive = current.associateBy { it.pid }
        lease.descendants.entries.removeAll { alive[it.key]?.start != it.value.start }
        var added: Boolean
        do {
            added = false
            for (p in current) {
                val parent = lease.descendants[p.parent] ?: continue
                if (p.start >= leader.start && proc(parent.pid)?.start == parent.start && p.pid !in lease.descendants) {
                    lease.descendants[p.pid] = p; added = true
                }
            }
        } while (added)
        return current
    }
    private fun terminate(lease: Lease) {
        val leader = lease.leader
        if (leader != null) {
            val current = trackDescendants(lease)
            val actualLeader = proc(leader.pid)
            // A re-used numeric PID must never allow signalling a different task's process group.
            val originalGroup = actualLeader?.start == leader.start ||
                (actualLeader == null && current.any { it.group == leader.group && it.session == leader.session })
            if (originalGroup) signal(-leader.group, lease)
            lease.descendants.values.toList().asReversed().forEach { p ->
                if (proc(p.pid)?.start == p.start) signal(p.pid, lease)
            }
        }
        runCatching { if (lease.process?.isAlive == true) lease.process?.destroyForcibly() }
        if (lease.descendants.values.any { proc(it.pid)?.start == it.start }) lease.incomplete = true
    }
    private fun signal(pid: Int, lease: Lease) {
        try { Os.kill(pid, OsConstants.SIGKILL) }
        catch (error: ErrnoException) { if (error.errno != OsConstants.ESRCH) lease.incomplete = true }
    }

    private class ByteTail(val capacity: Int) {
        private val data = ByteArray(capacity)
        private var cursor = 0
        private var size = 0
        var total = 0L
            private set
        fun add(bytes: ByteArray, length: Int) {
            total += length
            for (i in 0 until length) { data[cursor] = bytes[i]; cursor = (cursor + 1) % capacity }
            size = (size + length).coerceAtMost(capacity)
        }
        fun text(): String {
            val bytes = ByteArray(size)
            val start = if (size == capacity) cursor else 0
            for (i in 0 until size) bytes[i] = data[(start + i) % capacity]
            return String(bytes, Charsets.UTF_8) // A cut UTF-8 prefix may be replaced; truncation is explicit.
        }
    }
}
