package com.simpletavern.core.api

import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.simpletavern.core.data.NativeJsonInput
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.data.StoragePaths
import com.simpletavern.core.model.*
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable

@Serializable
private data class RestoreFileBefore(val kind: String, val mode: Int, val device: Long,
    val inode: Long, val size: Long, val linkTarget: String? = null)

@Serializable
private data class RestoreFileIntent(val index: Int, val path: String, val kind: String,
    val mode: Int?, val device: Long, val inode: Long, val size: Long,
    val action: String = "create", val before: RestoreFileBefore? = null)

/** Durable owned images before publication. No recursive live deletion, no hashes, no execution replay. */
internal class NativeRestoreFileLedger(private val paths: StoragePaths, private val stage: File,
                                       private val inspection: NativeRestoreInspectionService) {
    private val objects = stage.resolve("objects")
    private val undo = stage.resolve("undo")
    private val premises = stage.resolve("premises")
    private val journal = stage.resolve("files.jsonl")
    private val undoDone = stage.resolve("file_undo.done")
    private var intents = listOf<RestoreFileIntent>()
    private var newBytes = 0L
    private var beforeBytes = 0L
    var publishedFiles = 0
        private set
    var preparedBytes = 0L
        private set

    suspend fun prepare(archive: File, manifest: NativeBackupManifest,
                        mode: NativeRestoreMode = NativeRestoreMode.ADD_MISSING,
                        progress: suspend (Int, Long) -> Unit) {
        for (root in listOf(objects, undo, premises)) if (!root.mkdir()) throw StError.Validation("restore file staging unavailable")
        val pending = stage.resolve("files.pending")
        val prepared = mutableListOf<RestoreFileIntent>()
        var metadataBytes = 0L
        ZipFile(archive).use { zip -> pending.outputStream().use { ledger ->
            // Parents first and links last. Existing directory children are NEVER pruned.
            val ordered = manifest.files.sortedWith(compareBy<NativeBackupFile>({ if (it.kind == "symlink") 1 else 0 }, { it.path.count { c -> c == '/' } }, { it.path }))
            for (file in ordered) {
                currentCoroutineContext().ensureActive()
                val target = paths.root.resolve(file.path)
                if (!inspection.safeParents(target)) throw StError.Conflict("restore parent is linked or not a directory")
                val old = inspection.statOrNull(target)
                val directory = file.kind == "directory"
                val incomingMode = file.mode
                if (old != null) {
                    var same = when (file.kind) {
                        "directory" -> OsConstants.S_ISDIR(old.st_mode)
                        "symlink" -> OsConstants.S_ISLNK(old.st_mode) &&
                            NativeBackupLinkResolver.portableTarget(file.path, Os.readlink(target.path)) == file.linkTarget
                        "file" -> zip.getInputStream(zip.getEntry(file.path)).use { inspection.compareFile(target, old, it, file.size) }
                        else -> throw StError.Unsupported("unsupported restore file kind")
                    }
                    if (file.kind != "symlink" && incomingMode != null && (old.st_mode and 4095) != (incomingMode and 4095)) same = false
                    if (!same && (mode != NativeRestoreMode.REPLACE_INCLUDED ||
                        directory != OsConstants.S_ISDIR(old.st_mode) ||
                        !directory && !OsConstants.S_ISREG(old.st_mode) && !OsConstants.S_ISLNK(old.st_mode)))
                        throw StError.Conflict("restore cannot replace this file target")
                    // A reused read-only directory needs an undo intent before temporary owner permissions.
                    if (same && (!directory || (old.st_mode and 448) == 448)) continue
                }
                val index = prepared.size
                val objectFile = objects.resolve(index.toString())
                val oldImage = old?.let { captureBefore(target, it, index) { progress(prepared.size, preparedBytes) } }
                if (!directory || old == null) when (file.kind) {
                    "directory" -> Os.mkdir(objectFile.path, 448)
                    "symlink" -> Os.symlink(file.linkTarget!!, objectFile.path)
                    "file" -> {
                        val entry = zip.getEntry(file.path) ?: throw StError.Validation("restore file missing")
                        objectFile.outputStream().use { output -> zip.getInputStream(entry).use { input ->
                            val buffer = ByteArray(65_536)
                            var size = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count > file.size - size) throw StError.Validation("restore file exceeds descriptor")
                                size += count; newBytes += count; preparedBytes += count
                                if (newBytes > NativeBackupService.MAX_BYTES) throw StError.Validation("restore new file images exceed 8 GiB")
                                output.write(buffer, 0, count); progress(prepared.size, preparedBytes)
                            }
                            if (size != file.size) throw StError.Validation("restore file size differs from descriptor")
                            Os.fchmod(output.fd, (incomingMode ?: 384) and 4095)
                            output.flush(); output.fd.sync()
                        } }
                    }
                    else -> throw StError.Unsupported("unsupported restore file kind")
                }
                val stat = if (directory && old != null) old else Os.lstat(objectFile.path)
                val action = when { old == null -> "create"; directory -> "directory_mode"; else -> "replace_leaf" }
                val intent = RestoreFileIntent(index, file.path, file.kind, incomingMode, stat.st_dev, stat.st_ino, file.size, action, oldImage)
                val line = Jsonx.encode(intent).toByteArray(Charsets.UTF_8)
                if (line.size > 65_536 || line.size + 1L > 64_000_000 - metadataBytes) throw StError.Validation("restore file journal exceeds budget")
                ledger.write(line); ledger.write(10); metadataBytes += line.size + 1
                prepared += intent
            }
            ledger.flush(); ledger.fd.sync()
        } }
        for (root in listOf(objects, undo, premises, stage)) syncDirectory(root)
        Os.rename(pending.path, journal.path); syncDirectory(stage)
        // Journal ancestor entries are durable BEFORE any live pathname or permissions change.
        for (root in listOf(stage.parentFile!!, paths.backups, paths.root, paths.root.parentFile!!)) syncDirectory(root)
        intents = prepared
    }

    private suspend fun captureBefore(target: File, old: StructStat, index: Int,
                                      progress: suspend () -> Unit): RestoreFileBefore {
        val kind = when { OsConstants.S_ISDIR(old.st_mode) -> "directory"; OsConstants.S_ISREG(old.st_mode) -> "file"
            OsConstants.S_ISLNK(old.st_mode) -> "symlink"; else -> throw StError.Validation("special restore target rejected") }
        if (kind == "directory") return RestoreFileBefore(kind, old.st_mode and 4095, old.st_dev, old.st_ino, 0)
        val backup = undo.resolve(index.toString())
        Os.link(target.path, backup.path) // Preserve the old inode; Linux does not follow a source symlink.
        val linked = Os.lstat(target.path)
        val saved = Os.lstat(backup.path)
        if (!identity(old, linked) || !identity(old, saved) || old.st_size != linked.st_size || old.st_mode != linked.st_mode)
            throw StError.Conflict("restore old image changed before capture")
        // Hard-link creation changes ctime; stability is checked against the post-link snapshot.
        if (kind == "symlink") {
            val link = Os.readlink(target.path)
            if (Os.readlink(backup.path) != link || !stable(linked, Os.lstat(target.path))) throw StError.Conflict("restore link changed during capture")
            return RestoreFileBefore(kind, old.st_mode and 4095, old.st_dev, old.st_ino, old.st_size, link)
        }
        if (old.st_size < 0 || old.st_size > NativeBackupService.MAX_BYTES - beforeBytes) throw StError.Validation("restore old file images exceed 8 GiB")
        FileInputStream(openFile(target)).use { input ->
            if (!stable(linked, Os.fstat(input.fd))) throw StError.Conflict("restore old file changed before reading")
            // Separate full bytes, NOT a hard link: an old open writer cannot silently change the undo premise.
            premises.resolve(index.toString()).outputStream().use { output ->
                val buffer = ByteArray(65_536)
                var bytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > linked.st_size - bytes) throw StError.Conflict("restore old file grew during capture")
                    bytes += count; beforeBytes += count; preparedBytes += count
                    output.write(buffer, 0, count); progress()
                }
                if (bytes != linked.st_size || !stable(linked, Os.fstat(input.fd)) || !stable(linked, Os.lstat(target.path)))
                    throw StError.Conflict("restore old file changed during capture")
                Os.fchmod(output.fd, 384); output.flush(); output.fd.sync()
                Os.fsync(input.fd)
            }
        }
        return RestoreFileBefore(kind, old.st_mode and 4095, old.st_dev, old.st_ino, old.st_size)
    }

    suspend fun publish(progress: suspend (Int) -> Unit) {
        for (intent in intents) {
            currentCoroutineContext().ensureActive()
            val target = target(intent)
            if (!inspection.safeParents(target)) throw StError.Conflict("restore file parent changed before publication")
            if (intent.action == "create") {
                if (inspection.statOrNull(target) != null) throw StError.Conflict("restore file target appeared before publication")
            } else verifyBefore(intent, target)
            // Keep mutation and durability sync together; journal rollback handles cancellation before commit.
            withContext(NonCancellable) {
                if (intent.action == "directory_mode") setDirectoryMode(intent, target, 448)
                else {
                    val prepared = objects.resolve(intent.index.toString())
                    if (!owns(intent, Os.lstat(prepared.path))) throw StError.Conflict("restore staging identity changed")
                    if (intent.action == "replace_leaf" || intent.kind == "directory") Os.rename(prepared.path, target.path)
                    else Os.link(prepared.path, target.path) // Atomic no-replace for new leaves.
                    syncDirectory(target.parentFile!!); syncDirectory(objects)
                }
            }
            publishedFiles++; progress(publishedFiles)
        }
        for (intent in intents.asReversed().filter { it.kind == "directory" }) {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { setDirectoryMode(intent, target(intent), (intent.mode ?: intent.before?.mode ?: 448) and 4095) }
        }
    }

    suspend fun rollback() {
        if (inspection.statOrNull(undoDone) != null) {
            val stat = Os.lstat(undoDone.path)
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != 32L || undoDone.readText(Charsets.US_ASCII) != stage.name)
                throw StError.Validation("invalid file undo completion marker")
            return // A crash during private cleanup must not undo again.
        }
        load()
        for (intent in intents.filter { it.kind == "directory" }) {
            val file = target(intent)
            if (!inspection.safeParents(file)) throw StError.Conflict("restore rollback parent changed")
            inspection.statOrNull(file)?.let {
                if (!owns(intent, it)) throw StError.Conflict("restore rollback encountered unrelated directory")
                val currentMode = it.st_mode and 4095
                if (currentMode !in setOf(448, (intent.mode ?: intent.before?.mode ?: 448) and 4095, intent.before?.mode))
                    throw StError.Conflict("restore rollback directory mode changed unexpectedly")
                setDirectoryMode(intent, file, 448)
            }
        }
        for (intent in intents.asReversed()) {
            val file = target(intent)
            if (!inspection.safeParents(file)) throw StError.Conflict("restore rollback parent changed")
            val stat = inspection.statOrNull(file)
            when (intent.action) {
                "directory_mode" -> {
                    if (stat == null || !owns(intent, stat)) throw StError.Conflict("restore rollback directory changed")
                    setDirectoryMode(intent, file, intent.before!!.mode)
                }
                "replace_leaf" -> {
                    val before = intent.before!!
                    if (stat != null && before.device == stat.st_dev && before.inode == stat.st_ino) {
                        verifyBefore(intent, file) // Not published or already undone.
                    } else {
                        if (stat != null && !owns(intent, stat)) throw StError.Conflict("restore rollback encountered unrelated file")
                        val backup = undo.resolve(intent.index.toString())
                        verifyBefore(intent, backup)
                        if (stat == null) Os.link(backup.path, file.path) else Os.rename(backup.path, file.path)
                        syncDirectory(file.parentFile!!); syncDirectory(undo)
                    }
                }
                else -> if (stat != null) {
                    if (!owns(intent, stat)) throw StError.Conflict("restore rollback encountered unrelated file")
                    deleteSingle(file); syncDirectory(file.parentFile!!)
                }
            }
        }
        val pendingDone = stage.resolve("file_undo.pending")
        pendingDone.outputStream().use { it.write(stage.name.toByteArray(Charsets.US_ASCII)); it.flush(); it.fd.sync() }
        Os.rename(pendingDone.path, undoDone.path)
        syncDirectory(stage)
    }

    private suspend fun verifyBefore(intent: RestoreFileIntent, file: File) {
        val before = intent.before ?: throw StError.Validation("restore before-image missing")
        val stat = inspection.statOrNull(file) ?: throw StError.Conflict("restore before-image disappeared")
        val correctKind = when (before.kind) { "directory" -> OsConstants.S_ISDIR(stat.st_mode); "file" -> OsConstants.S_ISREG(stat.st_mode)
            "symlink" -> OsConstants.S_ISLNK(stat.st_mode); else -> false }
        if (!correctKind || before.device != stat.st_dev || before.inode != stat.st_ino || before.mode != (stat.st_mode and 4095))
            throw StError.Conflict("restore before-image identity or mode changed")
        if (before.kind == "symlink") {
            if (Os.readlink(file.path) != before.linkTarget) throw StError.Conflict("restore old link changed")
        } else if (before.kind == "file") {
            val premise = premises.resolve(intent.index.toString())
            val saved = inspection.statOrNull(premise) ?: throw StError.Conflict("restore old byte premise missing")
            if (!OsConstants.S_ISREG(saved.st_mode) || saved.st_size != before.size) throw StError.Validation("invalid restore old byte premise")
            FileInputStream(openFile(premise)).use {
                if (!inspection.compareFile(file, stat, it, before.size)) throw StError.Conflict("restore old bytes changed")
            }
        }
    }

    private suspend fun load() {
        val stat = inspection.statOrNull(journal) ?: return // No pathname/mode effects before journal publication.
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size > 64_000_000) throw StError.Validation("invalid restore file journal")
        val records = mutableListOf<RestoreFileIntent>()
        journal.inputStream().use { input -> NativeJsonInput.lines(input, stat.st_size) { line ->
            if (line.toByteArray(Charsets.UTF_8).size > 65_536 || records.size >= 200_000) throw StError.Validation("restore journal entry exceeds budget")
            NativeJsonInput.parse(line)
            val intent = Jsonx.decode<RestoreFileIntent>(line)
            if (intent.index != records.size || intent.size < 0 || intent.kind !in setOf("file", "directory", "symlink") ||
                intent.action !in setOf("create", "replace_leaf", "directory_mode") ||
                (intent.action == "create") != (intent.before == null) ||
                intent.action == "directory_mode" && (intent.kind != "directory" || intent.before?.kind != "directory") ||
                intent.action == "replace_leaf" && (intent.kind == "directory" || intent.before?.kind !in setOf("file", "symlink")) ||
                intent.before?.let { it.size < 0 || it.mode !in 0..4095 } == true)
                throw StError.Validation("invalid restore intent")
            target(intent); records += intent
        } }
        if (records.map { it.path }.toSet().size != records.size) throw StError.Validation("duplicate restore journal target")
        intents = records
    }
    private fun target(intent: RestoreFileIntent): File {
        NativeBackupPreflightService.validateArchivePath(intent.path)
        if (intent.path != "attachments" && !intent.path.startsWith("attachments/") && intent.path != "sandboxes" && !intent.path.startsWith("sandboxes/"))
            throw StError.Validation("restore journal target is outside managed roots")
        return paths.root.resolve(intent.path)
    }
    private fun owns(intent: RestoreFileIntent, stat: StructStat) = intent.device == stat.st_dev && intent.inode == stat.st_ino
    private fun identity(a: StructStat, b: StructStat) = a.st_dev == b.st_dev && a.st_ino == b.st_ino
    private fun stable(a: StructStat, b: StructStat) = identity(a, b) && a.st_mode == b.st_mode && a.st_size == b.st_size && a.st_mtime == b.st_mtime && a.st_ctime == b.st_ctime

    /** Flat private images + explicit files only; never traverse a user's directory tree. */
    fun cleanupStage() {
        if (stage.parentFile != paths.backups.resolve("restores") || !stage.name.matches(Regex("[a-f0-9]{32}")) ||
            stage.canonicalFile != stage.absoluteFile || !OsConstants.S_ISDIR(Os.lstat(stage.path).st_mode))
            throw StError.Validation("invalid private restore cleanup target")
        for (root in listOf(objects, undo, premises)) if (inspection.statOrNull(root) != null) {
            if (root.canonicalFile != root.absoluteFile || !OsConstants.S_ISDIR(Os.lstat(root.path).st_mode)) throw StError.Validation("linked restore image root")
            val files = root.listFiles() ?: throw StError.Validation("restore images unavailable")
            for (file in files) {
                if (!file.name.matches(Regex("[0-9]{1,6}"))) throw StError.Validation("unknown restore image")
                val mode = Os.lstat(file.path).st_mode
                if (OsConstants.S_ISDIR(mode) || OsConstants.S_ISREG(mode) || OsConstants.S_ISLNK(mode)) deleteSingle(file)
                else throw StError.Validation("special restore image rejected")
            }
            deleteSingle(root)
        }
        val allowed = setOf("archive.zip", "rows.db", "rows.db-journal", "rows.db-wal", "rows.db-shm", "files.pending", "files.jsonl", "file_undo.pending", "file_undo.done")
        val files = stage.listFiles() ?: throw StError.Validation("restore staging unavailable")
        // Keep rollback completion until ALL images and the journal are gone.
        for (file in files.sortedBy { if (it.name == "file_undo.done") 2 else if (it.name == "files.jsonl") 1 else 0 }) {
            if (file.name !in allowed || !OsConstants.S_ISREG(Os.lstat(file.path).st_mode)) throw StError.Validation("unknown restore staging file")
            if (file.name == "file_undo.done") syncDirectory(stage) // Journal removal must precede marker removal durably.
            deleteSingle(file)
        }
        deleteSingle(stage); syncDirectory(stage.parentFile!!)
    }
    private fun setDirectoryMode(intent: RestoreFileIntent, directory: File, mode: Int) {
        val before = Os.lstat(directory.path)
        if (!OsConstants.S_ISDIR(before.st_mode) || !owns(intent, before)) throw StError.Conflict("restore directory identity changed")
        // The app owns this inode. Temporarily regain access even after an intended 0000 mode.
        Os.chmod(directory.path, 448)
        val fd = openDirectory(directory)
        try {
            if (!owns(intent, Os.fstat(fd))) throw StError.Conflict("restore directory changed before chmod")
            Os.fchmod(fd, mode); Os.fsync(fd)
        } finally { Os.close(fd) }
    }
    private fun syncDirectory(directory: File) {
        val fd = openDirectory(directory)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
    private fun openFile(file: File) = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
    private fun openDirectory(directory: File): java.io.FileDescriptor {
        val fd = openFile(directory)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw StError.Validation("restore fsync target is not a directory")
            return fd
        } catch (error: Throwable) { Os.close(fd); throw error }
    }
    private fun deleteSingle(file: File) {
        if (!file.delete()) throw StError.Validation("restore single-entry cleanup failed")
    }
}
