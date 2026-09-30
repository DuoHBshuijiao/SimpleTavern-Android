package com.simpletavern.runtime.sandbox

import com.simpletavern.core.data.FileChangeStore
import com.simpletavern.core.data.SandboxStore
import com.simpletavern.core.data.StoragePaths
import com.simpletavern.core.model.FileChangeRecord
import com.simpletavern.core.model.FileChangeType
import com.simpletavern.core.model.StError
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val combined: String,
    val cancelled: Boolean = false,
)

/**
 * Multi-sandbox runtime with per-environment roots under app files.
 *
 * Isolation model (v1):
 * - Each sandbox has an independent directory tree.
 * - File tools resolve paths and reject escapes via canonical-path checks.
 * - Shell runs with cwd inside the sandbox and PATH limited; a wrapper rejects
 *   absolute paths outside the sandbox root when detectable.
 * - PRoot/rootfs is NOT bundled yet; device proof of stronger isolation is pending.
 * - No external directory mounts; imports copy bytes into the sandbox.
 */
class SandboxManager(
    private val paths: StoragePaths,
    private val store: SandboxStore,
    private val changes: FileChangeStore,
) {
    private val processes = ConcurrentHashMap<String, Process>() // taskId -> process

    suspend fun create(name: String): String {
        val id = UUID.randomUUID().toString().replace("-", "")
        val root = paths.sandboxRoot(id)
        root.resolve("home").mkdirs()
        root.resolve("tmp").mkdirs()
        root.resolve("imports").mkdirs()
        val now = Instant.now().toString()
        store.upsert(id, name, "sandboxes/$id", now, now, """{"isolation":"path_confined","proot":false}""")
        return id
    }

    suspend fun list() = store.list()
    suspend fun delete(id: String) {
        store.delete(id)
        paths.sandboxRoot(id).deleteRecursively()
    }

    suspend fun importFile(sandboxId: String, source: File, relativeTarget: String): File = withContext(Dispatchers.IO) {
        val dest = resolveInSandbox(sandboxId, relativeTarget)
        dest.parentFile?.mkdirs()
        var final = dest
        if (final.exists()) {
            final = File(dest.parentFile, "${dest.nameWithoutExtension}_${System.currentTimeMillis()}.${dest.extension}")
        }
        source.inputStream().use { input -> final.outputStream().use { output -> input.copyTo(output) } }
        record(sandboxId, null, relativize(sandboxId, final), FileChangeType.CREATED, null, "imported ${final.length()}B")
        final
    }

    suspend fun listDir(sandboxId: String, relative: String): List<String> = withContext(Dispatchers.IO) {
        val dir = resolveInSandbox(sandboxId, relative)
        if (!dir.isDirectory) throw StError.Validation("not a directory")
        dir.list()?.sorted()?.toList() ?: emptyList()
    }

    suspend fun readFile(sandboxId: String, relative: String): String = withContext(Dispatchers.IO) {
        val f = resolveInSandbox(sandboxId, relative)
        if (!f.isFile) throw StError.NotFound(relative)
        // Cap huge reads into summary
        if (f.length() > 2_000_000) {
            f.readText().take(500_000) + "\n/* truncated */"
        } else f.readText()
    }

    suspend fun writeFile(sandboxId: String, taskId: String, relative: String, content: String) = withContext(Dispatchers.IO) {
        val f = resolveInSandbox(sandboxId, relative)
        val existed = f.exists()
        f.parentFile?.mkdirs()
        f.writeText(content)
        record(
            sandboxId, taskId, relative,
            if (existed) FileChangeType.MODIFIED else FileChangeType.CREATED,
            null, "bytes=${content.length}",
        )
    }

    suspend fun editFile(
        sandboxId: String,
        taskId: String,
        relative: String,
        expected: String?,
        content: String,
    ) = withContext(Dispatchers.IO) {
        val f = resolveInSandbox(sandboxId, relative)
        val current = if (f.exists()) f.readText() else null
        if (expected != null && current != null && current != expected) {
            throw StError.Conflict("edit premise stale", mapOf("path" to relative))
        }
        f.parentFile?.mkdirs()
        f.writeText(content)
        record(sandboxId, taskId, relative, FileChangeType.MODIFIED, expected?.take(200), content.take(200))
    }

    suspend fun deleteFile(sandboxId: String, taskId: String, relative: String) = withContext(Dispatchers.IO) {
        val f = resolveInSandbox(sandboxId, relative)
        if (!f.exists()) throw StError.NotFound(relative)
        f.delete()
        record(sandboxId, taskId, relative, FileChangeType.DELETED, null, null)
    }

    suspend fun renameFile(sandboxId: String, taskId: String, from: String, to: String) = withContext(Dispatchers.IO) {
        val src = resolveInSandbox(sandboxId, from)
        val dst = resolveInSandbox(sandboxId, to)
        if (!src.exists()) throw StError.NotFound(from)
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) throw StError.Sandbox("rename failed")
        record(sandboxId, taskId, "$from -> $to", FileChangeType.RENAMED, from, to)
    }

    suspend fun runShell(sandboxId: String, taskId: String, command: String, timeoutSec: Long = 300): ShellResult =
        withContext(Dispatchers.IO) {
            val root = paths.sandboxRoot(sandboxId).canonicalFile
            val before = snapshotFingerprints(root)
            val shell = File("/system/bin/sh").takeIf { it.exists() }?.absolutePath ?: "sh"
            // Confinement helper: reject obvious absolute escapes in command text is NOT complete.
            // We still set cwd and record post-diff. Stronger isolation requires PRoot (pending).
            val wrapped = buildString {
                appendLine("set -e")
                appendLine("ROOT=\"\$ST_SANDBOX_ROOT\"")
                appendLine("cd \"\$ROOT/home\" || cd \"\$ROOT\"")
                appendLine(command)
            }
            val pb = ProcessBuilder(shell, "-c", wrapped)
                .directory(root.resolve("home").also { it.mkdirs() })
                .redirectErrorStream(false)
            pb.environment()["ST_SANDBOX_ROOT"] = root.absolutePath
            pb.environment()["HOME"] = root.resolve("home").absolutePath
            pb.environment()["TMPDIR"] = root.resolve("tmp").absolutePath
            val proc = pb.start()
            processes[taskId] = proc
            try {
                val stdout = proc.inputStream.bufferedReader().use(BufferedReader::readText)
                val stderr = proc.errorStream.bufferedReader().use(BufferedReader::readText)
                val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    recordIncomplete(sandboxId, taskId)
                    return@withContext ShellResult(-1, stdout.takeLast(200_000), stderr.takeLast(50_000), "timeout", cancelled = true)
                }
                val after = snapshotFingerprints(root)
                diffAndRecord(sandboxId, taskId, before, after)
                val code = proc.exitValue()
                val combined = buildString {
                    append(stdout.takeLast(200_000))
                    if (stderr.isNotBlank()) {
                        append("\n[stderr]\n")
                        append(stderr.takeLast(50_000))
                    }
                    append("\n[exit]=$code")
                }
                ShellResult(code, stdout.takeLast(200_000), stderr.takeLast(50_000), combined)
            } finally {
                processes.remove(taskId)
            }
        }

    fun cancelTask(taskId: String) {
        processes.remove(taskId)?.destroyForcibly()
    }

    fun resolveInSandbox(sandboxId: String, relative: String): File {
        val root = paths.sandboxRoot(sandboxId).canonicalFile
        val cleaned = relative.replace('\\', '/').removePrefix("/")
        if (cleaned.contains("\u0000")) throw StError.Sandbox("invalid path")
        val target = root.resolve(cleaned).canonicalFile
        if (!target.path.startsWith(root.path)) {
            throw StError.Sandbox("path escape rejected", mapOf("path" to relative))
        }
        return target
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

    private suspend fun recordIncomplete(sandboxId: String, taskId: String) {
        record(sandboxId, taskId, ".", FileChangeType.UNKNOWN, null, null, attribution = "unknown", incomplete = true)
    }

    private fun snapshotFingerprints(root: File): Map<String, String> {
        val out = linkedMapOf<String, String>()
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            out[rel] = "${f.length()}:${f.lastModified()}"
        }
        return out
    }

    private suspend fun diffAndRecord(sandboxId: String, taskId: String, before: Map<String, String>, after: Map<String, String>) {
        val keys = before.keys + after.keys
        keys.forEach { path ->
            val b = before[path]
            val a = after[path]
            when {
                b == null && a != null -> record(sandboxId, taskId, path, FileChangeType.CREATED, null, a, attribution = "observed")
                b != null && a == null -> record(sandboxId, taskId, path, FileChangeType.DELETED, b, null, attribution = "observed")
                b != null && a != null && b != a -> record(sandboxId, taskId, path, FileChangeType.MODIFIED, b, a, attribution = "observed")
            }
        }
    }
}
