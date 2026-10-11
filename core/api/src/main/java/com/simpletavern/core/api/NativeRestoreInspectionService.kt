package com.simpletavern.core.api

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.simpletavern.core.data.*
import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.model.*
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Read-only caller-owned inspection. No live data writes, credentials changes or execution registration. */
class NativeRestoreInspectionService(
    private val db: AppDatabase, private val paths: StoragePaths, private val tasks: TaskStore,
    private val gate: DataOperationGate, private val credentials: CredentialStore,
) {
    private val operationRoot get() = paths.backups.resolve("restore_inspections")

    suspend fun inspect(archive: File, password: CharArray? = null,
                        mode: NativeRestoreMode = NativeRestoreMode.ADD_MISSING): NativeRestoreInspection =
        inspectInput(password, mode) { target -> copyArchive(archive, target) }

    internal suspend fun inspect(archive: SourceArchiveInput, inputs: SourceArchiveInputs, password: CharArray?,
        mode: NativeRestoreMode): NativeRestoreInspection = inspectInput(password, mode) { target ->
        try { inputs.copyTo(archive, target) {} } finally { archive.close() }
    }

    private suspend fun inspectInput(password: CharArray?, mode: NativeRestoreMode,
        copyInput: suspend (File) -> Unit): NativeRestoreInspection = stagedInput(password, true, copyInput) { input, secret ->
        val manifest = NativeBackupPreflightService(db).inspect(input).manifest
        val at = Instant.now().toString()
        NativeDatabaseRestorePlan.prepare(db, input, manifest, input.parentFile!!.resolve("rows.db")).use { rows ->
            inspectPrepared(input, manifest, rows, secret, at, mode)
        }
    }

    internal suspend fun preflight(archive: SourceArchiveInput, inputs: SourceArchiveInputs): NativeBackupPreflight =
        stagedInput(null, false, { target ->
            try { inputs.copyTo(archive, target) {} } finally { archive.close() }
        }) { input, _ -> NativeBackupPreflightService(db).inspect(input) }

    private suspend fun <T> stagedInput(password: CharArray?, exclusive: Boolean, copyInput: suspend (File) -> Unit,
        inspect: suspend (File, CharArray?) -> T): T = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().replace("-", "")
        val lease = if (exclusive) gate.exclusiveLease(id) else gate.sharedLease()
        val stage = operationRoot.resolve(id)
        val secret = password?.copyOf()
        var owned = false
        try {
            if (exclusive && tasks.nonTerminal().isNotEmpty()) throw StError.Conflict("active tasks prevent a consistent restore inspection")
            if (paths.backups.canonicalFile != paths.backups.absoluteFile || operationRoot.canonicalFile != operationRoot.absoluteFile)
                throw StError.Validation("linked restore inspection root rejected")
            if (!stage.mkdirs()) throw StError.Validation("restore inspection staging unavailable")
            owned = true
            val input = stage.resolve("archive.zip")
            copyInput(input)
            inspect(input, secret)
        } finally {
            secret?.fill('\u0000')
            try { withContext(NonCancellable) { if (owned) deleteStage(stage) } } finally { lease.close() }
        }
    }

    /** Caller already owns gate/input/plan. Also used by a registered RESTORE worker, never re-enters gate. */
    internal suspend fun inspectPrepared(input: File, manifest: NativeBackupManifest, rows: NativeDatabaseRestorePlan,
                                         secret: CharArray?, at: String,
                                         mode: NativeRestoreMode = NativeRestoreMode.ADD_MISSING,
                                         requirePublishedAssets: Boolean = false): NativeRestoreInspection = withContext(Dispatchers.IO) {
            val replacing = mode == NativeRestoreMode.REPLACE_INCLUDED
            val issues = mutableListOf<NativeRestoreIssue>()
            var blocking = 0L; var warnings = 0L
            var issueBytes = 0L
            fun issue(next: NativeRestoreIssue) {
                if (next.blocking) blocking++ else warnings++
                val bytes = Jsonx.encode(next).toByteArray(Charsets.UTF_8).size
                if (issues.size < 200 && bytes <= 1_000_000 - issueBytes) { issues += next; issueBytes += bytes }
            }
                val database = rows.inspect(mode, ::issue)
                inspectLinks(manifest, ::issue)
                var newFiles = 0; var sameFiles = 0; var conflictFiles = 0
                ZipFile(input).use { zip ->
                    for (file in manifest.files) {
                        currentCoroutineContext().ensureActive()
                        val target = paths.root.resolve(file.path)
                        val parentsOkay = safeParents(target)
                        val before = if (parentsOkay) statOrNull(target) else null
                        var same = before == null
                        when (file.kind) {
                            "directory" -> same = before == null || OsConstants.S_ISDIR(before.st_mode)
                            "symlink" -> same = before == null || OsConstants.S_ISLNK(before.st_mode) &&
                                NativeBackupLinkResolver.portableTarget(file.path, Os.readlink(target.path)) == file.linkTarget
                            "file" -> {
                                val entry = zip.getEntry(file.path) ?: throw StError.Validation("native file disappeared")
                                // Read every file, including new/conflicting ones. Metadata alone cannot prove readable data.
                                zip.getInputStream(entry).use { incoming -> same = compareFile(target, before, incoming, file.size) }
                            }
                        }
                        val mode = file.mode
                        if (before != null && file.kind != "symlink" && mode != null &&
                            (before.st_mode and 4095) != (mode and 4095)) same = false
                        val protectedBytes = database.protectedSpeechFiles[file.path]
                        if (protectedBytes != null && before != null && OsConstants.S_ISREG(before.st_mode) &&
                            before.st_size == protectedBytes && !same)
                            issue(NativeRestoreIssue("PRESERVED_SPEECH_FILE_CHANGED", "attachments", path = file.path))
                        when {
                            !parentsOkay || !same || requirePublishedAssets && before == null -> {
                                conflictFiles++
                                val compatible = before != null && (file.kind == "directory" && OsConstants.S_ISDIR(before.st_mode) ||
                                    file.kind != "directory" && (OsConstants.S_ISREG(before.st_mode) || OsConstants.S_ISLNK(before.st_mode)))
                                issue(NativeRestoreIssue(if (parentsOkay) "FILE_CONFLICT" else "UNSAFE_FILE_PARENT", path = file.path,
                                    blocking = requirePublishedAssets || !replacing || !parentsOkay || !compatible))
                            }
                            before == null -> newFiles++
                            else -> sameFiles++
                        }
                    }
                    val captured = manifest.files.associate { it.path to it.kind }
                    fun requireFile(path: String?, table: String, key: String, directory: Boolean = false, required: Boolean = false) {
                        if (path.isNullOrBlank()) {
                            if (required) issue(NativeRestoreIssue("MISSING_MANAGED_FILE", table, key.take(2048)))
                            return
                        }
                        if (!directory && (path.startsWith("http:") || path.startsWith("https:") || path.startsWith("data:"))) return
                        val managed = if (directory) path else "attachments/$path"
                        NativeBackupPreflightService.validateArchivePath(managed)
                        val kind = if (directory) "directory" else "file"
                        val selected = if (directory) manifest.options.includeSandboxUserFiles else manifest.options.includeAttachments
                        val local = paths.root.resolve(managed)
                        val exists = if (selected) captured[managed] == kind else safeParents(local) && statOrNull(local)?.let {
                            if (directory) OsConstants.S_ISDIR(it.st_mode) else OsConstants.S_ISREG(it.st_mode)
                        } == true
                        if (!exists) issue(NativeRestoreIssue("MISSING_MANAGED_FILE", table, key.take(2048), path = managed))
                    }
                    for (table in manifest.tables.filter { it.name in setOf("attachments", "characters", "personas", "message_candidates", "sandboxes") }) {
                        rows.forEachRow(table) { _, row ->
                            fun text(column: String) = row[column]?.jsonPrimitive?.contentOrNull
                            val key = buildJsonObject { put("id", text("id")) }.toString()
                            when (table.name) {
                                "attachments" -> requireFile(text("relativePath"), table.name, key, required = true)
                                "characters", "personas" -> requireFile(text("avatarRelativePath"), table.name, key)
                                "sandboxes" -> requireFile("sandboxes/${text("id")}", table.name, key, true)
                                "message_candidates" -> {
                                    requireFile(text("senderAvatar"), table.name, key)
                                    for (column in listOf("imagesJson", "attachmentsJson")) Jsonx.decode<List<AttachmentRef>>(text(column)!!)
                                        .forEach { requireFile(it.relativePath, table.name, key, required = true) }
                                }
                            }
                        }
                    }
                    var checkedCredentials = !manifest.credentialsIncluded
                    var newCredentials = 0; var sameCredentials = 0; var conflictCredentials = 0
                    if (manifest.credentialsIncluded) {
                        if (secret == null) issue(NativeRestoreIssue("CREDENTIAL_PASSWORD_REQUIRED")) else {
                            val entry = zip.getEntry(manifest.credentialArchivePath!!)!!
                            val encrypted = zip.getInputStream(entry).use { NativeJsonInput.read(it, 3_000_000) }
                            val restored = CredentialArchiveCipher.decrypt(encrypted, secret)
                            currentCoroutineContext().ensureActive()
                            val existing = credentials.snapshotSecrets()
                            restored.forEach { (key, value) -> when {
                                key !in existing -> newCredentials++
                                existing[key] == value -> sameCredentials++
                                else -> conflictCredentials++
                            } }
                            if (conflictCredentials != 0) issue(NativeRestoreIssue("CREDENTIAL_CONFLICT", blocking = requirePublishedAssets || !replacing))
                            if (requirePublishedAssets && newCredentials != 0) issue(NativeRestoreIssue("CREDENTIAL_PUBLISH_MISSING"))
                            checkedCredentials = true
                        }
                    }
                    NativeRestoreInspection(at, database.tables, newFiles, sameFiles, conflictFiles,
                        database.interruptedTasks, database.expiredApprovals, database.interruptedSummaryJobs,
                        checkedCredentials, newCredentials, sameCredentials, conflictCredentials, blocking, warnings,
                        issues, blocking + warnings > issues.size, listOf(
                            "Read-only inspection; no database rows, user files or credentials restored.",
                            if (replacing) "Explicit replacement of included identities only; unrelated local data and directory children are preserved."
                            else "Different local primary-key rows, files or credentials are conflicts; unrelated local data is preserved.",
                            "Historical references/import tombstones may warn without being re-created or replayed.",
                            "Incoming active tasks/summary jobs become INTERRUPTED and pending approvals become EXPIRED in candidate data only.",
                            "No reusable plan token: commit must validate again under its own exclusive gate and transaction.",
                            "Inspection itself neither registers workers nor commits data; registered RESTORE owns its separate lifetime.",
                            "System shell is not isolated; the facade gate is not a hostile-process filesystem boundary."), mode)
                }
    }

    /** Effective incoming + retained local graph, including preserved links activated by incoming directories. */
    private suspend fun inspectLinks(manifest: NativeBackupManifest, issue: (NativeRestoreIssue) -> Unit) {
        if (!manifest.options.includeSandboxUserFiles) return
        val incoming = manifest.files.associate { it.path to NativeLinkEntry(it.kind, it.linkTarget) }
        val links = linkedMapOf<String, String>()
        for (file in manifest.files) if (file.kind == "symlink") links[file.path] = file.linkTarget!!
        val ids = manifest.files.asSequence().map { it.path.split('/') }.filter { it.size >= 2 && it[0] == "sandboxes" }
            .map { it[1] }.toSet()
        var scanned = 0
        for (id in ids) {
            val queue = java.util.ArrayDeque<File>()
            queue.add(paths.sandboxes.resolve(id))
            while (queue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val file = queue.removeFirst()
                if (++scanned > 200_000) throw StError.Validation("retained sandbox link inspection exceeds 200000 entries")
                if (!safeParents(file)) throw StError.Conflict("linked parent prevents sandbox link inspection")
                val stat = statOrNull(file) ?: continue
                val name = file.relativeTo(paths.root).invariantSeparatorsPath
                if (OsConstants.S_ISLNK(stat.st_mode)) {
                    if (name !in incoming) links[name] = NativeBackupLinkResolver.portableTarget(name, Os.readlink(file.path))
                } else if (OsConstants.S_ISDIR(stat.st_mode)) {
                    val children = file.listFiles() ?: throw StError.Validation("cannot enumerate retained sandbox links")
                    if (children.size + queue.size + scanned > 200_000) throw StError.Validation("retained sandbox link inspection exceeds 200000 entries")
                    children.forEach(queue::add)
                }
            }
        }
        val local = mutableMapOf<String, NativeLinkEntry?>()
        suspend fun lookup(name: String): NativeLinkEntry? {
            incoming[name]?.let { return it }
            if (local.containsKey(name)) return local[name]
            if (local.size >= 200_000) throw StError.Validation("retained link dependency inspection exceeds budget")
            val file = paths.root.resolve(name)
            if (!safeParents(file)) throw StError.Conflict("linked parent prevents link dependency inspection")
            val stat = statOrNull(file)
            val entry = stat?.let { when {
                OsConstants.S_ISLNK(it.st_mode) -> NativeLinkEntry("symlink",
                    NativeBackupLinkResolver.portableTarget(name, Os.readlink(file.path)))
                OsConstants.S_ISDIR(it.st_mode) -> NativeLinkEntry("directory")
                OsConstants.S_ISREG(it.st_mode) -> NativeLinkEntry("file")
                else -> NativeLinkEntry("special")
            } }
            local[name] = entry
            return entry
        }
        for ((path, target) in links) {
            val result = NativeBackupLinkResolver.resolve(path, target, ::lookup)
            if (result != NativeLinkResolution.RESOLVED) issue(NativeRestoreIssue("SYMLINK_${result.name}", path = path,
                blocking = result == NativeLinkResolution.ESCAPES_SANDBOX))
        }
    }

    internal suspend fun copyArchive(source: File, target: File) {
        val before = Os.lstat(source.absolutePath)
        if (!OsConstants.S_ISREG(before.st_mode)) throw StError.Validation("native input must be a regular non-linked archive")
        val limit = NativeBackupService.MAX_BYTES + 512L * 1024 * 1024
        if (before.st_size > limit) throw StError.Validation("compressed native archive exceeds budget")
        val fd = Os.open(source.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
        FileInputStream(fd).use { input ->
            val opened = Os.fstat(fd)
            if (!sameIdentity(before, opened)) throw StError.Conflict("native archive changed before reading")
            target.outputStream().use { output ->
                val buffer = ByteArray(65_536)
                var size = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > limit) throw StError.Validation("compressed native archive exceeds budget")
                    output.write(buffer, 0, count)
                }
                output.flush(); output.fd.sync()
                if (size != before.st_size || !sameIdentity(before, Os.fstat(fd)) || !sameIdentity(before, Os.lstat(source.absolutePath)))
                    throw StError.Conflict("native archive changed during inspection copy")
            }
        }
    }

    internal suspend fun compareFile(target: File, before: StructStat?, incoming: InputStream, size: Long): Boolean {
        val comparable = before != null && OsConstants.S_ISREG(before.st_mode) && before.st_size == size
        val local = if (comparable) FileInputStream(Os.open(target.path,
            OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)) else null
        local.use { existing ->
            var same = before == null || comparable
            if (existing != null && !sameIdentity(before!!, Os.fstat(existing.fd))) throw StError.Conflict("local restore file changed before comparison")
            val buffer = ByteArray(65_536); val old = ByteArray(65_536)
            var bytes = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = incoming.read(buffer)
                if (count < 0) break
                if (count > size - bytes) throw StError.Validation("native file exceeds declared size")
                bytes += count
                if (existing != null) {
                    var read = 0
                    while (read < count) {
                        currentCoroutineContext().ensureActive()
                        val n = existing.read(old, read, count - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read != count || (0 until count).any { buffer[it] != old[it] }) same = false
                }
            }
            if (bytes != size) throw StError.Validation("native file differs from declared size")
            if (existing != null && (existing.read() != -1 || !sameIdentity(before!!, Os.fstat(existing.fd)) ||
                !sameIdentity(before, Os.lstat(target.path)))) throw StError.Conflict("local restore file changed during comparison")
            return same
        }
    }

    internal fun safeParents(target: File): Boolean {
        if (paths.root.canonicalFile != paths.root.absoluteFile || !OsConstants.S_ISDIR(Os.lstat(paths.root.path).st_mode))
            throw StError.Validation("linked or unavailable managed data root")
        var parent = target.parentFile
        while (parent != null && parent != paths.root) {
            if (!parent.toPath().startsWith(paths.root.toPath())) throw StError.Validation("restore path escapes managed data root")
            val stat = statOrNull(parent)
            if (stat != null && !OsConstants.S_ISDIR(stat.st_mode)) return false
            parent = parent.parentFile
        }
        return parent == paths.root
    }
    internal fun statOrNull(file: File): StructStat? = try { Os.lstat(file.path) }
        catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) null else throw StError.Validation("cannot inspect native file target") }
    private fun sameIdentity(a: StructStat, b: StructStat) = a.st_dev == b.st_dev && a.st_ino == b.st_ino &&
        a.st_mode == b.st_mode && a.st_size == b.st_size && a.st_mtime == b.st_mtime && a.st_ctime == b.st_ctime

    suspend fun clearInterruptedStaging() = withContext(Dispatchers.IO) {
        if (!operationRoot.exists()) return@withContext
        if (operationRoot.canonicalFile != operationRoot.absoluteFile) throw StError.Validation("linked restore inspection root rejected")
        val stages = operationRoot.listFiles() ?: throw StError.Validation("cannot enumerate restore inspection staging")
        stages.forEach(::deleteStage)
    }
    private fun deleteStage(stage: File) {
        if (stage.parentFile != operationRoot || !stage.name.matches(Regex("[a-f0-9]{32}")) ||
            stage.canonicalFile != stage.absoluteFile || !OsConstants.S_ISDIR(Os.lstat(stage.path).st_mode))
            throw StError.Validation("invalid restore inspection staging")
        val allowed = setOf("archive.zip", "rows.db", "rows.db-journal", "rows.db-wal", "rows.db-shm")
        val files = stage.listFiles() ?: throw StError.Validation("cannot enumerate restore inspection files")
        for (file in files) {
            if (file.name !in allowed || !OsConstants.S_ISREG(Os.lstat(file.path).st_mode) || !file.delete())
                throw StError.Validation("restore inspection staging cleanup failed")
        }
        if (!stage.delete()) throw StError.Validation("restore inspection staging cleanup failed")
    }
}
